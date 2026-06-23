package com.webcamclone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraManager;
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
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
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
    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST = 101;

    // ── iOS 26 Design Tokens ──
    private static final String COLOR_BG          = "#000000";
    private static final String COLOR_SURFACE      = "#1C1C1E";
    private static final String COLOR_SURFACE_GLASS= "#B32C2C2E"; // 70% opacity
    private static final String COLOR_LABEL        = "#FFFFFF";
    private static final String COLOR_LABEL_2      = "#8E8E93";
    private static final String COLOR_LABEL_3      = "#636366";
    private static final String COLOR_SEPARATOR    = "#38383A";
    private String COLOR_ACCENT = "#64D2FF"; // Dynamic Accent (Default Teal)
    private static final String COLOR_GREEN        = "#30D158";
    private static final String COLOR_ORANGE       = "#FF9F0A";
    private static final String COLOR_RED          = "#FF453A";
    private static final String COLOR_TEAL         = "#64D2FF";
    private static final String COLOR_PINK         = "#FF375F";
    private static final int RADIUS_SM = 10;
    private static final int RADIUS_MD = 16;
    private static final int RADIUS_LG = 24;
    private static final int RADIUS_XL = 28;

    private TextureView textureView;
    private TextView ipTextView;
    private TextView statusTextView;
    private TextView titleTextView;
    private LinearLayout statusBadge;
    private FrameLayout permissionOverlay;
    private LinearLayout topHud;
    private LinearLayout bottomPanel;
    private LinearLayout telemetryLayout;
    private LinearLayout buttonRow;
    private FrameLayout standbyOverlay;
    private TextView standbyIpTextView;
    private TextView switchCamBtn;
    private TextView flashBtn;
    private TextView filterBtn;
    private TextView focusBtn;
    private TextView settingsBtn;
    private TextView guideBtn;
    private TextView previewBtn;
    private int lastLayoutWidth = 0;
    private int lastLayoutHeight = 0;
    private View guidesOverlay;
    private int currentGuideMode = 0; // 0 = None, 1 = Grid, 2 = Crosshair, 3 = TikTok
    private long lastTapTime = 0;
    private boolean isUiHidden = false;
    private long streamStartTime = 0;

    // Redesigned UI Accent components
    private String currentStreamFormat = "avc";
    private LinearLayout switchCamContainer;
    private LinearLayout flashContainer;
    private LinearLayout filterContainer;
    private LinearLayout focusContainer;
    private LinearLayout settingsContainer;
    private LinearLayout guideContainer;
    private LinearLayout previewContainer;
    private TextView[] resButtons;
    private TextView[] fpsButtons;
    private String[] resolutions = {"640x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"};
    private int[] framerates = {15, 24, 30, 60};
    private final String[] accentColors = {
        "#64D2FF", // Teal
        "#FF375F", // Pink
        "#5E5CE6", // Indigo/Purple
        "#FF9F0A", // Orange
        "#30D158"  // Green
    };
    private int selectedAccentIndex = 0;

    CameraStreamer cameraStreamer;
    private SocketServer socketServer;
    private Surface previewSurface;
    private boolean isSurfaceReady = false;

    private SettingsManager settingsManager;
    private FrameLayout rootLayout;

    private int currentVideoWidth = 1280;
    private int currentVideoHeight = 720;

    private View statusLed;

    // Preview mute
    private boolean isPreviewMuted = false;
    private FrameLayout previewMuteOverlay;

    // Dim Mode overlay
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
    
    // Telemetry fields
    private TextView txRateVal;
    private TextView txTotalVal;
    private TextView tempVal;
    private TextView batteryVal;
    private TextView focusVal;
    private TextView filterVal;

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
        0, 1, 2, 3, 4, 5
    };
    private final String[] filterLabels = {
        "NORMAL", "BEAUTY", "PORTRAIT", "COMIC", "NEON", "GLITCH"
    };
    private int currentFilterIndex = 0;

    // ── iOS 26 Style: Metric Tile ──
    private LinearLayout createMetricTile(String labelText, TextView valueTextView) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8));
        
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#0FFFFFFF")); // 6% white
        cardBg.setCornerRadius(dpToPx(RADIUS_SM));
        card.setBackground(cardBg);
        
        // Value first (larger)
        valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        valueTextView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valueTextView.setGravity(Gravity.CENTER);
        
        card.addView(valueTextView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        
        // Label below (smaller)
        TextView label = new TextView(this);
        label.setText(labelText.toUpperCase());
        label.setTextColor(Color.parseColor(COLOR_LABEL_3));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dpToPx(3), 0, 0);
        card.addView(label, labelParams);
        
        return card;
    }

    // ── iOS 26 Style: Circle Action Button ──
    private LinearLayout createCircleButton(String labelText, boolean isActive, String accentColor) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dpToPx(2), dpToPx(4), dpToPx(2), dpToPx(4));
        
        // Circular icon area
        TextView iconArea = new TextView(this);
        iconArea.setGravity(Gravity.CENTER);
        iconArea.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        
        if (isActive) {
            int accent = Color.parseColor(accentColor);
            circleBg.setColor(accent);
            iconArea.setTextColor(Color.BLACK);
        } else {
            circleBg.setColor(Color.parseColor("#1AFFFFFF")); // 10% white
            iconArea.setTextColor(Color.WHITE);
        }
        iconArea.setBackground(circleBg);
        
        int iconSize = dpToPx(44);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        container.addView(iconArea, iconParams);
        
        // Label below
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(isActive ? Color.parseColor(accentColor) : Color.parseColor(COLOR_LABEL_2));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dpToPx(4), 0, 0);
        container.addView(label, labelParams);
        
        return container;
    }

    private void styleCircleButton(LinearLayout container, boolean isActive, String accentColor) {
        if (container == null || container.getChildCount() < 2) return;
        
        View iconArea = container.getChildAt(0);
        TextView label = (TextView) container.getChildAt(1);
        
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        
        if (isActive) {
            int accent = Color.parseColor(accentColor);
            circleBg.setColor(accent);
            if (iconArea instanceof TextView) {
                ((TextView) iconArea).setTextColor(Color.BLACK);
            }
            label.setTextColor(Color.parseColor(accentColor));
        } else {
            circleBg.setColor(Color.parseColor("#1AFFFFFF"));
            if (iconArea instanceof TextView) {
                ((TextView) iconArea).setTextColor(Color.WHITE);
            }
            label.setTextColor(Color.parseColor(COLOR_LABEL_2));
        }
        iconArea.setBackground(circleBg);
    }

    // Helper to get icon TextView from a circle button container
    private TextView getCircleButtonIcon(LinearLayout container) {
        if (container != null && container.getChildCount() > 0 && container.getChildAt(0) instanceof TextView) {
            return (TextView) container.getChildAt(0);
        }
        return null;
    }

    // Keep legacy method for settings dialog buttons
    private void styleModernButton(TextView btn, boolean isActive, String accentColor) {
        btn.setTextColor(isActive ? Color.BLACK : Color.parseColor(COLOR_LABEL));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dpToPx(4), dpToPx(12), dpToPx(4), dpToPx(12));
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(RADIUS_SM));
        if (isActive) {
            bg.setColor(Color.WHITE);
            bg.setStroke(dpToPx(1), Color.WHITE);
            btn.setTextColor(Color.BLACK);
        } else {
            bg.setColor(Color.parseColor(COLOR_SURFACE));
            bg.setStroke(dpToPx(1), Color.parseColor(COLOR_SEPARATOR));
            btn.setTextColor(Color.parseColor(COLOR_LABEL_2));
        }
        btn.setBackground(bg);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(this);

        // Hide default Action Bar for a clean full-screen look
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // Keep screen on and hide status/navigation bar for a clean full-screen look
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

        // ── Root Layout ──
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor(COLOR_BG));
        rootLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                updateFoldLayout(right - left, bottom - top);
            }
        });

        // ── 1. Camera TextureView ──
        textureView = new TextureView(this);
        final FrameLayout.LayoutParams textureParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        rootLayout.addView(textureView, textureParams);
        textureView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);
            }
        });

        // ── Framing Guides Overlay ──
        guidesOverlay = new View(this) {
            private final android.graphics.Paint paint = new android.graphics.Paint();
            {
                paint.setColor(Color.parseColor("#40FFFFFF")); // White 25%
                paint.setStrokeWidth(1f);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setAntiAlias(true);
            }
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if (currentGuideMode == 0) return;
                
                int w = getWidth();
                int h = getHeight();
                if (w == 0 || h == 0) return;
                
                // Mirror adjustAspectRatio math to find active video bounds
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                int sensorOrientation = 90;
                int activeFacing = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK;
                if (cameraStreamer != null) {
                    activeFacing = cameraStreamer.getLensFacing();
                }
                try {
                    android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
                    String[] cameraIdList = manager.getCameraIdList();
                    if (cameraIdList.length > 0) {
                        String targetCameraId = cameraIdList[0];
                        for (String id : cameraIdList) {
                            android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                            if (facing != null && facing == activeFacing) {
                                targetCameraId = id;
                                break;
                            }
                        }
                        android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
                        Integer orient = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);
                        if (orient != null) {
                            sensorOrientation = orient;
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
                
                int deviceRotationDegrees = 0;
                switch (rotation) {
                    case Surface.ROTATION_0: deviceRotationDegrees = 0; break;
                    case Surface.ROTATION_90: deviceRotationDegrees = 90; break;
                    case Surface.ROTATION_180: deviceRotationDegrees = 180; break;
                    case Surface.ROTATION_270: deviceRotationDegrees = 270; break;
                }
                
                int relativeRotation;
                if (activeFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                    relativeRotation = (sensorOrientation + deviceRotationDegrees) % 360;
                } else {
                    relativeRotation = (sensorOrientation - deviceRotationDegrees + 360) % 360;
                }
                
                double videoAspect = (relativeRotation == 90 || relativeRotation == 270)
                        ? (double) currentVideoHeight / currentVideoWidth
                        : (double) currentVideoWidth / currentVideoHeight;
                double viewAspect = (double) w / h;
                
                double targetW, targetH;
                if (viewAspect > videoAspect) {
                    targetH = h;
                    targetW = h * videoAspect;
                } else {
                    targetW = w;
                    targetH = w / videoAspect;
                }
                
                float left = (float) (w - targetW) / 2f;
                float top = (float) (h - targetH) / 2f;
                float right = left + (float) targetW;
                float bottom = top + (float) targetH;
                
                if (currentGuideMode == 1) { // 3x3 Grid
                    float colWidth = (float) targetW / 3f;
                    float rowHeight = (float) targetH / 3f;
                    
                    canvas.drawLine(left + colWidth, top, left + colWidth, bottom, paint);
                    canvas.drawLine(left + colWidth * 2, top, left + colWidth * 2, bottom, paint);
                    canvas.drawLine(left, top + rowHeight, right, top + rowHeight, paint);
                    canvas.drawLine(left, top + rowHeight * 2, right, top + rowHeight * 2, paint);
                } else if (currentGuideMode == 2) { // Crosshair
                    float cx = left + (float) targetW / 2f;
                    float cy = top + (float) targetH / 2f;
                    canvas.drawLine(cx, cy - dpToPx(20), cx, cy + dpToPx(20), paint);
                    canvas.drawLine(cx - dpToPx(20), cy, cx + dpToPx(20), cy, paint);
                    canvas.drawCircle(cx, cy, dpToPx(8), paint);
                } else if (currentGuideMode == 3) { // TikTok Crop Box
                    float activeAspect = (float) (targetW / targetH);
                    float tiktokW, tiktokH;
                    if (activeAspect > (9f / 16f)) {
                        tiktokH = (float) targetH;
                        tiktokW = tiktokH * (9f / 16f);
                    } else {
                        tiktokW = (float) targetW;
                        tiktokH = tiktokW / (9f / 16f);
                    }
                    
                    float tLeft = left + ((float) targetW - tiktokW) / 2f;
                    float tTop = top + ((float) targetH - tiktokH) / 2f;
                    float tRight = tLeft + tiktokW;
                    float tBottom = tTop + tiktokH;
                    
                    canvas.drawRect(tLeft, tTop, tRight, tBottom, paint);
                    
                    android.graphics.Paint dimPaint = new android.graphics.Paint();
                    dimPaint.setColor(Color.parseColor("#80000000")); // 50% overlay
                    canvas.drawRect(left, top, tLeft, bottom, dimPaint);
                    canvas.drawRect(tRight, top, right, bottom, dimPaint);
                    canvas.drawRect(tLeft, top, tRight, tTop, dimPaint);
                    canvas.drawRect(tLeft, tBottom, tRight, bottom, dimPaint);
                }
            }
        };
        guidesOverlay.setClickable(false);
        guidesOverlay.setFocusable(false);
        guidesOverlay.setVisibility(View.GONE);
        rootLayout.addView(guidesOverlay, textureParams);

        // Tap on preview to trigger manual autofocus scan or hide UI (double tap)
        textureView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - lastTapTime < 300) {
                    toggleUiVisibility();
                } else {
                    if (cameraStreamer != null) {
                        cameraStreamer.triggerAutofocus();
                        showToast("Autofocus triggered");
                    }
                }
                lastTapTime = now;
            }
        });

        // ── 2. Top HUD — iOS 26 Style ──
        topHud = new LinearLayout(this);
        topHud.setOrientation(LinearLayout.HORIZONTAL);
        topHud.setGravity(Gravity.CENTER_VERTICAL);
        topHud.setPadding(dpToPx(16), dpToPx(44), dpToPx(16), dpToPx(10));

        // Ultra-thin material background
        GradientDrawable topBg = new GradientDrawable();
        topBg.setColor(Color.parseColor("#CC1C1C1E")); // 80% surface
        topHud.setBackground(topBg);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topParams.gravity = Gravity.TOP;
        rootLayout.addView(topHud, topParams);

        // 2a. App Title — Clean SF Pro style
        titleTextView = new TextView(this);
        titleTextView.setText("Studio Cam");
        titleTextView.setTextColor(Color.parseColor(COLOR_LABEL));
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleTextView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topHud.addView(titleTextView);

        // Accent theme picker dots
        LinearLayout dotsLayout = new LinearLayout(this);
        dotsLayout.setOrientation(LinearLayout.HORIZONTAL);
        dotsLayout.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsParams.setMargins(dpToPx(8), 0, 0, 0);

        final View[] dotViews = new View[accentColors.length];
        for (int i = 0; i < accentColors.length; i++) {
            final int index = i;
            final View dot = new View(this);
            int size = (index == selectedAccentIndex) ? dpToPx(11) : dpToPx(8);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(size, size);
            dotParams.setMargins(dpToPx(3), 0, dpToPx(3), 0);

            final GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(Color.parseColor(accentColors[i]));
            if (index == selectedAccentIndex) {
                dotBg.setStroke(dpToPx(1), Color.WHITE);
            }
            dot.setBackground(dotBg);

            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedAccentIndex = index;
                    for (int j = 0; j < dotViews.length; j++) {
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dotViews[j].getLayoutParams();
                        int newSize = (j == selectedAccentIndex) ? dpToPx(11) : dpToPx(8);
                        lp.width = newSize;
                        lp.height = newSize;
                        dotViews[j].setLayoutParams(lp);

                        GradientDrawable bg = (GradientDrawable) dotViews[j].getBackground();
                        if (bg != null) {
                            if (j == selectedAccentIndex) {
                                bg.setStroke(dpToPx(1), Color.WHITE);
                            } else {
                                bg.setStroke(0, Color.TRANSPARENT);
                            }
                        }
                    }
                    applyAccentColor(index);
                }
            });

            dotViews[i] = dot;
            dotsLayout.addView(dot, dotParams);
        }
        topHud.addView(dotsLayout, dotsParams);

        // Spacer
        View topSpacer1 = new View(this);
        topHud.addView(topSpacer1, new LinearLayout.LayoutParams(0, 1, 1f));

        // 2b. Status Pill — Dynamic Island inspired
        statusBadge = new LinearLayout(this);
        statusBadge.setOrientation(LinearLayout.HORIZONTAL);
        statusBadge.setGravity(Gravity.CENTER_VERTICAL);
        statusBadge.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));

        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#14FFFFFF")); // 8% white
        badgeBg.setCornerRadius(dpToPx(99)); // Pill
        statusBadge.setBackground(badgeBg);

        statusLed = new View(this);
        LinearLayout.LayoutParams ledParams = new LinearLayout.LayoutParams(dpToPx(7), dpToPx(7));
        ledParams.setMargins(0, 0, dpToPx(6), 0);
        GradientDrawable ledBg = new GradientDrawable();
        ledBg.setShape(GradientDrawable.OVAL);
        ledBg.setColor(Color.parseColor(COLOR_LABEL_2));
        statusLed.setBackground(ledBg);
        statusBadge.addView(statusLed, ledParams);

        statusTextView = new TextView(this);
        statusTextView.setText("Standby");
        statusTextView.setTextColor(Color.parseColor(COLOR_LABEL_2));
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusTextView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusBadge.addView(statusTextView);

        topHud.addView(statusBadge);

        // Spacer
        View topSpacer2 = new View(this);
        topHud.addView(topSpacer2, new LinearLayout.LayoutParams(0, 1, 1f));

        // 2c. IP text
        ipTextView = new TextView(this);
        ipTextView.setText(getWifiIpAddress() + ":" + settingsManager.getPort());
        ipTextView.setTextColor(Color.parseColor(COLOR_LABEL_3));
        ipTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ipTextView.setTypeface(Typeface.MONOSPACE);
        topHud.addView(ipTextView);

        // 2d. DIM button — Moon circle
        final TextView dimHudBtn = new TextView(this);
        dimHudBtn.setText("☽");
        dimHudBtn.setTextColor(Color.parseColor(COLOR_LABEL_2));
        dimHudBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dimHudBtn.setGravity(Gravity.CENTER);

        GradientDrawable moonBg = new GradientDrawable();
        moonBg.setShape(GradientDrawable.OVAL);
        moonBg.setColor(Color.parseColor("#14FFFFFF")); // 8% white
        dimHudBtn.setBackground(moonBg);

        LinearLayout.LayoutParams dimHudParams = new LinearLayout.LayoutParams(dpToPx(30), dpToPx(30));
        dimHudParams.setMargins(dpToPx(8), 0, 0, 0);
        topHud.addView(dimHudBtn, dimHudParams);

        dimHudBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterDimMode();
            }
        });

        // ── 3. Bottom Control Panel — Redesigned Glass Sheet ──
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(28));

        GradientDrawable bottomBg = new GradientDrawable();
        bottomBg.setColor(Color.parseColor("#E61C1C1E")); // glassmorphic dark surface
        bottomBg.setCornerRadii(new float[]{
            dpToPx(RADIUS_XL), dpToPx(RADIUS_XL), dpToPx(RADIUS_XL), dpToPx(RADIUS_XL), 0, 0, 0, 0
        });
        bottomPanel.setBackground(bottomBg);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bottomParams.gravity = Gravity.BOTTOM;
        bottomParams.setMargins(dpToPx(8), 0, dpToPx(8), 0);
        bottomPanel.setVisibility(View.GONE);
        rootLayout.addView(bottomPanel, bottomParams);

        // 3a. Voice Level wave shape (fluctuates dynamically)
        final View voiceWaveform = new View(this) {
            private float phase = 0f;
            private final android.graphics.Paint paint = new android.graphics.Paint();
            {
                paint.setStrokeWidth(dpToPx(2));
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setAntiAlias(true);
            }
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                if (w == 0 || h == 0) return;

                float midY = h / 2f;
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(0, midY);

                boolean isStreaming = socketServer != null && socketServer.isStreaming();

                for (int x = 0; x < w; x += 4) {
                    float relativeX = (float) x / w;
                    float amplitude = isStreaming 
                        ? (float) (Math.sin(relativeX * Math.PI) * dpToPx(8) * Math.sin(phase * 1.5))
                        : (float) (Math.sin(relativeX * Math.PI) * dpToPx(2));

                    float y = (float) (midY + Math.sin(relativeX * 10 + phase) * amplitude);
                    path.lineTo(x, y);
                }

                paint.setColor(Color.parseColor(getAccentColorHex()));
                canvas.drawPath(path, paint);

                phase += 0.08f;
                postInvalidateDelayed(33); // ~30fps
            }
        };
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(18));
        waveParams.setMargins(0, 0, 0, dpToPx(6));
        bottomPanel.addView(voiceWaveform, waveParams);

        // 3b. Telemetry Row — Metric Tiles
        telemetryLayout = new LinearLayout(this);
        telemetryLayout.setOrientation(LinearLayout.HORIZONTAL);
        telemetryLayout.setGravity(Gravity.CENTER_VERTICAL);
        bottomPanel.addView(telemetryLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // TX Rate
        txRateVal = new TextView(this);
        txRateVal.setText("0.0 KB/s");
        txRateVal.setTextColor(Color.parseColor(getAccentColorHex()));
        LinearLayout txRateCard = createMetricTile("Rate", txRateVal);
        telemetryLayout.addView(txRateCard);

        // TX Total
        txTotalVal = new TextView(this);
        txTotalVal.setText("0.0 MB");
        txTotalVal.setTextColor(Color.parseColor(getAccentColorHex()));
        LinearLayout txTotalCard = createMetricTile("Total", txTotalVal);
        telemetryLayout.addView(txTotalCard);

        // Temp
        tempVal = new TextView(this);
        tempVal.setText("0.0°C");
        tempVal.setTextColor(Color.parseColor(COLOR_GREEN));
        LinearLayout tempCard = createMetricTile("Temp", tempVal);
        telemetryLayout.addView(tempCard);

        // Battery
        batteryVal = new TextView(this);
        batteryVal.setText("--%");
        batteryVal.setTextColor(Color.parseColor(COLOR_GREEN));
        LinearLayout batteryCard = createMetricTile("Battery", batteryVal);
        telemetryLayout.addView(batteryCard);

        // Focus
        focusVal = new TextView(this);
        focusVal.setText("AUTO-C");
        focusVal.setTextColor(Color.parseColor(getAccentColorHex()));
        LinearLayout focusCard = createMetricTile("Focus", focusVal);
        telemetryLayout.addView(focusCard);

        // Filter
        filterVal = new TextView(this);
        filterVal.setText("NORMAL");
        filterVal.setTextColor(Color.parseColor(COLOR_LABEL_2));
        LinearLayout filterCard = createMetricTile("Filter", filterVal);
        telemetryLayout.addView(filterCard);

        // 3c. Quick Selectors (Resolution & FPS)
        HorizontalScrollView quickSelectorScroll = new HorizontalScrollView(this);
        quickSelectorScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout quickSelectorLayout = new LinearLayout(this);
        quickSelectorLayout.setOrientation(LinearLayout.HORIZONTAL);
        quickSelectorLayout.setGravity(Gravity.CENTER_VERTICAL);
        quickSelectorScroll.addView(quickSelectorLayout);

        resButtons = new TextView[resolutions.length];
        final String[] resLabels = {"480p", "720p", "1080p", "2K", "4K"};
        for (int i = 0; i < resolutions.length; i++) {
            final int index = i;
            final String res = resolutions[i];
            final TextView pill = new TextView(this);
            pill.setText(resLabels[i]);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            pill.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            pill.setGravity(Gravity.CENTER);

            final GradientDrawable pillBg = new GradientDrawable();
            pillBg.setCornerRadius(dpToPx(99));

            boolean isSelected = settingsManager.getResolution().equals(res);
            if (isSelected) {
                pillBg.setColor(Color.parseColor(getAccentColorHex()));
                pill.setTextColor(Color.BLACK);
            } else {
                pillBg.setColor(Color.parseColor("#14FFFFFF"));
                pill.setTextColor(Color.WHITE);
            }
            pill.setBackground(pillBg);

            pill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    settingsManager.setResolution(res);
                    // Update styles
                    for (int j = 0; j < resButtons.length; j++) {
                        GradientDrawable bg = (GradientDrawable) resButtons[j].getBackground();
                        if (bg != null) {
                            if (j == index) {
                                bg.setColor(Color.parseColor(getAccentColorHex()));
                                resButtons[j].setTextColor(Color.BLACK);
                            } else {
                                bg.setColor(Color.parseColor("#14FFFFFF"));
                                resButtons[j].setTextColor(Color.WHITE);
                            }
                        }
                    }
                    if (cameraStreamer != null && cameraStreamer.isStreaming()) {
                        int[] wxh = parseResolution(res);
                        cameraStreamer.startStreaming(currentStreamFormat, wxh[0], wxh[1]);
                    }
                }
            });

            resButtons[i] = pill;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dpToPx(6), 0);
            quickSelectorLayout.addView(pill, lp);
        }

        View qDivider = new View(this);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(dpToPx(1), dpToPx(16));
        divParams.setMargins(dpToPx(6), 0, dpToPx(12), 0);
        qDivider.setBackgroundColor(Color.parseColor("#30FFFFFF"));
        quickSelectorLayout.addView(qDivider, divParams);

        fpsButtons = new TextView[framerates.length];
        for (int i = 0; i < framerates.length; i++) {
            final int index = i;
            final int fps = framerates[i];
            final TextView pill = new TextView(this);
            pill.setText(fps + " FPS");
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            pill.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            pill.setGravity(Gravity.CENTER);

            final GradientDrawable pillBg = new GradientDrawable();
            pillBg.setCornerRadius(dpToPx(99));

            boolean isSelected = settingsManager.getFramerate() == fps;
            if (isSelected) {
                pillBg.setColor(Color.parseColor(getAccentColorHex()));
                pill.setTextColor(Color.BLACK);
            } else {
                pillBg.setColor(Color.parseColor("#14FFFFFF"));
                pill.setTextColor(Color.WHITE);
            }
            pill.setBackground(pillBg);

            pill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    settingsManager.setFramerate(fps);
                    for (int j = 0; j < fpsButtons.length; j++) {
                        GradientDrawable bg = (GradientDrawable) fpsButtons[j].getBackground();
                        if (bg != null) {
                            if (j == index) {
                                bg.setColor(Color.parseColor(getAccentColorHex()));
                                fpsButtons[j].setTextColor(Color.BLACK);
                            } else {
                                bg.setColor(Color.parseColor("#14FFFFFF"));
                                fpsButtons[j].setTextColor(Color.WHITE);
                            }
                        }
                    }
                }
            });

            fpsButtons[i] = pill;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dpToPx(6), 0);
            quickSelectorLayout.addView(pill, lp);
        }

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.setMargins(0, dpToPx(6), 0, dpToPx(14));
        bottomPanel.addView(quickSelectorScroll, scrollParams);

        // 3d. Button Actions Row — Circle Buttons
        buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        bottomPanel.addView(buttonRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Button 1: Flip Cam
        switchCamContainer = createCircleButton("Flip", false, getAccentColorHex());
        TextView switchCamIcon = getCircleButtonIcon(switchCamContainer);
        if (switchCamIcon != null) switchCamIcon.setText("⟳");
        switchCamContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCameraSelectionMenu(v);
            }
        });
        buttonRow.addView(switchCamContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Button 2: Torch Light
        flashContainer = createCircleButton("Light", false, COLOR_ORANGE);
        TextView flashIcon = getCircleButtonIcon(flashContainer);
        if (flashIcon != null) flashIcon.setText("⚡");
        flashContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cameraStreamer != null) {
                    cameraStreamer.toggleTorch();
                    boolean active = cameraStreamer.isTorchEnabled();
                    showToast(active ? "Torch on" : "Torch off");
                    styleCircleButton(flashContainer, active, COLOR_ORANGE);
                }
            }
        });
        buttonRow.addView(flashContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Button 3: FX Filter
        filterContainer = createCircleButton("Filter", false, getAccentColorHex());
        TextView filterIcon = getCircleButtonIcon(filterContainer);
        if (filterIcon != null) filterIcon.setText("◉");
        filterContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleFilter();
            }
        });
        buttonRow.addView(filterContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Button 4: Autofocus Toggle
        focusContainer = createCircleButton("Focus", false, COLOR_GREEN);
        TextView focusIcon = getCircleButtonIcon(focusContainer);
        if (focusIcon != null) focusIcon.setText("◎");
        focusContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleFocusClick();
            }
        });
        buttonRow.addView(focusContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Button 5: Settings
        settingsContainer = createCircleButton("Settings", false, COLOR_LABEL_2);
        TextView settingsIcon = getCircleButtonIcon(settingsContainer);
        if (settingsIcon != null) settingsIcon.setText("⚙");
        settingsContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });
        buttonRow.addView(settingsContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Button 6: Framing Guides
        guideContainer = createCircleButton("Guide", false, getAccentColorHex());
        TextView guideIcon = getCircleButtonIcon(guideContainer);
        if (guideIcon != null) guideIcon.setText("▦");
        guideContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleGuides();
            }
        });
        buttonRow.addView(guideContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Button 7: Preview Mute
        previewContainer = createCircleButton("Preview", false, COLOR_ORANGE);
        TextView previewIcon = getCircleButtonIcon(previewContainer);
        if (previewIcon != null) previewIcon.setText("👁");
        previewContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePreviewMute();
                styleCircleButton(previewContainer, isPreviewMuted, COLOR_ORANGE);
                TextView icon = getCircleButtonIcon(previewContainer);
                if (icon != null) icon.setText(isPreviewMuted ? "⊘" : "👁");
                showToast(isPreviewMuted ? "Preview paused · OBS stream active" : "Preview restored");
            }
        });
        buttonRow.addView(previewContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Store references for updates
        switchCamBtn = switchCamIcon;
        flashBtn = flashIcon;
        filterBtn = filterIcon;
        focusBtn = focusIcon;
        settingsBtn = settingsIcon;
        guideBtn = guideIcon;
        previewBtn = previewIcon;

        // ── Preview Mute Overlay ──
        previewMuteOverlay = new FrameLayout(this);
        previewMuteOverlay.setBackgroundColor(Color.BLACK);
        previewMuteOverlay.setVisibility(View.GONE);

        LinearLayout muteContent = new LinearLayout(this);
        muteContent.setOrientation(LinearLayout.VERTICAL);
        muteContent.setGravity(Gravity.CENTER);

        ImageView muteIcon = new ImageView(this);
        muteIcon.setImageResource(R.mipmap.ic_launcher);
        muteContent.addView(muteIcon, new LinearLayout.LayoutParams(dpToPx(100), dpToPx(100)));

        TextView muteLabel = new TextView(this);
        muteLabel.setText("Preview Paused");
        muteLabel.setTextColor(Color.parseColor(COLOR_LABEL));
        muteLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        muteLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        muteLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams muteLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        muteLabelParams.setMargins(0, dpToPx(12), 0, 0);
        muteContent.addView(muteLabel, muteLabelParams);

        TextView muteSubLabel = new TextView(this);
        muteSubLabel.setText("OBS stream is still active");
        muteSubLabel.setTextColor(Color.parseColor(COLOR_GREEN));
        muteSubLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        muteSubLabel.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        muteSubLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams muteSubParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        muteSubParams.setMargins(0, dpToPx(6), 0, 0);
        muteContent.addView(muteSubLabel, muteSubParams);

        previewMuteOverlay.addView(muteContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));
        rootLayout.addView(previewMuteOverlay, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // ── 4. Permission Request Overlay ──
        permissionOverlay = new FrameLayout(this);
        permissionOverlay.setBackgroundColor(Color.parseColor(COLOR_BG));
        permissionOverlay.setVisibility(View.GONE);

        LinearLayout permContent = new LinearLayout(this);
        permContent.setOrientation(LinearLayout.VERTICAL);
        permContent.setGravity(Gravity.CENTER);
        permContent.setPadding(dpToPx(32), dpToPx(32), dpToPx(32), dpToPx(32));

        TextView permText = new TextView(this);
        permText.setText("Camera permission is required to stream video.");
        permText.setTextColor(Color.parseColor(COLOR_LABEL));
        permText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        permText.setGravity(Gravity.CENTER);
        permText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        permContent.addView(permText);

        TextView grantBtn = new TextView(this);
        grantBtn.setText("Grant Permission");
        grantBtn.setTextColor(Color.WHITE);
        grantBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        grantBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        grantBtn.setPadding(dpToPx(28), dpToPx(14), dpToPx(28), dpToPx(14));
        grantBtn.setGravity(Gravity.CENTER);

        GradientDrawable btnBg2 = new GradientDrawable();
        btnBg2.setColor(Color.parseColor(COLOR_ACCENT));
        btnBg2.setCornerRadius(dpToPx(RADIUS_MD));
        grantBtn.setBackground(btnBg2);
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
        rootLayout.addView(permissionOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // ── Standby Screen — iOS 26 Clean Card ──
        standbyOverlay = new FrameLayout(this);
        standbyOverlay.setBackgroundColor(Color.parseColor(COLOR_BG));

        // Card container
        LinearLayout standbyCard = new LinearLayout(this);
        standbyCard.setOrientation(LinearLayout.VERTICAL);
        standbyCard.setPadding(dpToPx(28), dpToPx(32), dpToPx(28), dpToPx(28));
        standbyCard.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable standbyCardBg = new GradientDrawable();
        standbyCardBg.setColor(Color.parseColor(COLOR_SURFACE));
        standbyCardBg.setCornerRadius(dpToPx(RADIUS_XL));
        standbyCard.setBackground(standbyCardBg);
        standbyCard.setElevation(dpToPx(12));

        FrameLayout.LayoutParams standbyCardParams = new FrameLayout.LayoutParams(
                dpToPx(340),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        standbyCardParams.gravity = Gravity.CENTER;
        standbyOverlay.addView(standbyCard, standbyCardParams);

        // App Name
        TextView standbyTitle = new TextView(this);
        standbyTitle.setText("Studio Cam");
        standbyTitle.setTextColor(Color.parseColor(COLOR_LABEL));
        standbyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        standbyTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams standbyTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        standbyTitleParams.setMargins(0, 0, 0, dpToPx(4));
        standbyCard.addView(standbyTitle, standbyTitleParams);

        // Subtitle
        TextView standbySubtitle = new TextView(this);
        standbySubtitle.setText("Waiting for OBS connection");
        standbySubtitle.setTextColor(Color.parseColor(COLOR_LABEL_2));
        standbySubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        standbySubtitle.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        LinearLayout.LayoutParams standbySubtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        standbySubtitleParams.setMargins(0, 0, 0, dpToPx(24));
        standbyCard.addView(standbySubtitle, standbySubtitleParams);

        // Divider
        View standbyDivider1 = new View(this);
        standbyDivider1.setBackgroundColor(Color.parseColor(COLOR_SEPARATOR));
        LinearLayout.LayoutParams divParams1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
        divParams1.setMargins(0, 0, 0, dpToPx(20));
        standbyCard.addView(standbyDivider1, divParams1);

        // IP list box
        LinearLayout ipBox = new LinearLayout(this);
        ipBox.setOrientation(LinearLayout.VERTICAL);
        ipBox.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));

        GradientDrawable ipBoxBg = new GradientDrawable();
        ipBoxBg.setColor(Color.parseColor(COLOR_BG));
        ipBoxBg.setCornerRadius(dpToPx(RADIUS_MD));
        ipBox.setBackground(ipBoxBg);

        standbyIpTextView = new TextView(this);
        standbyIpTextView.setTextColor(Color.parseColor(COLOR_ACCENT));
        standbyIpTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        standbyIpTextView.setTypeface(Typeface.MONOSPACE);
        standbyIpTextView.setLineSpacing(dpToPx(4), 1f);
        standbyIpTextView.setText(getDeviceIpAddresses() + "\nPort: " + settingsManager.getPort());
        ipBox.addView(standbyIpTextView);

        LinearLayout.LayoutParams ipBoxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ipBoxParams.setMargins(0, 0, 0, dpToPx(20));
        standbyCard.addView(ipBox, ipBoxParams);

        // Divider 2
        View standbyDivider2 = new View(this);
        standbyDivider2.setBackgroundColor(Color.parseColor(COLOR_SEPARATOR));
        LinearLayout.LayoutParams divParams2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
        divParams2.setMargins(0, 0, 0, dpToPx(20));
        standbyCard.addView(standbyDivider2, divParams2);

        // Action buttons
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);

        TextView standbyFlipBtn = new TextView(this);
        standbyFlipBtn.setText("Camera");
        styleModernButton(standbyFlipBtn, false, COLOR_ACCENT);
        standbyFlipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showCameraSelectionMenu(v); }
        });

        TextView standbySetupBtn = new TextView(this);
        standbySetupBtn.setText("Settings");
        styleModernButton(standbySetupBtn, false, COLOR_LABEL_3);
        standbySetupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showSettingsDialog(); }
        });

        LinearLayout.LayoutParams btnParams1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnParams2.setMargins(dpToPx(10), 0, 0, 0);

        actionRow.addView(standbyFlipBtn, btnParams1);
        actionRow.addView(standbySetupBtn, btnParams2);
        standbyCard.addView(actionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootLayout.addView(standbyOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootLayout);

        // Listen for TextureView lifecycle to bind camera preview Surface
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                previewSurface = new Surface(surfaceTexture);
                isSurfaceReady = true;
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                isSurfaceReady = false;
                if (cameraStreamer != null) {
                    cameraStreamer.stopPreview();
                }
                if (previewSurface != null) {
                    previewSurface.release();
                    previewSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });

        // Initialize socket server and camera components
        socketServer = new SocketServer(this);
        cameraStreamer = new CameraStreamer(this, socketServer, this);

        checkAndRequestPermissions();

        rootLayout.post(new Runnable() {
            @Override
            public void run() {
                updateFoldLayout(rootLayout.getWidth(), rootLayout.getHeight());
            }
        });
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
        updateStatusText("Waiting for connection...");
        ipTextView.setText(getWifiIpAddress() + ":" + settingsManager.getPort());
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        telemetryHandler.post(telemetryRunnable);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            socketServer.start();
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
        Log.i(TAG, "Configuration changed, updating network info");
        if (ipTextView != null) {
            ipTextView.setText(getWifiIpAddress() + ":" + settingsManager.getPort());
        }
    }

    // --- StreamListener callbacks ---

    @Override
    public void onStartStream(final String format, final int width, final int height) {
        streamStartTime = System.currentTimeMillis();
        currentStreamFormat = format;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (cameraStreamer != null) {
                    cameraStreamer.startStreaming(format, width, height);
                }
                updateStatusText("Active Stream: " + format.toUpperCase() + " " + width + "x" + height);
                titleTextView.setTextColor(Color.parseColor(COLOR_GREEN));
                
                if (standbyOverlay != null) {
                    standbyOverlay.setVisibility(View.GONE);
                }
                if (bottomPanel != null) {
                    bottomPanel.setVisibility(View.VISIBLE);
                }
                if (guidesOverlay != null && currentGuideMode != 0) {
                    guidesOverlay.setVisibility(View.VISIBLE);
                }
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
                updateStatusText("Disconnected. Waiting...");
                titleTextView.setTextColor(Color.parseColor(COLOR_LABEL));
                
                if (standbyOverlay != null) {
                    standbyOverlay.setVisibility(View.VISIBLE);
                    if (standbyIpTextView != null) {
                        standbyIpTextView.setText(getDeviceIpAddresses() + "\nPort: " + settingsManager.getPort());
                    }
                }
                if (bottomPanel != null) {
                    bottomPanel.setVisibility(View.GONE);
                }
                if (guidesOverlay != null) {
                    guidesOverlay.setVisibility(View.GONE);
                }
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

    // --- PreviewSurfaceProvider callbacks ---

    @Override
    public Surface getPreviewSurface() {
        return previewSurface;
    }

    @Override
    public void updateStatusText(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (statusTextView == null) return;
                
                if (text.startsWith(">> ")) {
                    statusTextView.setText(text.substring(3));
                    statusTextView.setTextColor(Color.parseColor(COLOR_ACCENT));
                    if (statusBadge != null) {
                        GradientDrawable badgeBg = new GradientDrawable();
                        badgeBg.setColor(Color.parseColor("#26007AFF")); // 15% accent
                        badgeBg.setCornerRadius(dpToPx(99));
                        statusBadge.setBackground(badgeBg);
                    }
                    if (statusLed != null) {
                        GradientDrawable ledBg = new GradientDrawable();
                        ledBg.setShape(GradientDrawable.OVAL);
                        ledBg.setColor(Color.parseColor(COLOR_ACCENT));
                        statusLed.setBackground(ledBg);
                    }
                    return;
                }

                if (socketServer != null && socketServer.isStreaming()) {
                    statusTextView.setText("Live");
                    statusTextView.setTextColor(Color.parseColor(COLOR_GREEN));
                    if (statusBadge != null) {
                        GradientDrawable badgeBg = new GradientDrawable();
                        badgeBg.setColor(Color.parseColor("#2630D158")); // 15% green
                        badgeBg.setCornerRadius(dpToPx(99));
                        statusBadge.setBackground(badgeBg);
                    }
                    if (statusLed != null) {
                        GradientDrawable ledBg = new GradientDrawable();
                        ledBg.setShape(GradientDrawable.OVAL);
                        ledBg.setColor(Color.parseColor(COLOR_GREEN));
                        statusLed.setBackground(ledBg);
                    }
                    if (titleTextView != null) {
                        titleTextView.setTextColor(Color.parseColor(COLOR_GREEN));
                    }
                } else {
                    statusTextView.setText("Standby");
                    statusTextView.setTextColor(Color.parseColor(COLOR_LABEL_2));
                    if (statusBadge != null) {
                        GradientDrawable badgeBg = new GradientDrawable();
                        badgeBg.setColor(Color.parseColor("#14FFFFFF")); // 8% white
                        badgeBg.setCornerRadius(dpToPx(99));
                        statusBadge.setBackground(badgeBg);
                    }
                    if (statusLed != null) {
                        GradientDrawable ledBg = new GradientDrawable();
                        ledBg.setShape(GradientDrawable.OVAL);
                        ledBg.setColor(Color.parseColor(COLOR_LABEL_2));
                        statusLed.setBackground(ledBg);
                    }
                    if (titleTextView != null) {
                        titleTextView.setTextColor(Color.parseColor(COLOR_LABEL));
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

    // --- Preview Mute ---

    private void togglePreviewMute() {
        isPreviewMuted = !isPreviewMuted;
        if (previewMuteOverlay != null) {
            previewMuteOverlay.setVisibility(isPreviewMuted ? View.VISIBLE : View.GONE);
        }
        if (textureView != null) {
            textureView.setAlpha(isPreviewMuted ? 0f : 1f);
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
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            
            // First pass: Prioritize Wi-Fi interfaces (e.g., wlan0, ap0, etc.)
            for (NetworkInterface intf : interfaces) {
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("ap") || name.contains("p2p")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            
            // Second pass: Fallback to any active non-loopback IPv4 interface
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String sAddr = addr.getHostAddress();
                        if (!sAddr.startsWith("192.168.56.") && !sAddr.startsWith("192.168.99.")) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get network interface IP address", ex);
        }
        return "127.0.0.1";
    }

    private String getDeviceIpAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            
            // First pass: Find and display Wi-Fi IPs
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
            
            // Second pass: Find and display other interface IPs
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

    // ── Settings Dialog — iOS 26 Style ──
    private void showSettingsDialog() {
        // Create full screen overlay
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#CC000000")); // 80% black
        overlay.setClickable(true);
        overlay.setFocusable(true);

        // Clean card container
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(24));
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor(COLOR_SURFACE));
        cardBg.setCornerRadius(dpToPx(RADIUS_XL));
        card.setBackground(cardBg);

        scrollView.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dpToPx(340),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.gravity = Gravity.CENTER;
        overlay.addView(scrollView, cardParams);

        // Drag indicator pill
        View pill = new View(this);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#33FFFFFF"));
        pillBg.setCornerRadius(dpToPx(3));
        pill.setBackground(pillBg);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(dpToPx(36), dpToPx(5));
        pillParams.setMargins(0, 0, 0, dpToPx(16));
        card.addView(pill, pillParams);

        // Title
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.parseColor(COLOR_LABEL));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.setMargins(0, 0, 0, dpToPx(20));
        card.addView(title, titleParams);

        // ── Resolution Section ──
        addSectionLabel(card, "RESOLUTION");

        android.widget.HorizontalScrollView resScroll = new android.widget.HorizontalScrollView(this);
        resScroll.setHorizontalScrollBarEnabled(false);
        resScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        
        LinearLayout resRow = new LinearLayout(this);
        resRow.setOrientation(LinearLayout.HORIZONTAL);
        resRow.setGravity(Gravity.CENTER_VERTICAL);
        resScroll.addView(resRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams resScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        resScrollParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(resScroll, resScrollParams);

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
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
            btn.setGravity(Gravity.CENTER);
            
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) chipParams.setMargins(dpToPx(8), 0, 0, 0);
            
            updateChipState(btn, index == selectedResIdx[0]);
            
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedResIdx[0] = index;
                    for (int j = 0; j < resOptions.length; j++) {
                        updateChipState(resButtons[j], j == index);
                    }
                }
            });
            
            resButtons[i] = btn;
            resRow.addView(btn, chipParams);
        }

        // ── Port Section ──
        addSectionLabel(card, "SERVER PORT");

        final android.widget.EditText portInput = new android.widget.EditText(this);
        portInput.setText(String.valueOf(settingsManager.getPort()));
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setTextColor(Color.parseColor(COLOR_ACCENT));
        portInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        portInput.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        portInput.setTypeface(Typeface.MONOSPACE);
        
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor(COLOR_BG));
        inputBg.setCornerRadius(dpToPx(RADIUS_SM));
        portInput.setBackground(inputBg);

        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        portParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(portInput, portParams);

        // ── Bitrate Section ──
        addSectionLabel(card, "VIDEO BITRATE");

        LinearLayout bitrateRow = new LinearLayout(this);
        bitrateRow.setOrientation(LinearLayout.HORIZONTAL);
        bitrateRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bitrateRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
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
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setPadding(0, dpToPx(8), 0, dpToPx(8));
            btn.setGravity(Gravity.CENTER);
            
            LinearLayout.LayoutParams chipBtnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            if (i > 0) chipBtnParams.setMargins(dpToPx(6), 0, 0, 0);
            
            updateChipState(btn, index == selectedBitrateIdx[0]);
            
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedBitrateIdx[0] = index;
                    for (int j = 0; j < 4; j++) {
                        updateChipState(bitrateButtons[j], j == index);
                    }
                }
            });
            
            bitrateButtons[i] = btn;
            bitrateRow.addView(btn, chipBtnParams);
        }

        // ── Framerate Section ──
        addSectionLabel(card, "TARGET FRAMERATE");

        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fpsRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
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
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setPadding(0, dpToPx(8), 0, dpToPx(8));
            btn.setGravity(Gravity.CENTER);
            
            LinearLayout.LayoutParams fpsBtnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            if (i > 0) fpsBtnParams.setMargins(dpToPx(8), 0, 0, 0);
            
            updateChipState(btn, index == selectedFpsIdx[0]);
            
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedFpsIdx[0] = index;
                    for (int j = 0; j < 2; j++) {
                        updateChipState(fpsButtons[j], j == index);
                    }
                }
            });
            
            fpsButtons[i] = btn;
            fpsRow.addView(btn, fpsBtnParams);
        }

        // ── Toggle Preferences ──
        addSectionLabel(card, "PREFERENCES");

        // Keep Screen On
        final android.widget.Switch screenSwitch = addToggleRow(card, "Keep Screen On");
        screenSwitch.setChecked(settingsManager.getKeepScreenOn());

        // Flip Horizontal
        final android.widget.Switch flipHorizontalSwitch = addToggleRow(card, "Flip Horizontal");
        flipHorizontalSwitch.setChecked(settingsManager.getFlipHorizontal());

        // Flip Vertical
        final android.widget.Switch flipVerticalSwitch = addToggleRow(card, "Flip Vertical");
        flipVerticalSwitch.setChecked(settingsManager.getFlipVertical());

        // Face Auto-Focus
        final android.widget.Switch faceAfSwitch = addToggleRow(card, "Face Auto-Focus");
        faceAfSwitch.setChecked(settingsManager.getFaceAutoFocus());

        // Spacer before actions
        View actionSpacer = new View(this);
        card.addView(actionSpacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(16)));

        // ── Action Row ──
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        card.addView(actionRow);

        // Cancel button
        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextColor(Color.parseColor(COLOR_LABEL_2));
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cancelBtn.setPadding(0, dpToPx(14), 0, dpToPx(14));
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rootLayout.removeView(overlay);
            }
        });
        actionRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Save button
        TextView saveBtn = new TextView(this);
        saveBtn.setText("Save");
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        saveBtn.setPadding(0, dpToPx(14), 0, dpToPx(14));
        saveBtn.setGravity(Gravity.CENTER);
        
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(Color.parseColor(COLOR_ACCENT));
        saveBg.setCornerRadius(dpToPx(RADIUS_MD));
        saveBtn.setBackground(saveBg);
        
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f
        );
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
                String selectedRes = resOptions[selectedResIdx[0]];
                boolean flipHorizontal = flipHorizontalSwitch.isChecked();
                boolean flipVertical = flipVerticalSwitch.isChecked();
                boolean faceAf = faceAfSwitch.isChecked();

                // Save
                settingsManager.setPort(newPort);
                settingsManager.setBitrate(selectedBitrate);
                settingsManager.setFramerate(selectedFps);
                settingsManager.setKeepScreenOn(keepScreenOn);
                settingsManager.setResolution(selectedRes);
                settingsManager.setFlipHorizontal(flipHorizontal);
                settingsManager.setFlipVertical(flipVertical);
                settingsManager.setFaceAutoFocus(faceAf);

                if (cameraStreamer != null) {
                    cameraStreamer.setFaceAutoFocusEnabled(faceAf);
                }

                // Apply screen flag
                if (keepScreenOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }

                // Update UI text displays
                ipTextView.setText(getWifiIpAddress() + ":" + newPort);

                int[] wxh = parseResolution(selectedRes);
                cameraStreamer.setTargetResolution(wxh[0], wxh[1]);
                if (standbyIpTextView != null) {
                    standbyIpTextView.setText(getDeviceIpAddresses() + "\nPort: " + newPort);
                }
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);

                // Restart server if port changed
                if (newPort != oldPort) {
                    socketServer.stop();
                    socketServer.setPort(newPort);
                    socketServer.start();
                    updateStatusText("Server port changed to " + newPort);
                }

                rootLayout.removeView(overlay);
            }
        });
        actionRow.addView(saveBtn, saveParams);

        rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    // ── Settings Dialog Helpers ──
    
    private void addSectionLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.parseColor(COLOR_LABEL_3));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dpToPx(4), 0, dpToPx(2));
        parent.addView(label, params);
    }

    private android.widget.Switch addToggleRow(LinearLayout parent, String labelText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, dpToPx(2), 0, dpToPx(2));
        parent.addView(row, rowParams);

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Color.parseColor(COLOR_LABEL));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch toggle = new android.widget.Switch(this);
        row.addView(toggle);
        
        return toggle;
    }

    private void updateChipState(TextView btn, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(99)); // Capsule
        if (selected) {
            bg.setColor(Color.WHITE);
            btn.setTextColor(Color.BLACK);
            btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        } else {
            bg.setColor(Color.parseColor("#14FFFFFF")); // 8% white
            btn.setTextColor(Color.parseColor(COLOR_LABEL_2));
            btn.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        btn.setBackground(bg);
    }

    private void updateBitrateButtonState(TextView btn, boolean selected) {
        updateChipState(btn, selected);
    }

    private void adjustAspectRatio(final int videoWidth, final int videoHeight) {
        textureView.post(new Runnable() {
            @Override
            public void run() {
                int viewWidth = textureView.getWidth();
                int viewHeight = textureView.getHeight();
                if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) {
                    return;
                }

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        android.graphics.Matrix matrix = new android.graphics.Matrix();

        int sensorOrientation = 90; // Default fallback orientation
        int activeFacing = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK;
        if (cameraStreamer != null) {
            activeFacing = cameraStreamer.getLensFacing();
        }
        try {
            android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList.length > 0) {
                String targetCameraId = cameraIdList[0];
                for (String id : cameraIdList) {
                    android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == activeFacing) {
                        targetCameraId = id;
                        break;
                    }
                }
                android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
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

        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        
        boolean isNativeLandscape;
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            isNativeLandscape = screenWidth > screenHeight;
        } else {
            isNativeLandscape = screenWidth < screenHeight;
        }

        int effectiveSensorOrientation = sensorOrientation;
        if (isNativeLandscape && (sensorOrientation == 90 || sensorOrientation == 270)) {
            effectiveSensorOrientation = 0;
        }

        int relativeRotation;
        if (activeFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            relativeRotation = (effectiveSensorOrientation + deviceRotationDegrees) % 360;
        } else {
            relativeRotation = (effectiveSensorOrientation - deviceRotationDegrees + 360) % 360;
        }

        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        matrix.postTranslate(-centerX, -centerY);

        matrix.postScale((float) videoWidth / viewWidth, (float) videoHeight / viewHeight);

        if (relativeRotation != 0) {
            matrix.postRotate(relativeRotation);
        }

        boolean swapped = (relativeRotation == 90 || relativeRotation == 270);
        float bufferWidth = swapped ? videoHeight : videoWidth;
        float bufferHeight = swapped ? videoWidth : videoHeight;

        float scaleWidth = (float) viewWidth / bufferWidth;
        float scaleHeight = (float) viewHeight / bufferHeight;
        float scale = Math.max(scaleWidth, scaleHeight);

        matrix.postScale(scale, scale);

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
            matrix.postScale(-1, 1);
        }
        if (flipVertical) {
            matrix.postScale(1, -1);
        }

        matrix.postTranslate(centerX, centerY);

        textureView.setTransform(matrix);
            }
        });
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
                    tempVal.setText(String.format(java.util.Locale.US, "%.1f°C", currentBatteryTemp));
                    if (currentBatteryTemp > 42.0f) {
                        tempVal.setTextColor(Color.parseColor(COLOR_RED));
                    } else if (currentBatteryTemp > 38.0f) {
                        tempVal.setTextColor(Color.parseColor(COLOR_ORANGE));
                    } else {
                        tempVal.setTextColor(Color.parseColor(COLOR_GREEN));
                    }
                }
                if (batteryVal != null) {
                    batteryVal.setText(currentBatteryPercent + "%");
                    if (currentBatteryPercent <= 15) {
                        batteryVal.setTextColor(Color.parseColor(COLOR_RED));
                    } else if (currentBatteryPercent <= 30) {
                        batteryVal.setTextColor(Color.parseColor(COLOR_ORANGE));
                    } else {
                        batteryVal.setTextColor(Color.parseColor(COLOR_GREEN));
                    }
                }

                if (focusVal != null) {
                    if (cameraStreamer != null && cameraStreamer.isAutofocusLocked()) {
                        focusVal.setText("LOCKED");
                        focusVal.setTextColor(Color.parseColor(COLOR_ORANGE));
                    } else {
                        focusVal.setText("AUTO-C");
                        focusVal.setTextColor(Color.parseColor(COLOR_TEAL));
                    }
                }
                if (filterVal != null) {
                    filterVal.setText(filterLabels[currentFilterIndex]);
                }

                // If streaming, update live status text
                if (socketServer != null && socketServer.isStreaming() && statusTextView != null) {
                    String currentText = statusTextView.getText().toString();
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
                        statusTextView.setText("Live " + duration);
                        statusTextView.setTextColor(Color.parseColor(COLOR_GREEN));
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
                        statusLed.setAlpha(blinkState ? 1.0f : 0.3f);
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
        showToast("Filter: " + filterLabels[currentFilterIndex]);
        
        // Re-style filter button to indicate if active
        styleCircleButton(filterContainer, currentFilterIndex > 0, getAccentColorHex());
    }

    private String getAccentColorHex() {
        return accentColors[selectedAccentIndex];
    }

    private void applyAccentColor(int index) {
        String colorHex = accentColors[index];
        COLOR_ACCENT = colorHex; // Set instance variable for dialogs etc.
        int color = Color.parseColor(colorHex);
        
        if (txRateVal != null) txRateVal.setTextColor(color);
        if (txTotalVal != null) txTotalVal.setTextColor(color);
        if (focusVal != null) focusVal.setTextColor(color);
        
        // Update quick resolution selector capsule highlights
        if (resButtons != null && resolutions != null) {
            for (int i = 0; i < resButtons.length; i++) {
                boolean isSelected = settingsManager.getResolution().equals(resolutions[i]);
                GradientDrawable bg = (GradientDrawable) resButtons[i].getBackground();
                if (bg != null) {
                    if (isSelected) {
                        bg.setColor(color);
                        resButtons[i].setTextColor(Color.BLACK);
                    } else {
                        bg.setColor(Color.parseColor("#14FFFFFF"));
                        resButtons[i].setTextColor(Color.WHITE);
                    }
                }
            }
        }
        
        // Update quick framerate selector capsule highlights
        if (fpsButtons != null && framerates != null) {
            for (int i = 0; i < fpsButtons.length; i++) {
                boolean isSelected = settingsManager.getFramerate() == framerates[i];
                GradientDrawable bg = (GradientDrawable) fpsButtons[i].getBackground();
                if (bg != null) {
                    if (isSelected) {
                        bg.setColor(color);
                        fpsButtons[i].setTextColor(Color.BLACK);
                    } else {
                        bg.setColor(Color.parseColor("#14FFFFFF"));
                        fpsButtons[i].setTextColor(Color.WHITE);
                    }
                }
            }
        }
        
        // Update active highlight color for active containers
        if (switchCamContainer != null) styleCircleButton(switchCamContainer, false, colorHex);
        if (filterContainer != null) styleCircleButton(filterContainer, currentFilterIndex > 0, colorHex);
        if (guideContainer != null) styleCircleButton(guideContainer, currentGuideMode > 0, colorHex);
        if (focusContainer != null) styleCircleButton(focusContainer, cameraStreamer != null && cameraStreamer.isAutofocusLocked(), COLOR_GREEN);
    }

    private String resLabels(String res) {
        if ("3840x2160".equals(res)) return "4K";
        if ("2560x1440".equals(res)) return "2K";
        if ("1920x1080".equals(res)) return "1080p";
        if ("1280x720".equals(res)) return "720p";
        if ("640x480".equals(res)) return "480p";
        return res;
    }

    private void handleFocusClick() {
        if (cameraStreamer == null) return;
        cameraStreamer.toggleAutofocusMode();
        boolean locked = cameraStreamer.isAutofocusLocked();
        updateTelemetryDisplay();
        showToast(locked ? "Focus locked" : "Continuous AF active");
    }

    private void showToast(String message) {
        updateStatusText(">> " + message);
        toastHandler.removeCallbacks(revertStatusRunnable);
        toastHandler.postDelayed(revertStatusRunnable, 2000);
    }

    private void enterDimMode() {
        if (isDimMode) return;
        isDimMode = true;
        
        // 1. Lower screen brightness
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
        
        // 2. Create dim overlay
        if (dimOverlay == null) {
            dimOverlay = new FrameLayout(this);
            dimOverlay.setBackgroundColor(Color.parseColor("#FC000000")); // 99% black
            dimOverlay.setClickable(true);
            dimOverlay.setFocusable(true);
            dimOverlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exitDimMode();
                }
            });
            
            dimTextContainer = new LinearLayout(this);
            dimTextContainer.setOrientation(LinearLayout.VERTICAL);
            dimTextContainer.setGravity(Gravity.CENTER);
            
            TextView activeText = new TextView(this);
            activeText.setText("Streaming Active");
            activeText.setTextColor(Color.parseColor("#8030D158")); // 50% green
            activeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            activeText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            activeText.setGravity(Gravity.CENTER);
            dimTextContainer.addView(activeText);
            
            TextView wakeText = new TextView(this);
            wakeText.setText("Tap anywhere to wake");
            wakeText.setTextColor(Color.parseColor(COLOR_LABEL_3));
            wakeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            wakeText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            wakeText.setGravity(Gravity.CENTER);
            
            LinearLayout.LayoutParams wakeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            wakeParams.setMargins(0, dpToPx(8), 0, 0);
            dimTextContainer.addView(wakeText, wakeParams);
            
            FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
            );
            dimOverlay.addView(dimTextContainer, containerParams);
        }
        
        if (dimOverlay.getParent() != null) {
            ((ViewGroup) dimOverlay.getParent()).removeView(dimOverlay);
        }
        
        rootLayout.addView(dimOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        
        dimTextContainer.setTranslationX(0);
        dimTextContainer.setTranslationY(0);
        dimHandler.removeCallbacks(dimRunnable);
        dimHandler.postDelayed(dimRunnable, 10000);
        
        showToast("Dim mode active");
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
        showToast("Screen restored");
    }

    private void updateFoldLayout(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (width == lastLayoutWidth && height == lastLayoutHeight) return;
        
        lastLayoutWidth = width;
        lastLayoutHeight = height;
        
        if (textureView == null || topHud == null || bottomPanel == null) return;
        
        double aspect = (double) width / height;
        
        FrameLayout.LayoutParams textureParams = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        FrameLayout.LayoutParams topParams = (FrameLayout.LayoutParams) topHud.getLayoutParams();
        FrameLayout.LayoutParams bottomParams = (FrameLayout.LayoutParams) bottomPanel.getLayoutParams();
        
        if (telemetryLayout == null || buttonRow == null) return;
        
        if (aspect < 0.75) {
            // 1. Tall Mode (Cover Screen / Standard Portrait)
            textureParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            textureParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            textureParams.gravity = Gravity.CENTER;
            
            topParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            topParams.gravity = Gravity.TOP;
            
            bottomParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            bottomParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            bottomParams.gravity = Gravity.BOTTOM;
            bottomParams.setMargins(dpToPx(8), 0, dpToPx(8), 0);
            
            setControlsHorizontal(telemetryLayout, buttonRow);
            
        } else if (aspect >= 0.75 && aspect <= 1.33) {
            // 2. Split Mode (Unfolded Main Screen / Flex Mode)
            int textureHeight = (int) (height * 0.55);
            textureParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            textureParams.height = textureHeight;
            textureParams.gravity = Gravity.TOP;
            
            topParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            topParams.gravity = Gravity.TOP;
            
            bottomParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            bottomParams.height = height - textureHeight - dpToPx(16);
            bottomParams.gravity = Gravity.BOTTOM;
            bottomParams.setMargins(dpToPx(8), 0, dpToPx(8), 0);
            
            setControlsHorizontal(telemetryLayout, buttonRow);
            
        } else {
            // 3. Wide Mode (Landscape or Unfolded Landscape)
            int textureWidth = (int) (width * 0.60);
            textureParams.width = textureWidth;
            textureParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            textureParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            
            topParams.width = textureWidth;
            topParams.gravity = Gravity.TOP | Gravity.START;
            
            bottomParams.width = width - textureWidth - dpToPx(24);
            bottomParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            bottomParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            bottomParams.setMargins(0, dpToPx(16), dpToPx(16), dpToPx(16));
            
            telemetryLayout.setOrientation(LinearLayout.VERTICAL);
            buttonRow.setOrientation(LinearLayout.VERTICAL);
            
            for (int i = 0; i < telemetryLayout.getChildCount(); i++) {
                View child = telemetryLayout.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.weight = 0f;
                lp.setMargins(0, 0, 0, dpToPx(6));
                child.setLayoutParams(lp);
            }
            
            for (int i = 0; i < buttonRow.getChildCount(); i++) {
                View child = buttonRow.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.weight = 0f;
                lp.setMargins(0, 0, 0, dpToPx(6));
                child.setLayoutParams(lp);
            }
        }
        
        textureView.setLayoutParams(textureParams);
        if (guidesOverlay != null) {
            guidesOverlay.setLayoutParams(textureParams);
        }
        topHud.setLayoutParams(topParams);
        bottomPanel.setLayoutParams(bottomParams);
        
        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
    }

    private void setControlsHorizontal(LinearLayout telemetryLayout, LinearLayout buttonRow) {
        telemetryLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        
        for (int i = 0; i < telemetryLayout.getChildCount(); i++) {
            View child = telemetryLayout.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.weight = 1f;
            if (i > 0) {
                lp.setMargins(dpToPx(4), 0, 0, 0);
            } else {
                lp.setMargins(0, 0, 0, 0);
            }
            child.setLayoutParams(lp);
        }
        
        for (int i = 0; i < buttonRow.getChildCount(); i++) {
            View child = buttonRow.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.weight = 1f;
            lp.setMargins(0, 0, 0, 0);
            child.setLayoutParams(lp);
        }
    }

    private void toggleUiVisibility() {
        isUiHidden = !isUiHidden;
        if (topHud != null) topHud.setVisibility(isUiHidden ? View.GONE : View.VISIBLE);
        if (bottomPanel != null) bottomPanel.setVisibility(isUiHidden ? View.GONE : View.VISIBLE);
        showToast(isUiHidden ? "Clean preview (double-tap to restore)" : "UI restored");
    }

    private void cycleGuides() {
        currentGuideMode = (currentGuideMode + 1) % 4;
        if (guidesOverlay != null) {
            guidesOverlay.invalidate();
        }
        String modeText = "None";
        if (currentGuideMode == 1) modeText = "Rule of thirds";
        else if (currentGuideMode == 2) modeText = "Crosshair";
        else if (currentGuideMode == 3) modeText = "TikTok crop";
        showToast("Guide: " + modeText);
    }

    private void showCameraSelectionMenu(View anchor) {
        if (cameraStreamer == null) return;
        try {
            android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            final String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList.length == 0) {
                showToast("No cameras available");
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
                        showToast("Camera: " + faceLabel + " (ID " + selectedId + ")");
                    } catch (Exception e) {
                        showToast("Camera switched");
                    }
                    return true;
                }
            });
            popup.show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show camera selection menu", e);
            cameraStreamer.switchCamera();
            showToast("Camera switched");
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
                        showToast("Face AF " + (faceAf ? "on" : "off"));
                    } else if (key.equals("keep_screen_on")) {
                        boolean keepOn = Boolean.parseBoolean(val);
                        settingsManager.setKeepScreenOn(keepOn);
                        if (keepOn) {
                            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        } else {
                            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        }
                        showToast("Keep screen " + (keepOn ? "on" : "off"));
                    } else if (key.equals("flip_horizontal")) {
                        boolean flip = Boolean.parseBoolean(val);
                        settingsManager.setFlipHorizontal(flip);
                        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
                        showToast("Flip H " + (flip ? "on" : "off"));
                    } else if (key.equals("flip_vertical")) {
                        boolean flip = Boolean.parseBoolean(val);
                        settingsManager.setFlipVertical(flip);
                        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
                        showToast("Flip V " + (flip ? "on" : "off"));
                    } else if (key.equals("resolution")) {
                        settingsManager.setResolution(val);
                        int[] wxh = parseResolution(val);
                        if (cameraStreamer != null) {
                            cameraStreamer.setTargetResolution(wxh[0], wxh[1]);
                        }
                        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
                        showToast("Resolution: " + val);
                    } else if (key.equals("bitrate")) {
                        int bitrate = Integer.parseInt(val);
                        settingsManager.setBitrate(bitrate);
                        showToast("Bitrate: " + (bitrate / 1000) + " kbps");
                    } else if (key.equals("framerate")) {
                        int fps = Integer.parseInt(val);
                        settingsManager.setFramerate(fps);
                        showToast("FPS: " + fps);
                    } else if (key.equals("camera")) {
                        if (cameraStreamer != null) {
                            cameraStreamer.selectCamera(val);
                            showToast("Camera: " + val);
                        }
                    } else if (key.equals("trigger_af")) {
                        if (cameraStreamer != null) {
                            cameraStreamer.triggerAutofocus();
                            showToast("AF triggered");
                        }
                    } else if (key.equals("toggle_af_mode")) {
                        if (cameraStreamer != null) {
                            cameraStreamer.toggleAutofocusMode();
                            boolean locked = cameraStreamer.isAutofocusLocked();
                            showToast(locked ? "Focus locked" : "Continuous AF");
                        }
                    } else if (key.equals("port")) {
                        int newPort = Integer.parseInt(val);
                        int oldPort = settingsManager.getPort();
                        if (newPort != oldPort) {
                            settingsManager.setPort(newPort);
                            socketServer.stop();
                            socketServer.setPort(newPort);
                            socketServer.start();
                            showToast("Port: " + newPort);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating setting from web: key=" + key + ", val=" + val, e);
                }
            }
        });
    }
}
