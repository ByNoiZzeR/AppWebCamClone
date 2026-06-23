import SwiftUI
import AVFoundation
import SystemConfiguration
import Darwin

// MARK: - iOS 26 Design System
struct DesignTokens {
    // Backgrounds
    static let background = Color.black
    static let surface = Color(red: 28/255, green: 28/255, blue: 30/255)
    static let surfaceGlass = Color(red: 44/255, green: 44/255, blue: 46/255).opacity(0.7)
    
    // Text
    static let label = Color.white
    static let labelSecondary = Color(red: 142/255, green: 142/255, blue: 147/255)
    static let labelTertiary = Color(red: 99/255, green: 99/255, blue: 102/255)
    
    // Separators
    static let separator = Color(red: 56/255, green: 56/255, blue: 58/255)
    
    // Accents
    static let accent = Color(red: 0/255, green: 122/255, blue: 255/255) // System Blue
    static let green = Color(red: 48/255, green: 209/255, blue: 88/255)  // System Green
    static let orange = Color(red: 255/255, green: 159/255, blue: 10/255) // System Orange
    static let red = Color(red: 255/255, green: 69/255, blue: 58/255)     // System Red
    static let indigo = Color(red: 94/255, green: 92/255, blue: 230/255)  // System Indigo
    static let pink = Color(red: 255/255, green: 55/255, blue: 95/255)    // System Pink
    static let teal = Color(red: 100/255, green: 210/255, blue: 255/255)  // System Teal
    
    // Radii
    static let radiusSmall: CGFloat = 10
    static let radiusMedium: CGFloat = 16
    static let radiusLarge: CGFloat = 24
    static let radiusXL: CGFloat = 28
}

struct ContentView: View {
    @StateObject private var settings = SettingsManager.shared
    @StateObject private var socketServer = SocketServer()
    @StateObject private var streamer: CameraStreamer
    @Environment(\.verticalSizeClass) var verticalSizeClass
    @Environment(\.scenePhase) var scenePhase
    
    @State private var showSettings = false
    @State private var guideMode = 0 // 0: None, 1: 3x3 Grid, 2: Crosshair, 3: TikTok 9:16
    @State private var isPreviewMuted = false
    @State private var isDimMode = false
    @State private var isUiHidden = false
    
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
            // Full black camera background
            Color.black
                .ignoresSafeArea()
            
            // 1. Live Camera Preview (if not muted)
            if !isPreviewMuted {
                CameraPreview(session: streamer.captureSession)
                    .ignoresSafeArea()
            } else {
                // Preview Mute screen
                ZStack {
                    Color.black
                        .ignoresSafeArea()
                    
                    VStack(spacing: 16) {
                        Image(systemName: "video.slash.fill")
                            .font(.system(size: 52, weight: .light))
                            .foregroundColor(DesignTokens.orange)
                        
                        Text("Preview Paused")
                            .font(.system(size: 20, weight: .semibold, design: .rounded))
                            .foregroundColor(DesignTokens.label)
                        
                        Text("OBS stream is still active")
                            .font(.system(size: 14, weight: .regular))
                            .foregroundColor(DesignTokens.green)
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
                if !isUiHidden {
                    // Top HUD
                    topHudView
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                
                Spacer()
                    .allowsHitTesting(false)
                
                if !isUiHidden {
                    // Bottom control panel
                    bottomPanel
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .ignoresSafeArea(.all, edges: .top)
            
            // 4. OLED Saver Dim Mode Overlay
            if isDimMode {
                DimModeOverlay(isDimMode: $isDimMode)
                    .ignoresSafeArea()
            }
        }
        .onTapGesture(count: 2) {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                isUiHidden.toggle()
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
            
            socketServer.onUpdateSetting = { key, val in
                DispatchQueue.main.async {
                    if key == "camera" {
                        let requestedPosition: AVCaptureDevice.Position = (val == "front" || val == "1") ? .front : .back
                        if streamer.activeCameraPosition != requestedPosition {
                            streamer.switchCamera()
                        }
                    } else if key == "resolution" {
                        settings.resolution = val
                    } else if key == "format" {
                        settings.format = val
                    } else if key == "framerate" {
                        if let fps = Int(val) {
                            settings.framerate = fps
                        }
                    } else if key == "bitrate" {
                        if let br = Int(val) {
                            settings.bitrate = br
                        }
                    } else if key == "face_auto_focus" {
                        settings.faceAutoFocus = (val == "true")
                    } else if key == "keep_screen_on" {
                        settings.keepScreenOn = (val == "true")
                        UIApplication.shared.isIdleTimerDisabled = settings.keepScreenOn
                    } else if key == "flip_horizontal" {
                        settings.flipHorizontal = (val == "true")
                    } else if key == "flip_vertical" {
                        settings.flipVertical = (val == "true")
                    } else if key == "trigger_af" {
                        streamer.triggerAutofocus()
                    } else if key == "toggle_af_mode" {
                        streamer.triggerAutofocus()
                    }
                }
            }
            
            socketServer.getStatusJSON = {
                let battery = batteryPercent
                let isStreaming = socketServer.isStreaming
                let faceAf = settings.faceAutoFocus
                let keepOn = settings.keepScreenOn
                let flipH = settings.flipHorizontal
                let flipV = settings.flipVertical
                let activeCamera = streamer.activeCameraPosition == .back ? "0" : "1"
                let isAutofocusLocked = streamer.focusModeText == "AUTO-L"
                let resolution = settings.resolution
                let bitrate = settings.bitrate
                let framerate = settings.framerate
                
                let supportedResolutions = streamer.getSupportedResolutions()
                let resolutionsJSON = "[" + supportedResolutions.map { "\"\($0)\"" }.joined(separator: ",") + "]"
                
                return """
                {
                    "battery": \(battery),
                    "isStreaming": \(isStreaming),
                    "faceAutoFocus": \(faceAf),
                    "keepScreenOn": \(keepOn),
                    "flipHorizontal": \(flipH),
                    "flipVertical": \(flipV),
                    "activeCameraId": "\(activeCamera)",
                    "isAutofocusLocked": \(isAutofocusLocked),
                    "resolution": "\(resolution)",
                    "bitrate": \(bitrate),
                    "framerate": \(framerate),
                    "availableCameras": [{"id":"0","facing":"Back"},{"id":"1","facing":"Front"}],
                    "availableResolutions": \(resolutionsJSON)
                }
                """
            }
            
            updateLocalTelemetry()
        }
        .onReceive(telemetryTimer) { _ in
            updateLocalTelemetry()
        }
        .sheet(isPresented: $showSettings) {
            SettingsSheetView(showSettings: $showSettings, socketServer: socketServer)
        }
        .onChange(of: scenePhase) { newPhase in
            if newPhase == .active {
                print("App active: restarting camera preview and socket server...")
                streamer.startPreview()
                socketServer.start(port: settings.port)
            } else if newPhase == .background {
                print("App in background: stopping camera preview and socket server to release port...")
                streamer.stopPreview()
                socketServer.stop()
            }
        }
    }
    
    // MARK: - Top HUD — iOS 26 Dynamic Island Style
    private var topHudView: some View {
        let isLandscape = verticalSizeClass == .compact
        return HStack(spacing: 10) {
            // App Name — clean SF Pro
            Text("Studio Cam")
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .foregroundColor(DesignTokens.label)
            
            Spacer()
            
            // Status Pill — Dynamic Island inspired
            HStack(spacing: 6) {
                Circle()
                    .fill(socketServer.isStreaming ? DesignTokens.green : DesignTokens.labelSecondary)
                    .frame(width: 7, height: 7)
                
                Text(socketServer.isStreaming ? "Live" : "Standby")
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundColor(socketServer.isStreaming ? DesignTokens.green : DesignTokens.labelSecondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                Capsule()
                    .fill(socketServer.isStreaming
                          ? DesignTokens.green.opacity(0.15)
                          : Color.white.opacity(0.08))
            )
            
            Spacer()
            
            // IP : Port
            Text("\(getWiFiAddress() ?? "No WiFi"):\(settings.port)")
                .font(.system(size: 11, weight: .regular, design: .monospaced))
                .foregroundColor(DesignTokens.labelTertiary)
            
            // Dim Mode Button
            Button(action: {
                isDimMode = true
            }) {
                Image(systemName: "moon.fill")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(DesignTokens.labelSecondary)
                    .frame(width: 30, height: 30)
                    .background(
                        Circle()
                            .fill(Color.white.opacity(0.08))
                    )
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, isLandscape ? 8 : 52)
        .padding(.bottom, 10)
        .background(
            Material.ultraThinMaterial
        )
    }
    
    // MARK: - Bottom Panel — iOS 26 Glass Sheet
    private var bottomPanel: some View {
        let isLandscape = verticalSizeClass == .compact
        return VStack(spacing: isLandscape ? 8 : 14) {
            // Telemetry Metrics Row
            HStack(spacing: 6) {
                MetricTile(label: "Rate", value: socketServer.txRateText, color: DesignTokens.accent)
                MetricTile(label: "Total", value: socketServer.totalTxText, color: DesignTokens.accent)
                if !isLandscape {
                    MetricTile(label: "Temp", value: deviceTemp, color: DesignTokens.green)
                    MetricTile(label: "Battery", value: "\(batteryPercent)%", color: DesignTokens.green)
                }
                MetricTile(label: "Focus", value: streamer.focusModeText, color: DesignTokens.teal)
                MetricTile(label: "Filter", value: streamer.filterModeText, color: DesignTokens.labelSecondary)
            }
            .frame(height: isLandscape ? 42 : 52)
            
            // Action Buttons Row
            HStack(spacing: isLandscape ? 6 : 10) {
                CircleActionButton(icon: "arrow.triangle.2.circlepath", label: "Flip", isActive: false, activeColor: DesignTokens.accent) {
                    streamer.switchCamera()
                }
                
                CircleActionButton(icon: "bolt.fill", label: "Light", isActive: streamer.torchEnabled, activeColor: DesignTokens.orange) {
                    streamer.toggleTorch()
                }
                
                CircleActionButton(icon: "paintpalette.fill", label: "Filter", isActive: false, activeColor: DesignTokens.pink) {
                    streamer.cycleFilter()
                }
                
                CircleActionButton(icon: "scope", label: "Focus", isActive: false, activeColor: DesignTokens.green) {
                    streamer.triggerAutofocus()
                }
                
                CircleActionButton(icon: "gearshape.fill", label: "Settings", isActive: false, activeColor: DesignTokens.labelSecondary) {
                    showSettings = true
                }
                
                CircleActionButton(icon: "grid", label: "Guide", isActive: guideMode > 0, activeColor: DesignTokens.teal) {
                    guideMode = (guideMode + 1) % 4
                }
                
                CircleActionButton(icon: isPreviewMuted ? "eye.slash.fill" : "eye.fill", label: "Preview", isActive: isPreviewMuted, activeColor: DesignTokens.orange) {
                    isPreviewMuted.toggle()
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, isLandscape ? 10 : 16)
        .padding(.bottom, isLandscape ? 10 : 28)
        .background(
            RoundedCorner(radius: DesignTokens.radiusXL, corners: [.topLeft, .topRight])
                .fill(Material.regularMaterial)
        )
        .padding(.horizontal, 8)
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

// MARK: - Camera Preview UIView
class PreviewUIView: UIView {
    private var previewLayer: AVCaptureVideoPreviewLayer?
    
    init(session: AVCaptureSession) {
        super.init(frame: .zero)
        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        self.layer.addSublayer(previewLayer)
        self.previewLayer = previewLayer
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    override func layoutSubviews() {
        super.layoutSubviews()
        
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer?.frame = self.bounds
        CATransaction.commit()
        
        updateOrientation()
    }
    
    func updateOrientation() {
        guard let connection = previewLayer?.connection, connection.isVideoOrientationSupported else { return }
        if let windowScene = self.window?.windowScene {
            let orientation = windowScene.interfaceOrientation
            switch orientation {
            case .portrait:
                connection.videoOrientation = .portrait
            case .portraitUpsideDown:
                connection.videoOrientation = .portraitUpsideDown
            case .landscapeLeft:
                connection.videoOrientation = .landscapeLeft
            case .landscapeRight:
                connection.videoOrientation = .landscapeRight
            default:
                break
            }
        }
    }
    
    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateOrientation()
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    
    func makeUIView(context: Context) -> PreviewUIView {
        return PreviewUIView(session: session)
    }
    
    func updateUIView(_ uiView: PreviewUIView, context: Context) {
        uiView.updateOrientation()
    }
}

// MARK: - Metric Tile (iOS 26 Style)
struct MetricTile: View {
    let label: String
    let value: String
    let color: Color
    
    var body: some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.system(size: 12, weight: .bold, design: .rounded))
                .foregroundColor(color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            
            Text(label)
                .font(.system(size: 8, weight: .medium))
                .foregroundColor(DesignTokens.labelTertiary)
                .textCase(.uppercase)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: DesignTokens.radiusSmall)
                .fill(Color.white.opacity(0.06))
        )
    }
}

// MARK: - Circle Action Button (iOS 26 Style)
struct CircleActionButton: View {
    let icon: String
    let label: String
    var isActive: Bool = false
    var activeColor: Color = DesignTokens.accent
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                ZStack {
                    Circle()
                        .fill(isActive ? activeColor : Color.white.opacity(0.1))
                        .frame(width: 44, height: 44)
                    
                    Image(systemName: icon)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(isActive ? .black : .white)
                }
                
                Text(label)
                    .font(.system(size: 9, weight: .medium))
                    .foregroundColor(isActive ? activeColor : DesignTokens.labelSecondary)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(ScaleButtonStyle())
    }
}

// MARK: - Scale Button Style (iOS 26 micro-interaction)
struct ScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1.0)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
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
                    .stroke(Color.white.opacity(0.25), lineWidth: 0.5)
                } else if mode == 2 {
                    // Crosshair
                    ZStack {
                        Path { path in
                            path.move(to: CGPoint(x: w/2, y: h/2 - 20))
                            path.addLine(to: CGPoint(x: w/2, y: h/2 + 20))
                            path.move(to: CGPoint(x: w/2 - 20, y: h/2))
                            path.addLine(to: CGPoint(x: w/2 + 20, y: h/2))
                        }
                        .stroke(Color.white.opacity(0.3), lineWidth: 0.5)
                        
                        Circle()
                            .stroke(Color.white.opacity(0.3), lineWidth: 0.5)
                            .frame(width: 16, height: 16)
                    }
                } else if mode == 3 {
                    // TikTok 9:16 Crop Box
                    let boxH = h
                    let boxW = h * (9.0 / 16.0)
                    let left = (w - boxW) / 2
                    
                    ZStack {
                        // Side dimming overlays
                        Color.black.opacity(0.5)
                            .frame(width: left, height: h)
                            .position(x: left / 2, y: h / 2)
                        
                        Color.black.opacity(0.5)
                            .frame(width: left, height: h)
                            .position(x: w - left / 2, y: h / 2)
                        
                        Rectangle()
                            .stroke(Color.white.opacity(0.3), lineWidth: 1)
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
            
            VStack(spacing: 10) {
                Text("Streaming Active")
                    .font(.system(size: 18, weight: .semibold, design: .rounded))
                    .foregroundColor(DesignTokens.green.opacity(0.5))
                
                Text("Tap anywhere to restore screen")
                    .font(.system(size: 13, weight: .regular))
                    .foregroundColor(DesignTokens.labelTertiary.opacity(0.5))
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
                textOffset = CGSize(width: 10, height: -20)
            }
        }
    }
}

// MARK: - Settings Sheet — iOS 26 Grouped List Style
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
    let bitrates: [(value: Int, label: String)] = [
        (1000000, "1 Mbps"),
        (2000000, "2 Mbps"),
        (3000000, "3 Mbps"),
        (5000000, "5 Mbps"),
        (10000000, "10 Mbps"),
        (15000000, "15 Mbps"),
        (20000000, "20 Mbps"),
        (30000000, "30 Mbps")
    ]
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    // Drag Indicator
                    Capsule()
                        .fill(Color.white.opacity(0.2))
                        .frame(width: 36, height: 5)
                        .padding(.top, 8)
                    
                    // Network Section
                    SettingsSection(title: "Network") {
                        HStack {
                            Label("Port", systemImage: "network")
                                .font(.system(size: 15, weight: .regular))
                                .foregroundColor(DesignTokens.label)
                            Spacer()
                            TextField("Port", text: $localPort)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                                .font(.system(size: 15, weight: .regular, design: .monospaced))
                                .foregroundColor(DesignTokens.accent)
                                .frame(width: 80)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    
                    // Video Section
                    SettingsSection(title: "Video Encoder") {
                        VStack(spacing: 0) {
                            // Format
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Format")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(DesignTokens.labelSecondary)
                                    .padding(.horizontal, 16)
                                
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        ForEach(formats, id: \.self) { format in
                                            ChipButton(
                                                title: format.uppercased(),
                                                isSelected: selectedFormat == format
                                            ) {
                                                selectedFormat = format
                                            }
                                        }
                                    }
                                    .padding(.horizontal, 16)
                                }
                            }
                            .padding(.vertical, 12)
                            
                            Divider().background(DesignTokens.separator)
                                .padding(.horizontal, 16)
                            
                            // Resolution
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Resolution")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(DesignTokens.labelSecondary)
                                    .padding(.horizontal, 16)
                                
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        ForEach(resolutions, id: \.self) { res in
                                            ChipButton(
                                                title: resolutionLabel(res),
                                                isSelected: selectedResolution == res
                                            ) {
                                                selectedResolution = res
                                            }
                                        }
                                    }
                                    .padding(.horizontal, 16)
                                }
                            }
                            .padding(.vertical, 12)
                            
                            Divider().background(DesignTokens.separator)
                                .padding(.horizontal, 16)
                            
                            // Framerate
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Framerate")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(DesignTokens.labelSecondary)
                                    .padding(.horizontal, 16)
                                
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        ForEach(framerates, id: \.self) { fps in
                                            ChipButton(
                                                title: "\(fps) FPS",
                                                isSelected: selectedFramerate == fps
                                            ) {
                                                selectedFramerate = fps
                                            }
                                        }
                                    }
                                    .padding(.horizontal, 16)
                                }
                            }
                            .padding(.vertical, 12)
                            
                            Divider().background(DesignTokens.separator)
                                .padding(.horizontal, 16)
                            
                            // Bitrate
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Bitrate")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(DesignTokens.labelSecondary)
                                    .padding(.horizontal, 16)
                                
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        ForEach(bitrates, id: \.value) { br in
                                            ChipButton(
                                                title: br.label,
                                                isSelected: selectedBitrate == br.value
                                            ) {
                                                selectedBitrate = br.value
                                            }
                                        }
                                    }
                                    .padding(.horizontal, 16)
                                }
                            }
                            .padding(.vertical, 12)
                        }
                    }
                    
                    // Preferences Section
                    SettingsSection(title: "Preferences") {
                        VStack(spacing: 0) {
                            SettingsToggleRow(icon: "sun.max.fill", title: "Keep Screen On", isOn: $settings.keepScreenOn)
                            Divider().background(DesignTokens.separator).padding(.horizontal, 16)
                            SettingsToggleRow(icon: "arrow.left.and.right", title: "Flip Horizontal", isOn: $settings.flipHorizontal)
                            Divider().background(DesignTokens.separator).padding(.horizontal, 16)
                            SettingsToggleRow(icon: "arrow.up.and.down", title: "Flip Vertical", isOn: $settings.flipVertical)
                            Divider().background(DesignTokens.separator).padding(.horizontal, 16)
                            SettingsToggleRow(icon: "face.smiling", title: "Face Auto-Focus", isOn: $settings.faceAutoFocus)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 40)
            }
            .background(Color(red: 18/255, green: 18/255, blue: 20/255).ignoresSafeArea())
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        showSettings = false
                    }
                    .foregroundColor(DesignTokens.accent)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Save") {
                        saveSettings()
                        showSettings = false
                    }
                    .fontWeight(.semibold)
                    .foregroundColor(DesignTokens.accent)
                }
            }
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
    
    private func resolutionLabel(_ res: String) -> String {
        switch res {
        case "3840x2160": return "4K"
        case "2560x1440": return "2K"
        case "1920x1080": return "1080p"
        case "1280x720": return "720p"
        case "640x480": return "480p"
        default: return res
        }
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

// MARK: - Settings Section Container
struct SettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(DesignTokens.labelTertiary)
                .padding(.horizontal, 4)
            
            VStack(spacing: 0) {
                content
            }
            .background(
                RoundedRectangle(cornerRadius: DesignTokens.radiusMedium)
                    .fill(DesignTokens.surface)
            )
        }
    }
}

// MARK: - Settings Toggle Row
struct SettingsToggleRow: View {
    let icon: String
    let title: String
    @Binding var isOn: Bool
    
    var body: some View {
        HStack {
            Label(title, systemImage: icon)
                .font(.system(size: 15, weight: .regular))
                .foregroundColor(DesignTokens.label)
            Spacer()
            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(DesignTokens.green)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }
}

// MARK: - Chip Button (Segmented Selection)
struct ChipButton: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? .black : DesignTokens.labelSecondary)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    Capsule()
                        .fill(isSelected ? Color.white : Color.white.opacity(0.08))
                )
        }
        .buttonStyle(ScaleButtonStyle())
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
