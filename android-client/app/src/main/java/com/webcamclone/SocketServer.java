package com.webcamclone;

import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.LinkedBlockingQueue;

public class SocketServer {
    private static final String TAG = "SocketServer";
    private int port = 4747;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private boolean isRunning = false;
    private final MainActivity activity;

    private Socket activeVideoSocket;
    private final Object videoLock = new Object();

    // MJPEG web preview clients removed
    private volatile long lastPreviewFrameMs = 0;
    private static final long PREVIEW_INTERVAL_MS = 80; // ~12 FPS for web preview

    // Asynchronous writing structures
    private static class FramePacket {
        final byte[] data;
        final long pts;

        FramePacket(byte[] data, long pts) {
            this.data = data;
            this.pts = pts;
        }
    }

    private final LinkedBlockingQueue<FramePacket> writeQueue = new LinkedBlockingQueue<>(10);
    private Thread writeThread;
    private volatile boolean isWriting = false;
    private int droppedFramesCount = 0;

    public interface StreamListener {
        void onStartStream(String format, int width, int height);
        void onStopStream();
        int getBatteryPercentage();
    }

    public SocketServer(MainActivity activity) {
        this.activity = activity;
        SettingsManager settings = new SettingsManager(activity);
        this.port = settings.getPort();
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getPort() {
        return this.port;
    }

    public boolean isServerRunning() {
        return isRunning;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;

        isWriting = true;
        writeQueue.clear();
        droppedFramesCount = 0;
        writeThread = new Thread(new WriteRunnable(), "SocketWriteThread");
        writeThread.start();

        serverThread = new Thread(new ServerRunnable());
        serverThread.start();
        Log.i(TAG, "Server started on port " + port);
    }

    public void stop() {
        isRunning = false;

        isWriting = false;
        if (writeThread != null) {
            writeThread.interrupt();
            writeThread = null;
        }
        writeQueue.clear();

        // MJPEG web preview clients removed

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }
        closeActiveVideoSocket();
        if (serverThread != null) {
            serverThread.interrupt();
        }
        Log.i(TAG, "Server stopped");
    }

    public boolean hasPreviewClients() {
        return false;
    }

    public void sendPreviewFrame(final byte[] jpegData) {
        // No-op, preview removed
    }

    private void closeActiveVideoSocket() {
        synchronized (videoLock) {
            if (activeVideoSocket != null) {
                try {
                    activeVideoSocket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing active video socket", e);
                }
                activeVideoSocket = null;
            }
        }
    }

    public void sendVideoConfig(byte[] configBytes) {
        submitPacket(new FramePacket(configBytes, -1L));
    }

    public void sendVideoFrame(byte[] frameBytes) {
        sendVideoFrame(frameBytes, System.nanoTime());
    }

    public void sendVideoFrame(byte[] frameBytes, long pts) {
        submitPacket(new FramePacket(frameBytes, pts));
    }

    private void submitPacket(FramePacket packet) {
        synchronized (videoLock) {
            if (activeVideoSocket == null || activeVideoSocket.isClosed()) {
                return;
            }
        }

        try {
            // Block until space is available. This backpressures the MediaCodec,
            // which causes the Camera HAL to cleanly drop raw source frames 
            // instead of corrupting the encoded H.264 stream by dropping P-frames.
            writeQueue.put(packet);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class ServerRunnable implements Runnable {
        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(port);
                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    new Thread(new ClientHandler(socket)).start();
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "Server error", e);
                }
            }
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(5000);
                InputStream is = socket.getInputStream();
                byte[] requestBuffer = new byte[2048];
                int initialRead = is.read(requestBuffer);
                if (initialRead <= 0) {
                    socket.close();
                    return;
                }
                String requestLine = new String(requestBuffer, 0, initialRead, "UTF-8");
                Log.d(TAG, "Received request: " + requestLine);

                String[] requestParts = requestLine.split("\r\n");
                String firstLine = requestParts.length > 0 ? requestParts[0] : "";
                String[] methodAndUri = firstLine.split(" ");
                String uri = methodAndUri.length >= 2 ? methodAndUri[1] : "";

                if (uri.equals("/") || uri.startsWith("/control") || uri.startsWith("/settings")) {
                    byte[] responseBytes = HTML_PAGE.getBytes("UTF-8");
                    String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: " + responseBytes.length + "\r\n" +
                            "Connection: close\r\n\r\n";
                    OutputStream os = socket.getOutputStream();
                    os.write(responseHeaders.getBytes());
                    os.write(responseBytes);
                    os.flush();
                    socket.close();
                    return;

                } else if (uri.equals("/preview") || uri.startsWith("/preview?")) {
                    OutputStream os = socket.getOutputStream();
                    os.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes("UTF-8"));
                    os.flush();
                    socket.close();
                    return;

                } else if (uri.startsWith("/api/status")) {
                    SettingsManager settings = new SettingsManager(activity);
                    int level = activity.getBatteryPercentage();
                    boolean streaming = isStreaming();
                    boolean faceAf = settings.getFaceAutoFocus();
                    boolean keepOn = settings.getKeepScreenOn();
                    boolean flipH = settings.getFlipHorizontal();
                    boolean flipV = settings.getFlipVertical();
                    String activeCameraId = "";
                    boolean lockedAf = false;

                    if (activity.cameraStreamer != null) {
                        activeCameraId = activity.cameraStreamer.getCurrentCameraId();
                        lockedAf = activity.cameraStreamer.isAutofocusLocked();
                    } else {
                        activeCameraId = "0";
                    }

                    java.util.List<String> resList = activity.getSupportedResolutions();
                    StringBuilder resSb = new StringBuilder("[");
                    for (int i = 0; i < resList.size(); i++) {
                        resSb.append("\"").append(resList.get(i)).append("\"");
                        if (i < resList.size() - 1) resSb.append(",");
                    }
                    resSb.append("]");

                    StringBuilder camSb = new StringBuilder("[");
                    try {
                        android.hardware.camera2.CameraManager camManager = (android.hardware.camera2.CameraManager) activity.getSystemService(android.content.Context.CAMERA_SERVICE);
                        String[] idList = camManager.getCameraIdList();
                        for (int i = 0; i < idList.length; i++) {
                            String id = idList[i];
                            android.hardware.camera2.CameraCharacteristics chars = camManager.getCameraCharacteristics(id);
                            Integer facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                            String facingStr = "Unknown";
                            if (facing != null) {
                                if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) facingStr = "Front";
                                else if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) facingStr = "Back";
                                else if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL) facingStr = "External";
                            }
                            camSb.append("{\"id\":\"").append(id).append("\",\"facing\":\"").append(facingStr).append("\"}");
                            if (i < idList.length - 1) camSb.append(",");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting camera list for API status", e);
                    }
                    camSb.append("]");

                    float maxZoom = (activity.cameraStreamer != null) ? activity.cameraStreamer.getMaxZoom() : 1.0f;
                    int minExp = (activity.cameraStreamer != null) ? activity.cameraStreamer.getMinExposure() : 0;
                    int maxExp = (activity.cameraStreamer != null) ? activity.cameraStreamer.getMaxExposure() : 0;

                    String body = "{" +
                            "\"battery\":" + level + "," +
                            "\"isStreaming\":" + streaming + "," +
                            "\"faceAutoFocus\":" + faceAf + "," +
                            "\"keepScreenOn\":" + keepOn + "," +
                            "\"flipHorizontal\":" + flipH + "," +
                            "\"flipVertical\":" + flipV + "," +
                            "\"activeCameraId\":\"" + activeCameraId + "\"," +
                            "\"isAutofocusLocked\":" + lockedAf + "," +
                            "\"resolution\":\"" + settings.getResolution() + "\"," +
                            "\"bitrate\":" + settings.getBitrate() + "," +
                            "\"framerate\":" + settings.getFramerate() + "," +
                            "\"zoomFactor\":" + settings.getZoomFactor() + "," +
                            "\"maxZoom\":" + maxZoom + "," +
                            "\"exposureCompensation\":" + settings.getExposureCompensation() + "," +
                            "\"minExposure\":" + minExp + "," +
                            "\"maxExposure\":" + maxExp + "," +
                            "\"focusMode\":" + settings.getFocusMode() + "," +
                            "\"manualFocusDistance\":" + settings.getManualFocusDistance() + "," +
                            "\"awbMode\":" + settings.getAwbMode() + "," +
                            "\"stabilizationEnabled\":" + settings.getStabilizationEnabled() + "," +
                            "\"availableCameras\":" + camSb.toString() + "," +
                            "\"availableResolutions\":" + resSb.toString() +
                            "}";

                    byte[] bodyBytes = body.getBytes("UTF-8");
                    String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: " + bodyBytes.length + "\r\n" +
                            "Connection: close\r\n\r\n";
                    OutputStream os = socket.getOutputStream();
                    os.write(response.getBytes());
                    os.write(bodyBytes);
                    os.flush();
                    socket.close();
                    return;

                } else if (uri.startsWith("/api/set")) {
                    String key = getQueryParam(uri, "key");
                    String val = getQueryParam(uri, "val");
                    if (key != null && val != null) {
                        activity.updateSettingFromWeb(key, val);
                    }
                    String body = "{\"status\":\"success\"}";
                    byte[] bodyBytes = body.getBytes("UTF-8");
                    String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: " + bodyBytes.length + "\r\n" +
                            "Connection: close\r\n\r\n";
                    OutputStream os = socket.getOutputStream();
                    os.write(response.getBytes());
                    os.write(bodyBytes);
                    os.flush();
                    socket.close();
                    return;

                } else if (requestLine.contains("/video")) {
                    int width = 1280;
                    int height = 720;
                    String format = "jpg";
                    Pattern pattern = Pattern.compile("/video/(avc|jpg|hevc)/(\\d+)x(\\d+)");
                    Matcher matcher = pattern.matcher(requestLine);
                    if (matcher.find()) {
                        format = matcher.group(1);
                        width = Integer.parseInt(matcher.group(2));
                        height = Integer.parseInt(matcher.group(3));
                    }

                    Log.i(TAG, "Starting video stream format: " + format + " at " + width + "x" + height);

                    synchronized (videoLock) {
                        if (activeVideoSocket != null) {
                            closeActiveVideoSocket();
                        }
                        activeVideoSocket = socket;
                    }

                    activity.onStartStream(format, width, height);

                    socket.setSoTimeout(0);
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        // Block/consume any client packet data
                    }
                    Log.i(TAG, "Client disconnected (EOF received)");
                    closeActiveVideoSocket();
                    activity.onStopStream();

                } else if (requestLine.contains("/battery")) {
                    int level = activity.getBatteryPercentage();
                    String body = String.valueOf(level);
                    String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: " + body.length() + "\r\n" +
                            "Connection: close\r\n\r\n" +
                            body;
                    OutputStream os = socket.getOutputStream();
                    os.write(response.getBytes());
                    os.flush();
                    socket.close();

                } else if (requestLine.contains("/tally")) {
                    String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n";
                    OutputStream os = socket.getOutputStream();
                    os.write(response.getBytes());
                    os.flush();
                    socket.close();

                } else if (requestLine.contains("/ping")) {
                    String body = "pong";
                    String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: " + body.length() + "\r\n" +
                            "Connection: close\r\n\r\n" +
                            body;
                    OutputStream os = socket.getOutputStream();
                    os.write(response.getBytes());
                    os.flush();
                    socket.close();

                } else {
                    String response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n";
                    OutputStream os = socket.getOutputStream();
                    os.write(response.getBytes());
                    os.flush();
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error in ClientHandler", e);
            } finally {
                synchronized (videoLock) {
                    if (activeVideoSocket == socket) {
                        closeActiveVideoSocket();
                        activity.onStopStream();
                    }
                }
                try {
                    if (!socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing socket in finally", e);
                }
            }
        }
    }

    public boolean isStreaming() {
        synchronized (videoLock) {
            return activeVideoSocket != null && !activeVideoSocket.isClosed();
        }
    }

    public int getDroppedFramesCount() {
        return droppedFramesCount;
    }

    private class WriteRunnable implements Runnable {
        @Override
        public void run() {
            while (isWriting) {
                try {
                    FramePacket packet = writeQueue.take();
                    writePacketToSocketDirect(packet);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void writePacketToSocketDirect(FramePacket packet) {
        Socket socket;
        synchronized (videoLock) {
            socket = activeVideoSocket;
        }
        if (socket == null || socket.isClosed()) {
            return;
        }

        try {
            OutputStream os = socket.getOutputStream();
            ByteBuffer header = ByteBuffer.allocate(12);
            header.putLong(packet.pts);
            header.putInt(packet.data.length);

            os.write(header.array());
            os.write(packet.data);
            os.flush();

            if (packet.pts == -1L) {
                Log.i(TAG, "Sent video codec config packet: " + packet.data.length + " bytes");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write frame to socket direct, closing connection", e);
            closeActiveVideoSocket();
            activity.onStopStream();
            writeQueue.clear();
        }
    }

    private String getQueryParam(String uri, String key) {
        if (uri == null || !uri.contains("?")) return null;
        String queryString = uri.substring(uri.indexOf("?") + 1);
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            if (keyValue.length == 2 && keyValue[0].equals(key)) {
                try {
                    return java.net.URLDecoder.decode(keyValue[1], "UTF-8");
                } catch (Exception e) {
                    return keyValue[1];
                }
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Web Dashboard — Studio Cam modern split-pane UI with live MJPEG preview
    // ─────────────────────────────────────────────────────────────────────────
    private static final String HTML_PAGE =
        "<!DOCTYPE html>\n" +
        "<html lang='en'>\n" +
        "<head>\n" +
        "<meta charset='UTF-8'>\n" +
        "<meta name='viewport' content='width=device-width,initial-scale=1'>\n" +
        "<title>Studio Cam \u2014 Control Panel</title>\n" +
        "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600;700&family=JetBrains+Mono:wght@400;600&display=swap' rel='stylesheet'>\n" +
        "<style>\n" +
        ":root{\n" +
        "  --bg:#080B14;--surface:#0D1117;--surface2:#161B27;\n" +
        "  --border:#1E2940;--accent:#6366F1;--accent-l:#818CF8;\n" +
        "  --accent-d:rgba(99,102,241,.12);--green:#22C55E;\n" +
        "  --green-d:rgba(34,197,94,.12);--rose:#F43F5E;\n" +
        "  --amber:#F59E0B;--text:#F1F5F9;--text2:#94A3B8;--text3:#475569;\n" +
        "  --mono:'JetBrains Mono',monospace;--sans:'Inter',sans-serif;\n" +
        "}\n" +
        "*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}\n" +
        "html,body{height:100%}\n" +
        "body{background:var(--bg);color:var(--text);font-family:var(--sans);display:flex;flex-direction:column;min-height:100vh}\n" +
        ".nav{\n" +
        "  display:flex;align-items:center;gap:1rem;padding:0 1.5rem;height:52px;\n" +
        "  border-bottom:1px solid var(--border);background:rgba(8,11,20,.94);\n" +
        "  backdrop-filter:blur(12px);position:sticky;top:0;z-index:100;\n" +
        "}\n" +
        ".logo{font-size:.95rem;font-weight:700;letter-spacing:.18em;color:var(--accent-l)}\n" +
        ".logo span{color:var(--text3);font-weight:300}\n" +
        ".spacer{flex:1}\n" +
        "#badge{\n" +
        "  display:flex;align-items:center;gap:.4rem;padding:.25rem .8rem;\n" +
        "  border-radius:99px;font-size:.68rem;font-weight:700;letter-spacing:.1em;\n" +
        "  text-transform:uppercase;background:var(--green-d);\n" +
        "  border:1px solid var(--green);color:var(--green);transition:all .3s;\n" +
        "}\n" +
        "#badge.off{background:rgba(71,85,105,.15);border-color:var(--text3);color:var(--text3)}\n" +
        ".dot{width:7px;height:7px;border-radius:50%;background:currentColor;animation:blink 1.4s ease-in-out infinite}\n" +
        "#badge.off .dot{animation:none}\n" +
        "@keyframes blink{0%,100%{opacity:.2;transform:scale(.85)}50%{opacity:1;transform:scale(1.15)}}\n" +
        ".main{display:grid;grid-template-columns:1fr 360px;flex:1;height:calc(100vh - 52px)}\n" +
        ".preview{\n" +
        "  background:#000;display:flex;align-items:center;justify-content:center;\n" +
        "  position:relative;border-right:1px solid var(--border);overflow:hidden;\n" +
        "}\n" +
        "#cam{width:100%;height:100%;object-fit:contain;display:block}\n" +
        ".no-signal{\n" +
        "  position:absolute;inset:0;display:flex;flex-direction:column;\n" +
        "  align-items:center;justify-content:center;text-align:center;\n" +
        "  background:radial-gradient(ellipse at center,rgba(13,17,23,.55) 0%,rgba(8,11,20,.96) 100%);\n" +
        "  pointer-events:none;\n" +
        "}\n" +
        ".no-signal .icon{font-size:3.2rem;opacity:.25}\n" +
        ".no-signal p{color:var(--text3);font-size:.8rem;margin-top:.6rem;letter-spacing:.05em}\n" +
        ".cam-badge{\n" +
        "  position:absolute;top:10px;left:10px;padding:.18rem .55rem;\n" +
        "  border-radius:6px;font-size:.6rem;font-family:var(--mono);font-weight:600;\n" +
        "  letter-spacing:.1em;background:rgba(0,0,0,.72);\n" +
        "  border:1px solid var(--border);color:var(--text2);\n" +
        "}\n" +
        ".panel{background:var(--surface);overflow-y:auto;display:flex;flex-direction:column}\n" +
        ".section{padding:1.1rem 1.2rem .9rem;border-bottom:1px solid var(--border)}\n" +
        ".section:last-child{border-bottom:none}\n" +
        ".sec-title{font-size:.6rem;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:var(--text3);margin-bottom:.85rem}\n" +
        ".row{display:flex;justify-content:space-between;align-items:center;margin-bottom:.55rem;font-size:.8rem}\n" +
        ".row:last-child{margin-bottom:0}\n" +
        ".lbl{color:var(--text2)}\n" +
        ".val{font-family:var(--mono);font-size:.75rem;font-weight:600}\n" +
        ".btn-r{display:flex;gap:.5rem;margin-bottom:.5rem}\n" +
        ".btn-r:last-child{margin-bottom:0}\n" +
        ".btn{\n" +
        "  flex:1;padding:.6rem .4rem;border-radius:8px;\n" +
        "  border:1px solid var(--border);background:var(--surface2);\n" +
        "  color:var(--text);font-size:.75rem;font-weight:600;font-family:var(--sans);\n" +
        "  cursor:pointer;transition:all .18s;text-align:center;\n" +
        "}\n" +
        ".btn:hover{background:var(--accent);border-color:var(--accent);color:#fff;transform:translateY(-1px);box-shadow:0 4px 16px rgba(99,102,241,.35)}\n" +
        ".btn:active{transform:translateY(0)}\n" +
        ".btn.a{background:var(--accent-d);border-color:var(--accent);color:var(--accent-l)}\n" +
        ".btn.a:hover{background:var(--accent);color:#fff}\n" +
        ".fg{margin-bottom:.65rem}\n" +
        ".fg:last-child{margin-bottom:0}\n" +
        "label.fl{display:block;font-size:.6rem;font-weight:700;letter-spacing:.1em;text-transform:uppercase;color:var(--text3);margin-bottom:.3rem}\n" +
        "select{\n" +
        "  width:100%;padding:.5rem .7rem;background:var(--surface2);\n" +
        "  border:1px solid var(--border);border-radius:8px;color:var(--text);\n" +
        "  font-size:.8rem;font-family:var(--sans);outline:none;transition:border-color .2s;\n" +
        "  -webkit-appearance:none;appearance:none;\n" +
        "  background-image:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='10' viewBox='0 0 10 10'%3E%3Cpath fill='%2394A3B8' d='M5 7L0 2h10z'/%3E%3C/svg%3E\");\n" +
        "  background-repeat:no-repeat;background-position:right .7rem center;padding-right:2rem;\n" +
        "}\n" +
        "select:focus{border-color:var(--accent)}\n" +
        ".tog-r{display:flex;justify-content:space-between;align-items:center;padding:.4rem 0;border-bottom:1px solid rgba(255,255,255,.04)}\n" +
        ".tog-r:last-child{border-bottom:none}\n" +
        ".tog-lbl{font-size:.8rem;color:var(--text2)}\n" +
        ".sw{position:relative;display:inline-block;width:40px;height:21px}\n" +
        ".sw input{opacity:0;width:0;height:0}\n" +
        ".sl{position:absolute;inset:0;background:var(--surface2);border:1px solid var(--border);border-radius:21px;cursor:pointer;transition:.25s}\n" +
        ".sl::before{content:'';position:absolute;width:13px;height:13px;left:3px;top:3px;background:var(--text3);border-radius:50%;transition:.25s}\n" +
        "input:checked+.sl{background:var(--accent-d);border-color:var(--accent)}\n" +
        "input:checked+.sl::before{transform:translateX(19px);background:var(--accent-l)}\n" +
        ".batt{display:flex;align-items:center;gap:.4rem}\n" +
        ".batt-b{width:28px;height:13px;border:1px solid var(--text3);border-radius:3px;padding:1px}\n" +
        ".batt-f{height:100%;background:var(--green);border-radius:2px;transition:width .4s}\n" +
        ".toast{\n" +
        "  position:fixed;bottom:1.2rem;right:1.2rem;padding:.65rem 1.1rem;\n" +
        "  background:var(--surface2);border:1px solid var(--accent);\n" +
        "  border-radius:10px;font-size:.76rem;font-family:var(--mono);\n" +
        "  color:var(--accent-l);box-shadow:0 8px 32px rgba(99,102,241,.25);\n" +
        "  transform:translateY(80px);opacity:0;\n" +
        "  transition:all .3s cubic-bezier(.34,1.56,.64,1);z-index:999;\n" +
        "}\n" +
        ".toast.show{transform:translateY(0);opacity:1}\n" +
        "input[type=range]{-webkit-appearance:none;width:100%;background:transparent;margin:.4rem 0}\n" +
        "input[type=range]:focus{outline:none}\n" +
        "input[type=range]::-webkit-slider-runnable-track{width:100%;height:5px;cursor:pointer;background:var(--surface2);border-radius:4px;border:1px solid var(--border)}\n" +
        "input[type=range]::-webkit-slider-thumb{height:15px;width:15px;border-radius:50%;background:var(--accent-l);cursor:pointer;-webkit-appearance:none;margin-top:-6px;border:1px solid var(--accent)}\n" +
        "input[type=range]:focus::-webkit-slider-runnable-track{background:var(--surface2)}\n" +
        ".range-val{font-family:var(--mono);font-size:.72rem;font-weight:600;color:var(--accent-l)}\n" +
        "@media(max-width:680px){\n" +
        "  .main{grid-template-columns:1fr;grid-template-rows:55vw 1fr;height:auto}\n" +
        "  .preview{border-right:none;border-bottom:1px solid var(--border)}\n" +
        "  .panel{overflow:visible;height:auto}\n" +
        "}\n" +
        "</style>\n" +
        "</head>\n" +
        "<body>\n" +
        "<nav class='nav'>\n" +
        "  <div class='logo'>STUDIO<span>CAM</span></div>\n" +
        "  <div class='spacer'></div>\n" +
        "  <div id='badge' class='off'><span class='dot'></span><span id='bt'>STANDBY</span></div>\n" +
        "</nav>\n" +
        "<div class='main'>\n" +
        "  <div class='preview'>\n" +
        "    <img id='cam' src='/preview' alt='Live Preview'>\n" +
        "    <div class='no-signal' id='ns'>\n" +
        "      <div class='icon'>&#128247;</div>\n" +
        "      <p>CONNECTING TO CAMERA</p>\n" +
        "      <p style='margin-top:.35rem;font-size:.72rem'>Preview appears here automatically</p>\n" +
        "    </div>\n" +
        "    <div class='cam-badge'>LIVE PREVIEW &middot; MJPEG</div>\n" +
        "  </div>\n" +
        "  <div class='panel'>\n" +
        "    <div class='section'>\n" +
        "      <div class='sec-title'>System Status</div>\n" +
        "      <div class='row'><span class='lbl'>OBS Stream</span><span id='ss' class='val' style='color:var(--text3)'>STANDBY</span></div>\n" +
        "      <div class='row'><span class='lbl'>Battery</span><span class='batt'><span id='bp' class='val'>--%</span><div class='batt-b'><div id='bf' class='batt-f' style='width:0'></div></div></span></div>\n" +
        "      <div class='row'><span class='lbl'>Camera</span><span id='ac' class='val'>--</span></div>\n" +
        "      <div class='row'><span class='lbl'>Resolution</span><span id='sr' class='val'>--</span></div>\n" +
        "      <div class='row'><span class='lbl'>FPS / Bitrate</span><span id='sfb' class='val'>--</span></div>\n" +
        "    </div>\n" +
        "    <div class='section'>\n" +
        "      <div class='sec-title'>Focus Control</div>\n" +
        "      <div class='btn-r'>\n" +
        "        <button id='taf' class='btn a'>&#127919; Trigger AF</button>\n" +
        "        <button id='tam' class='btn'>&#128274; Lock Focus</button>\n" +
        "      </div>\n" +
        "      <div class='row' style='margin-top:.5rem'><span class='lbl'>Focus Mode</span><span id='fl' class='val' style='color:var(--green)'>AUTO-C</span></div>\n" +
        "      <div class='fg' style='margin-top:.5rem'>\n" +
        "        <label class='fl' for='fmd'>Focus Method</label>\n" +
        "        <select id='fmd'>\n" +
        "          <option value='0'>Auto Focus</option>\n" +
        "          <option value='1'>Manual Focus</option>\n" +
        "        </select>\n" +
        "      </div>\n" +
        "      <div class='fg' id='fd_container' style='display:none'>\n" +
        "        <div class='row'><label class='fl'>Focus Distance</label><span id='fd_val' class='range-val'>0.0</span></div>\n" +
        "        <input type='range' id='fdi' min='0.0' max='1.0' step='0.01' value='0.0'>\n" +
        "      </div>\n" +
        "    </div>\n" +
        "    <div class='section'>\n" +
        "      <div class='sec-title'>Camera &amp; Stream</div>\n" +
        "      <div class='fg'><label class='fl'>Camera Sensor</label><div id='cam_buttons' class='btn-r' style='flex-wrap:wrap;gap:0.4rem'></div></div>\n" +
        "      <div class='fg'><label class='fl' for='rs'>Resolution</label><select id='rs'></select></div>\n" +
        "      <div class='fg'><label class='fl' for='fs'>Framerate</label>\n" +
        "        <select id='fs'>\n" +
        "          <option value='15'>15 FPS</option>\n" +
        "          <option value='24'>24 FPS</option>\n" +
        "          <option value='30'>30 FPS</option>\n" +
        "          <option value='60'>60 FPS</option>\n" +
        "        </select>\n" +
        "      </div>\n" +
        "      <div class='fg'><label class='fl' for='bs'>Bitrate</label>\n" +
        "        <select id='bs'>\n" +
        "          <option value='1000000'>1.0 Mbps</option>\n" +
        "          <option value='2000000'>2.0 Mbps</option>\n" +
        "          <option value='3000000'>3.0 Mbps</option>\n" +
        "          <option value='3500000'>3.5 Mbps</option>\n" +
        "          <option value='4000000'>4.0 Mbps</option>\n" +
        "          <option value='5000000'>5.0 Mbps</option>\n" +
        "          <option value='6000000'>6.0 Mbps</option>\n" +
        "          <option value='10000000'>10 Mbps</option>\n" +
        "        </select>\n" +
        "      </div>\n" +
        "    </div>\n" +
        "    <div class='section'>\n" +
        "      <div class='sec-title'>Manual Camera Controls</div>\n" +
        "      <div class='fg'>\n" +
        "        <div class='row'><label class='fl'>Zoom Factor</label><span id='zm_val' class='range-val'>1.0x</span></div>\n" +
        "        <input type='range' id='zmi' min='1.0' max='8.0' step='0.1' value='1.0'>\n" +
        "      </div>\n" +
        "      <div class='fg'>\n" +
        "        <div class='row'><label class='fl'>Exposure Compensation</label><span id='ex_val' class='range-val'>0</span></div>\n" +
        "        <input type='range' id='exi' min='-6' max='6' step='1' value='0'>\n" +
        "      </div>\n" +
        "      <div class='fg'>\n" +
        "        <label class='fl' for='wbs'>White Balance</label>\n" +
        "        <select id='wbs'>\n" +
        "          <option value='1'>Auto</option>\n" +
        "          <option value='2'>Incandescent</option>\n" +
        "          <option value='3'>Fluorescent</option>\n" +
        "          <option value='4'>Warm Fluorescent</option>\n" +
        "          <option value='5'>Daylight</option>\n" +
        "          <option value='6'>Cloudy</option>\n" +
        "          <option value='7'>Twilight</option>\n" +
        "          <option value='8'>Shade</option>\n" +
        "        </select>\n" +
        "      </div>\n" +
        "      <div class='tog-r'>\n" +
        "        <span class='tog-lbl'>Video Stabilization</span>\n" +
        "        <label class='sw'><input type='checkbox' id='tst'><span class='sl'></span></label>\n" +
        "      </div>\n" +
        "    </div>\n" +
        "    <div class='section'>\n" +
        "      <div class='sec-title'>Preferences</div>\n" +
        "      <div class='tog-r'><span class='tog-lbl'>Face Auto-Focus</span><label class='sw'><input type='checkbox' id='tfa'><span class='sl'></span></label></div>\n" +
        "      <div class='tog-r'><span class='tog-lbl'>Keep Screen On</span><label class='sw'><input type='checkbox' id='tso'><span class='sl'></span></label></div>\n" +
        "      <div class='tog-r'><span class='tog-lbl'>Flip Horizontal</span><label class='sw'><input type='checkbox' id='tfh'><span class='sl'></span></label></div>\n" +
        "      <div class='tog-r'><span class='tog-lbl'>Flip Vertical</span><label class='sw'><input type='checkbox' id='tfv'><span class='sl'></span></label></div>\n" +
        "    </div>\n" +
        "  </div>\n" +
        "</div>\n" +
        "<div id='toast' class='toast'></div>\n" +
        "<script>\n" +
        "let loaded=false;\n" +
        "const T=id=>document.getElementById(id);\n" +
        "function toast(m){const t=T('toast');t.textContent=m;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),2600)}\n" +
        "async function api(k,v){\n" +
        "  try{await fetch('/api/set?key='+encodeURIComponent(k)+'&val='+encodeURIComponent(v));\n" +
        "  toast('\\u2713 '+k.replace(/_/g,' ').toUpperCase());fetchStatus();}catch(e){toast('Error')}\n" +
        "}\n" +
        "T('rs').onchange=e=>api('resolution',e.target.value);\n" +
        "T('fs').onchange=e=>api('framerate',e.target.value);\n" +
        "T('bs').onchange=e=>api('bitrate',e.target.value);\n" +
        "T('tfa').onchange=e=>api('face_auto_focus',e.target.checked);\n" +
        "T('tso').onchange=e=>api('keep_screen_on',e.target.checked);\n" +
        "T('tfh').onchange=e=>api('flip_horizontal',e.target.checked);\n" +
        "T('tfv').onchange=e=>api('flip_vertical',e.target.checked);\n" +
        "T('taf').onclick=()=>api('trigger_af','now');\n" +
        "T('tam').onclick=()=>api('toggle_af_mode','now');\n" +
        "T('zmi').oninput=e=>{T('zm_val').textContent=parseFloat(e.target.value).toFixed(1)+'x';};\n" +
        "T('zmi').onchange=e=>api('zoom',e.target.value);\n" +
        "T('exi').oninput=e=>{const v=parseInt(e.target.value);T('ex_val').textContent=(v>0?'+':'')+v;};\n" +
        "T('exi').onchange=e=>api('exposure',e.target.value);\n" +
        "T('fmd').onchange=e=>{const v=parseInt(e.target.value);T('fd_container').style.display=v===1?'block':'none';api('focus_mode',e.target.value);};\n" +
        "T('fdi').oninput=e=>{T('fd_val').textContent=parseFloat(e.target.value).toFixed(2);};\n" +
        "T('fdi').onchange=e=>api('focus_distance',e.target.value);\n" +
        "T('wbs').onchange=e=>api('awb_mode',e.target.value);\n" +
        "T('tst').onchange=e=>api('stabilization',e.target.checked);\n" +
        "async function fetchStatus(){\n" +
        "  try{\n" +
        "    const r=await fetch('/api/status');if(!r.ok)return;\n" +
        "    const d=await r.json();\n" +
        "    const b=T('badge'),bt=T('bt'),ss=T('ss');\n" +
        "    if(d.isStreaming){b.classList.remove('off');bt.textContent='OBS LIVE';\n" +
        "      ss.textContent='STREAMING';ss.style.color='var(--green)';\n" +
        "    }else{b.classList.add('off');bt.textContent='STANDBY';\n" +
        "      ss.textContent='STANDBY';ss.style.color='var(--text3)';}\n" +
        "    T('bp').textContent=d.battery+'%';T('bf').style.width=d.battery+'%';\n" +
        "    const cam=d.availableCameras.find(c=>c.id===d.activeCameraId);\n" +
        "    T('ac').textContent=cam?'Cam '+d.activeCameraId+' ('+cam.facing+')':'Cam '+d.activeCameraId;\n" +
        "    T('sr').textContent=d.resolution;\n" +
        "    T('sfb').textContent=d.framerate+' FPS / '+(d.bitrate/1e6).toFixed(1)+' Mbps';\n" +
        "    const fl=T('fl');fl.textContent=d.isAutofocusLocked?'LOCKED':'AUTO-C';\n" +
        "    fl.style.color=d.isAutofocusLocked?'var(--amber)':'var(--green)';\n" +
        "    T('zm_val').textContent=d.zoomFactor.toFixed(1)+'x';\n" +
        "    T('zmi').value=d.zoomFactor;\n" +
        "    T('zmi').max=d.maxZoom;\n" +
        "    T('ex_val').textContent=(d.exposureCompensation>0?'+':'')+d.exposureCompensation;\n" +
        "    T('exi').value=d.exposureCompensation;\n" +
        "    T('exi').min=d.minExposure;\n" +
        "    T('exi').max=d.maxExposure;\n" +
        "    T('fmd').value=d.focusMode;\n" +
        "    T('fd_container').style.display=d.focusMode===1?'block':'none';\n" +
        "    T('fd_val').textContent=d.manualFocusDistance.toFixed(2);\n" +
        "    T('fdi').value=d.manualFocusDistance;\n" +
        "    T('wbs').value=d.awbMode;\n" +
        "    T('tst').checked=d.stabilizationEnabled;\n" +
        "    d.availableCameras.forEach(c=>{\n" +
        "      const btn=T('cambtn_'+c.id);\n" +
        "      if(btn){\n" +
        "        if(c.id===d.activeCameraId)btn.classList.add('a');\n" +
        "        else btn.classList.remove('a');\n" +
        "      }\n" +
        "    });\n" +
        "    if(!loaded){\n" +
        "      loaded=true;\n" +
        "      const cb=T('cam_buttons');cb.innerHTML='';\n" +
        "      d.availableCameras.forEach(c=>{\n" +
        "        const btn=document.createElement('button');\n" +
        "        btn.id='cambtn_'+c.id;\n" +
        "        btn.className='btn' + (c.id===d.activeCameraId?' a':'');\n" +
        "        btn.innerHTML='Cam ' + c.id + ' (' + c.facing + ')';\n" +
        "        btn.onclick=()=>{api('camera',c.id);Array.from(cb.children).forEach(b=>b.classList.remove('a'));btn.classList.add('a');};\n" +
        "        cb.appendChild(btn);\n" +
        "      });\n" +
        "      const rs=T('rs');rs.innerHTML='';\n" +
        "      d.availableResolutions.forEach(r=>{const o=document.createElement('option');\n" +
        "        o.value=r;o.textContent=r;o.selected=r===d.resolution;rs.appendChild(o);});\n" +
        "      T('fs').value=d.framerate;T('bs').value=d.bitrate;\n" +
        "      T('tfa').checked=d.faceAutoFocus;T('tso').checked=d.keepScreenOn;\n" +
        "      T('tfh').checked=d.flipHorizontal;T('tfv').checked=d.flipVertical;\n" +
        "    }\n" +
        "  }catch(e){console.warn(e)}\n" +
        "}\n" +
        "// MJPEG reconnect\n" +
        "const cam=T('cam'),ns=T('ns');\n" +
        "function recon(){cam.style.display='block';cam.src='/preview?t='+Date.now()}\n" +
        "cam.onerror=()=>{cam.style.display='none';ns.style.display='flex';setTimeout(recon,3000)};\n" +
        "cam.onload=()=>{ns.style.display='none';cam.style.display='block'};\n" +
        "fetchStatus();\n" +
        "setInterval(fetchStatus,2000);\n" +
        "</script>\n" +
        "</body></html>\n";
}
