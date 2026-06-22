import Foundation
import Network
import Combine
import UIKit

class SocketServer: ObservableObject {
    @Published var isStreaming = false
    @Published var currentStatus = "Ready for OBS connection"
    @Published var tallyStatus = "idle" // idle, preview, program
    @Published var txRateText = "0.0 KB/s"
    @Published var totalTxText = "0.0 MB"
    @Published var droppedFramesCount = 0
    
    private var listener: NWListener?
    private var activeVideoConnection: NWConnection?
    private var connections: [UUID: NWConnection] = [:]
    
    private var totalBytesSent: Int64 = 0
    private var bytesSentLastSec: Int64 = 0
    private var speedTimer: Timer?
    
    var onStartStream: ((String, Int, Int) -> Void)?
    var onStopStream: (() -> Void)?
    
    init() {
        startSpeedTracker()
    }
    
    deinit {
        stop()
    }
    
    func start(port: Int) {
        stop()
        
        do {
            let parameters = NWParameters.tcp
            // Allow fast reuse of port
            parameters.requiredInterfaceType = .wifi
            parameters.allowLocalEndpointReuse = true
            
            let nwPort = NWEndpoint.Port(rawValue: UInt16(port))!
            let listener = try NWListener(using: parameters, on: nwPort)
            
            // Set up Bonjour/mDNS discovery service matching _droidcamobs._tcp.local.
            listener.service = NWListener.Service(name: UIDevice.current.name, type: "_droidcamobs._tcp")
            
            listener.stateUpdateHandler = { [weak self] state in
                DispatchQueue.main.async {
                    switch state {
                    case .ready:
                        self?.currentStatus = "Listening on port \(port)"
                        print("Socket server ready on port \(port)")
                    case .failed(let error):
                        self?.currentStatus = "Start failed: \(error.localizedDescription)"
                        print("Socket server failed: \(error)")
                    default:
                        break
                    }
                }
            }
            
            listener.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            
            listener.start(queue: .global(qos: .userInteractive))
            self.listener = listener
            
        } catch {
            self.currentStatus = "Failed to create listener: \(error.localizedDescription)"
            print("Error starting listener: \(error)")
        }
    }
    
    func stop() {
        listener?.cancel()
        listener = nil
        
        closeActiveVideoConnection()
        
        for (_, conn) in connections {
            conn.cancel()
        }
        connections.removeAll()
        
        isStreaming = false
        currentStatus = "Server Stopped"
    }
    
    private func handleNewConnection(_ connection: NWConnection) {
        let id = UUID()
        connections[id] = connection
        
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed(let error):
                print("Connection \(id) failed: \(error)")
                self?.removeConnection(id)
            case .cancelled:
                self?.removeConnection(id)
            default:
                break
            }
        }
        
        connection.start(queue: .global(qos: .userInteractive))
        readRequest(connection, id: id)
    }
    
    private func removeConnection(_ id: UUID) {
        if let conn = connections[id] {
            conn.cancel()
            connections.removeValue(forKey: id)
        }
    }
    
    private func closeActiveVideoConnection() {
        if let conn = activeVideoConnection {
            conn.cancel()
            activeVideoConnection = nil
        }
        DispatchQueue.main.async {
            self.isStreaming = false
            self.currentStatus = "Ready for OBS connection"
        }
    }
    
    private func readRequest(_ connection: NWConnection, id: UUID) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 2048) { [weak self] data, context, isComplete, error in
            guard let self = self else { return }
            
            if let error = error {
                print("Read error on connection \(id): \(error)")
                self.removeConnection(id)
                return
            }
            
            guard let data = data, !data.isEmpty else {
                if isComplete {
                    self.removeConnection(id)
                } else {
                    self.readRequest(connection, id: id)
                }
                return
            }
            
            guard let requestLine = String(data: data, encoding: .utf8) else {
                self.removeConnection(id)
                return
            }
            
            print("Received request:\n\(requestLine)")
            self.parseHttpRequest(requestLine, connection: connection, id: id)
        }
    }
    
    private func parseHttpRequest(_ request: String, connection: NWConnection, id: UUID) {
        let parts = request.components(separatedBy: "\r\n")
        guard let firstLine = parts.first else {
            sendResponse(connection, response: "HTTP/1.1 400 Bad Request\r\n\r\n", id: id)
            return
        }
        
        let reqParts = firstLine.components(separatedBy: " ")
        guard reqParts.count >= 2 else {
            sendResponse(connection, response: "HTTP/1.1 400 Bad Request\r\n\r\n", id: id)
            return
        }
        
        let uri = reqParts[1]
        
        if uri == "/ping" {
            let body = "pong"
            let response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n\(body)"
            sendResponse(connection, response: response, id: id)
            
        } else if uri == "/battery" {
            UIDevice.current.isBatteryMonitoringEnabled = true
            let percent = Int(max(0, UIDevice.current.batteryLevel) * 100)
            let body = "\(percent)"
            let response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n\(body)"
            sendResponse(connection, response: response, id: id)
            
        } else if uri.contains("/tally/") {
            // PUT /v1/tally/<status>/ HTTP/1.1
            // e.g. PUT /v1/tally/program/
            var status = "idle"
            if uri.contains("/program") {
                status = "program"
            } else if uri.contains("/preview") {
                status = "preview"
            }
            
            DispatchQueue.main.async {
                self.tallyStatus = status
            }
            
            let response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            sendResponse(connection, response: response, id: id)
            
        } else if uri.contains("/video") {
            // GET /v5/video/<format>/<width>x<height>/...
            // Parse format, width, height
            var format = "avc"
            var width = 1280
            var height = 720
            
            // Regex to parse /video/(avc|jpg|hevc)/(\d+)x(\d+)
            let regexStr = "/video/(avc|jpg|hevc)/(\\d+)x(\\d+)"
            if let regex = try? NSRegularExpression(pattern: regexStr, options: .caseInsensitive) {
                let nsString = uri as NSString
                let results = regex.matches(in: uri, options: [], range: NSRange(location: 0, length: nsString.length))
                if let match = results.first {
                    if match.numberOfRanges >= 4 {
                        format = nsString.substring(with: match.range(at: 1))
                        if let w = Int(nsString.substring(with: match.range(at: 2))) { width = w }
                        if let h = Int(nsString.substring(with: match.range(at: 3))) { height = h }
                    }
                }
            }
            
            print("Accepted stream request: format=\(format) resolution=\(width)x\(height)")
            
            // Promote this connection to the active video socket
            closeActiveVideoConnection()
            self.activeVideoConnection = connection
            self.connections.removeValue(forKey: id) // remove from temp connections list
            
            DispatchQueue.main.async {
                self.isStreaming = true
                self.currentStatus = "OBS Streaming Live (\(format.uppercased()))"
                self.onStartStream?(format, width, height)
            }
            
            // Monitor disconnection
            monitorActiveVideoDisconnection(connection)
            
        } else {
            sendResponse(connection, response: "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", id: id)
        }
    }
    
    private func sendResponse(_ connection: NWConnection, response: String, id: UUID) {
        guard let data = response.data(using: .utf8) else {
            self.removeConnection(id)
            return
        }
        
        connection.send(content: data, completion: .contentProcessed({ [weak self] error in
            if let error = error {
                print("Failed to send response: \(error)")
            }
            self?.removeConnection(id)
        }))
    }
    
    private func monitorActiveVideoDisconnection(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { [weak self] data, context, isComplete, error in
            guard let self = self else { return }
            
            if error != nil || isComplete {
                print("OBS Client disconnected from video stream")
                self.handleStreamDisconnect()
            } else {
                // Keep reading/monitoring for disconnection
                self.monitorActiveVideoDisconnection(connection)
            }
        }
    }
    
    private func handleStreamDisconnect() {
        closeActiveVideoConnection()
        DispatchQueue.main.async {
            self.onStopStream?()
        }
    }
    
    // Sends a frame to the active video connection.
    // 12-byte header: 8-byte big-endian PTS (timestamp), 4-byte big-endian frame length.
    func sendVideoFrame(pts: Int64, data: Data) {
        guard let connection = activeVideoConnection else {
            return
        }
        
        var header = Data()
        var bigPts = pts.bigEndian
        var bigLen = Int32(data.count).bigEndian
        
        withUnsafeBytes(of: &bigPts) { header.append(contentsOf: $0) }
        withUnsafeBytes(of: &bigLen) { header.append(contentsOf: $0) }
        
        let packet = header + data
        
        connection.send(content: packet, completion: .contentProcessed({ [weak self] error in
            guard let self = self else { return }
            if let error = error {
                print("Socket write failed: \(error)")
                self.handleStreamDisconnect()
            } else {
                let bytesCount = Int64(packet.count)
                DispatchQueue.main.async {
                    self.totalBytesSent += bytesCount
                    self.bytesSentLastSec += bytesCount
                }
            }
        }))
    }
    
    private func startSpeedTracker() {
        speedTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            
            // Format TX Speed
            let rateKB = Double(self.bytesSentLastSec) / 1024.0
            if rateKB > 1024.0 {
                let rateMB = rateKB / 1024.0
                self.txRateText = String(format: "%.1f MB/s", rateMB)
            } else {
                self.txRateText = String(format: "%.1f KB/s", rateKB)
            }
            self.bytesSentLastSec = 0
            
            // Format Total TX
            let totalMB = Double(self.totalBytesSent) / (1024.0 * 1024.0)
            self.totalTxText = String(format: "%.1f MB", totalMB)
        }
    }
}
