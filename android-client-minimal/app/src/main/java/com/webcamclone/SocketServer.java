package com.webcamclone;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
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
        
        // If queue is full, drop the oldest frame to prevent blocking
        if (writeQueue.remainingCapacity() == 0) {
            writeQueue.poll();
            droppedFramesCount++;
        }
        writeQueue.offer(packet);
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
                socket.setSoTimeout(5000); // 5 seconds timeout to receive request
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
                    // Extract resolution and format, e.g., GET /v5/video/avc/1920x1080/port/4747/...
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
                        // Close any existing active stream first
                        if (activeVideoSocket != null) {
                            closeActiveVideoSocket();
                        }
                        activeVideoSocket = socket;
                    }

                    activity.onStartStream(format, width, height);

                    socket.setSoTimeout(0); // Reset to infinite timeout for streaming
                    // Block this thread to keep the socket and streams alive (prevents GC cleanup closing socket)
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        // Block/consume any client packet data
                    }
                    Log.i(TAG, "Client disconnected (EOF received)");
                    closeActiveVideoSocket();
                    activity.onStopStream();

                } else if (requestLine.contains("/battery")) {
                    // Send HTTP response for battery percentage
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
                    // Send 200 OK response for tally request
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
                    // Unknown request
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

    private static final String HTML_PAGE = 
        "<!DOCTYPE html>\n" +
        "<html lang='en'>\n" +
        "<head>\n" +
        "  <meta charset='UTF-8'>\n" +
        "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
        "  <title>DroidCam Web Control Panel</title>\n" +
        "  <link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600;700&family=JetBrains+Mono:wght@400;700&display=swap' rel='stylesheet'>\n" +
        "  <style>\n" +
        "    :root {\n" +
        "      --bg-color: #08080C;\n" +
        "      --card-bg: rgba(13, 13, 20, 0.75);\n" +
        "      --accent-cyan: #00F0FF;\n" +
        "      --accent-green: #00FF66;\n" +
        "      --accent-orange: #FF9900;\n" +
        "      --text-main: #FFFFFF;\n" +
        "      --text-muted: #8E9A9F;\n" +
        "      --border-color: rgba(0, 240, 255, 0.15);\n" +
        "      --font-main: 'Inter', sans-serif;\n" +
        "      --font-mono: 'JetBrains Mono', monospace;\n" +
        "    }\n" +
        "    * {\n" +
        "      box-sizing: border-box;\n" +
        "      margin: 0;\n" +
        "      padding: 0;\n" +
        "    }\n" +
        "    body {\n" +
        "      background-color: var(--bg-color);\n" +
        "      color: var(--text-main);\n" +
        "      font-family: var(--font-main);\n" +
        "      padding: 2rem 1.5rem;\n" +
        "      min-height: 100vh;\n" +
        "      display: flex;\n" +
        "      flex-direction: column;\n" +
        "      align-items: center;\n" +
        "      background-image: radial-gradient(circle at 50% 50%, #111122 0%, #08080C 100%);\n" +
        "    }\n" +
        "    .container {\n" +
        "      width: 100%;\n" +
        "      max-width: 900px;\n" +
        "      display: flex;\n" +
        "      flex-direction: column;\n" +
        "      gap: 1.5rem;\n" +
        "    }\n" +
        "    header {\n" +
        "      text-align: center;\n" +
        "      margin-bottom: 1rem;\n" +
        "      border-bottom: 1px solid var(--border-color);\n" +
        "      padding-bottom: 1.5rem;\n" +
        "    }\n" +
        "    h1 {\n" +
        "      font-size: 2.2rem;\n" +
        "      font-weight: 700;\n" +
        "      letter-spacing: 2px;\n" +
        "      background: linear-gradient(90deg, #00F0FF, #00FF66);\n" +
        "      -webkit-background-clip: text;\n" +
        "      -webkit-text-fill-color: transparent;\n" +
        "      text-transform: uppercase;\n" +
        "      text-shadow: 0 0 20px rgba(0,240,255,0.2);\n" +
        "    }\n" +
        "    p.subtitle {\n" +
        "      color: var(--text-muted);\n" +
        "      font-size: 0.9rem;\n" +
        "      margin-top: 0.5rem;\n" +
        "      text-transform: uppercase;\n" +
        "      letter-spacing: 1px;\n" +
        "    }\n" +
        "    .grid {\n" +
        "      display: grid;\n" +
        "      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));\n" +
        "      gap: 1.5rem;\n" +
        "    }\n" +
        "    .card {\n" +
        "      background: var(--card-bg);\n" +
        "      backdrop-filter: blur(16px);\n" +
        "      -webkit-backdrop-filter: blur(16px);\n" +
        "      border: 1px solid var(--border-color);\n" +
        "      border-radius: 12px;\n" +
        "      padding: 1.5rem;\n" +
        "      box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.4);\n" +
        "      transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);\n" +
        "    }\n" +
        "    .card:hover {\n" +
        "      border-color: rgba(0, 240, 255, 0.4);\n" +
        "      transform: translateY(-2px);\n" +
        "      box-shadow: 0 12px 40px 0 rgba(0, 240, 255, 0.1);\n" +
        "    }\n" +
        "    .card-title {\n" +
        "      font-size: 1.1rem;\n" +
        "      font-weight: 600;\n" +
        "      margin-bottom: 1.2rem;\n" +
        "      text-transform: uppercase;\n" +
        "      letter-spacing: 1px;\n" +
        "      border-left: 3px solid var(--accent-cyan);\n" +
        "      padding-left: 0.6rem;\n" +
        "      color: var(--text-main);\n" +
        "    }\n" +
        "    .card-title.green {\n" +
        "      border-left-color: var(--accent-green);\n" +
        "    }\n" +
        "    .card-title.orange {\n" +
        "      border-left-color: var(--accent-orange);\n" +
        "    }\n" +
        "    .status-row {\n" +
        "      display: flex;\n" +
        "      justify-content: space-between;\n" +
        "      align-items: center;\n" +
        "      margin-bottom: 1rem;\n" +
        "      font-family: var(--font-mono);\n" +
        "      font-size: 0.9rem;\n" +
        "    }\n" +
        "    .status-row:last-child {\n" +
        "      margin-bottom: 0;\n" +
        "    }\n" +
        "    .status-label {\n" +
        "      color: var(--text-muted);\n" +
        "    }\n" +
        "    .status-value {\n" +
        "      font-weight: bold;\n" +
        "    }\n" +
        "    .live-badge {\n" +
        "      display: inline-flex;\n" +
        "      align-items: center;\n" +
        "      gap: 0.5rem;\n" +
        "      padding: 0.25rem 0.75rem;\n" +
        "      border-radius: 20px;\n" +
        "      font-size: 0.75rem;\n" +
        "      font-weight: 700;\n" +
        "      text-transform: uppercase;\n" +
        "      letter-spacing: 1px;\n" +
        "      background: rgba(0, 255, 102, 0.1);\n" +
        "      border: 1px solid var(--accent-green);\n" +
        "      color: var(--accent-green);\n" +
        "      box-shadow: 0 0 10px rgba(0, 255, 102, 0.2);\n" +
        "    }\n" +
        "    .live-badge.standby {\n" +
        "      background: rgba(142, 154, 159, 0.1);\n" +
        "      border-color: var(--text-muted);\n" +
        "      color: var(--text-muted);\n" +
        "      box-shadow: none;\n" +
        "    }\n" +
        "    .blink-dot {\n" +
        "      width: 8px;\n" +
        "      height: 8px;\n" +
        "      border-radius: 50%;\n" +
        "      background-color: var(--accent-green);\n" +
        "      animation: blink 1.2s infinite;\n" +
        "    }\n" +
        "    .live-badge.standby .blink-dot {\n" +
        "      background-color: var(--text-muted);\n" +
        "      animation: none;\n" +
        "    }\n" +
        "    @keyframes blink {\n" +
        "      0% { opacity: 0.2; }\n" +
        "      50% { opacity: 1; }\n" +
        "      100% { opacity: 0.2; }\n" +
        "    }\n" +
        "    .btn {\n" +
        "      display: block;\n" +
        "      width: 100%;\n" +
        "      background: #11111E;\n" +
        "      border: 1px solid var(--accent-green);\n" +
        "      color: #FFFFFF;\n" +
        "      padding: 0.8rem 1.2rem;\n" +
        "      border-radius: 6px;\n" +
        "      font-size: 0.9rem;\n" +
        "      font-weight: 600;\n" +
        "      font-family: var(--font-main);\n" +
        "      text-transform: uppercase;\n" +
        "      letter-spacing: 1px;\n" +
        "      cursor: pointer;\n" +
        "      margin-bottom: 0.8rem;\n" +
        "      transition: all 0.2s ease;\n" +
        "      text-align: center;\n" +
        "      text-shadow: 0 0 5px rgba(255,255,255,0.2);\n" +
        "    }\n" +
        "    .btn:hover {\n" +
        "      background: var(--accent-green);\n" +
        "      color: #000000;\n" +
        "      box-shadow: 0 0 15px rgba(0, 255, 102, 0.4);\n" +
        "      transform: translateY(-1px);\n" +
        "    }\n" +
        "    .btn.btn-cyan {\n" +
        "      border-color: var(--accent-cyan);\n" +
        "    }\n" +
        "    .btn.btn-cyan:hover {\n" +
        "      background: var(--accent-cyan);\n" +
        "      color: #000000;\n" +
        "      box-shadow: 0 0 15px rgba(0, 240, 255, 0.4);\n" +
        "    }\n" +
        "    .btn:active {\n" +
        "      transform: translateY(1px);\n" +
        "    }\n" +
        "    .form-group {\n" +
        "      margin-bottom: 1.2rem;\n" +
        "    }\n" +
        "    .form-group:last-child {\n" +
        "      margin-bottom: 0;\n" +
        "    }\n" +
        "    label {\n" +
        "      display: block;\n" +
        "      color: var(--text-muted);\n" +
        "      font-size: 0.8rem;\n" +
        "      text-transform: uppercase;\n" +
        "      letter-spacing: 1px;\n" +
        "      margin-bottom: 0.5rem;\n" +
        "    }\n" +
        "    select, input[type='text'] {\n" +
        "      width: 100%;\n" +
        "      background: rgba(0, 0, 0, 0.5);\n" +
        "      border: 1px solid var(--border-color);\n" +
        "      border-radius: 6px;\n" +
        "      color: #FFFFFF;\n" +
        "      padding: 0.75rem;\n" +
        "      font-size: 0.95rem;\n" +
        "      font-family: var(--font-main);\n" +
        "      outline: none;\n" +
        "      transition: border-color 0.2s ease;\n" +
        "    }\n" +
        "    select:focus, input[type='text']:focus {\n" +
        "      border-color: var(--accent-cyan);\n" +
        "      box-shadow: 0 0 10px rgba(0, 240, 255, 0.1);\n" +
        "    }\n" +
        "    .toggle-row {\n" +
        "      display: flex;\n" +
        "      justify-content: space-between;\n" +
        "      align-items: center;\n" +
        "      padding: 0.5rem 0;\n" +
        "    }\n" +
        "    .switch {\n" +
        "      position: relative;\n" +
        "      display: inline-block;\n" +
        "      width: 48px;\n" +
        "      height: 24px;\n" +
        "    }\n" +
        "    .switch input {\n" +
        "      opacity: 0;\n" +
        "      width: 0;\n" +
        "      height: 0;\n" +
        "    }\n" +
        "    .slider {\n" +
        "      position: absolute;\n" +
        "      cursor: pointer;\n" +
        "      top: 0;\n" +
        "      left: 0;\n" +
        "      right: 0;\n" +
        "      bottom: 0;\n" +
        "      background-color: #1A1A24;\n" +
        "      border: 1px solid var(--border-color);\n" +
        "      transition: .3s;\n" +
        "      border-radius: 24px;\n" +
        "    }\n" +
        "    .slider:before {\n" +
        "      position: absolute;\n" +
        "      content: '';\n" +
        "      height: 16px;\n" +
        "      width: 16px;\n" +
        "      left: 3px;\n" +
        "      bottom: 3px;\n" +
        "      background-color: var(--text-muted);\n" +
        "      transition: .3s;\n" +
        "      border-radius: 50%;\n" +
        "    }\n" +
        "    input:checked + .slider {\n" +
        "      background-color: rgba(0, 240, 255, 0.15);\n" +
        "      border-color: var(--accent-cyan);\n" +
        "    }\n" +
        "    input:checked + .slider:before {\n" +
        "      transform: translateX(24px);\n" +
        "      background-color: var(--accent-cyan);\n" +
        "      box-shadow: 0 0 10px var(--accent-cyan);\n" +
        "    }\n" +
        "    .switch-green input:checked + .slider {\n" +
        "      background-color: rgba(0, 255, 102, 0.15);\n" +
        "      border-color: var(--accent-green);\n" +
        "    }\n" +
        "    .switch-green input:checked + .slider:before {\n" +
        "      background-color: var(--accent-green);\n" +
        "      box-shadow: 0 0 10px var(--accent-green);\n" +
        "    }\n" +
        "    .slider-round {\n" +
        "      border-radius: 34px;\n" +
        "    }\n" +
        "    .battery-container {\n" +
        "      display: flex;\n" +
        "      align-items: center;\n" +
        "      gap: 0.5rem;\n" +
        "    }\n" +
        "    .battery-bar {\n" +
        "      width: 32px;\n" +
        "      height: 16px;\n" +
        "      border: 1px solid var(--text-muted);\n" +
        "      border-radius: 3px;\n" +
        "      padding: 1px;\n" +
        "      position: relative;\n" +
        "    }\n" +
        "    .battery-bar:after {\n" +
        "      content: '';\n" +
        "      position: absolute;\n" +
        "      right: -4px;\n" +
        "      top: 4px;\n" +
        "      width: 2px;\n" +
        "      height: 6px;\n" +
        "      background: var(--text-muted);\n" +
        "      border-radius: 0 1px 1px 0;\n" +
        "    }\n" +
        "    .battery-fill {\n" +
        "      height: 100%;\n" +
        "      background: var(--accent-green);\n" +
        "      width: 0%;\n" +
        "      border-radius: 1px;\n" +
        "      transition: width 0.3s ease;\n" +
        "    }\n" +
        "    footer {\n" +
        "      text-align: center;\n" +
        "      margin-top: 3rem;\n" +
        "      color: var(--text-muted);\n" +
        "      font-size: 0.8rem;\n" +
        "      font-family: var(--font-mono);\n" +
        "      text-transform: uppercase;\n" +
        "      letter-spacing: 1px;\n" +
        "    }\n" +
        "    .toast {\n" +
        "      position: fixed;\n" +
        "      bottom: 20px;\n" +
        "      right: 20px;\n" +
        "      background: rgba(13, 13, 20, 0.9);\n" +
        "      border: 1px solid var(--accent-cyan);\n" +
        "      color: #fff;\n" +
        "      padding: 0.8rem 1.5rem;\n" +
        "      border-radius: 6px;\n" +
        "      font-family: var(--font-mono);\n" +
        "      font-size: 0.85rem;\n" +
        "      box-shadow: 0 4px 20px rgba(0, 240, 255, 0.2);\n" +
        "      transform: translateY(100px);\n" +
        "      opacity: 0;\n" +
        "      transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);\n" +
        "      z-index: 1000;\n" +
        "    }\n" +
        "    .toast.show {\n" +
        "      transform: translateY(0);\n" +
        "      opacity: 1;\n" +
        "    }\n" +
        "  </style>\n" +
        "</head>\n" +
        "<body>\n" +
        "  <div class='container'>\n" +
        "    <header>\n" +
        "      <h1>Webcam Control Panel</h1>\n" +
        "      <p class='subtitle'>Local Network Configuration Interface</p>\n" +
        "    </header>\n" +
        "    <div class='grid'>\n" +
        "      <div class='card'>\n" +
        "        <div class='card-title'>System Status</div>\n" +
        "        <div class='status-row'>\n" +
        "          <span class='status-label'>Stream Status</span>\n" +
        "          <span class='status-value'>\n" +
        "            <span id='stream-badge' class='live-badge standby'>\n" +
        "              <span class='blink-dot'></span>\n" +
        "              <span id='stream-status-text'>STANDBY</span>\n" +
        "            </span>\n" +
        "          </span>\n" +
        "        </div>\n" +
        "        <div class='status-row'>\n" +
        "          <span class='status-label'>Battery Level</span>\n" +
        "          <span class='status-value battery-container'>\n" +
        "            <span id='battery-text'>--%</span>\n" +
        "            <div class='battery-bar'>\n" +
        "              <div id='battery-fill' class='battery-fill'></div>\n" +
        "            </div>\n" +
        "          </span>\n" +
        "        </div>\n" +
        "        <div class='status-row'>\n" +
        "          <span class='status-label'>Active Camera</span>\n" +
        "          <span id='active-camera' class='status-value'>Camera --</span>\n" +
        "        </div>\n" +
        "        <div class='status-row'>\n" +
        "          <span class='status-label'>Resolution</span>\n" +
        "          <span id='status-resolution' class='status-value'>--</span>\n" +
        "        </div>\n" +
        "        <div class='status-row'>\n" +
        "          <span class='status-label'>FPS / Bitrate</span>\n" +
        "          <span id='status-fps-bitrate' class='status-value'>-- FPS / -- Mbps</span>\n" +
        "        </div>\n" +
        "      </div>\n" +
        "      <div class='card'>\n" +
        "        <div class='card-title orange'>Quick Actions</div>\n" +
        "        <button id='btn-trigger-af' class='btn'>🎯 Trigger Autofocus Scan</button>\n" +
        "        <button id='btn-toggle-af-mode' class='btn btn-cyan'>🔄 Toggle Focus Lock</button>\n" +
        "        <div class='status-row' style='margin-top: 1rem;'>\n" +
        "          <span class='status-label'>Focus Lock State</span>\n" +
        "          <span id='status-focus-lock' class='status-value' style='color: var(--accent-green);'>AUTO-C</span>\n" +
        "        </div>\n" +
        "      </div>\n" +
        "      <div class='card'>\n" +
        "        <div class='card-title cyan'>Hardware & Stream Settings</div>\n" +
        "        <div class='form-group'>\n" +
        "          <label for='camera-select'>Active Camera Sensor</label>\n" +
        "          <select id='camera-select'></select>\n" +
        "        </div>\n" +
        "        <div class='form-group'>\n" +
        "          <label for='resolution-select'>Video Resolution</label>\n" +
        "          <select id='resolution-select'></select>\n" +
        "        </div>\n" +
        "        <div class='form-group'>\n" +
        "          <label for='fps-select'>Video Framerate</label>\n" +
        "          <select id='fps-select'>\n" +
        "            <option value='15'>15 FPS</option>\n" +
        "            <option value='24'>24 FPS</option>\n" +
        "            <option value='30'>30 FPS</option>\n" +
        "            <option value='60'>60 FPS</option>\n" +
        "          </select>\n" +
        "        </div>\n" +
        "        <div class='form-group'>\n" +
        "          <label for='bitrate-select'>Video Bitrate</label>\n" +
        "          <select id='bitrate-select'>\n" +
        "            <option value='1000000'>1.0 Mbps</option>\n" +
        "            <option value='2000000'>2.0 Mbps</option>\n" +
        "            <option value='3000000'>3.0 Mbps</option>\n" +
        "            <option value='3500000'>3.5 Mbps</option>\n" +
        "            <option value='4000000'>4.0 Mbps</option>\n" +
        "            <option value='5000000'>5.0 Mbps</option>\n" +
        "            <option value='6000000'>6.0 Mbps</option>\n" +
        "          </select>\n" +
        "        </div>\n" +
        "      </div>\n" +
        "      <div class='card'>\n" +
        "        <div class='card-title cyan'>Preferences</div>\n" +
        "        <div class='toggle-row'>\n" +
        "          <span class='status-label'>Face Auto-Focus</span>\n" +
        "          <label class='switch switch-green'>\n" +
        "            <input type='checkbox' id='toggle-face-af'>\n" +
        "            <span class='slider slider-round'></span>\n" +
        "          </label>\n" +
        "        </div>\n" +
        "        <div class='toggle-row'>\n" +
        "          <span class='status-label'>Keep Screen On</span>\n" +
        "          <label class='switch'>\n" +
        "            <input type='checkbox' id='toggle-screen-on'>\n" +
        "            <span class='slider slider-round'></span>\n" +
        "          </label>\n" +
        "        </div>\n" +
        "        <div class='toggle-row'>\n" +
        "          <span class='status-label'>Flip Horizontally</span>\n" +
        "          <label class='switch'>\n" +
        "            <input type='checkbox' id='toggle-flip-h'>\n" +
        "            <span class='slider slider-round'></span>\n" +
        "          </label>\n" +
        "        </div>\n" +
        "        <div class='toggle-row'>\n" +
        "          <span class='status-label'>Flip Vertically</span>\n" +
        "          <label class='switch'>\n" +
        "            <input type='checkbox' id='toggle-flip-v'>\n" +
        "            <span class='slider slider-round'></span>\n" +
        "          </label>\n" +
        "        </div>\n" +
        "      </div>\n" +
        "    </div>\n" +
        "    <footer>\n" +
        "      OBS Webcam Clone &copy; 2026\n" +
        "    </footer>\n" +
        "  </div>\n" +
        "  <div id='toast' class='toast'>Settings saved!</div>\n" +
        "  <script>\n" +
        "    let loading = true;\n" +
        "    function showToast(msg) {\n" +
        "      const toast = document.getElementById('toast');\n" +
        "      toast.innerText = msg;\n" +
        "      toast.classList.add('show');\n" +
        "      setTimeout(() => toast.classList.remove('show'), 3000);\n" +
        "    }\n" +
        "    async function setSetting(key, val) {\n" +
        "      try {\n" +
        "        const res = await fetch('/api/set?key=' + encodeURIComponent(key) + '&val=' + encodeURIComponent(val));\n" +
        "        if (res.ok) {\n" +
        "          showToast('Updated: ' + key.replace('_', ' ').toUpperCase());\n" +
        "          fetchStatus();\n" +
        "        }\n" +
        "      } catch (e) {\n" +
        "        showToast('Error updating setting');\n" +
        "      }\n" +
        "    }\n" +
        "    document.getElementById('camera-select').onchange = (e) => setSetting('camera', e.target.value);\n" +
        "    document.getElementById('resolution-select').onchange = (e) => setSetting('resolution', e.target.value);\n" +
        "    document.getElementById('fps-select').onchange = (e) => setSetting('framerate', e.target.value);\n" +
        "    document.getElementById('bitrate-select').onchange = (e) => setSetting('bitrate', e.target.value);\n" +
        "    document.getElementById('toggle-face-af').onchange = (e) => setSetting('face_auto_focus', e.target.checked);\n" +
        "    document.getElementById('toggle-screen-on').onchange = (e) => setSetting('keep_screen_on', e.target.checked);\n" +
        "    document.getElementById('toggle-flip-h').onchange = (e) => setSetting('flip_horizontal', e.target.checked);\n" +
        "    document.getElementById('toggle-flip-v').onchange = (e) => setSetting('flip_vertical', e.target.checked);\n" +
        "    document.getElementById('btn-trigger-af').onclick = () => setSetting('trigger_af', 'now');\n" +
        "    document.getElementById('btn-toggle-af-mode').onclick = () => setSetting('toggle_af_mode', 'now');\n" +
        "    async function fetchStatus() {\n" +
        "      try {\n" +
        "        const res = await fetch('/api/status');\n" +
        "        if (!res.ok) return;\n" +
        "        const data = await res.json();\n" +
        "        const badge = document.getElementById('stream-badge');\n" +
        "        const stText = document.getElementById('stream-status-text');\n" +
        "        if (data.isStreaming) {\n" +
        "          badge.classList.remove('standby');\n" +
        "          stText.innerText = 'LIVE STREAM ACTIVE';\n" +
        "        } else {\n" +
        "          badge.classList.add('standby');\n" +
        "          stText.innerText = 'STANDBY';\n" +
        "        }\n" +
        "        document.getElementById('battery-text').innerText = data.battery + '%';\n" +
        "        document.getElementById('battery-fill').style.width = data.battery + '%';\n" +
        "        let activeCamName = 'Camera ' + data.activeCameraId;\n" +
        "        const camObj = data.availableCameras.find(c => c.id === data.activeCameraId);\n" +
        "        if (camObj) activeCamName += ' (' + camObj.facing + ')';\n" +
        "        document.getElementById('active-camera').innerText = activeCamName;\n" +
        "        document.getElementById('status-resolution').innerText = data.resolution;\n" +
        "        document.getElementById('status-fps-bitrate').innerText = data.framerate + ' FPS / ' + (data.bitrate / 1000000).toFixed(1) + ' Mbps';\n" +
        "        const focusLockText = document.getElementById('status-focus-lock');\n" +
        "        if (data.isAutofocusLocked) {\n" +
        "          focusLockText.innerText = 'LOCKED';\n" +
        "          focusLockText.style.color = 'var(--accent-orange)';\n" +
        "        } else {\n" +
        "          focusLockText.innerText = 'AUTO-C';\n" +
        "          focusLockText.style.color = 'var(--accent-green)';\n" +
        "        }\n" +
        "        if (loading) {\n" +
        "          loading = false;\n" +
        "          const camSelect = document.getElementById('camera-select');\n" +
        "          camSelect.innerHTML = '';\n" +
        "          data.availableCameras.forEach(cam => {\n" +
        "            const opt = document.createElement('option');\n" +
        "            opt.value = cam.id;\n" +
        "            opt.innerText = 'Camera ' + cam.id + ' (' + cam.facing + ')';\n" +
        "            opt.selected = cam.id === data.activeCameraId;\n" +
        "            camSelect.appendChild(opt);\n" +
        "          });\n" +
        "          const resSelect = document.getElementById('resolution-select');\n" +
        "          resSelect.innerHTML = '';\n" +
        "          data.availableResolutions.forEach(r => {\n" +
        "            const opt = document.createElement('option');\n" +
        "            opt.value = r;\n" +
        "            opt.innerText = r;\n" +
        "            opt.selected = r === data.resolution;\n" +
        "            resSelect.appendChild(opt);\n" +
        "          });\n" +
        "          document.getElementById('fps-select').value = data.framerate;\n" +
        "          document.getElementById('bitrate-select').value = data.bitrate;\n" +
        "          document.getElementById('toggle-face-af').checked = data.faceAutoFocus;\n" +
        "          document.getElementById('toggle-screen-on').checked = data.keepScreenOn;\n" +
        "          document.getElementById('toggle-flip-h').checked = data.flipHorizontal;\n" +
        "          document.getElementById('toggle-flip-v').checked = data.flipVertical;\n" +
        "        }\n" +
        "      } catch (e) {\n" +
        "        console.error(e);\n" +
        "      }\n" +
        "    }\n" +
        "  </script>\n" +
        "</body>\n" +
        "</html>\n";
}
