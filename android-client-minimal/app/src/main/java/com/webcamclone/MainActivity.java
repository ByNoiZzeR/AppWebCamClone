package com.webcamclone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CaptureRequest;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.TextureView;
import android.view.ScaleGestureDetector;
import android.view.MotionEvent;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.widget.Switch;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Process;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements CameraStreamer.PreviewSurfaceProvider, SocketServer.StreamListener {
    private static final String TAG = "MainActivityMinimal";
    private static final int CAMERA_PERMISSION_REQUEST = 101;

    private FrameLayout rootLayout;
    private FrameLayout permissionOverlay;
    private FrameLayout standbyOverlay;
    private FrameLayout activeStatsOverlay;

    private TextureView textureView;
    private Surface previewSurface;
    private boolean isSurfaceReady = false;
    private int currentVideoWidth = 1280;
    private int currentVideoHeight = 720;

    private TextView standbyIpTextView;
    private TextView activeTitleTextView;
    private TextView activeStatusTextView;

    // Active stats TextViews
    private TextView txRateVal;
    private TextView txTotalVal;
    private TextView tempVal;
    private TextView batteryVal;
    private TextView focusVal;
    private TextView filterVal;
    private View statusLed;

    private boolean isUiHidden = false;
    private long streamStartTime = 0;

    CameraStreamer cameraStreamer;
    private SocketServer socketServer;

    private SettingsManager settingsManager;

    private boolean isPreviewEnabled = true;
    private TextView previewToggleStandbyBtn;
    private TextView previewToggleActiveBtn;

    // Dim Mode overlay fields
    private FrameLayout dimOverlay;
    private LinearLayout dimTextContainer;
    private boolean isDimMode = false;
    private final Handler dimHandler = new Handler();
    private final Runnable dimRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDimMode || dimOverlay == null || dimTextContainer == null) return;
            
            int parentWidth = dimOverlay.getWidth();
            int parentHeight = dimOverlay.getHeight();
            
            if (parentWidth > 0 && parentHeight > 0) {
                float maxX = parentWidth / 4.0f;
                float maxY = parentHeight / 4.0f;
                
                float randomX = (float) (Math.random() * (maxX * 2) - maxX);
                float randomY = (float) (Math.random() * (maxY * 2) - maxY);
                
                dimTextContainer.animate()
                        .translationX(randomX)
                        .translationY(randomY)
                        .setDuration(500)
                        .start();
            }
            
            dimHandler.postDelayed(this, 10000);
        }
    };
    
    // Telemetry states
    private float currentBatteryTemp = 0.0f;
    private int currentBatteryPercent = 100;
    private long lastTxBytes = 0;
    private long lastSpeedCheckTime = 0;
    private String currentTxSpeedText = "0.0 KB/s";
    private String totalTxText = "0.0 MB";
    private boolean blinkState = false;

    private final Handler telemetryHandler = new Handler();
    private final Runnable telemetryRunnable = new Runnable() {
        @Override
        public void run() {
            updateTrafficStats();
            updateTelemetryDisplay();
            toggleStatusBlink();
            telemetryHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            currentBatteryTemp = tempRaw / 10.0f;
            currentBatteryPercent = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
            updateTelemetryDisplay();
        }
    };

    private final Handler toastHandler = new Handler();
    private final Runnable revertStatusRunnable = new Runnable() {
        @Override
        public void run() {
            if (socketServer != null && socketServer.isStreaming()) {
                updateStatusText("Streaming Active...");
            } else {
                updateStatusText("Ready for OBS connection");
            }
        }
    };

    private final int[] filterEffects = {
        CaptureRequest.CONTROL_EFFECT_MODE_OFF,
        CaptureRequest.CONTROL_EFFECT_MODE_MONO,
        CaptureRequest.CONTROL_EFFECT_MODE_NEGATIVE,
        CaptureRequest.CONTROL_EFFECT_MODE_SEPIA,
        CaptureRequest.CONTROL_EFFECT_MODE_AQUA,
        CaptureRequest.CONTROL_EFFECT_MODE_SOLARIZE
    };
    private final String[] filterLabels = {
        "NORMAL", "MONO", "NEGATIVE", "SEPIA", "AQUA", "SOLAR"
    };
    private int currentFilterIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(this);
        isPreviewEnabled = settingsManager.getPreviewEnabled();

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        if (settingsManager.getKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#050508")); // Deep space black

        // Initialize TextureView for preview
        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                previewSurface = new Surface(surfaceTexture);
                isSurfaceReady = true;
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);
                if (cameraStreamer != null) {
                    cameraStreamer.restartCaptureSession();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                isSurfaceReady = false;
                if (previewSurface != null) {
                    previewSurface.release();
                    previewSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        rootLayout.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        textureView.setVisibility(isPreviewEnabled ? View.VISIBLE : View.GONE);

        // Initialize ScaleGestureDetector for pinch-to-zoom
        final ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    private float zoomFactor = 1.0f;

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (cameraStreamer == null) return false;
                        float maxZoom = cameraStreamer.getMaxZoom();
                        if (maxZoom <= 1.0f) return false;

                        zoomFactor *= detector.getScaleFactor();
                        if (zoomFactor < 1.0f) zoomFactor = 1.0f;
                        if (zoomFactor > maxZoom) zoomFactor = maxZoom;

                        cameraStreamer.setZoom(zoomFactor);
                        showCyberToast(String.format(Locale.US, "ZOOM: %.1fx", zoomFactor));
                        return true;
                    }
                });

        // Initialize GestureDetector for double-tap (toggle UI visibility) and single-tap (autofocus trigger)
        final android.view.GestureDetector doubleTapDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        toggleUiVisibility();
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (cameraStreamer != null) {
                            cameraStreamer.triggerAutofocus();
                            showCyberToast("AUTOFOCUS SCAN TRIGGERED");
                        }
                        return true;
                    }
                });

        final View.OnTouchListener overlayTouchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                scaleGestureDetector.onTouchEvent(event);
                doubleTapDetector.onTouchEvent(event);
                return true;
            }
        };

        textureView.setOnTouchListener(overlayTouchListener);

        // 1. Initialize Standby Overlay & Card
        setupStandbyOverlay();

        // 2. Initialize Active Streaming Telemetry Overlay & Card
        setupActiveStatsOverlay();

        // 3. Initialize Permission Request Overlay
        setupPermissionOverlay();

        // Register gesture listeners on the HUD overlays as well to capture touches on empty areas
        if (standbyOverlay != null) {
            standbyOverlay.setOnTouchListener(overlayTouchListener);
        }
        if (activeStatsOverlay != null) {
            activeStatsOverlay.setOnTouchListener(overlayTouchListener);
        }

        // Add overlays to root (default visibilities)
        rootLayout.addView(activeStatsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(standbyOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(permissionOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);

        // Initialize components
        socketServer = new SocketServer(this);
        cameraStreamer = new CameraStreamer(this, socketServer, this);

        checkAndRequestPermissions();
    }

    private void setupStandbyOverlay() {
        standbyOverlay = new FrameLayout(this);
        standbyOverlay.setBackgroundColor(Color.TRANSPARENT);
        standbyOverlay.setVisibility(View.VISIBLE);

        LinearLayout standbyCard = new LinearLayout(this);
        standbyCard.setOrientation(LinearLayout.VERTICAL);
        standbyCard.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));
        standbyCard.setGravity(Gravity.CENTER_HORIZONTAL);
        standbyCard.setClickable(true); // Consume touches so they do not trigger double-tap toggle

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(Color.parseColor("#E608080C"));
        cardBg.setCornerRadius(dpToPx(16));
        cardBg.setStroke(dpToPx(1), Color.parseColor("#1A00F0FF")); // Cyan border
        standbyCard.setBackground(cardBg);

        TextView title = new TextView(this);
        title.setText("OBS WEBCAM MINIMAL");
        title.setTextColor(Color.parseColor("#00F0FF"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.08f);
        standbyCard.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("STANDBY - NO DEVICE PREVIEW");
        subtitle.setTextColor(Color.parseColor("#FF0055"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        subtitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        subtitle.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dpToPx(4), 0, dpToPx(20));
        standbyCard.addView(subtitle, subLp);

        View div1 = new View(this);
        div1.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp.setMargins(0, 0, 0, dpToPx(16));
        standbyCard.addView(div1, divLp);

        LinearLayout ipBox = new LinearLayout(this);
        ipBox.setOrientation(LinearLayout.VERTICAL);
        ipBox.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        GradientDrawable ipBg = new GradientDrawable();
        ipBg.setColor(Color.parseColor("#121217"));
        ipBg.setCornerRadius(dpToPx(8));
        ipBg.setStroke(dpToPx(1), Color.parseColor("#1AFFFFFF"));
        ipBox.setBackground(ipBg);

        standbyIpTextView = new TextView(this);
        standbyIpTextView.setTextColor(Color.WHITE);
        standbyIpTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        standbyIpTextView.setTypeface(Typeface.MONOSPACE);
        standbyIpTextView.setLineSpacing(dpToPx(4), 1f);
        ipBox.addView(standbyIpTextView);

        LinearLayout.LayoutParams ipBoxLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ipBoxLp.setMargins(0, 0, 0, dpToPx(20));
        standbyCard.addView(ipBox, ipBoxLp);

        View div2 = new View(this);
        div2.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        standbyCard.addView(div2, divLp);

        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        actionRow1.setGravity(Gravity.CENTER);

        TextView flipBtn = new TextView(this);
        flipBtn.setText("🔄 FLIP CAM");
        styleCyberButton(flipBtn, "#1A00F0FF");
        flipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCameraSelectionMenu(v);
            }
        });

        TextView rotateBtn = new TextView(this);
        rotateBtn.setText("🔄 ROTATE");
        styleCyberButton(rotateBtn, "#1A00F0FF");
        rotateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cyclePreviewRotation();
            }
        });

        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        actionRow2.setGravity(Gravity.CENTER);

        previewToggleStandbyBtn = new TextView(this);
        previewToggleStandbyBtn.setText(isPreviewEnabled ? "📺 PREVIEW: ON" : "📺 PREVIEW: OFF");
        styleCyberButton(previewToggleStandbyBtn, isPreviewEnabled ? "#1A00FF66" : "#1AFF0055");
        if (isPreviewEnabled) {
            previewToggleStandbyBtn.setTextColor(Color.parseColor("#00FF66"));
        } else {
            previewToggleStandbyBtn.setTextColor(Color.parseColor("#FF0055"));
        }
        previewToggleStandbyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePreviewEnabled();
            }
        });

        TextView setupBtn = new TextView(this);
        setupBtn.setText("⚙️ SETUP");
        styleCyberButton(setupBtn, "#33FFFFFF");
        setupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp2.setMargins(dpToPx(12), 0, 0, 0);

        actionRow1.addView(flipBtn, btnLp1);
        actionRow1.addView(rotateBtn, btnLp2);

        actionRow2.addView(previewToggleStandbyBtn, btnLp1);
        actionRow2.addView(setupBtn, btnLp2);

        standbyCard.addView(actionRow1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        
        LinearLayout.LayoutParams rowLp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp2.setMargins(0, dpToPx(8), 0, 0);
        standbyCard.addView(actionRow2, rowLp2);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dpToPx(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        standbyOverlay.addView(standbyCard, cardParams);
    }

    private void setupActiveStatsOverlay() {
        activeStatsOverlay = new FrameLayout(this);
        activeStatsOverlay.setBackgroundColor(Color.TRANSPARENT);
        activeStatsOverlay.setVisibility(View.GONE);

        LinearLayout activeCard = new LinearLayout(this);
        activeCard.setOrientation(LinearLayout.VERTICAL);
        activeCard.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));
        activeCard.setGravity(Gravity.CENTER_HORIZONTAL);
        activeCard.setClickable(true); // Consume touches so they do not trigger double-tap toggle

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(Color.parseColor("#E608080C"));
        cardBg.setCornerRadius(dpToPx(16));
        cardBg.setStroke(dpToPx(1), Color.parseColor("#1AFF0055")); // Pink/Red border
        activeCard.setBackground(cardBg);

        activeTitleTextView = new TextView(this);
        activeTitleTextView.setText("// STREAMING ACTIVE //");
        activeTitleTextView.setTextColor(Color.parseColor("#00FF66")); // Active green
        activeTitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        activeTitleTextView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        activeTitleTextView.setLetterSpacing(0.08f);
        activeCard.addView(activeTitleTextView);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        
        statusLed = new View(this);
        LinearLayout.LayoutParams ledLp = new LinearLayout.LayoutParams(dpToPx(8), dpToPx(8));
        ledLp.setMargins(0, 0, dpToPx(8), 0);
        GradientDrawable ledBg = new GradientDrawable();
        ledBg.setShape(GradientDrawable.OVAL);
        ledBg.setColor(Color.parseColor("#00FF66"));
        statusLed.setBackground(ledBg);
        statusRow.addView(statusLed, ledLp);

        activeStatusTextView = new TextView(this);
        activeStatusTextView.setText("LIVE");
        activeStatusTextView.setTextColor(Color.parseColor("#00FF66"));
        activeStatusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        activeStatusTextView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusRow.addView(activeStatusTextView);

        LinearLayout.LayoutParams statusRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusRowLp.setMargins(0, dpToPx(4), 0, dpToPx(20));
        activeCard.addView(statusRow, statusRowLp);

        View div1 = new View(this);
        div1.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp.setMargins(0, 0, 0, dpToPx(16));
        activeCard.addView(div1, divLp);

        // Telemetry grid (3 rows, 2 columns)
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        txRateVal = createTelemetryRow(grid, "⚡ TX RATE", "0.0 KB/s");
        txTotalVal = createTelemetryRow(grid, "📦 TOTAL DATA", "0.0 MB");
        tempVal = createTelemetryRow(grid, "🌡️ SYS TEMP", "0.0 °C");
        batteryVal = createTelemetryRow(grid, "🔋 BATTERY", "100%");
        focusVal = createTelemetryRow(grid, "🎯 FOCUS MODE", "AUTO-C");
        filterVal = createTelemetryRow(grid, "🎨 FILTER FX", "NORMAL");

        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridLp.setMargins(0, 0, 0, dpToPx(20));
        activeCard.addView(grid, gridLp);

        View div2 = new View(this);
        div2.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        activeCard.addView(div2, divLp);

        // Action Buttons Grid (Two Rows)
        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        actionRow1.setGravity(Gravity.CENTER);

        TextView flipBtn = new TextView(this);
        flipBtn.setText("🔄 FLIP");
        styleCyberButton(flipBtn, "#1A00F0FF");
        flipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCameraSelectionMenu(v);
            }
        });

        final TextView lightBtn = new TextView(this);
        lightBtn.setText("⚡ LIGHT");
        styleCyberButton(lightBtn, "#1AFFCC00");
        lightBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cameraStreamer != null) {
                    cameraStreamer.toggleTorch();
                    boolean active = cameraStreamer.isTorchEnabled();
                    showCyberToast(active ? "TORCH ON" : "TORCH OFF");
                    lightBtn.setTextColor(active ? Color.parseColor("#FFCC00") : Color.WHITE);
                }
            }
        });

        TextView filterBtn = new TextView(this);
        filterBtn.setText("🎨 FX");
        styleCyberButton(filterBtn, "#1AFF0055");
        filterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleFilter();
            }
        });

        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        actionRow2.setGravity(Gravity.CENTER);

        TextView rotateBtn = new TextView(this);
        rotateBtn.setText("🔄 ROTATE");
        styleCyberButton(rotateBtn, "#1A00F0FF");
        rotateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cyclePreviewRotation();
            }
        });

        previewToggleActiveBtn = new TextView(this);
        previewToggleActiveBtn.setText(isPreviewEnabled ? "📺 PREVIEW: ON" : "📺 PREVIEW: OFF");
        styleCyberButton(previewToggleActiveBtn, isPreviewEnabled ? "#1A00FF66" : "#1AFF0055");
        if (isPreviewEnabled) {
            previewToggleActiveBtn.setTextColor(Color.parseColor("#00FF66"));
        } else {
            previewToggleActiveBtn.setTextColor(Color.parseColor("#FF0055"));
        }
        previewToggleActiveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePreviewEnabled();
            }
        });

        TextView dimBtn = new TextView(this);
        dimBtn.setText("🔆 DIM");
        styleCyberButton(dimBtn, "#1A00F0FF");
        dimBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterDimMode();
            }
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        
        // Row 1
        actionRow1.addView(flipBtn, btnLp);
        View sp1 = new View(this);
        actionRow1.addView(sp1, new LinearLayout.LayoutParams(dpToPx(6), 1));
        actionRow1.addView(lightBtn, btnLp);
        View sp2 = new View(this);
        actionRow1.addView(sp2, new LinearLayout.LayoutParams(dpToPx(6), 1));
        actionRow1.addView(filterBtn, btnLp);

        // Row 2
        actionRow2.addView(rotateBtn, btnLp);
        View sp3 = new View(this);
        actionRow2.addView(sp3, new LinearLayout.LayoutParams(dpToPx(6), 1));
        actionRow2.addView(previewToggleActiveBtn, btnLp);
        View sp4 = new View(this);
        actionRow2.addView(sp4, new LinearLayout.LayoutParams(dpToPx(6), 1));
        actionRow2.addView(dimBtn, btnLp);

        activeCard.addView(actionRow1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rowLp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp2.setMargins(0, dpToPx(8), 0, 0);
        activeCard.addView(actionRow2, rowLp2);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dpToPx(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        activeStatsOverlay.addView(activeCard, cardParams);
    }

    private TextView createTelemetryRow(LinearLayout container, String label, String valueDefault) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(4), 0, dpToPx(4));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.parseColor("#94A3B8"));
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setText(valueDefault);
        val.setTextColor(Color.WHITE);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        val.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return val;
    }

    private void setupPermissionOverlay() {
        permissionOverlay = new FrameLayout(this);
        permissionOverlay.setBackgroundColor(Color.parseColor("#121217"));
        permissionOverlay.setVisibility(View.GONE);

        LinearLayout permContent = new LinearLayout(this);
        permContent.setOrientation(LinearLayout.VERTICAL);
        permContent.setGravity(Gravity.CENTER);
        permContent.setPadding(dpToPx(32), dpToPx(32), dpToPx(32), dpToPx(32));

        TextView permText = new TextView(this);
        permText.setText("CAMERA PERMISSION REQUIRED TO STREAM VIDEO.");
        permText.setTextColor(Color.WHITE);
        permText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        permText.setGravity(Gravity.CENTER);
        permText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        permContent.addView(permText);

        TextView grantBtn = new TextView(this);
        grantBtn.setText("GRANT PERMISSION");
        grantBtn.setTextColor(Color.BLACK);
        grantBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        grantBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        grantBtn.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#00F0FF"));
        btnBg.setCornerRadius(dpToPx(4));
        grantBtn.setBackground(btnBg);
        grantBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkAndRequestPermissions();
            }
        });

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        btnParams.setMargins(0, dpToPx(24), 0, 0);
        permContent.addView(grantBtn, btnParams);

        permissionOverlay.addView(permContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));
    }

    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionOverlay.setVisibility(View.VISIBLE);
            permissionOverlay.bringToFront();
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        } else {
            permissionOverlay.setVisibility(View.GONE);
            startServices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionOverlay.setVisibility(View.GONE);
                startServices();
            } else {
                permissionOverlay.setVisibility(View.VISIBLE);
                permissionOverlay.bringToFront();
                updateStatusText("Permission denied");
            }
        }
    }

    private void startServices() {
        socketServer.start();
        if (cameraStreamer != null) {
            cameraStreamer.startPreview();
        }
        updateStatusText("Waiting for connection...");
        standbyIpTextView.setText(getDeviceIpAddresses() + "\nPort: " + settingsManager.getPort());
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        telemetryHandler.post(telemetryRunnable);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            socketServer.start();
            if (cameraStreamer != null) {
                cameraStreamer.startPreview();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isDimMode) {
            exitDimMode();
        }
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            // ignore
        }
        telemetryHandler.removeCallbacks(telemetryRunnable);
        if (cameraStreamer != null) {
            cameraStreamer.stopPreview();
        }
        if (socketServer != null) {
            socketServer.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraStreamer != null) {
            cameraStreamer.stopPreview();
        }
        if (socketServer != null) {
            socketServer.stop();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (standbyIpTextView != null) {
            standbyIpTextView.setText(getDeviceIpAddresses() + "\nPort: " + settingsManager.getPort());
        }
        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
    }

    // --- StreamListener callbacks ---

    @Override
    public void onStartStream(final String format, final int width, final int height) {
        streamStartTime = System.currentTimeMillis();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (cameraStreamer != null) {
                    cameraStreamer.startStreaming(format, width, height);
                }
                
                // Toggle overlays
                if (standbyOverlay != null) {
                    standbyOverlay.setVisibility(View.GONE);
                }
                if (activeStatsOverlay != null) {
                    activeStatsOverlay.setVisibility(isUiHidden ? View.GONE : View.VISIBLE);
                }
                
                updateStatusText("Active Stream: " + format.toUpperCase() + " " + width + "x" + height);
            }
        });
    }

    @Override
    public void onStopStream() {
        streamStartTime = 0;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (cameraStreamer != null) {
                    cameraStreamer.stopStreaming();
                }
                
                // Toggle overlays
                if (activeStatsOverlay != null) {
                    activeStatsOverlay.setVisibility(View.GONE);
                }
                if (standbyOverlay != null) {
                    standbyOverlay.setVisibility(isUiHidden ? View.GONE : View.VISIBLE);
                    if (standbyIpTextView != null) {
                        standbyIpTextView.setText(getDeviceIpAddresses() + "\nPort: " + settingsManager.getPort());
                    }
                }
                
                updateStatusText("Disconnected. Waiting...");
            }
        });
    }

    @Override
    public int getBatteryPercentage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        }
        return 100;
    }

    @Override
    public void updateStatusText(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activeStatusTextView == null) return;
                
                if (text.startsWith(">> ")) {
                    activeStatusTextView.setText(text);
                    activeStatusTextView.setTextColor(Color.parseColor("#00F0FF"));
                    return;
                }

                if (socketServer != null && socketServer.isStreaming()) {
                    activeStatusTextView.setText("TRANSMITTING");
                    activeStatusTextView.setTextColor(Color.parseColor("#00FF66"));
                    if (statusLed != null) {
                        GradientDrawable ledBg = new GradientDrawable();
                        ledBg.setShape(GradientDrawable.OVAL);
                        ledBg.setColor(Color.parseColor("#00FF66"));
                        statusLed.setBackground(ledBg);
                    }
                } else {
                    activeStatusTextView.setText("STANDBY | " + text.toUpperCase());
                    activeStatusTextView.setTextColor(Color.parseColor("#FF0055"));
                    if (statusLed != null) {
                        GradientDrawable ledBg = new GradientDrawable();
                        ledBg.setShape(GradientDrawable.OVAL);
                        ledBg.setColor(Color.parseColor("#FF0055"));
                        statusLed.setBackground(ledBg);
                    }
                }
            }
        });
    }

    @Override
    public void onPreviewSizeSelected(final int width, final int height) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentVideoWidth = width;
                currentVideoHeight = height;
                adjustAspectRatio(width, height);
            }
        });
    }

    @Override
    public Surface getPreviewSurface() {
        return (isSurfaceReady && isPreviewEnabled) ? previewSurface : null;
    }

    private void adjustAspectRatio(int videoWidth, int videoHeight) {
        if (textureView == null) return;
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) {
            return;
        }

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();

        int sensorOrientation = 90; // Default fallback orientation
        int activeFacing = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK;
        String activeCameraId = null;
        if (cameraStreamer != null) {
            activeFacing = cameraStreamer.getLensFacing();
            activeCameraId = cameraStreamer.getCurrentCameraId();
        }
        try {
            android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (activeCameraId == null) {
                // Fallback lookup
                for (String id : manager.getCameraIdList()) {
                    android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == activeFacing) {
                        activeCameraId = id;
                        break;
                    }
                }
            }
            if (activeCameraId != null) {
                android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(activeCameraId);
                Integer orient = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);
                if (orient != null) {
                    sensorOrientation = orient;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting sensor orientation", e);
        }

        int deviceRotationDegrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: deviceRotationDegrees = 0; break;
            case Surface.ROTATION_90: deviceRotationDegrees = 90; break;
            case Surface.ROTATION_180: deviceRotationDegrees = 180; break;
            case Surface.ROTATION_270: deviceRotationDegrees = 270; break;
        }

        int rotationOverride = 0;
        if (settingsManager != null) {
            rotationOverride = settingsManager.getRotationOverride();
        }

        int relativeRotation;
        if (activeFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            relativeRotation = (sensorOrientation + deviceRotationDegrees + rotationOverride) % 360;
        } else {
            relativeRotation = (sensorOrientation - deviceRotationDegrees + rotationOverride + 360) % 360;
        }

        float scaleX = 1.0f;
        float scaleY = 1.0f;

        boolean swapped = (relativeRotation == 90 || relativeRotation == 270);
        float bufferWidth = swapped ? videoHeight : videoWidth;
        float bufferHeight = swapped ? videoWidth : videoHeight;

        float scaleWidth = (float) viewWidth / bufferWidth;
        float scaleHeight = (float) viewHeight / bufferHeight;
        // Use Math.min to fit the preview to the screen showing the actual camera canvas not zoomed in
        float scale = Math.min(scaleWidth, scaleHeight);

        scaleX = (bufferWidth * scale) / viewWidth;
        scaleY = (bufferHeight * scale) / viewHeight;

        boolean flipHorizontal = false;
        boolean flipVertical = false;
        if (settingsManager != null) {
            flipHorizontal = settingsManager.getFlipHorizontal();
            flipVertical = settingsManager.getFlipVertical();
        }

        if (activeFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            flipHorizontal = !flipHorizontal;
        }

        if (flipHorizontal) {
            scaleX = -scaleX;
        }
        if (flipVertical) {
            scaleY = -scaleY;
        }

        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        if (relativeRotation != 0) {
            matrix.postRotate(relativeRotation, centerX, centerY);
        }
        matrix.postScale(scaleX, scaleY, centerX, centerY);

        textureView.setTransform(matrix);
    }

    private void toggleUiVisibility() {
        isUiHidden = !isUiHidden;
        if (isUiHidden) {
            if (standbyOverlay != null) standbyOverlay.setVisibility(View.GONE);
            if (activeStatsOverlay != null) activeStatsOverlay.setVisibility(View.GONE);
            showCyberToast("CLEAN PREVIEW (DOUBLE-TAP TO RESTORE UI)");
        } else {
            if (socketServer != null && socketServer.isStreaming()) {
                if (activeStatsOverlay != null) activeStatsOverlay.setVisibility(View.VISIBLE);
                if (standbyOverlay != null) standbyOverlay.setVisibility(View.GONE);
            } else {
                if (activeStatsOverlay != null) activeStatsOverlay.setVisibility(View.GONE);
                if (standbyOverlay != null) standbyOverlay.setVisibility(View.VISIBLE);
            }
            showCyberToast("UI RESTORED");
        }
    }

    private void cyclePreviewRotation() {
        if (settingsManager == null) return;
        int currentRotation = settingsManager.getRotationOverride();
        int nextRotation = (currentRotation + 90) % 360;
        settingsManager.setRotationOverride(nextRotation);
        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
        showCyberToast(String.format(Locale.US, "ROTATION: %d°", nextRotation));
    }

    private void togglePreviewEnabled() {
        isPreviewEnabled = !isPreviewEnabled;
        if (settingsManager != null) {
            settingsManager.setPreviewEnabled(isPreviewEnabled);
        }

        if (textureView != null) {
            textureView.setVisibility(isPreviewEnabled ? View.VISIBLE : View.GONE);
        }

        if (cameraStreamer != null) {
            cameraStreamer.restartCaptureSession();
        }

        updatePreviewButtonsDisplay();

        showCyberToast(isPreviewEnabled ? "PREVIEW ENABLED" : "PREVIEW DISABLED (ENERGY SAVING)");
    }

    private void updatePreviewButtonsDisplay() {
        String text = isPreviewEnabled ? "📺 PREVIEW: ON" : "📺 PREVIEW: OFF";
        String color = isPreviewEnabled ? "#1A00FF66" : "#1AFF0055"; // Green if on, Red if off
        int textColor = isPreviewEnabled ? Color.parseColor("#00FF66") : Color.parseColor("#FF0055");

        if (previewToggleStandbyBtn != null) {
            previewToggleStandbyBtn.setText(text);
            styleCyberButton(previewToggleStandbyBtn, color);
            previewToggleStandbyBtn.setTextColor(textColor);
        }
        if (previewToggleActiveBtn != null) {
            previewToggleActiveBtn.setText(text);
            styleCyberButton(previewToggleActiveBtn, color);
            previewToggleActiveBtn.setTextColor(textColor);
        }
    }



    // --- Utility Methods ---

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }

    private String getWifiIpAddress() {
        // Kept for backward compatibility if needed
        return getDeviceIpAddresses().split("\n")[0].replace("WiFi IP: ", "").replace("Device IP: ", "");
    }

    private String getDeviceIpAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("ap") || name.contains("p2p")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            sb.append("WiFi IP: ").append(addr.getHostAddress()).append("\n");
                        }
                    }
                }
            }
            for (NetworkInterface intf : interfaces) {
                String name = intf.getName().toLowerCase();
                if (intf.isLoopback() || name.contains("wlan") || name.contains("ap") || name.contains("p2p")) {
                    continue;
                }
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String sAddr = addr.getHostAddress();
                        if (!sAddr.startsWith("192.168.56.") && !sAddr.startsWith("192.168.99.")) {
                            sb.append("Device IP: ").append(sAddr).append("\n");
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get network interface IP addresses", ex);
        }
        if (sb.length() == 0) {
            sb.append("Device IP: 127.0.0.1\n");
        }
        return sb.toString().trim();
    }

    private void showSettingsDialog() {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#CC0F0F12"));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(Color.parseColor("#E618181F"));
        cardBg.setCornerRadius(dpToPx(24));
        cardBg.setStroke(dpToPx(1), Color.parseColor("#44FFFFFF"));
        card.setBackground(cardBg);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dpToPx(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        overlay.addView(card, cardParams);

        TextView title = new TextView(this);
        title.setText("STREAM SETTINGS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dpToPx(20));
        card.addView(title, titleParams);

        // Resolution Section
        TextView resLabel = new TextView(this);
        resLabel.setText("Camera Output Resolution");
        resLabel.setTextColor(Color.parseColor("#94A3B8"));
        resLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(resLabel);

        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        
        LinearLayout resRow = new LinearLayout(this);
        resRow.setOrientation(LinearLayout.HORIZONTAL);
        resRow.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(resRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(scroll, scrollParams);

        final List<String> resList = getSupportedResolutions();
        final String[] resOptions = resList.toArray(new String[0]);
        final TextView[] resButtons = new TextView[resOptions.length];
        final int[] selectedResIdx = {0};
        
        String currentRes = settingsManager.getResolution();
        for (int i = 0; i < resOptions.length; i++) {
            if (currentRes.equals(resOptions[i])) {
                selectedResIdx[0] = i;
            }
        }

        for (int i = 0; i < resOptions.length; i++) {
            final int index = i;
            TextView btn = new TextView(this);
            btn.setText(getResolutionLabel(resOptions[i]));
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
            btn.setGravity(Gravity.CENTER);
            
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) btnParams.setMargins(dpToPx(8), 0, 0, 0);
            
            updateBitrateButtonState(btn, index == selectedResIdx[0]);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedResIdx[0] = index;
                    for (int j = 0; j < resOptions.length; j++) {
                        updateBitrateButtonState(resButtons[j], j == index);
                    }
                }
            });
            resButtons[i] = btn;
            resRow.addView(btn, btnParams);
        }

        // Port Section
        TextView portLabel = new TextView(this);
        portLabel.setText("Server Port");
        portLabel.setTextColor(Color.parseColor("#94A3B8"));
        portLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(portLabel);

        final android.widget.EditText portInput = new android.widget.EditText(this);
        portInput.setText(String.valueOf(settingsManager.getPort()));
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setTextColor(Color.WHITE);
        portInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        portInput.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#1F1F2E"));
        inputBg.setCornerRadius(dpToPx(8));
        inputBg.setStroke(dpToPx(1), Color.parseColor("#33FFFFFF"));
        portInput.setBackground(inputBg);

        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        portParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(portInput, portParams);

        // Bitrate Section
        TextView bitrateLabel = new TextView(this);
        bitrateLabel.setText("Video Bitrate");
        bitrateLabel.setTextColor(Color.parseColor("#94A3B8"));
        bitrateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(bitrateLabel);

        LinearLayout bitrateRow = new LinearLayout(this);
        bitrateRow.setOrientation(LinearLayout.HORIZONTAL);
        bitrateRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bitrateRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bitrateRowParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(bitrateRow, bitrateRowParams);

        final int[] bitrates = {1500000, 3500000, 6000000, 10000000};
        final String[] bitrateLabels = {"1.5M", "3.5M", "6M", "10M"};
        final TextView[] bitrateButtons = new TextView[4];
        final int[] selectedBitrateIdx = {1};
        
        int currentBitrate = settingsManager.getBitrate();
        for (int i = 0; i < bitrates.length; i++) {
            if (currentBitrate == bitrates[i]) {
                selectedBitrateIdx[0] = i;
            }
        }

        for (int i = 0; i < 4; i++) {
            final int index = i;
            TextView btn = new TextView(this);
            btn.setText(bitrateLabels[i]);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setPadding(0, dpToPx(8), 0, dpToPx(8));
            btn.setGravity(Gravity.CENTER);
            
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) btnParams.setMargins(dpToPx(6), 0, 0, 0);
            
            updateBitrateButtonState(btn, index == selectedBitrateIdx[0]);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedBitrateIdx[0] = index;
                    for (int j = 0; j < 4; j++) {
                        updateBitrateButtonState(bitrateButtons[j], j == index);
                    }
                }
            });
            bitrateButtons[i] = btn;
            bitrateRow.addView(btn, btnParams);
        }

        // Framerate Section
        TextView fpsLabel = new TextView(this);
        fpsLabel.setText("Target Framerate");
        fpsLabel.setTextColor(Color.parseColor("#94A3B8"));
        fpsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(fpsLabel);

        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fpsRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fpsRowParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(fpsRow, fpsRowParams);

        final int[] fpsOptions = {30, 60};
        final String[] fpsLabels = {"30 FPS", "60 FPS"};
        final TextView[] fpsButtons = new TextView[2];
        final int[] selectedFpsIdx = {0};
        
        int currentFps = settingsManager.getFramerate();
        for (int i = 0; i < fpsOptions.length; i++) {
            if (currentFps == fpsOptions[i]) {
                selectedFpsIdx[0] = i;
            }
        }

        for (int i = 0; i < 2; i++) {
            final int index = i;
            TextView btn = new TextView(this);
            btn.setText(fpsLabels[i]);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setPadding(0, dpToPx(8), 0, dpToPx(8));
            btn.setGravity(Gravity.CENTER);
            
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) btnParams.setMargins(dpToPx(8), 0, 0, 0);
            
            updateBitrateButtonState(btn, index == selectedFpsIdx[0]);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedFpsIdx[0] = index;
                    for (int j = 0; j < 2; j++) {
                        updateBitrateButtonState(fpsButtons[j], j == index);
                    }
                }
            });
            fpsButtons[i] = btn;
            fpsRow.addView(btn, btnParams);
        }

        // Flip Horizontal Section
        LinearLayout flipHRow = new LinearLayout(this);
        flipHRow.setOrientation(LinearLayout.HORIZONTAL);
        flipHRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flipHRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flipHRowParams.setMargins(0, dpToPx(4), 0, dpToPx(12));
        card.addView(flipHRow, flipHRowParams);

        TextView flipHLabel = new TextView(this);
        flipHLabel.setText("Flip Horizontally (Mirror)");
        flipHLabel.setTextColor(Color.parseColor("#E2E8F0"));
        flipHLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        flipHRow.addView(flipHLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch flipHSwitch = new Switch(this);
        flipHSwitch.setChecked(settingsManager.getFlipHorizontal());
        flipHRow.addView(flipHSwitch);

        // Flip Vertical Section
        LinearLayout flipVRow = new LinearLayout(this);
        flipVRow.setOrientation(LinearLayout.HORIZONTAL);
        flipVRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flipVRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flipVRowParams.setMargins(0, dpToPx(4), 0, dpToPx(12));
        card.addView(flipVRow, flipVRowParams);

        TextView flipVLabel = new TextView(this);
        flipVLabel.setText("Flip Vertically");
        flipVLabel.setTextColor(Color.parseColor("#E2E8F0"));
        flipVLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        flipVRow.addView(flipVLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch flipVSwitch = new Switch(this);
        flipVSwitch.setChecked(settingsManager.getFlipVertical());
        flipVRow.addView(flipVSwitch);

        // Face Auto-Focus Section
        LinearLayout faceAfRow = new LinearLayout(this);
        faceAfRow.setOrientation(LinearLayout.HORIZONTAL);
        faceAfRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams faceAfRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        faceAfRowParams.setMargins(0, dpToPx(4), 0, dpToPx(12));
        card.addView(faceAfRow, faceAfRowParams);

        TextView faceAfLabel = new TextView(this);
        faceAfLabel.setText("Face Auto-Focus");
        faceAfLabel.setTextColor(Color.parseColor("#E2E8F0"));
        faceAfLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        faceAfRow.addView(faceAfLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch faceAfSwitch = new Switch(this);
        faceAfSwitch.setChecked(settingsManager.getFaceAutoFocus());
        faceAfRow.addView(faceAfSwitch);

        // Preview Rotation Section
        TextView rotationLabel = new TextView(this);
        rotationLabel.setText("Preview Rotation Offset");
        rotationLabel.setTextColor(Color.parseColor("#94A3B8"));
        rotationLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(rotationLabel);

        LinearLayout rotationRow = new LinearLayout(this);
        rotationRow.setOrientation(LinearLayout.HORIZONTAL);
        rotationRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rotationRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rotationRowParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(rotationRow, rotationRowParams);

        final int[] rotationOptions = {0, 90, 180, 270};
        final String[] rotationLabels = {"0°", "90°", "180°", "270°"};
        final TextView[] rotationButtons = new TextView[4];
        final int[] selectedRotationIdx = {0};

        int currentRotation = settingsManager.getRotationOverride();
        for (int i = 0; i < rotationOptions.length; i++) {
            if (currentRotation == rotationOptions[i]) {
                selectedRotationIdx[0] = i;
            }
        }

        for (int i = 0; i < 4; i++) {
            final int index = i;
            TextView btn = new TextView(this);
            btn.setText(rotationLabels[i]);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setPadding(0, dpToPx(8), 0, dpToPx(8));
            btn.setGravity(Gravity.CENTER);

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) btnParams.setMargins(dpToPx(6), 0, 0, 0);

            updateBitrateButtonState(btn, index == selectedRotationIdx[0]);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedRotationIdx[0] = index;
                    for (int j = 0; j < 4; j++) {
                        updateBitrateButtonState(rotationButtons[j], j == index);
                    }
                }
            });
            rotationButtons[i] = btn;
            rotationRow.addView(btn, btnParams);
        }

        // Keep Screen On Section
        LinearLayout screenOnRow = new LinearLayout(this);
        screenOnRow.setOrientation(LinearLayout.HORIZONTAL);
        screenOnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams screenOnRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        screenOnRowParams.setMargins(0, dpToPx(4), 0, dpToPx(24));
        card.addView(screenOnRow, screenOnRowParams);

        TextView screenLabel = new TextView(this);
        screenLabel.setText("Keep Screen Powered On");
        screenLabel.setTextColor(Color.parseColor("#E2E8F0"));
        screenLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        screenOnRow.addView(screenLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch screenSwitch = new Switch(this);
        screenSwitch.setChecked(settingsManager.getKeepScreenOn());
        screenOnRow.addView(screenSwitch);

        // Actions row
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        card.addView(actionRow);

        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextColor(Color.parseColor("#94A3B8"));
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancelBtn.setPadding(0, dpToPx(12), 0, dpToPx(12));
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rootLayout.removeView(overlay);
            }
        });
        actionRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView saveBtn = new TextView(this);
        saveBtn.setText("Save");
        saveBtn.setTextColor(Color.BLACK);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        saveBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        saveBtn.setPadding(0, dpToPx(12), 0, dpToPx(12));
        saveBtn.setGravity(Gravity.CENTER);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(Color.WHITE);
        saveBg.setCornerRadius(dpToPx(12));
        saveBtn.setBackground(saveBg);
        
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        saveParams.setMargins(dpToPx(12), 0, 0, 0);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String portStr = portInput.getText().toString().trim();
                if (portStr.isEmpty()) {
                    updateStatusText("Invalid Port!");
                    return;
                }
                int newPort = Integer.parseInt(portStr);
                if (newPort < 1024 || newPort > 65535) {
                    updateStatusText("Port must be 1024-65535");
                    return;
                }

                int oldPort = settingsManager.getPort();
                int selectedBitrate = bitrates[selectedBitrateIdx[0]];
                int selectedFps = fpsOptions[selectedFpsIdx[0]];
                boolean keepScreenOn = screenSwitch.isChecked();
                boolean flipH = flipHSwitch.isChecked();
                boolean flipV = flipVSwitch.isChecked();
                int selectedRotation = rotationOptions[selectedRotationIdx[0]];
                String selectedRes = resOptions[selectedResIdx[0]];
                boolean faceAf = faceAfSwitch.isChecked();

                settingsManager.setPort(newPort);
                settingsManager.setBitrate(selectedBitrate);
                settingsManager.setFramerate(selectedFps);
                settingsManager.setKeepScreenOn(keepScreenOn);
                settingsManager.setFlipHorizontal(flipH);
                settingsManager.setFlipVertical(flipV);
                settingsManager.setRotationOverride(selectedRotation);
                settingsManager.setResolution(selectedRes);
                settingsManager.setFaceAutoFocus(faceAf);

                if (cameraStreamer != null) {
                    cameraStreamer.setFaceAutoFocusEnabled(faceAf);
                }

                if (keepScreenOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }

                if (standbyIpTextView != null) {
                    standbyIpTextView.setText(getDeviceIpAddresses() + "\nPort: " + newPort);
                }
                int[] wxh = parseResolution(selectedRes);
                cameraStreamer.setTargetResolution(wxh[0], wxh[1]);

                adjustAspectRatio(currentVideoWidth, currentVideoHeight);

                if (newPort != oldPort) {
                    socketServer.stop();
                    socketServer.setPort(newPort);
                    socketServer.start();
                }
                rootLayout.removeView(overlay);
            }
        });
        actionRow.addView(saveBtn, saveParams);

        rootLayout.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void updateBitrateButtonState(TextView btn, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(10));
        if (selected) {
            bg.setColor(Color.WHITE);
            btn.setTextColor(Color.BLACK);
            bg.setStroke(dpToPx(1), Color.WHITE);
        } else {
            bg.setColor(Color.parseColor("#1F1F2E"));
            btn.setTextColor(Color.parseColor("#94A3B8"));
            bg.setStroke(dpToPx(1), Color.parseColor("#22FFFFFF"));
        }
        btn.setBackground(bg);
    }

    private int[] parseResolution(String res) {
        try {
            String[] parts = res.split("x");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception e) {
            return new int[]{1280, 720};
        }
    }

    public List<String> getSupportedResolutions() {
        List<String> supported = new java.util.ArrayList<>();
        try {
            android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList.length == 0) return supported;
            
            String targetCameraId = cameraIdList[0];
            for (String id : cameraIdList) {
                android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    targetCameraId = id;
                    break;
                }
            }
            
            android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
            android.hardware.camera2.params.StreamConfigurationMap map = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return supported;
            
            android.util.Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
            if (sizes == null) return supported;
            
            String[] candidates = {"3840x2160", "2560x1440", "1920x1080", "1280x720", "1024x768", "640x480"};
            for (String candidate : candidates) {
                String[] parts = candidate.split("x");
                int w = Integer.parseInt(parts[0]);
                int h = Integer.parseInt(parts[1]);
                for (android.util.Size size : sizes) {
                    if (size.getWidth() == w && size.getHeight() == h) {
                        supported.add(candidate);
                        break;
                    }
                }
            }
            if (supported.isEmpty() && sizes.length > 0) {
                supported.add(sizes[0].getWidth() + "x" + sizes[0].getHeight());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying camera resolutions", e);
        }
        return supported;
    }

    private String getResolutionLabel(String res) {
        if ("3840x2160".equals(res)) return "4K";
        if ("2560x1440".equals(res)) return "2K";
        if ("1920x1080".equals(res)) return "1080p";
        if ("1280x720".equals(res)) return "720p";
        if ("1024x768".equals(res)) return "768p";
        if ("640x480".equals(res)) return "480p";
        return res;
    }

    private void updateTrafficStats() {
        long currentTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
        long now = System.currentTimeMillis();
        if (lastTxBytes > 0 && lastSpeedCheckTime > 0) {
            long bytesSent = currentTx - lastTxBytes;
            long timeDeltaMs = now - lastSpeedCheckTime;
            if (timeDeltaMs > 0) {
                double bytesPerSec = (double) bytesSent / (timeDeltaMs / 1000.0);
                if (bytesPerSec < 1024) {
                    currentTxSpeedText = String.format(java.util.Locale.US, "%.1f B/s", bytesPerSec);
                } else if (bytesPerSec < 1024 * 1024) {
                    currentTxSpeedText = String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
                } else {
                    currentTxSpeedText = String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
                }
            }
        } else {
            currentTxSpeedText = "0.0 KB/s";
        }
        lastTxBytes = currentTx;
        lastSpeedCheckTime = now;

        double totalMb = (double) currentTx / (1024.0 * 1024.0);
        totalTxText = String.format(java.util.Locale.US, "%.1f MB", totalMb);
    }

    private void updateTelemetryDisplay() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (txRateVal != null) txRateVal.setText(currentTxSpeedText);
                if (txTotalVal != null) txTotalVal.setText(totalTxText);

                if (tempVal != null) {
                    tempVal.setText(String.format(java.util.Locale.US, "%.1f °C", currentBatteryTemp));
                    if (currentBatteryTemp > 42.0f) {
                        tempVal.setTextColor(Color.parseColor("#FF0055"));
                    } else if (currentBatteryTemp > 38.0f) {
                        tempVal.setTextColor(Color.parseColor("#FF9900"));
                    } else {
                        tempVal.setTextColor(Color.parseColor("#00FF66"));
                    }
                }
                if (batteryVal != null) batteryVal.setText(currentBatteryPercent + "%");

                if (focusVal != null) {
                    if (cameraStreamer != null && cameraStreamer.isAutofocusLocked()) {
                        focusVal.setText("LOCKED");
                        focusVal.setTextColor(Color.parseColor("#FF9900"));
                    } else {
                        focusVal.setText("AUTO-C");
                        focusVal.setTextColor(Color.parseColor("#00FF66"));
                    }
                }
                if (filterVal != null) {
                    filterVal.setText(filterLabels[currentFilterIndex]);
                }

                if (socketServer != null && socketServer.isStreaming() && activeStatusTextView != null) {
                    String currentText = activeStatusTextView.getText().toString();
                    if (!currentText.startsWith(">> ")) {
                        long diffMs = System.currentTimeMillis() - streamStartTime;
                        long secs = (diffMs / 1000) % 60;
                        long mins = (diffMs / (1000 * 60)) % 60;
                        long hours = (diffMs / (1000 * 60 * 60)) % 24;
                        String duration;
                        if (hours > 0) {
                            duration = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, mins, secs);
                        } else {
                            duration = String.format(java.util.Locale.US, "%02d:%02d", mins, secs);
                        }
                        int drops = socketServer.getDroppedFramesCount();
                        activeStatusTextView.setText("🔴 LIVE " + duration + " | DROPS: " + drops);
                        activeStatusTextView.setTextColor(Color.parseColor("#00FF66"));
                    }
                }
            }
        });
    }

    private void toggleStatusBlink() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (socketServer != null && socketServer.isStreaming()) {
                    blinkState = !blinkState;
                    if (statusLed != null) {
                        statusLed.setAlpha(blinkState ? 1.0f : 0.2f);
                    }
                } else {
                    if (statusLed != null) {
                        statusLed.setAlpha(1.0f);
                    }
                }
            }
        });
    }

    private void cycleFilter() {
        if (cameraStreamer == null) return;
        currentFilterIndex = (currentFilterIndex + 1) % filterEffects.length;
        cameraStreamer.setFilterEffect(filterEffects[currentFilterIndex]);
        updateTelemetryDisplay();
        showCyberToast("FILTER: " + filterLabels[currentFilterIndex]);
    }

    private void styleCyberButton(TextView btn, String neonColor) {
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, dpToPx(12), 0, dpToPx(12));
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E608080C"));
        bg.setCornerRadius(dpToPx(6));
        bg.setStroke(dpToPx(1), Color.parseColor(neonColor));
        btn.setBackground(bg);
    }

    private void showCyberToast(String message) {
        updateStatusText(">> " + message);
        toastHandler.removeCallbacks(revertStatusRunnable);
        toastHandler.postDelayed(revertStatusRunnable, 2000);
    }

    private void enterDimMode() {
        if (isDimMode) return;
        isDimMode = true;
        
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
        
        if (dimOverlay == null) {
            dimOverlay = new FrameLayout(this);
            dimOverlay.setBackgroundColor(Color.parseColor("#FC050508"));
            dimOverlay.setClickable(true);
            dimOverlay.setFocusable(true);
            dimOverlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exitDimMode();
                }
            });
            
            dimTextContainer = new LinearLayout(this);
            dimTextContainer.setGravity(Gravity.CENTER);
            dimTextContainer.setOrientation(LinearLayout.VERTICAL);
            
            TextView activeText = new TextView(this);
            activeText.setText("STREAMING ACTIVE");
            activeText.setTextColor(Color.parseColor("#00FF66"));
            activeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            activeText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            activeText.setShadowLayer(10f, 0f, 0f, Color.parseColor("#00FF66"));
            dimTextContainer.addView(activeText);
            
            TextView wakeText = new TextView(this);
            wakeText.setText("[TAP TO WAKE]");
            wakeText.setTextColor(Color.parseColor("#00F0FF"));
            wakeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            wakeText.setTypeface(Typeface.MONOSPACE);
            wakeText.setShadowLayer(6f, 0f, 0f, Color.parseColor("#00F0FF"));
            LinearLayout.LayoutParams lpWake = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpWake.setMargins(0, dpToPx(8), 0, 0);
            dimTextContainer.addView(wakeText, lpWake);
            
            dimOverlay.addView(dimTextContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        
        if (dimOverlay.getParent() != null) {
            ((ViewGroup) dimOverlay.getParent()).removeView(dimOverlay);
        }
        
        rootLayout.addView(dimOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dimTextContainer.setTranslationX(0);
        dimTextContainer.setTranslationY(0);
        dimHandler.removeCallbacks(dimRunnable);
        dimHandler.postDelayed(dimRunnable, 10000);
        
        showCyberToast("DIM MODE ACTIVE");
    }

    private void exitDimMode() {
        if (!isDimMode) return;
        isDimMode = false;
        
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        
        if (dimOverlay != null && dimOverlay.getParent() != null) {
            rootLayout.removeView(dimOverlay);
        }
        dimHandler.removeCallbacks(dimRunnable);
        showCyberToast("WAKING SCREEN");
    }

    private void showCameraSelectionMenu(View anchor) {
        if (cameraStreamer == null) return;
        try {
            android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            final String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList.length == 0) {
                showCyberToast("No cameras available");
                return;
            }

            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
            for (int i = 0; i < cameraIdList.length; i++) {
                String id = cameraIdList[i];
                android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                String label = "Camera " + id;
                if (facing != null) {
                    if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                        label += " (Front)";
                    } else if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                        label += " (Back)";
                    } else if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL) {
                        label += " (External)";
                    }
                }
                
                String activeId = cameraStreamer.getCurrentCameraId();
                if (id.equals(activeId)) {
                    label += "  ✓";
                }
                
                popup.getMenu().add(android.view.Menu.NONE, i, i, label);
            }

            popup.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    int index = item.getItemId();
                    String selectedId = cameraIdList[index];
                    cameraStreamer.selectCamera(selectedId);
                    
                    android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
                    try {
                        android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedId);
                        Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                        String faceLabel = "Back";
                        if (facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                            faceLabel = "Front";
                        } else if (facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL) {
                            faceLabel = "External";
                        }
                        showCyberToast("Camera selected: " + faceLabel + " (ID " + selectedId + ")");
                    } catch (Exception e) {
                        showCyberToast("Switched Camera");
                    }
                    return true;
                }
            });
            popup.show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show camera selection menu", e);
            cameraStreamer.switchCamera();
            showCyberToast("SWITCHING SENSOR");
        }
    }

    public void updateSettingFromWeb(final String key, final String val) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (key.equals("face_auto_focus")) {
                        boolean faceAf = Boolean.parseBoolean(val);
                        settingsManager.setFaceAutoFocus(faceAf);
                        if (cameraStreamer != null) {
                            cameraStreamer.setFaceAutoFocusEnabled(faceAf);
                        }
                        showCyberToast("WEB: FACE AF " + (faceAf ? "ON" : "OFF"));
                    } else if (key.equals("keep_screen_on")) {
                        boolean keepOn = Boolean.parseBoolean(val);
                        settingsManager.setKeepScreenOn(keepOn);
                        if (keepOn) {
                            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        } else {
                            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        }
                        showCyberToast("WEB: KEEP SCREEN " + (keepOn ? "ON" : "OFF"));
                    } else if (key.equals("flip_horizontal")) {
                        boolean flip = Boolean.parseBoolean(val);
                        settingsManager.setFlipHorizontal(flip);
                        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
                        showCyberToast("WEB: FLIP H " + (flip ? "ON" : "OFF"));
                    } else if (key.equals("flip_vertical")) {
                        boolean flip = Boolean.parseBoolean(val);
                        settingsManager.setFlipVertical(flip);
                        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
                        showCyberToast("WEB: FLIP V " + (flip ? "ON" : "OFF"));
                    } else if (key.equals("resolution")) {
                        settingsManager.setResolution(val);
                        int[] wxh = parseResolution(val);
                        if (cameraStreamer != null) {
                            cameraStreamer.setTargetResolution(wxh[0], wxh[1]);
                        }
                        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
                        showCyberToast("WEB: RESOLUTION " + val);
                    } else if (key.equals("bitrate")) {
                        int bitrate = Integer.parseInt(val);
                        settingsManager.setBitrate(bitrate);
                        showCyberToast("WEB: BITRATE " + (bitrate / 1000) + " kbps");
                    } else if (key.equals("framerate")) {
                        int fps = Integer.parseInt(val);
                        settingsManager.setFramerate(fps);
                        showCyberToast("WEB: FPS " + fps);
                    } else if (key.equals("camera")) {
                        if (cameraStreamer != null) {
                            cameraStreamer.selectCamera(val);
                            showCyberToast("WEB: CAMERA " + val);
                        }
                    } else if (key.equals("trigger_af")) {
                        if (cameraStreamer != null) {
                            cameraStreamer.triggerAutofocus();
                            showCyberToast("WEB: AF TRIGGERED");
                        }
                    } else if (key.equals("toggle_af_mode")) {
                        if (cameraStreamer != null) {
                            cameraStreamer.toggleAutofocusMode();
                            boolean locked = cameraStreamer.isAutofocusLocked();
                            showCyberToast("WEB: " + (locked ? "FOCUS LOCKED" : "CONTINUOUS AF"));
                        }
                    } else if (key.equals("port")) {
                        int newPort = Integer.parseInt(val);
                        int oldPort = settingsManager.getPort();
                        if (newPort != oldPort) {
                            settingsManager.setPort(newPort);
                            socketServer.stop();
                            socketServer.setPort(newPort);
                            socketServer.start();
                            showCyberToast("WEB: PORT TO " + newPort);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating setting from web: key=" + key + ", val=" + val, e);
                }
            }
        });
    }
}
