import Foundation
import Combine

class SettingsManager: ObservableObject {
    static let shared = SettingsManager()
    
    @Published var port: Int {
        didSet { UserDefaults.standard.set(port, forKey: "port") }
    }
    @Published var resolution: String {
        didSet { UserDefaults.standard.set(resolution, forKey: "resolution") }
    }
    @Published var format: String {
        didSet { UserDefaults.standard.set(format, forKey: "format") }
    }
    @Published var framerate: Int {
        didSet { UserDefaults.standard.set(framerate, forKey: "framerate") }
    }
    @Published var bitrate: Int {
        didSet { UserDefaults.standard.set(bitrate, forKey: "bitrate") }
    }
    @Published var keepScreenOn: Bool {
        didSet { UserDefaults.standard.set(keepScreenOn, forKey: "keepScreenOn") }
    }
    @Published var flipHorizontal: Bool {
        didSet { UserDefaults.standard.set(flipHorizontal, forKey: "flipHorizontal") }
    }
    @Published var flipVertical: Bool {
        didSet { UserDefaults.standard.set(flipVertical, forKey: "flipVertical") }
    }
    @Published var faceAutoFocus: Bool {
        didSet { UserDefaults.standard.set(faceAutoFocus, forKey: "faceAutoFocus") }
    }
    @Published var videoStabilization: Bool {
        didSet { UserDefaults.standard.set(videoStabilization, forKey: "videoStabilization") }
    }
    @Published var activeCameraId: String {
        didSet { UserDefaults.standard.set(activeCameraId, forKey: "activeCameraId") }
    }
    
    init() {
        UserDefaults.standard.register(defaults: [
            "port": 4747,
            "resolution": "1280x720",
            "format": "avc",
            "framerate": 30,
            "bitrate": 3000000,
            "keepScreenOn": true,
            "flipHorizontal": false,
            "flipVertical": false,
            "faceAutoFocus": true,
            "videoStabilization": true,
            "activeCameraId": "back"
        ])
        
        self.port = UserDefaults.standard.integer(forKey: "port")
        self.resolution = UserDefaults.standard.string(forKey: "resolution") ?? "1280x720"
        self.format = UserDefaults.standard.string(forKey: "format") ?? "avc"
        self.framerate = UserDefaults.standard.integer(forKey: "framerate")
        self.bitrate = UserDefaults.standard.integer(forKey: "bitrate")
        self.keepScreenOn = UserDefaults.standard.bool(forKey: "keepScreenOn")
        self.flipHorizontal = UserDefaults.standard.bool(forKey: "flipHorizontal")
        self.flipVertical = UserDefaults.standard.bool(forKey: "flipVertical")
        self.faceAutoFocus = UserDefaults.standard.bool(forKey: "faceAutoFocus")
        self.videoStabilization = UserDefaults.standard.bool(forKey: "videoStabilization")
        self.activeCameraId = UserDefaults.standard.string(forKey: "activeCameraId") ?? "back"
    }
}
