import Foundation
import AVFoundation
import VideoToolbox
import UIKit

class CameraStreamer: NSObject, ObservableObject {
    @Published var isPreviewRunning = false
    @Published var activeCameraPosition: AVCaptureDevice.Position = .back
    @Published var torchEnabled = false
    @Published var focusModeText = "AUTO-C"
    @Published var filterModeText = "NORMAL"
    
    let captureSession = AVCaptureSession()
    private var videoDeviceInput: AVCaptureDeviceInput?
    private let videoDataOutput = AVCaptureVideoDataOutput()
    private let videoQueue = DispatchQueue(label: "com.webcamclone.videoQueue", qos: .userInteractive)
    
    private var compressionSession: VTCompressionSession?
    private var socketServer: SocketServer
    
    private var streamingFormat = "avc"
    private var width = 1280
    private var height = 720
    private var isStreaming = false
    private var didSendConfig = false
    
    private var ciContext = CIContext()
    private var currentFilterEffect = 0 // 0: Normal, 1: Mono, 2: Negative, 3: Sepia, 4: Solarize
    
    init(socketServer: SocketServer) {
        self.socketServer = socketServer
        super.init()
    }
    
    func startPreview() {
        guard !isPreviewRunning else { return }
        
        // Request camera permission
        AVCaptureDevice.requestAccess(for: .video) { granted in
            guard granted else {
                print("Camera permission denied")
                return
            }
            
            DispatchQueue.global(qos: .userInitiated).async {
                self.setupCaptureSession()
                self.captureSession.startRunning()
                DispatchQueue.main.async {
                    self.isPreviewRunning = true
                }
            }
        }
    }
    
    func stopPreview() {
        guard isPreviewRunning else { return }
        stopStreaming()
        
        DispatchQueue.global(qos: .userInitiated).async {
            self.captureSession.stopRunning()
            self.teardownCaptureSession()
            DispatchQueue.main.async {
                self.isPreviewRunning = false
            }
        }
    }
    
    private func setupCaptureSession() {
        captureSession.beginConfiguration()
        captureSession.sessionPreset = .hd1280x720 // default, will auto scale
        
        // Select device
        guard let videoDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: activeCameraPosition) else {
            print("No camera found")
            return
        }
        
        do {
            let input = try AVCaptureDeviceInput(device: videoDevice)
            if captureSession.canAddInput(input) {
                captureSession.addInput(input)
                videoDeviceInput = input
            }
            
            // Set output
            videoDataOutput.alwaysDiscardsLateVideoFrames = true
            videoDataOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange // default to BiPlanar NV12
            ]
            
            if captureSession.canAddOutput(videoDataOutput) {
                captureSession.addOutput(videoDataOutput)
                videoDataOutput.setSampleBufferDelegate(self, queue: videoQueue)
            }
            
            // Configure default frame duration (30 FPS)
            try configureFPS(device: videoDevice, fps: 30)
            
            // Auto focus continuous
            try configureFocusMode(device: videoDevice, mode: .continuousAutoFocus)
            
        } catch {
            print("Error setting up capture session: \(error)")
        }
        
        captureSession.commitConfiguration()
    }
    
    private func teardownCaptureSession() {
        captureSession.beginConfiguration()
        if let input = videoDeviceInput {
            captureSession.removeInput(input)
            videoDeviceInput = nil
        }
        captureSession.removeOutput(videoDataOutput)
        captureSession.commitConfiguration()
    }
    
    func startStreaming(format: String, width: Int, height: Int) {
        self.streamingFormat = format
        self.width = width
        self.height = height
        self.didSendConfig = false
        self.isStreaming = true
        
        // Match resolution preset in captureSession if needed
        DispatchQueue.global(qos: .userInitiated).async {
            self.captureSession.beginConfiguration()
            if width >= 1920 && height >= 1080 {
                if self.captureSession.canSetSessionPreset(.hd1920x1080) {
                    self.captureSession.sessionPreset = .hd1920x1080
                }
            } else if width >= 1280 && height >= 720 {
                if self.captureSession.canSetSessionPreset(.hd1280x720) {
                    self.captureSession.sessionPreset = .hd1280x720
                }
            } else {
                if self.captureSession.canSetSessionPreset(.vga640x480) {
                    self.captureSession.sessionPreset = .vga640x480
                }
            }
            self.captureSession.commitConfiguration()
            
            if format == "avc" || format == "hevc" {
                self.setupCompressionSession(format: format, width: width, height: height)
            }
        }
    }
    
    func stopStreaming() {
        self.isStreaming = false
        self.didSendConfig = false
        
        DispatchQueue.global(qos: .userInitiated).async {
            self.teardownCompressionSession()
        }
    }
    
    private func setupCompressionSession(format: String, width: Int, height: Int) {
        teardownCompressionSession()
        
        let codecType = (format == "hevc") ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264
        
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(width),
            height: Int32(height),
            codecType: codecType,
            encoderSpecification: nil,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: compressionCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &compressionSession
        )
        
        guard status == noErr, let session = compressionSession else {
            print("Failed to create compression session: \(status)")
            return
        }
        
        let settings = SettingsManager.shared
        
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: settings.bitrate as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: settings.framerate as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: (settings.framerate * 2) as CFNumber)
        
        if format == "avc" {
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_High_AutoLevel)
        } else {
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_HEVC_Main_AutoLevel)
        }
        
        VTCompressionSessionPrepareToEncodeFrames(session)
        print("Hardware encoder session created successfully for \(format.uppercased())")
    }
    
    private func teardownCompressionSession() {
        if let session = compressionSession {
            VTCompressionSessionInvalidate(session)
            compressionSession = nil
        }
    }
    
    private func configureFPS(device: AVCaptureDevice, fps: Int) throws {
        try device.lockForConfiguration()
        
        var bestFormat: AVCaptureDevice.Format?
        var bestFrameRateRange: AVFrameRateRange?
        
        for format in device.formats {
            for range in format.videoSupportedFrameRateRanges {
                if range.maxFrameRate >= Double(fps) && range.minFrameRate <= Double(fps) {
                    bestFormat = format
                    bestFrameRateRange = range
                }
            }
        }
        
        if let format = bestFormat, bestFrameRateRange != nil {
            device.activeFormat = format
            device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: CMTimeScale(fps))
            device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: CMTimeScale(fps))
        }
        
        device.unlockForConfiguration()
    }
    
    private func configureFocusMode(device: AVCaptureDevice, mode: AVCaptureDevice.FocusMode) throws {
        try device.lockForConfiguration()
        if device.isFocusModeSupported(mode) {
            device.focusMode = mode
        }
        device.unlockForConfiguration()
    }
    
    // Output callback is defined as a global function at the bottom of this file.
    
    func handleEncodedFrame(_ sampleBuffer: CMSampleBuffer) {
        guard isStreaming else { return }
        
        // Get PTS
        let ptsTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let ptsNs = Int64(ptsTime.seconds * 1_000_000_000)
        
        // 1. Check for Keyframe & Extract/Send SPS/PPS first
        let isKeyframe = checkIsKeyframe(sampleBuffer)
        
        if isKeyframe || !didSendConfig {
            if let configBytes = extractSPS_PPS(sampleBuffer) {
                socketServer.sendVideoFrame(pts: -1, data: configBytes)
                didSendConfig = true
            }
        }
        
        // 2. Convert NAL units from length-prefixed (AVCC) to Annex B format
        if let frameData = convertAVCCtoAnnexB(sampleBuffer: sampleBuffer) {
            socketServer.sendVideoFrame(pts: ptsNs, data: frameData)
        }
    }
    
    private func checkIsKeyframe(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
              let firstAttachment = attachments.first else {
            return false
        }
        
        let notSync = firstAttachment[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
        return !notSync
    }
    
    private func extractSPS_PPS(_ sampleBuffer: CMSampleBuffer) -> Data? {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else { return nil }
        
        var configData = Data()
        let startCode: [UInt8] = [0, 0, 0, 1]
        
        if streamingFormat == "avc" {
            var parameterSetCount = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(formatDescription, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil, parameterSetCountOut: &parameterSetCount, nalUnitHeaderLengthOut: nil)
            
            for i in 0..<parameterSetCount {
                var parameterSetPointer: UnsafePointer<UInt8>?
                var parameterSetSize = 0
                CMVideoFormatDescriptionGetH264ParameterSetAtIndex(formatDescription, parameterSetIndex: i, parameterSetPointerOut: &parameterSetPointer, parameterSetSizeOut: &parameterSetSize, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                
                if let paramPtr = parameterSetPointer {
                    configData.append(contentsOf: startCode)
                    configData.append(paramPtr, count: parameterSetSize)
                }
            }
        } else if streamingFormat == "hevc" {
            // HEVC (H.265) parameters count is up to 3 usually (VPS, SPS, PPS)
            var parameterSetCount = 0
            CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(formatDescription, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil, parameterSetCountOut: &parameterSetCount, nalUnitHeaderLengthOut: nil)
            
            for i in 0..<parameterSetCount {
                var parameterSetPointer: UnsafePointer<UInt8>?
                var parameterSetSize = 0
                CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(formatDescription, parameterSetIndex: i, parameterSetPointerOut: &parameterSetPointer, parameterSetSizeOut: &parameterSetSize, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                
                if let paramPtr = parameterSetPointer {
                    configData.append(contentsOf: startCode)
                    configData.append(paramPtr, count: parameterSetSize)
                }
            }
        }
        
        return configData.isEmpty ? nil : configData
    }
    
    private func convertAVCCtoAnnexB(sampleBuffer: CMSampleBuffer) -> Data? {
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return nil }
        var length = 0
        var totalLength = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        
        let status = CMBlockBufferGetDataPointer(blockBuffer, atOffset: 0, lengthAtOffsetOut: &length, totalLengthOut: &totalLength, dataPointerOut: &dataPointer)
        guard status == kCMBlockBufferNoErr, let dataPtr = dataPointer else { return nil }
        
        var data = Data()
        var offset = 0
        while offset < totalLength - 4 {
            // Read 4-byte length
            var nalLength: UInt32 = 0
            memcpy(&nalLength, dataPtr + offset, 4)
            nalLength = UInt32(bigEndian: nalLength)
            
            offset += 4
            if offset + Int(nalLength) <= totalLength {
                // Append Annex B start code: 0x00000001
                let startCode: [UInt8] = [0, 0, 0, 1]
                data.append(contentsOf: startCode)
                
                // Append payload
                let rawPayload = UnsafeRawPointer(dataPtr + offset)
                data.append(rawPayload.assumingMemoryBound(to: UInt8.self), count: Int(nalLength))
                
                offset += Int(nalLength)
            } else {
                break
            }
        }
        return data
    }
    
    // MARK: - Camera Controls
    
    func switchCamera() {
        activeCameraPosition = (activeCameraPosition == .back) ? .front : .back
        torchEnabled = false
        
        if isPreviewRunning {
            DispatchQueue.global(qos: .userInitiated).async {
                self.teardownCaptureSession()
                self.setupCaptureSession()
                
                // Restart encoding if streaming
                if self.isStreaming && (self.streamingFormat == "avc" || self.streamingFormat == "hevc") {
                    self.setupCompressionSession(format: self.streamingFormat, width: self.width, height: self.height)
                }
            }
        }
    }
    
    func toggleTorch() {
        guard let input = videoDeviceInput, input.device.hasTorch else { return }
        
        do {
            try input.device.lockForConfiguration()
            if input.device.torchMode == .on {
                input.device.torchMode = .off
                torchEnabled = false
            } else {
                try input.device.setTorchModeOn(level: 1.0)
                torchEnabled = true
            }
            input.device.unlockForConfiguration()
        } catch {
            print("Failed to toggle torch: \(error)")
        }
    }
    
    func triggerAutofocus() {
        guard let input = videoDeviceInput else { return }
        let device = input.device
        
        do {
            try device.lockForConfiguration()
            if device.isFocusModeSupported(.autoFocus) {
                device.focusMode = .autoFocus
                focusModeText = "AUTO-L"
            }
            device.unlockForConfiguration()
            
            // Revert back to continuous after a delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                do {
                    try device.lockForConfiguration()
                    if device.isFocusModeSupported(.continuousAutoFocus) {
                        device.focusMode = .continuousAutoFocus
                        self.focusModeText = "AUTO-C"
                    }
                    device.unlockForConfiguration()
                } catch {}
            }
        } catch {
            print("Failed to trigger AF: \(error)")
        }
    }
    
    func cycleFilter() {
        currentFilterEffect = (currentFilterEffect + 1) % 5
        let filterLabels = ["NORMAL", "MONO", "NEGATIVE", "SEPIA", "SOLAR"]
        filterModeText = filterLabels[currentFilterEffect]
    }
    
    // Apply core image filter to the sample buffer pixel buffer (used in MJPEG mode)
    private func applyImageFilter(_ pixelBuffer: CVPixelBuffer) -> CVPixelBuffer? {
        guard currentFilterEffect > 0 else { return pixelBuffer }
        
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        var filteredImage: CIImage?
        
        switch currentFilterEffect {
        case 1: // Mono
            filteredImage = ciImage.applyingFilter("CIPhotoEffectMono")
        case 2: // Negative
            filteredImage = ciImage.applyingFilter("CIColorInvert")
        case 3: // Sepia
            filteredImage = ciImage.applyingFilter("CISepiaTone", parameters: [kCIInputIntensityKey: 0.8])
        case 4: // Solarize (simulated with color controls)
            filteredImage = ciImage.applyingFilter("CIColorControls", parameters: [
                kCIInputSaturationKey: 2.0,
                kCIInputBrightnessKey: 0.1,
                kCIInputContrastKey: 3.0
            ])
        default:
            filteredImage = ciImage
        }
        
        guard let outputImage = filteredImage else { return pixelBuffer }
        
        var newPixelBuffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, CVPixelBufferGetWidth(pixelBuffer), CVPixelBufferGetHeight(pixelBuffer), CVPixelBufferGetPixelFormatType(pixelBuffer), nil, &newPixelBuffer)
        
        if let outBuf = newPixelBuffer {
            ciContext.render(outputImage, to: outBuf)
            return outBuf
        }
        return pixelBuffer
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension CameraStreamer: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard isStreaming else { return }
        
        // Ensure orientation is correct (OBS expects landscape landscape/default)
        if connection.isVideoOrientationSupported {
            connection.videoOrientation = .landscapeRight
        }
        
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        
        if streamingFormat == "avc" || streamingFormat == "hevc" {
            // Apply filter (optional) and compress
            let processedBuffer = applyImageFilter(pixelBuffer) ?? pixelBuffer
            if let session = compressionSession {
                let ptsTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
                VTCompressionSessionEncodeFrame(
                    session,
                    imageBuffer: processedBuffer,
                    presentationTimeStamp: ptsTime,
                    duration: .invalid,
                    frameProperties: nil,
                    sourceFrameRefcon: nil,
                    infoFlagsOut: nil
                )
            }
        } else if streamingFormat == "jpg" {
            // JPEG / MJPEG Mode
            let processedBuffer = applyImageFilter(pixelBuffer) ?? pixelBuffer
            let ciImage = CIImage(cvPixelBuffer: processedBuffer)
            
            // Convert to JPEG data
            let colorSpace = CGColorSpaceCreateDeviceRGB()
            guard let jpegData = ciContext.jpegRepresentation(of: ciImage, colorSpace: colorSpace, options: [:]) else {
                return
            }
            
            let ptsTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            let ptsNs = Int64(ptsTime.seconds * 1_000_000_000)
            socketServer.sendVideoFrame(pts: ptsNs, data: jpegData)
        }
    }
}

// Global VideoToolbox compression callback
private func compressionCallback(
    refcon: UnsafeMutableRawPointer?,
    sourceFrameRefcon: UnsafeMutableRawPointer?,
    status: OSStatus,
    infoFlags: VTEncodeInfoFlags,
    sampleBuffer: CMSampleBuffer?
) {
    guard status == noErr, let sampleBuffer = sampleBuffer, let refcon = refcon else {
        return
    }
    let streamer = Unmanaged<CameraStreamer>.fromOpaque(refcon).takeUnretainedValue()
    streamer.handleEncodedFrame(sampleBuffer)
}
