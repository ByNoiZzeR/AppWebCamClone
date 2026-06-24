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
    
    // Accent colors list (Redesigned Theme selection)
    static let accentColors: [Color] = [
        Color(red: 100/255, green: 210/255, blue: 255/255), // Teal
        Color(red: 255/255, green: 55/255, blue: 95/255),   // Pink
        Color(red: 94/255, green: 92/255, blue: 230/255),   // Indigo
        Color(red: 255/255, green: 159/255, blue: 10/255),  // Orange
        Color(red: 48/255, green: 209/255, blue: 88/255)    // Green
    ]
    
    // Accents (Dynamic based on user selection)
    static var accent: Color {
        let index = UserDefaults.standard.integer(forKey: "selectedAccentIndex")
        guard index >= 0 && index < accentColors.count else { return accentColors[0] }
        return accentColors[index]
    }
    
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

// Procedural waveform simulating real-time audio levels
struct WaveformView: View {
    let isActive: Bool
    @State private var phase: CGFloat = 0.0
    
    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { context, size in
                let w = size.width
                let h = size.height
                let midY = h / 2
                
                context.stroke(
                    Path { path in
                        path.move(to: CGPoint(x: 0, y: midY))
                        for x in stride(from: 0, to: w, by: 2) {
                            let relativeX = x / w
                            let amplitude = isActive ? sin(relativeX * .pi) * 12.0 * sin(phase * 1.5) : sin(relativeX * .pi) * 2.0
                            let y = midY + sin(relativeX * 10 + phase) * amplitude
                            path.addLine(to: CGPoint(x: x, y: y))
                        }
                    },
                    with: .linearGradient(
                        Gradient(colors: [.clear, DesignTokens.accent.opacity(0.85), DesignTokens.accent.opacity(0.3), .clear]),
                        startPoint: .zero,
                        endPoint: CGPoint(x: w, y: 0)
                    ),
                    lineWidth: 2
                )
            }
            .frame(height: 20)
            .onAppear {
                withAnimation(.linear(duration: 1.5).repeatForever(autoreverses: false)) {
                    phase = .pi * 2
                }
            }
        }
    }
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
    @State private var shutterPulse = false
    
    @AppStorage("selectedAccentIndex") private var selectedAccentIndex = 0
    
    // Timer to update local telemetry (temp, battery)
    @State private var batteryPercent = 100
    @State private var deviceTemp = "28.5°C"
    let telemetryTimer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()
    
    init() {
        let server = SocketServer()
        _socketServer = StateObject(wrappedValue: server)
        _streamer = StateObject(wrappedValue: CameraStreamer(socketServer: server))
    }
    
    private func parseWidth(_ res: String) -> Int {
        let parts = res.split(separator: "x")
        return parts.count == 2 ? Int(parts[0]) ?? 1280 : 1280
    }
    
    private func parseHeight(_ res: String) -> Int {
        let parts = res.split(separator: "x")
        return parts.count == 2 ? Int(parts[1]) ?? 720 : 720
    }
    
    private func triggerHapticFeedback(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
        generator.impactOccurred()
    }
    
    var body: some View {
        ZStack {
            // Full black camera background
            Color.black
                .ignoresSafeArea()
            
            // 1. Live Camera Preview (if not muted) — hardware-accelerated via AVCaptureVideoPreviewLayer
            if !isPreviewMuted {
                CameraPreview(
                    session: streamer.captureSession,
                    flipHorizontal: settings.flipHorizontal,
                    flipVertical: settings.flipVertical,
                    activeCameraPosition: streamer.activeCameraPosition
                )
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
            
            // 3. Immersive camera controls overlay
            if !isUiHidden {
                let isPortrait = UIScreen.main.bounds.height > UIScreen.main.bounds.width
                
                VStack(spacing: 0) {
                    // Top HUD
                    topHudHeaderView
                        .transition(.move(edge: .top).combined(with: .opacity))
                    
                    // Upper Right Indicators (HD • 60)
                    HStack {
                        Spacer()
                        ResolutionFPSIndicator(settings: settings, streamer: streamer, socketServer: socketServer)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .transition(.opacity)
                    
                    Spacer()
                        .allowsHitTesting(false)
                    
                    // In Portrait, place the Quick Preferences menu horizontally above the Lens Selector
                    if isPortrait && !isPreviewMuted {
                        quickPreferencesSidebar
                            .padding(.bottom, 8)
                            .transition(.opacity)
                    }
                    
                    // Lens Selector above bottom controls
                    if !isPreviewMuted {
                        LensSelectorView(streamer: streamer)
                            .padding(.bottom, 8)
                            .transition(.opacity)
                    }
                    
                    // Bottom Controls Glass Panel
                    bottomControlsView
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                
                // In Landscape, place the Quick Preferences sidebar vertically on the left
                if !isPortrait && !isPreviewMuted {
                    HStack {
                        quickPreferencesSidebar
                            .padding(.leading, 12)
                            .transition(.move(edge: .leading).combined(with: .opacity))
                        Spacer()
                    }
                }
            }
            
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
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                shutterPulse = true
            }
            
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
                        streamer.updateConnectionOrientation()
                    } else if key == "flip_vertical" {
                        settings.flipVertical = (val == "true")
                        streamer.updateConnectionOrientation()
                    } else if key == "trigger_af" {
                        streamer.triggerAutofocus()
                    } else if key == "toggle_af_mode" {
                        streamer.toggleAutofocusMode()
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
            SettingsSheetView(showSettings: $showSettings, isDimMode: $isDimMode, socketServer: socketServer)
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
    
    // MARK: - Top HUD Header
    private var topHudHeaderView: some View {
        HStack(spacing: 12) {
            // Status Pill (Starts/Stops server listener when tapped)
            Button(action: {
                triggerHapticFeedback(.medium)
                if socketServer.isServerRunning {
                    socketServer.stop()
                } else {
                    socketServer.start(port: settings.port)
                }
            }) {
                HStack(spacing: 6) {
                    let isLive = socketServer.isStreaming
                    let isReady = socketServer.isServerRunning
                    
                    Circle()
                        .fill(isLive ? DesignTokens.red : (isReady ? DesignTokens.accent : DesignTokens.labelSecondary))
                        .frame(width: 7, height: 7)
                    
                    Text(isLive ? "LIVE" : (isReady ? "READY" : "OFFLINE"))
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                        .foregroundColor(isLive ? DesignTokens.red : (isReady ? DesignTokens.accent : DesignTokens.labelSecondary))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule()
                        .fill(Color.black.opacity(0.45))
                )
            }
            
            // IP & Port Pill
            let ips = getAllIPAddresses()
            if !ips.isEmpty {
                Text(ips[0] + ":\(settings.port)")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundColor(.white.opacity(0.8))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(
                        Capsule()
                            .fill(Color.black.opacity(0.45))
                    )
            } else {
                Text("NO NETWORK")
                    .font(.system(size: 11, weight: .semibold, design: .rounded))
                    .foregroundColor(DesignTokens.red)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(
                        Capsule()
                            .fill(Color.black.opacity(0.45))
                    )
            }
            
            Spacer()
            
            // Guides Toggle Button
            Button(action: {
                triggerHapticFeedback(.light)
                guideMode = (guideMode + 1) % 4
            }) {
                Image(systemName: guideMode > 0 ? "grid.circle.fill" : "grid")
                    .font(.system(size: 16))
                    .foregroundColor(guideMode > 0 ? DesignTokens.accent : .white)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(Color.black.opacity(0.45)))
            }
            
            // Settings Button
            Button(action: {
                triggerHapticFeedback(.light)
                showSettings = true
            }) {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 16))
                    .foregroundColor(.white)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(Color.black.opacity(0.45)))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, verticalSizeClass == .compact ? 8 : 52)
        .padding(.bottom, 8)
    }
    
    // MARK: - Bottom Controls Glass Panel
    private var bottomControlsView: some View {
        VStack(spacing: 12) {
            // Neon Audio Waveform View
            WaveformView(isActive: socketServer.isStreaming)
                .padding(.horizontal, 16)
            
            // Telemetry single-line readout
            microTelemetryRow
            
            // Horizontal sliding modes (Filter dial)
            FilterDialView(streamer: streamer)
            
            // Shutter row controls
            shutterControlsRow
        }
        .padding(.top, 12)
        .background(
            LinearGradient(
                colors: [.clear, .black.opacity(0.65), .black.opacity(0.9)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }
    
    // MARK: - Micro-Telemetry Line
    private var microTelemetryRow: some View {
        HStack(spacing: 8) {
            Text(socketServer.isStreaming ? socketServer.txRateText : "0 Kb/s")
            Text("•")
            Text(socketServer.isStreaming ? socketServer.totalTxText : "0.0 MB")
            Text("•")
            Text(deviceTemp)
            Text("•")
            Text("\(batteryPercent)%")
        }
        .font(.system(size: 10, weight: .bold, design: .monospaced))
        .foregroundColor(.white.opacity(0.65))
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(
            Capsule()
                .fill(Color.black.opacity(0.3))
        )
    }
    
    // MARK: - Shutter Controls Row
    private var shutterControlsRow: some View {
        HStack {
            // Light / Torch Toggle (Left)
            Button(action: {
                triggerHapticFeedback(.light)
                streamer.toggleTorch()
            }) {
                Image(systemName: streamer.torchEnabled ? "bolt.fill" : "bolt.slash.fill")
                    .font(.system(size: 20))
                    .foregroundColor(streamer.torchEnabled ? DesignTokens.orange : .white)
                    .frame(width: 50, height: 50)
                    .background(
                        Circle()
                            .fill(streamer.torchEnabled ? DesignTokens.orange.opacity(0.18) : Color.black.opacity(0.45))
                    )
                    .overlay(
                        Circle()
                            .stroke(streamer.torchEnabled ? DesignTokens.orange : Color.white.opacity(0.2), lineWidth: 1.5)
                    )
            }
            .frame(maxWidth: .infinity)
            
            // Connection Shutter (Center)
            Button(action: {
                triggerHapticFeedback(.medium)
                if socketServer.isServerRunning {
                    socketServer.stop()
                } else {
                    socketServer.start(port: settings.port)
                }
            }) {
                ZStack {
                    Circle()
                        .stroke(Color.white, lineWidth: 4)
                        .frame(width: 76, height: 76)
                    
                    let isLive = socketServer.isStreaming
                    let isReady = socketServer.isServerRunning && !isLive
                    
                    RoundedRectangle(cornerRadius: isLive ? 8 : 31)
                        .fill(isLive ? DesignTokens.red : (isReady ? DesignTokens.accent : Color.white))
                        .frame(width: isLive ? 32 : 62, height: isLive ? 32 : 62)
                        .scaleEffect(isLive && shutterPulse ? 0.92 : 1.0)
                        .animation(.spring(response: 0.35, dampingFraction: 0.75), value: isLive)
                        .animation(.spring(response: 0.35, dampingFraction: 0.75), value: isReady)
                }
            }
            .buttonStyle(ScaleButtonStyle())
            .frame(maxWidth: .infinity)
            
            // Flip Camera Toggle (Right)
            Button(action: {
                triggerHapticFeedback(.light)
                streamer.switchCamera()
            }) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.system(size: 20))
                    .foregroundColor(.white)
                    .frame(width: 50, height: 50)
                    .background(
                        Circle()
                            .fill(Color.black.opacity(0.45))
                    )
                    .overlay(
                        Circle()
                            .stroke(Color.white.opacity(0.2), lineWidth: 1.5)
                    )
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, verticalSizeClass == .compact ? 8 : 24)
    }
    
    // MARK: - Telemetry Update helpers
    private func updateLocalTelemetry() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        batteryPercent = Int(max(0, UIDevice.current.batteryLevel) * 100)
        
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
    
    private func getAllIPAddresses() -> [String] {
        var wifiAddresses: [String] = []
        var otherAddresses: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return [] }
        guard let firstAddr = ifaddr else { return [] }
        
        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family
            if addrFamily == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                
                // Skip loopback and cellular / secondary interfaces
                if name == "lo0" || name.hasPrefix("pdp_ip") || name.hasPrefix("awdl") || name.hasPrefix("llw") {
                    continue
                }
                
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                            &hostname, socklen_t(hostname.count),
                            nil, socklen_t(0), NI_NUMERICHOST)
                let ip = String(cString: hostname)
                if !ip.isEmpty && ip != "0.0.0.0" {
                    if name.hasPrefix("en") {
                        wifiAddresses.append(ip)
                    } else {
                        otherAddresses.append(ip)
                    }
                }
            }
        }
        freeifaddrs(ifaddr)
        return wifiAddresses + otherAddresses
    }
    
    // MARK: - Quick Preferences & Bitrate
    private var quickPreferencesSidebar: some View {
        Group {
            let isPortrait = UIScreen.main.bounds.height > UIScreen.main.bounds.width
            if isPortrait {
                HStack(spacing: 10) {
                    prefButtons
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 12)
            } else {
                VStack(spacing: 12) {
                    prefButtons
                }
                .padding(.vertical, 12)
                .padding(.horizontal, 8)
            }
        }
        .background(
            RoundedRectangle(cornerRadius: 24)
                .fill(Color.black.opacity(0.35))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(Color.white.opacity(0.15), lineWidth: 1)
        )
    }
    
    @ViewBuilder
    private var prefButtons: some View {
        // Face AF
        QuickPrefButton(
            icon: "person.fill.viewfinder",
            text: nil,
            isActive: settings.faceAutoFocus,
            action: {
                triggerHapticFeedback(.light)
                settings.faceAutoFocus.toggle()
            }
        )
        
        // Video Stabilization
        QuickPrefButton(
            icon: "waveform",
            text: nil,
            isActive: settings.videoStabilization,
            action: {
                triggerHapticFeedback(.light)
                settings.videoStabilization.toggle()
                streamer.updateConnectionOrientation()
            }
        )
        
        // Keep Screen On
        QuickPrefButton(
            icon: "sun.max.fill",
            text: nil,
            isActive: settings.keepScreenOn,
            action: {
                triggerHapticFeedback(.light)
                settings.keepScreenOn.toggle()
                UIApplication.shared.isIdleTimerDisabled = settings.keepScreenOn
            }
        )
        
        // Flip H
        QuickPrefButton(
            icon: "arrow.left.and.right",
            text: nil,
            isActive: settings.flipHorizontal,
            action: {
                triggerHapticFeedback(.light)
                settings.flipHorizontal.toggle()
                streamer.updateConnectionOrientation()
            }
        )
        
        // Flip V
        QuickPrefButton(
            icon: "arrow.up.and.down",
            text: nil,
            isActive: settings.flipVertical,
            action: {
                triggerHapticFeedback(.light)
                settings.flipVertical.toggle()
                streamer.updateConnectionOrientation()
            }
        )
        
        // Bitrate cycler
        QuickPrefButton(
            icon: nil,
            text: bitrateLabel(settings.bitrate),
            isActive: false,
            action: {
                triggerHapticFeedback(.light)
                cycleBitrate()
            }
        )
    }
    
    private func bitrateLabel(_ br: Int) -> String {
        return "\(br / 1000000)M"
    }
    
    private func cycleBitrate() {
        let bitrates = [1000000, 2000000, 3000000, 4000000, 5000000, 6000000, 10000000]
        if let index = bitrates.firstIndex(of: settings.bitrate) {
            let nextIndex = (index + 1) % bitrates.count
            settings.bitrate = bitrates[nextIndex]
        } else {
            settings.bitrate = 3000000
        }
        streamer.updateBitrateOnTheFly()
    }
}

// MARK: - HUD Helper Views
struct QuickPrefButton: View {
    let icon: String?
    let text: String?
    let isActive: Bool
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            Group {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.system(size: 18))
                } else if let text = text {
                    Text(text)
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                }
            }
            .foregroundColor(isActive ? DesignTokens.accent : .white)
            .frame(width: 48, height: 48)
            .background(
                Circle()
                    .fill(isActive ? DesignTokens.accent.opacity(0.15) : Color.black.opacity(0.45))
            )
            .overlay(
                Circle()
                    .stroke(isActive ? DesignTokens.accent.opacity(0.6) : Color.white.opacity(0.25), lineWidth: 1.5)
            )
        }
    }
}

struct LensSelectorView: View {
    @ObservedObject var streamer: CameraStreamer
    
    var body: some View {
        HStack(spacing: 12) {
            ForEach(streamer.availableLenses, id: \.self) { lens in
                let label = lensLabel(lens)
                let isSelected = streamer.activeLensType == lens
                
                Button(action: {
                    triggerHapticFeedback(.light)
                    streamer.selectLens(lens)
                }) {
                    Text(label)
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                        .foregroundColor(isSelected ? .black : .white)
                        .frame(width: 36, height: 36)
                        .background(
                            Circle()
                                .fill(isSelected ? DesignTokens.accent : Color.black.opacity(0.45))
                        )
                        .overlay(
                            Circle()
                                .stroke(isSelected ? Color.clear : Color.white.opacity(0.2), lineWidth: 1)
                        )
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(
            Capsule()
                .fill(Color.black.opacity(0.3))
        )
        .overlay(
            Capsule()
                .stroke(Color.white.opacity(0.1), lineWidth: 1)
        )
    }
    
    private func lensLabel(_ lens: AVCaptureDevice.DeviceType) -> String {
        switch lens {
        case .builtInUltraWideCamera: return ".5"
        case .builtInWideAngleCamera: return "1.0"
        case .builtInTelephotoCamera: return "2.0"
        default: return "1x"
        }
    }
    
    private func triggerHapticFeedback(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
        generator.impactOccurred()
    }
}

// MARK: - Camera Preview UIView
class PreviewUIView: UIView {
    private var previewLayer: AVCaptureVideoPreviewLayer?
    
    var flipHorizontal: Bool = false
    var flipVertical: Bool = false
    var activeCameraPosition: AVCaptureDevice.Position = .back
    
    init(session: AVCaptureSession) {
        super.init(frame: .zero)
        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        self.layer.addSublayer(previewLayer)
        self.previewLayer = previewLayer
        
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(sessionDidStart),
                                               name: .AVCaptureSessionDidStartRunning,
                                               object: session)
    }
    
    @objc private func sessionDidStart() {
        DispatchQueue.main.async { [weak self] in
            self?.updateOrientation()
        }
    }
    
    deinit {
        NotificationCenter.default.removeObserver(self)
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
        
        let isFront = activeCameraPosition == .front
        let shouldMirror = isFront != flipHorizontal
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = shouldMirror
        }
        
        if let windowScene = self.window?.windowScene {
            let orientation = windowScene.interfaceOrientation
            switch orientation {
            case .portrait:
                connection.videoOrientation = .portrait
            case .portraitUpsideDown:
                connection.videoOrientation = .portraitUpsideDown
            case .landscapeLeft:
                connection.videoOrientation = .landscapeRight
            case .landscapeRight:
                connection.videoOrientation = .landscapeLeft
            default:
                connection.videoOrientation = .landscapeRight
            }
        } else {
            connection.videoOrientation = .landscapeRight
        }
        
        DispatchQueue.main.async {
            self.transform = CGAffineTransform(scaleX: 1, y: self.flipVertical ? -1 : 1)
        }
    }
    
    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateOrientation()
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let flipHorizontal: Bool
    let flipVertical: Bool
    let activeCameraPosition: AVCaptureDevice.Position
    
    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView(session: session)
        view.flipHorizontal = flipHorizontal
        view.flipVertical = flipVertical
        view.activeCameraPosition = activeCameraPosition
        return view
    }
    
    func updateUIView(_ uiView: PreviewUIView, context: Context) {
        uiView.flipHorizontal = flipHorizontal
        uiView.flipVertical = flipVertical
        uiView.activeCameraPosition = activeCameraPosition
        uiView.updateOrientation()
    }
}

// MARK: - Upper Right Resolution/FPS overlay indicator
struct ResolutionFPSIndicator: View {
    @ObservedObject var settings: SettingsManager
    let streamer: CameraStreamer
    let socketServer: SocketServer
    
    var body: some View {
        HStack(spacing: 4) {
            Button(action: {
                triggerHapticFeedback(.light)
                cycleResolution()
            }) {
                Text(resolutionLabel(settings.resolution))
                    .font(.system(size: 13, weight: .bold, design: .rounded))
            }
            
            Text("•")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(.white.opacity(0.4))
            
            Button(action: {
                triggerHapticFeedback(.light)
                cycleFramerate()
            }) {
                Text("\(settings.framerate)")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(
            Capsule()
                .fill(Color.black.opacity(0.45))
        )
    }
    
    private func resolutionLabel(_ res: String) -> String {
        switch res {
        case "3840x2160": return "4K"
        case "2560x1440": return "2K"
        case "1920x1080": return "HD"
        case "1280x720": return "720"
        case "640x480": return "480"
        default: return res
        }
    }
    
    private func cycleResolution() {
        let resolutions = ["640x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"]
        if let currentIndex = resolutions.firstIndex(of: settings.resolution) {
            let nextIndex = (currentIndex + 1) % resolutions.count
            let nextRes = resolutions[nextIndex]
            settings.resolution = nextRes
            if socketServer.isStreaming {
                let parts = nextRes.split(separator: "x")
                let w = parts.count == 2 ? Int(parts[0]) ?? 1280 : 1280
                let h = parts.count == 2 ? Int(parts[1]) ?? 720 : 720
                streamer.startStreaming(format: settings.format, width: w, height: h)
            }
        }
    }
    
    private func cycleFramerate() {
        let framerates = [15, 24, 30, 60]
        if let currentIndex = framerates.firstIndex(of: settings.framerate) {
            let nextIndex = (currentIndex + 1) % framerates.count
            settings.framerate = framerates[nextIndex]
        }
    }
    
    private func triggerHapticFeedback(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
        generator.impactOccurred()
    }
}

// MARK: - Sliding Camera Filter Mode Dial Picker
struct FilterDialView: View {
    @ObservedObject var streamer: CameraStreamer
    let filters = ["NORMAL", "BEAUTY", "PORTRAIT", "COMIC", "NEON", "GLITCH"]
    
    var body: some View {
        GeometryReader { geo in
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 24) {
                        Spacer().frame(width: geo.size.width / 2 - 40)
                        
                        ForEach(filters, id: \.self) { filter in
                            Button(action: {
                                triggerHapticFeedback(.light)
                                withAnimation(.spring(response: 0.3, dampingFraction: 0.75)) {
                                    streamer.setFilterMode(filter)
                                }
                            }) {
                                VStack(spacing: 4) {
                                    Text(filter)
                                        .font(.system(size: 13, weight: streamer.filterModeText == filter ? .bold : .semibold, design: .rounded))
                                        .foregroundColor(streamer.filterModeText == filter ? Color(red: 255/255, green: 204/255, blue: 0/255) : .white.opacity(0.55))
                                        .scaleEffect(streamer.filterModeText == filter ? 1.12 : 1.0)
                                    
                                    Circle()
                                        .fill(streamer.filterModeText == filter ? Color(red: 255/255, green: 204/255, blue: 0/255) : .clear)
                                        .frame(width: 4, height: 4)
                                }
                            }
                            .id(filter)
                        }
                        
                        Spacer().frame(width: geo.size.width / 2 - 40)
                    }
                }
                .onChange(of: streamer.filterModeText) { newFilter in
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.75)) {
                        proxy.scrollTo(newFilter, anchor: .center)
                    }
                }
                .onAppear {
                    proxy.scrollTo(streamer.filterModeText, anchor: .center)
                }
            }
        }
        .frame(height: 40)
    }
    
    private func triggerHapticFeedback(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
        generator.impactOccurred()
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
    @Binding var isDimMode: Bool
    @ObservedObject var socketServer: SocketServer
    
    @StateObject private var settings = SettingsManager.shared
    @AppStorage("selectedAccentIndex") private var selectedAccentIndex = 0
    
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
                    
                    // Accent Color Picker Section (Moved to Settings for clean HUD)
                    SettingsSection(title: "Theme Accent") {
                        HStack(spacing: 16) {
                            ForEach(0..<DesignTokens.accentColors.count, id: \.self) { index in
                                Circle()
                                    .fill(DesignTokens.accentColors[index])
                                    .frame(width: 32, height: 32)
                                    .scaleEffect(selectedAccentIndex == index ? 1.2 : 1.0)
                                    .overlay(
                                        Circle()
                                            .stroke(Color.white, lineWidth: selectedAccentIndex == index ? 2 : 0)
                                    )
                                    .onTapGesture {
                                        triggerHapticFeedback(.light)
                                        withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) {
                                            selectedAccentIndex = index
                                        }
                                    }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    
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
                    
                    // Display / OLED Dim Section (Moved to settings)
                    SettingsSection(title: "Display") {
                        Button(action: {
                            triggerHapticFeedback(.light)
                            showSettings = false
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                isDimMode = true
                            }
                        }) {
                            HStack {
                                Label("Dim Screen (OLED Saver)", systemImage: "moon.fill")
                                    .font(.system(size: 15, weight: .regular))
                                    .foregroundColor(DesignTokens.label)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundColor(DesignTokens.labelSecondary)
                            }
                            .padding(.horizontal, 16)
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
                    .font(.system(size: 17, weight: .semibold))
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
            socketServer.start(port: p)
        }
        
        settings.resolution = selectedResolution
        settings.format = selectedFormat
        settings.framerate = selectedFramerate
        settings.bitrate = selectedBitrate
        
        UIApplication.shared.isIdleTimerDisabled = settings.keepScreenOn
    }
    
    private func triggerHapticFeedback(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
        generator.impactOccurred()
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
