import SwiftUI
import AVFoundation
import SystemConfiguration
import Darwin

struct ContentView: View {
    @StateObject private var settings = SettingsManager.shared
    @StateObject private var socketServer = SocketServer()
    @StateObject private var streamer: CameraStreamer
    
    @State private var showSettings = false
    @State private var guideMode = 0 // 0: None, 1: 3x3 Grid, 2: Crosshair, 3: TikTok 9:16
    @State private var isPreviewMuted = false
    @State private var isDimMode = false
    
    // Timer to update local telemetry (temp, battery)
    @State private var batteryPercent = 100
    @State private var deviceTemp = "28.5°C"
    let telemetryTimer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()
    
    init() {
        let server = SocketServer()
        _socketServer = StateObject(wrappedValue: server)
        _streamer = StateObject(wrappedValue: CameraStreamer(socketServer: server))
    }
    
    var body: some View {
        ZStack {
            // Deep navy studio background
            Color(red: 8/255, green: 11/255, blue: 20/255)
                .ignoresSafeArea()
            
            // 1. Live Camera Preview (if not muted)
            if !isPreviewMuted {
                CameraPreview(session: streamer.captureSession)
                    .ignoresSafeArea()
            } else {
                // Preview Mute screen
                ZStack {
                    Color(red: 5/255, green: 5/255, blue: 8/255)
                        .ignoresSafeArea()
                    
                    VStack(spacing: 12) {
                        Image(systemName: "video.slash.fill")
                            .font(.system(size: 64))
                            .foregroundColor(.orange)
                        
                        Text("PREVIEW PAUSED")
                            .font(.system(.headline, design: .rounded))
                            .fontWeight(.bold)
                            .foregroundColor(.orange)
                            .tracking(2)
                        
                        Text("OBS stream is still active")
                            .font(.system(.subheadline, design: .monospaced))
                            .foregroundColor(Color(red: 0, green: 1, blue: 0.4))
                    }
                }
            }
            
            // 2. Framing Guides Overlay
            if guideMode > 0 && !isPreviewMuted {
                GuidesOverlay(mode: guideMode)
                    .ignoresSafeArea()
            }
            
            // 3. Main HUD UI
            VStack(spacing: 0) {
                // Top HUD minimal bar
                topHudView
                
                Spacer()
                
                // Bottom control panel (visible only when UI is not hidden)
                bottomPanel
            }
            .ignoresSafeArea(.all, edges: .top)
            
            // 4. OLED Saver Dim Mode Overlay
            if isDimMode {
                DimModeOverlay(isDimMode: $isDimMode)
                    .ignoresSafeArea()
            }
        }
        .onAppear {
            streamer.startPreview()
            socketServer.start(port: settings.port)
            
            socketServer.onStartStream = { format, w, h in
                streamer.startStreaming(format: format, width: w, height: h)
            }
            
            socketServer.onStopStream = {
                streamer.stopStreaming()
            }
            
            updateLocalTelemetry()
        }
        .onReceive(telemetryTimer) { _ in
            updateLocalTelemetry()
        }
        .sheet(isPresented: $showSettings) {
            SettingsSheetView(showSettings: $showSettings, socketServer: socketServer)
        }
    }
    
    // MARK: - Top HUD
    private var topHudView: some View {
        HStack(spacing: 12) {
            // Logo
            HStack(spacing: 2) {
                Text("STUDIO")
                    .foregroundColor(Color(red: 129/255, green: 140/255, blue: 248/255))
                    .fontWeight(.bold)
                    .tracking(2)
                Text("CAM")
                    .foregroundColor(Color(red: 148/255, green: 163/255, blue: 184/255))
                    .fontWeight(.light)
                    .tracking(2)
            }
            .font(.system(size: 13))
            
            Spacer()
            
            // Status LED Badge
            HStack(spacing: 6) {
                Circle()
                    .fill(socketServer.isStreaming ? Color.green : Color.red)
                    .frame(width: 6, height: 6)
                    .opacity(socketServer.isStreaming ? 1.0 : 0.6)
                
                Text(socketServer.isStreaming ? "OBS LIVE" : "STANDBY")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(socketServer.isStreaming ? .green : .red)
                    .tracking(1)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(
                Capsule()
                    .fill(socketServer.isStreaming ? Color.green.opacity(0.1) : Color.red.opacity(0.1))
                    .overlay(
                        Capsule().stroke(socketServer.isStreaming ? Color.green.opacity(0.4) : Color.red.opacity(0.4), lineWidth: 1)
                    )
            )
            
            Spacer()
            
            // IP / Port Text
            Text("\(getWiFiAddress() ?? "No WiFi"): \(settings.port)")
                .font(.system(size: 9, design: .monospaced))
                .foregroundColor(Color(red: 148/255, green: 163/255, blue: 184/255))
            
            // Dim Mode Moon Button
            Button(action: {
                isDimMode = true
            }) {
                Text("◐")
                    .font(.system(size: 18))
                    .foregroundColor(Color(red: 99/255, green: 102/255, blue: 241/255))
                    .padding(4)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 48) // Safe area padding
        .padding(.bottom, 12)
        .background(
            Color(red: 8/255, green: 11/255, blue: 20/255).opacity(0.92)
                .overlay(
                    Rectangle().frame(height: 1).foregroundColor(Color(red: 30/255, green: 41/255, blue: 64/255)), alignment: .bottom
                )
        )
    }
    
    // MARK: - Bottom Panel
    private var bottomPanel: some View {
        VStack(spacing: 12) {
            // Telemetry Grid
            HStack(spacing: 6) {
                TelemetryCard(label: "RATE", value: socketServer.txRateText, color: Color(red: 129/255, green: 140/255, blue: 248/255))
                TelemetryCard(label: "TOTAL", value: socketServer.totalTxText, color: Color(red: 129/255, green: 140/255, blue: 248/255))
                TelemetryCard(label: "TEMP", value: deviceTemp, color: .green)
                TelemetryCard(label: "BATTERY", value: "\(batteryPercent)%", color: .green)
                TelemetryCard(label: "FOCUS", value: streamer.focusModeText, color: .green)
                TelemetryCard(label: "FILTER", value: streamer.filterModeText, color: Color(red: 148/255, green: 163/255, blue: 184/255))
            }
            .frame(height: 48)
            
            Divider()
                .background(Color(red: 30/255, green: 41/255, blue: 64/255))
            
            // Action Buttons
            HStack(spacing: 6) {
                ActionButton(icon: "arrow.triangle.2.circlepath", title: "FLIP") {
                    streamer.switchCamera()
                }
                
                ActionButton(icon: "bolt.fill", title: "LIGHT", isActive: streamer.torchEnabled, activeColor: .amber) {
                    streamer.toggleTorch()
                }
                
                ActionButton(icon: "paintpalette.fill", title: "FILTER") {
                    streamer.cycleFilter()
                }
                
                ActionButton(icon: "scope", title: "FOCUS") {
                    streamer.triggerAutofocus()
                }
                
                ActionButton(icon: "gearshape.fill", title: "SETUP") {
                    showSettings = true
                }
                
                ActionButton(icon: "grid", title: "GUIDE", isActive: guideMode > 0, activeColor: .sky) {
                    guideMode = (guideMode + 1) % 4
                }
                
                ActionButton(icon: isPreviewMuted ? "eye.slash.fill" : "eye.fill", title: "PRVW", isActive: isPreviewMuted, activeColor: .orange) {
                    isPreviewMuted.toggle()
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 14)
        .padding(.bottom, 24)
        .background(
            Color(red: 8/255, green: 11/255, blue: 20/255).opacity(0.94)
                .cornerRadius(18, corners: [.topLeft, .topRight])
                .overlay(
                    RoundedCorner(radius: 18, corners: [.topLeft, .topRight])
                        .stroke(Color(red: 30/255, green: 41/255, blue: 64/255), lineWidth: 1)
                )
        )
        .padding(.horizontal, 12)
        .padding(.bottom, 12)
    }
    
    // MARK: - Helpers
    private func updateLocalTelemetry() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        batteryPercent = Int(max(0, UIDevice.current.batteryLevel) * 100)
        
        // Map iOS thermal state to temperature approximations for aesthetic telemetry
        switch ProcessInfo.processInfo.thermalState {
        case .nominal:
            deviceTemp = "29.4°C"
        case .fair:
            deviceTemp = "34.8°C"
        case .serious:
            deviceTemp = "41.2°C"
        case .critical:
            deviceTemp = "46.7°C"
        @unknown default:
            deviceTemp = "28.0°C"
        }
    }
    
    private func getWiFiAddress() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return nil }
        guard let firstAddr = ifaddr else { return nil }
        
        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family
            if addrFamily == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                if name == "en0" { // Wifi interface on iOS
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                                &hostname, socklen_t(hostname.count),
                                nil, socklen_t(0), NI_NUMERICHOST)
                    address = String(cString: hostname)
                }
            }
        }
        freeifaddrs(ifaddr)
        return address
    }
}

// MARK: - Camera Preview Layer Representable
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    
    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)
        context.coordinator.previewLayer = previewLayer
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {
        DispatchQueue.main.async {
            context.coordinator.previewLayer?.frame = uiView.bounds
        }
    }
    
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }
    
    class Coordinator {
        var previewLayer: AVCaptureVideoPreviewLayer?
    }
}

// MARK: - Telemetry Card UI
struct TelemetryCard: View {
    let label: String
    let value: String
    let color: Color
    
    var body: some View {
        VStack(spacing: 3) {
            Text(label)
                .font(.system(size: 7, weight: .bold))
                .foregroundColor(Color(red: 148/255, green: 163/255, blue: 184/255))
                .tracking(0.5)
            
            Text(value)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundColor(color)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(red: 22/255, green: 27/255, blue: 39/255))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color(red: 30/255, green: 41/255, blue: 64/255), lineWidth: 1)
                )
        )
    }
}

// MARK: - Action Button UI
struct ActionButton: View {
    let icon: String
    let title: String
    var isActive: Bool = false
    var activeColor: Color = .blue
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 14))
                Text(title)
                    .font(.system(size: 9, weight: .bold))
                    .tracking(0.5)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .foregroundColor(isActive ? .white : Color(red: 241/255, green: 245/255, blue: 249/255))
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(isActive ? activeColor : Color(red: 22/255, green: 27/255, blue: 39/255))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(isActive ? activeColor : Color(red: 30/255, green: 41/255, blue: 64/255), lineWidth: 1)
                    )
            )
        }
    }
}

// Color Extensions for HUD Buttons
extension Color {
    static let amber = Color(red: 245/255, green: 158/255, blue: 11/255)
    static let sky = Color(red: 56/255, green: 189/255, blue: 248/255)
}

// MARK: - Guides Overlay Drawings
struct GuidesOverlay: View {
    let mode: Int // 1: 3x3, 2: Crosshair, 3: TikTok 9:16
    
    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            
            ZStack {
                if mode == 1 {
                    // 3x3 Grid
                    Path { path in
                        path.move(to: CGPoint(x: w / 3, y: 0))
                        path.addLine(to: CGPoint(x: w / 3, y: h))
                        path.move(to: CGPoint(x: w * 2 / 3, y: 0))
                        path.addLine(to: CGPoint(x: w * 2 / 3, y: h))
                        
                        path.move(to: CGPoint(x: 0, y: h / 3))
                        path.addLine(to: CGPoint(x: w, y: h / 3))
                        path.move(to: CGPoint(x: 0, y: h * 2 / 3))
                        path.addLine(to: CGPoint(x: w, y: h * 2 / 3))
                    }
                    .stroke(Color.indigo.opacity(0.4), lineWidth: 1)
                } else if mode == 2 {
                    // Crosshair
                    ZStack {
                        Path { path in
                            path.move(to: CGPoint(x: w/2, y: h/2 - 20))
                            path.addLine(to: CGPoint(x: w/2, y: h/2 + 20))
                            path.move(to: CGPoint(x: w/2 - 20, y: h/2))
                            path.addLine(to: CGPoint(x: w/2 + 20, y: h/2))
                        }
                        .stroke(Color.indigo.opacity(0.4), lineWidth: 1)
                        
                        Circle()
                            .stroke(Color.indigo.opacity(0.4), lineWidth: 1)
                            .frame(width: 16, height: 16)
                    }
                } else if mode == 3 {
                    // TikTok 9:16 Crop Box
                    let boxH = h
                    let boxW = h * (9.0 / 16.0)
                    let left = (w - boxW) / 2
                    
                    ZStack {
                        // Side dimming overlays
                        Color.black.opacity(0.6)
                            .frame(width: left, height: h)
                            .position(x: left / 2, y: h / 2)
                        
                        Color.black.opacity(0.6)
                            .frame(width: left, height: h)
                            .position(x: w - left / 2, y: h / 2)
                        
                        Rectangle()
                            .stroke(Color.indigo.opacity(0.4), lineWidth: 1.5)
                            .frame(width: boxW, height: boxH)
                    }
                }
            }
        }
    }
}

// MARK: - OLED Dim Mode Overlay
struct DimModeOverlay: View {
    @Binding var isDimMode: Bool
    @State private var textOffset = CGSize.zero
    let floatTimer = Timer.publish(every: 8, on: .main, in: .common).autoconnect()
    
    var body: some View {
        ZStack {
            Color.black.opacity(0.97)
                .onTapGesture {
                    isDimMode = false
                }
            
            VStack(spacing: 8) {
                Text("STREAMING ACTIVE")
                    .font(.system(.headline, design: .rounded))
                    .fontWeight(.bold)
                    .foregroundColor(Color.green.opacity(0.4))
                    .tracking(2)
                
                Text("Tap anywhere to restore screen")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundColor(Color.gray.opacity(0.4))
            }
            .offset(textOffset)
            .animation(.easeInOut(duration: 2.0), value: textOffset)
            .onReceive(floatTimer) { _ in
                // Slowly float text to prevent OLED burn-in
                let rangeX = UIScreen.main.bounds.width / 4
                let rangeY = UIScreen.main.bounds.height / 4
                let randX = CGFloat.random(in: -rangeX...rangeX)
                let randY = CGFloat.random(in: -rangeY...rangeY)
                textOffset = CGSize(width: randX, height: randY)
            }
            .onAppear {
                // Initialize floating offset
                textOffset = CGSize(width: 10, height: -20)
            }
        }
    }
}

// MARK: - Settings Control Dialog / Sheet
struct SettingsSheetView: View {
    @Binding var showSettings: Bool
    @ObservedObject var socketServer: SocketServer
    
    @StateObject private var settings = SettingsManager.shared
    
    @State private var localPort: String = ""
    @State private var selectedResolution = "1280x720"
    @State private var selectedFormat = "avc"
    @State private var selectedFramerate = 30
    @State private var selectedBitrate = 3000000
    
    let resolutions = ["640x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"]
    let formats = ["avc", "hevc", "jpg"]
    let framerates = [15, 24, 30, 60]
    let bitrates = [
        1000000: "1.0 Mbps",
        2000000: "2.0 Mbps",
        3000000: "3.0 Mbps",
        5000000: "5.0 Mbps",
        10000000: "10.0 Mbps",
        15000000: "15.0 Mbps",
        20000000: "20.0 Mbps",
        30000000: "30.0 Mbps"
    ]
    
    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Network Connection")) {
                    HStack {
                        Text("Port")
                        Spacer()
                        TextField("Port", text: $localPort)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                    }
                }
                
                Section(header: Text("Video Encoder Setup")) {
                    Picker("Format", selection: $selectedFormat) {
                        ForEach(formats, id: \.self) { format in
                            Text(format.uppercased()).tag(format)
                        }
                    }
                    
                    Picker("Resolution", selection: $selectedResolution) {
                        ForEach(resolutions, id: \.self) { res in
                            Text(res).tag(res)
                        }
                    }
                    
                    Picker("Framerate", selection: $selectedFramerate) {
                        ForEach(framerates, id: \.self) { fps in
                            Text("\(fps) FPS").tag(fps)
                        }
                    }
                    
                    Picker("Bitrate", selection: $selectedBitrate) {
                        ForEach(bitrates.keys.sorted(), id: \.self) { br in
                            Text(bitrates[br] ?? "").tag(br)
                        }
                    }
                }
                
                Section(header: Text("Preferences")) {
                    Toggle("Keep Screen On", isOn: $settings.keepScreenOn)
                    Toggle("Flip Horizontal", isOn: $settings.flipHorizontal)
                    Toggle("Flip Vertical", isOn: $settings.flipVertical)
                    Toggle("Face Auto-Focus", isOn: $settings.faceAutoFocus)
                }
            }
            .navigationTitle("Configuration")
            .navigationBarItems(
                leading: Button("Cancel") {
                    showSettings = false
                },
                trailing: Button("Save") {
                    saveSettings()
                    showSettings = false
                }
            )
            .onAppear {
                localPort = String(settings.port)
                selectedResolution = settings.resolution
                selectedFormat = settings.format
                selectedFramerate = settings.framerate
                selectedBitrate = settings.bitrate
            }
        }
        .preferredColorScheme(.dark)
    }
    
    private func saveSettings() {
        if let p = Int(localPort) {
            settings.port = p
            // Restart server on new port
            socketServer.start(port: p)
        }
        
        settings.resolution = selectedResolution
        settings.format = selectedFormat
        settings.framerate = selectedFramerate
        settings.bitrate = selectedBitrate
        
        // Apply keep screen on state
        UIApplication.shared.isIdleTimerDisabled = settings.keepScreenOn
    }
}

// MARK: - Utilities and Extensions
struct RoundedCorner: Shape {
    var radius: CGFloat = .infinity
    var corners: UIRectCorner = .allCorners

    func path(in rect: CGRect) -> Path {
        let path = UIBezierPath(roundedRect: rect, byRoundingCorners: corners, cornerRadii: CGSize(width: radius, height: radius))
        return Path(path.cgPath)
    }
}

extension View {
    func cornerRadius(_ radius: CGFloat, corners: UIRectCorner) -> some View {
        clipShape( RoundedCorner(radius: radius, corners: corners) )
    }
}
