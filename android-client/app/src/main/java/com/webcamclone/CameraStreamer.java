package com.webcamclone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class CameraStreamer {
    private static final String TAG = "CameraStreamer";

    private final Context context;
    private final SocketServer socketServer;
    private final PreviewSurfaceProvider previewProvider;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private MediaCodec mediaCodec;
    private Surface codecInputSurface;
    private CaptureRequest.Builder captureBuilder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private String currentCameraId = null;
    private boolean torchEnabled = false;
    private boolean isStreamingToSocket = false;
    private boolean isPreviewRunning = false;
    private String currentFormat = "jpg"; // "jpg" (MJPEG), "avc" (H.264), or "hevc" (H.265)

    private int currentFilterEffect = CaptureRequest.CONTROL_EFFECT_MODE_OFF;
    private boolean isAutofocusLocked = false;

    private boolean faceAutoFocusEnabled = false;
    private Rect activeArraySize = null;
    private int maxAfRegions = 0;
    private Rect lastFocusedFaceRect = null;
    private long lastFaceFocusUpdateTime = 0;

    private int targetWidth = 1280;
    private int targetHeight = 720;

    // Advanced manual controls
    private float zoomFactor = 1.0f;
    private int exposureCompensation = 0;
    private int focusMode = 0; // 0 = Auto-Continuous, 1 = Manual
    private float manualFocusDistance = 0.0f;
    private int awbMode = 1; // CONTROL_AWB_MODE_AUTO
    private boolean stabilizationEnabled = true;

    public interface PreviewSurfaceProvider {
        Surface getPreviewSurface();
        void updateStatusText(String text);
        void onPreviewSizeSelected(int width, int height);
    }

    public CameraStreamer(Context context, SocketServer socketServer, PreviewSurfaceProvider previewProvider) {
        this.context = context;
        this.socketServer = socketServer;
        this.previewProvider = previewProvider;
        
        SettingsManager settings = new SettingsManager(context);
        this.faceAutoFocusEnabled = settings.getFaceAutoFocus();
        String res = settings.getResolution();
        int[] wxh = parseResolution(res);
        this.targetWidth = wxh[0];
        this.targetHeight = wxh[1];

        // Load new settings
        this.zoomFactor = settings.getZoomFactor();
        this.exposureCompensation = settings.getExposureCompensation();
        this.focusMode = settings.getFocusMode();
        this.manualFocusDistance = settings.getManualFocusDistance();
        this.awbMode = settings.getAwbMode();
        this.stabilizationEnabled = settings.getStabilizationEnabled();
    }

    private int[] parseResolution(String res) {
        try {
            String[] parts = res.split("x");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception e) {
            return new int[]{1280, 720};
        }
    }

    public synchronized void startPreview() {
        if (isPreviewRunning) return;
        isPreviewRunning = true;
        
        startBackgroundThread();
        openCamera();
    }

    public synchronized void stopPreview() {
        if (!isPreviewRunning) return;
        isPreviewRunning = false;
        isStreamingToSocket = false;

        closeCamera();
        stopBackgroundThread();
        Log.i(TAG, "Preview stopped");
    }

    public synchronized void startStreaming(String format, int width, int height) {
        this.currentFormat = format;
        this.targetWidth = width;
        this.targetHeight = height;
        this.isStreamingToSocket = true;

        // Restart camera with the requested stream resolution & format encoder
        closeCamera();
        openCamera();
        Log.i(TAG, "Streaming active. Format: " + format + ", Resolution: " + width + "x" + height);
    }

    public synchronized void stopStreaming() {
        this.isStreamingToSocket = false;
        this.currentFormat = "jpg";
        
        SettingsManager settings = new SettingsManager(context);
        String res = settings.getResolution();
        int[] wxh = parseResolution(res);
        this.targetWidth = wxh[0];
        this.targetHeight = wxh[1];

        closeCamera();
        if (isPreviewRunning) {
            openCamera();
        }
        Log.i(TAG, "Streaming stopped, preview running");
    }

    public synchronized void setTargetResolution(int width, int height) {
        this.targetWidth = width;
        this.targetHeight = height;
    }

    public synchronized boolean isStreaming() {
        return isStreamingToSocket;
    }

    public synchronized void switchCamera() {
        lensFacing = (lensFacing == CameraCharacteristics.LENS_FACING_BACK) 
                ? CameraCharacteristics.LENS_FACING_FRONT 
                : CameraCharacteristics.LENS_FACING_BACK;
        torchEnabled = false; // Turn off torch for safety/front cam compat
        currentCameraId = null; // Force recalculation based on lensFacing
        
        if (isPreviewRunning || isStreamingToSocket) {
            closeCamera();
            openCamera();
        }
        Log.i(TAG, "Switched camera lens facing to " + lensFacing);
    }

    public synchronized void toggleTorch() {
        if (cameraDevice == null || captureSession == null || captureBuilder == null) return;
        
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = cameraDevice.getId();
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            
            if (hasFlash != null && hasFlash) {
                torchEnabled = !torchEnabled;
                if (torchEnabled) {
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                } else {
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                }
                captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
                Log.i(TAG, "Toggled torch state to: " + torchEnabled);
            } else {
                previewProvider.updateStatusText("Flash is not supported on this camera");
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Setting flash failed", e);
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (backgroundHandler == null) {
            startBackgroundThread();
        }

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (currentCameraId == null) {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == lensFacing) {
                        currentCameraId = id;
                        break;
                    }
                }
                if (currentCameraId == null && manager.getCameraIdList().length > 0) {
                    currentCameraId = manager.getCameraIdList()[0];
                }
            }
            String cameraId = currentCameraId;

            if (cameraId == null) {
                Log.e(TAG, "No camera found");
                previewProvider.updateStatusText("Error: No camera found");
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;

            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            Size selectedSize = getClosestSize(sizes, targetWidth, targetHeight);
            Log.i(TAG, "Selected camera output resolution: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());
            
            previewProvider.onPreviewSizeSelected(selectedSize.getWidth(), selectedSize.getHeight());

            boolean useHardwareEncoder = isStreamingToSocket && (currentFormat.equals("avc") || currentFormat.equals("hevc"));

            // Conditionally create the imageReader to avoid extra ISP load when not needed
            boolean needImageReader = !useHardwareEncoder || socketServer.hasPreviewClients();
            if (needImageReader) {
                final long[] lastFrameTime = {0L};
                imageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.YUV_420_888, 2);
                imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        Image image = reader.acquireLatestImage();
                        if (image != null) {
                            try {
                                boolean needObsFrame = isStreamingToSocket && !useHardwareEncoder;
                                boolean needWebPreview = socketServer.hasPreviewClients();

                                if (needObsFrame || needWebPreview) {
                                    long now = System.currentTimeMillis();
                                    boolean shouldProcess = true;
                                    if (!needObsFrame) {
                                        // Throttle web preview frame processing to ~12 FPS (80ms interval)
                                        if (now - lastFrameTime[0] < 80) {
                                            shouldProcess = false;
                                        } else {
                                            lastFrameTime[0] = now;
                                        }
                                    }

                                    if (shouldProcess) {
                                        byte[] jpegBytes = convertYuvToJpeg(image);
                                        if (jpegBytes != null) {
                                            if (needObsFrame) {
                                                socketServer.sendVideoFrame(jpegBytes);
                                            }
                                            if (needWebPreview) {
                                                socketServer.sendPreviewFrame(jpegBytes);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error processing frame", e);
                            } finally {
                                image.close();
                            }
                        }
                    }
                }, backgroundHandler);
            } else {
                imageReader = null;
            }

            if (useHardwareEncoder) {
                setupHardwareEncoder(currentFormat, selectedSize.getWidth(), selectedSize.getHeight());
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    if (error == ERROR_CAMERA_DISABLED) {
                        previewProvider.updateStatusText("Camera disabled by system policy!\nCheck global settings (Camera Access toggle).");
                    } else {
                        previewProvider.updateStatusText("Camera error code: " + error);
                    }
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Accessing camera failed", e);
            if (e.getReason() == CameraAccessException.CAMERA_DISABLED) {
                previewProvider.updateStatusText("Camera disabled by system policy!\nCheck global settings (Camera Access toggle).");
            } else {
                previewProvider.updateStatusText("Camera access exception: " + e.getMessage());
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception", e);
            previewProvider.updateStatusText("Camera permission missing.");
        }
    }

    private void setupHardwareEncoder(String format, int width, int height) {
        String mimeType = format.equals("hevc") ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        try {
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(mimeType, width, height);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            SettingsManager settings = new SettingsManager(context);
            int bitrate = settings.getBitrate();
            int fps = settings.getFramerate();

            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // Keyframe every 1 second

            mediaCodec = MediaCodec.createEncoderByType(mimeType);
            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            
            codecInputSurface = mediaCodec.createInputSurface();
            
            mediaCodec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer != null) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        
                        byte[] outData = new byte[info.size];
                        outputBuffer.get(outData);
                        
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            socketServer.sendVideoConfig(outData);
                        } else {
                            socketServer.sendVideoFrame(outData, info.presentationTimeUs * 1000L);
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    Log.e(TAG, "MediaCodec error: " + e.getMessage());
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    Log.d(TAG, "MediaCodec format changed to: " + format);
                }
            }, backgroundHandler);

            mediaCodec.start();
            Log.i(TAG, "Hardware encoder started successfully for: " + format);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize hardware encoder for: " + format + ", falling back to MJPEG", e);
            currentFormat = "jpg";
        }
    }

    private void createCameraCaptureSession() {
        try {
            List<Surface> targets = new ArrayList<>();
            boolean useHardwareEncoder = isStreamingToSocket && (currentFormat.equals("avc") || currentFormat.equals("hevc"));

            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            if (useHardwareEncoder && codecInputSurface != null) {
                targets.add(codecInputSurface);
                captureBuilder.addTarget(codecInputSurface);
            }

            if (imageReader != null) {
                targets.add(imageReader.getSurface());
                captureBuilder.addTarget(imageReader.getSurface());
            }

            // On-screen preview surface
            Surface previewSurface = previewProvider.getPreviewSurface();
            if (previewSurface != null) {
                targets.add(previewSurface);
                captureBuilder.addTarget(previewSurface);
            }

            // Apply flash/torch configuration if enabled
            if (torchEnabled) {
                captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            } else {
                captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }

            // Apply current filter effect
            captureBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, currentFilterEffect);

            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    try {
                        // Apply all camera settings (including Zoom, Focus, Exposure, AWB, Stabilization)
                        applyCameraSettings();

                        // Configure face detection
                        try {
                            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
                            activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                            Integer maxRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                            maxAfRegions = (maxRegions != null) ? maxRegions : 0;

                            int[] faceModes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                            int faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF;
                            if (faceAutoFocusEnabled && faceModes != null) {
                                for (int mode : faceModes) {
                                    if (mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) {
                                        faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL;
                                        break;
                                    } else if (mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                                        faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE;
                                    }
                                }
                            }
                            captureBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode);
                            Log.i(TAG, "Face detection statistics mode configured to: " + faceDetectMode);

                            // Apply Face Priority Scene Mode if enabled and focusMode is Auto
                            if (focusMode == 0 && !isAutofocusLocked) {
                                if (faceAutoFocusEnabled) {
                                    boolean facePrioritySupported = false;
                                    int[] sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
                                    if (sceneModes != null) {
                                        for (int mode : sceneModes) {
                                            if (mode == CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY) {
                                                facePrioritySupported = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (facePrioritySupported) {
                                        captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
                                        captureBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
                                        Log.i(TAG, "Face priority scene mode enabled");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to configure face detection modes", e);
                        }

                        captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
                        if (isStreamingToSocket) {
                            previewProvider.updateStatusText("Streaming Active...");
                        } else {
                            previewProvider.updateStatusText("Ready for OBS connection");
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start camera preview request", e);
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "Session was closed before repeating request could be set: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Configuration of session failed");
                    previewProvider.updateStatusText("Failed to configure capture session");
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Creating capture session failed", e);
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                // ignore
            }
            mediaCodec = null;
        }
        if (codecInputSurface != null) {
            codecInputSurface.release();
            codecInputSurface = null;
        }
    }

    private Size getClosestSize(Size[] sizes, int targetW, int targetH) {
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        Size bestSize = sizes[0];
        int bestDiff = Integer.MAX_VALUE;
        for (Size size : sizes) {
            int diff = Math.abs(size.getWidth() - targetW) + Math.abs(size.getHeight() - targetH);
            if (diff < bestDiff) {
                bestSize = size;
                bestDiff = diff;
            }
        }
        return bestSize;
    }

    private byte[] convertYuvToJpeg(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = yuv420ToNv21(image);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 85, out);
        return out.toByteArray();
    }

    private byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        // 1. Copy Y Plane (bulk copy if pixel stride is 1)
        int pos = 0;
        ByteBuffer yBuf = yBuffer.duplicate();
        yBuf.clear();
        if (yPixelStride == 1 && yRowStride == width) {
            yBuf.get(nv21, 0, width * height);
            pos = width * height;
        } else {
            for (int r = 0; r < height; r++) {
                yBuf.position(r * yRowStride);
                yBuf.get(nv21, pos, width);
                pos += width;
            }
        }

        // 2. Interleave V and U (NV21 expects V-U-V-U...)
        int uvLimit = nv21.length;
        ByteBuffer uBuf = uBuffer.duplicate();
        ByteBuffer vBuf = vBuffer.duplicate();
        uBuf.clear();
        vBuf.clear();
        
        int uRemaining = uBuf.remaining();
        int vRemaining = vBuf.remaining();
        byte[] uBytes = new byte[uRemaining];
        byte[] vBytes = new byte[vRemaining];
        uBuf.get(uBytes);
        vBuf.get(vBytes);

        for (int r = 0; r < height / 2; r++) {
            int uRowStart = r * uRowStride;
            int vRowStart = r * vRowStride;
            for (int c = 0; c < width / 2; c++) {
                int vIdx = vRowStart + c * vPixelStride;
                int uIdx = uRowStart + c * uPixelStride;

                if (vIdx >= 0 && vIdx < vRemaining && pos < uvLimit) {
                    nv21[pos++] = vBytes[vIdx];
                } else if (pos < uvLimit) {
                    nv21[pos++] = 0;
                }
                if (uIdx >= 0 && uIdx < uRemaining && pos < uvLimit) {
                    nv21[pos++] = uBytes[uIdx];
                } else if (pos < uvLimit) {
                    nv21[pos++] = 0;
                }
            }
        }
        return nv21;
    }

    public void setFilterEffect(int effectIndex) {
        currentFilterEffect = effectIndex;
        if (captureSession == null || captureBuilder == null) return;
        
        try {
            // Reset to defaults first
            captureBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF);
            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);
            captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST);
            captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
            captureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST);
            
            switch (effectIndex) {
                case 0: // NORMAL
                    break;
                case 1: // BEAUTY (AI Skin Smooth & Glow)
                    // High-quality noise reduction smooths out skin details
                    captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                    // Disable sharp edge enhancements to soften facial features
                    captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
                    // Brighten the exposure slightly (+2 steps) for a glowing face look
                    captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 2);
                    break;
                case 2: // PORTRAIT (AI Bokeh)
                    try {
                        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
                        int[] sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
                        if (sceneModes != null) {
                            for (int mode : sceneModes) {
                                if (mode == CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT) {
                                    captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
                                    captureBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT);
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // fallback
                    }
                    captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 1);
                    break;
                case 3: // COMIC (Posterize & High Edge Sharpening)
                    captureBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_POSTERIZE);
                    captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
                    break;
                case 4: // NEON (Cyberpunk Purple/Teal transform)
                    captureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
                    android.hardware.camera2.params.RggbChannelVector neonGains = 
                        new android.hardware.camera2.params.RggbChannelVector(2.6f, 0.8f, 0.8f, 2.9f);
                    captureBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, neonGains);
                    break;
                case 5: // GLITCH (Solarized & High Contrast)
                    captureBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SOLARIZE);
                    captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -2);
                    break;
            }
            
            captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
            Log.i(TAG, "Applied Camera2 filter index: " + effectIndex);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to apply filter index: " + effectIndex, e);
        }
    }

    public int getCurrentFilterEffect() {
        return currentFilterEffect;
    }

    public void toggleAutofocusMode() {
        isAutofocusLocked = !isAutofocusLocked;
        if (captureSession != null && captureBuilder != null) {
            try {
                if (isAutofocusLocked) {
                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                } else {
                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                    captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                }
                captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to toggle autofocus mode", e);
            }
        }
    }

    public boolean isAutofocusLocked() {
        return isAutofocusLocked;
    }

    public void triggerAutofocus() {
        if (captureSession == null || captureBuilder == null) return;
        try {
            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull android.hardware.camera2.TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    try {
                        captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to reset AF trigger", e);
                    }
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to trigger autofocus", e);
        }
    }

    public boolean isTorchEnabled() {
        return torchEnabled;
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public synchronized void setFaceAutoFocusEnabled(boolean enabled) {
        if (this.faceAutoFocusEnabled != enabled) {
            this.faceAutoFocusEnabled = enabled;
            if (cameraDevice != null && captureSession != null && captureBuilder != null) {
                try {
                    CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
                    int[] faceModes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                    
                    int faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF;
                    if (enabled && faceModes != null) {
                        for (int mode : faceModes) {
                            if (mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) {
                                faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL;
                                break;
                            } else if (mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                                faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE;
                            }
                        }
                    }
                    
                    captureBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode);
                    
                    if (enabled) {
                        if (!isAutofocusLocked) {
                            boolean facePrioritySupported = false;
                            int[] sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
                            if (sceneModes != null) {
                                for (int mode : sceneModes) {
                                    if (mode == CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY) {
                                        facePrioritySupported = true;
                                        break;
                                    }
                                }
                            }
                            if (facePrioritySupported) {
                                captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
                                captureBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
                            } else {
                                captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                            }
                        }
                    } else {
                        captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null);
                        captureBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, null);
                        lastFocusedFaceRect = null;
                    }
                    
                    captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
                    Log.i(TAG, "Dynamic face autofocus enabled: " + enabled);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to dynamically change face autofocus mode", e);
                }
            }
        }
    }

    private void processCaptureResult(TotalCaptureResult result) {
        if (!faceAutoFocusEnabled || maxAfRegions <= 0 || activeArraySize == null) {
            return;
        }

        android.hardware.camera2.params.Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
        if (faces != null && faces.length > 0) {
            android.hardware.camera2.params.Face primaryFace = null;
            int maxArea = 0;
            for (android.hardware.camera2.params.Face face : faces) {
                if (face.getScore() < 50) continue;
                Rect bounds = face.getBounds();
                int area = bounds.width() * bounds.height();
                if (area > maxArea) {
                    maxArea = area;
                    primaryFace = face;
                }
            }

            if (primaryFace != null) {
                Rect faceRect = primaryFace.getBounds();
                long now = System.currentTimeMillis();
                boolean shouldUpdate = false;
                if (lastFocusedFaceRect == null) {
                    shouldUpdate = true;
                } else {
                    int toleranceX = activeArraySize.width() / 15;
                    int toleranceY = activeArraySize.height() / 15;
                    int diffCenterX = Math.abs(faceRect.centerX() - lastFocusedFaceRect.centerX());
                    int diffCenterY = Math.abs(faceRect.centerY() - lastFocusedFaceRect.centerY());
                    int diffWidth = Math.abs(faceRect.width() - lastFocusedFaceRect.width());
                    
                    if (diffCenterX > toleranceX || diffCenterY > toleranceY || diffWidth > toleranceX) {
                        shouldUpdate = true;
                    } else if (now - lastFaceFocusUpdateTime > 2000) {
                        shouldUpdate = true;
                    }
                }

                if (shouldUpdate) {
                    lastFocusedFaceRect = faceRect;
                    lastFaceFocusUpdateTime = now;
                    applyFaceFocusRegion(faceRect);
                }
            }
        } else {
            if (lastFocusedFaceRect != null && System.currentTimeMillis() - lastFaceFocusUpdateTime > 3000) {
                lastFocusedFaceRect = null;
                resetFocusRegion();
            }
        }
    }

    private void applyFaceFocusRegion(Rect faceRect) {
        if (captureSession == null || captureBuilder == null || activeArraySize == null) return;
        try {
            int left = Math.max(activeArraySize.left, faceRect.left);
            int top = Math.max(activeArraySize.top, faceRect.top);
            int right = Math.min(activeArraySize.right, faceRect.right);
            int bottom = Math.min(activeArraySize.bottom, faceRect.bottom);
            Rect clippedRect = new Rect(left, top, right, bottom);

            android.hardware.camera2.params.MeteringRectangle[] afRegions = new android.hardware.camera2.params.MeteringRectangle[]{
                new android.hardware.camera2.params.MeteringRectangle(clippedRect, 1000)
            };

            captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
            captureBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, afRegions);
            captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
            Log.d(TAG, "Applied face focus region: " + clippedRect.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply face focus region", e);
        }
    }

    private void resetFocusRegion() {
        if (captureSession == null || captureBuilder == null) return;
        try {
            captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null);
            captureBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, null);
            captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
            Log.d(TAG, "Reset focus region to default");
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset focus region", e);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            processCaptureResult(result);
        }
    };

    public synchronized void selectCamera(String cameraId) {
        if (cameraId == null) return;
        this.currentCameraId = cameraId;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null) {
                this.lensFacing = facing;
            }
        } catch (Exception e) {
            // ignore
        }
        if (isPreviewRunning || isStreamingToSocket) {
            closeCamera();
            openCamera();
        }
    }

    public synchronized String getCurrentCameraId() {
        return currentCameraId;
    }

    private void applyCameraSettings() {
        if (captureBuilder == null) return;
        
        // 1. Apply Zoom
        Rect zoomRect = calculateZoomRect(zoomFactor);
        if (zoomRect != null) {
            captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
        }

        // 2. Apply Exposure Compensation
        captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation);

        // 3. Apply Focus Mode
        if (focusMode == 1) { // Manual Focus
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance);
        } else {
            if (isAutofocusLocked) {
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }
        }

        // 4. Apply White Balance (AWB)
        captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);

        // 5. Apply Stabilization
        if (stabilizationEnabled) {
            captureBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        } else {
            captureBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
    }

    private Rect calculateZoomRect(float zoomLevel) {
        if (activeArraySize == null) return null;
        float maxZoom = getMaxZoom();
        zoomLevel = Math.max(1.0f, Math.min(zoomLevel, maxZoom));
        int xOffset = (int) ((activeArraySize.width() / 2f) * (1f - 1f / zoomLevel));
        int yOffset = (int) ((activeArraySize.height() / 2f) * (1f - 1f / zoomLevel));
        return new Rect(
            activeArraySize.left + xOffset,
            activeArraySize.top + yOffset,
            activeArraySize.right - xOffset,
            activeArraySize.bottom - yOffset
        );
    }

    public float getMaxZoom() {
        if (currentCameraId == null) return 1.0f;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentCameraId);
            Float maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            return (maxZoom != null) ? maxZoom : 1.0f;
        } catch (Exception e) {
            return 1.0f;
        }
    }

    public int getMinExposure() {
        if (currentCameraId == null) return 0;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentCameraId);
            android.util.Range<Integer> range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            return (range != null) ? range.getLower() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getMaxExposure() {
        if (currentCameraId == null) return 0;
        try {
            CameraManager manager = (Context.CAMERA_SERVICE != null) ? 
                (CameraManager) context.getSystemService(Context.CAMERA_SERVICE) : null;
            if (manager == null) return 0;
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentCameraId);
            android.util.Range<Integer> range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            return (range != null) ? range.getUpper() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized void setZoomFactor(float zoom) {
        this.zoomFactor = zoom;
        new SettingsManager(context).setZoomFactor(zoom);
        triggerSettingsUpdate();
    }

    public float getZoomFactor() {
        return zoomFactor;
    }

    public synchronized void setExposureCompensation(int ec) {
        this.exposureCompensation = ec;
        new SettingsManager(context).setExposureCompensation(ec);
        triggerSettingsUpdate();
    }

    public int getExposureCompensation() {
        return exposureCompensation;
    }

    public synchronized void setFocusMode(int mode) {
        this.focusMode = mode;
        new SettingsManager(context).setFocusMode(mode);
        triggerSettingsUpdate();
    }

    public int getFocusMode() {
        return focusMode;
    }

    public synchronized void setManualFocusDistance(float distance) {
        this.manualFocusDistance = distance;
        new SettingsManager(context).setManualFocusDistance(distance);
        triggerSettingsUpdate();
    }

    public float getManualFocusDistance() {
        return manualFocusDistance;
    }

    public synchronized void setAwbMode(int mode) {
        this.awbMode = mode;
        new SettingsManager(context).setAwbMode(mode);
        triggerSettingsUpdate();
    }

    public int getAwbMode() {
        return awbMode;
    }

    public synchronized void setStabilizationEnabled(boolean enabled) {
        this.stabilizationEnabled = enabled;
        new SettingsManager(context).setStabilizationEnabled(enabled);
        triggerSettingsUpdate();
    }

    public boolean getStabilizationEnabled() {
        return stabilizationEnabled;
    }

    private void triggerSettingsUpdate() {
        if (captureBuilder == null || captureSession == null || backgroundHandler == null) return;
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                applyCameraSettings();
                try {
                    captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
                } catch (Exception e) {
                    Log.e(TAG, "Error applying camera settings dynamically", e);
                }
            }
        });
    }

    public synchronized void applyFocusAtPoint(float touchX, float touchY, int viewWidth, int viewHeight) {
        if (captureSession == null || captureBuilder == null || activeArraySize == null || backgroundHandler == null) return;
        
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int sensorW = activeArraySize.width();
                    int sensorH = activeArraySize.height();
                    
                    float scaleX = (float) sensorW / viewWidth;
                    float scaleY = (float) sensorH / viewHeight;
                    
                    int sensorTouchX = (int) (touchX * scaleX);
                    int sensorTouchY = (int) (touchY * scaleY);
                    
                    int boxSize = 150;
                    int left = Math.max(0, sensorTouchX - boxSize / 2);
                    int top = Math.max(0, sensorTouchY - boxSize / 2);
                    int right = Math.min(sensorW, left + boxSize);
                    int bottom = Math.min(sensorH, top + boxSize);
                    
                    Rect focusRect = new Rect(left, top, right, bottom);
                    android.hardware.camera2.params.MeteringRectangle[] afRegions = new android.hardware.camera2.params.MeteringRectangle[]{
                        new android.hardware.camera2.params.MeteringRectangle(focusRect, 1000)
                    };
                    
                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
                    captureBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, afRegions);
                    captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    
                    captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            super.onCaptureCompleted(session, request, result);
                            try {
                                captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                                captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to reset AF trigger after touch focus", e);
                            }
                        }
                    }, backgroundHandler);
                    
                    Log.i(TAG, "Triggered Touch-to-Focus at: " + focusRect.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Touch-to-Focus failed", e);
                }
            }
        });
    }
}
