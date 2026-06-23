package com.webcamclone;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
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
    private String COLOR_ACCENT = "#64D2FF"; // Dynamic Accent
    private static final String COLOR_GREEN        = "#30D158";
    private static final String COLOR_ORANGE       = "#FF9F0A";
    private static final String COLOR_RED          = "#FF453A";
    private static final int RADIUS_SM = 10;
    private static final int RADIUS_MD = 16;
    private static final int RADIUS_LG = 24;
    private static final int RADIUS_XL = 28;

    private final String[] accentColors = {
        "#64D2FF", // Teal
        "#FF375F", // Pink
        "#5E5CE6", // Indigo/Purple
        "#FF9F0A", // Orange
        "#30D158"  // Green
    };
    private int selectedAccentIndex = 0;

    private final String[] resolutions = {"640x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"};
    private final int[] framerates = {15, 24, 30, 60};
    private TextView guideBtn;
    private TextView settingsBtn;

    private TextureView textureView;
    private TextView ipTextView;
    private TextView statusTextView;
    private TextView titleTextView;
    private LinearLayout statusBadge;
    private View statusLed;
    
    private FrameLayout permissionOverlay;
    private LinearLayout topHud;
    private LinearLayout bottomPanel;
    private View guidesOverlay;
    private int currentGuideMode = 0; // 0 = None, 1 = Grid, 2 = Crosshair, 3 = TikTok

    private int lastLayoutWidth = 0;
    private int lastLayoutHeight = 0;
    private long lastTapTime = 0;
    private boolean isUiHidden = false;
    private long streamStartTime = 0;

    // Indicators & Dials
    private LinearLayout resFpsPill;
    private TextView resPillText;
    private TextView fpsPillText;
    private HorizontalScrollView filterDialScroll;
    private LinearLayout filterDialLayout;
    private TextView[] filterViews;
    private View startSpacer;
    private View endSpacer;

    // Shutter Row Buttons
    private FrameLayout shutterBtn;
    private View outerRing;
    private View innerFill;
    private LinearLayout flashContainer;
    private LinearLayout switchCamContainer;
    private LinearLayout settingsContainer;
    private LinearLayout guideContainer;
    private LinearLayout previewContainer;

    private String currentStreamFormat = "avc";
    private int currentVideoWidth = 1280;
    private int currentVideoHeight = 720;

    public CameraStreamer cameraStreamer;
    private SocketServer socketServer;
    private Surface previewSurface;
    private boolean isSurfaceReady = false;
    private SettingsManager settingsManager;
    private FrameLayout rootLayout;

    // Preview Mute
    private boolean isPreviewMuted = false;
    private FrameLayout previewMuteOverlay;

    // Dim Mode
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

    // Telemetry & Stats
    private TextView telemetryTextView;
    private float currentBatteryTemp = 0.0f;
    private int currentBatteryPercent = 100;
    private long lastTxBytes = 0;
    private long lastSpeedCheckTime = 0;
    private String currentTxSpeedText = "0 Kb/s";
    private String totalTxText = "0.0 MB";
    private boolean blinkState = false;
    private ValueAnimator shutterAnimator;

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

    private final String[] filterLabels = {
        "NORMAL", "BEAUTY", "PORTRAIT", "COMIC", "NEON", "GLITCH"
    };
    private final int[] filterEffects = {
        0, 1, 2, 3, 4, 5
    };
    private int currentFilterIndex = 0;

    // ── Helper UI builders ──
    private LinearLayout createCircleButton(String labelText, boolean isActive, String accentColor) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dpToPx(2), dpToPx(4), dpToPx(2), dpToPx(4));

        TextView iconArea = new TextView(this);
        iconArea.setGravity(Gravity.CENTER);
        iconArea.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);

        if (isActive) {
            circleBg.setColor(Color.parseColor(accentColor));
            iconArea.setTextColor(Color.BLACK);
        } else {
            circleBg.setColor(Color.parseColor("#4D000000")); // 30% black
            iconArea.setTextColor(Color.WHITE);
        }
        iconArea.setBackground(circleBg);

        int iconSize = dpToPx(50);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        container.addView(iconArea, iconParams);

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
            circleBg.setColor(Color.parseColor(accentColor));
            if (iconArea instanceof TextView) {
                ((TextView) iconArea).setTextColor(Color.BLACK);
            }
            label.setTextColor(Color.parseColor(accentColor));
            circleBg.setStroke(dpToPx(1.5f), Color.parseColor(accentColor));
        } else {
            circleBg.setColor(Color.parseColor("#4D000000"));
            if (iconArea instanceof TextView) {
                ((TextView) iconArea).setTextColor(Color.WHITE);
            }
            label.setTextColor(Color.parseColor(COLOR_LABEL_2));
            circleBg.setStroke(dpToPx(1.5f), Color.parseColor("#33FFFFFF"));
        }
        iconArea.setBackground(circleBg);
    }

    private TextView getCircleButtonIcon(LinearLayout container) {
        if (container != null && container.getChildCount() > 0 && container.getChildAt(0) instanceof TextView) {
            return (TextView) container.getChildAt(0);
        }
        return null;
    }

    private void styleModernButton(TextView btn, boolean isActive, String accentColor) {
        btn.setTextColor(isActive ? Color.BLACK : Color.parseColor(COLOR_LABEL));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(RADIUS_SM));
        if (isActive) {
            bg.setColor(Color.WHITE);
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
        selectedAccentIndex = settingsManager.getAccentColorIndex();
        COLOR_ACCENT = accentColors[selectedAccentIndex];

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        applyScreenOnPolicy();

        // Landscape-only window configuration
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // ── Root Frame Layout ──
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor(COLOR_BG));
        rootLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                updateFoldLayout(right - left, bottom - top);
            }
        });

        // ── 1. Camera Viewfinder ──
        textureView = new TextureView(this);
        rootLayout.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        textureView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);
            }
        });

        // ── Guides Overlay ──
        guidesOverlay = new View(this) {
            private final android.graphics.Paint paint = new android.graphics.Paint();
            {
                paint.setColor(Color.parseColor("#40FFFFFF")); // 25% white
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

                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                int sensorOrientation = 90;
                int activeFacing = CameraCharacteristics.LENS_FACING_BACK;
                if (cameraStreamer != null) {
                    activeFacing = cameraStreamer.getLensFacing();
                }
                try {
                    CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                    String[] cameraIdList = manager.getCameraIdList();
                    if (cameraIdList.length > 0) {
                        String targetCameraId = cameraIdList[0];
                        for (String id : cameraIdList) {
                            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if (facing != null && facing == activeFacing) {
                                targetCameraId = id;
                                break;
                            }
                        }
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
                        Integer orient = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        if (orient != null) sensorOrientation = orient;
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
                if (activeFacing == CameraCharacteristics.LENS_FACING_FRONT) {
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
                    dimPaint.setColor(Color.parseColor("#80000000"));
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
        rootLayout.addView(guidesOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Single and Double Tap listeners on viewfinder
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

        // ── 2. Top HUD Overlay ──
        topHud = new LinearLayout(this);
        topHud.setOrientation(LinearLayout.HORIZONTAL);
        topHud.setGravity(Gravity.CENTER_VERTICAL);
        topHud.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(10));
        
        GradientDrawable topBg = new GradientDrawable();
        topBg.setColor(Color.parseColor("#73000000")); // 45% black translucent
        topHud.setBackground(topBg);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.gravity = Gravity.TOP;
        rootLayout.addView(topHud, topParams);

        // 2a. Title
        titleTextView = new TextView(this);
        titleTextView.setText("Studio Cam");
        titleTextView.setTextColor(Color.parseColor(COLOR_LABEL));
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleTextView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topHud.addView(titleTextView);

        View topSpacer1 = new View(this);
        topHud.addView(topSpacer1, new LinearLayout.LayoutParams(dpToPx(16), 1));

        // 2b. Status Pill (Tap to toggle listener)
        statusBadge = new LinearLayout(this);
        statusBadge.setOrientation(LinearLayout.HORIZONTAL);
        statusBadge.setGravity(Gravity.CENTER_VERTICAL);
        statusBadge.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#4D000000"));
        badgeBg.setCornerRadius(dpToPx(99));
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
        statusTextView.setText("OFFLINE");
        statusTextView.setTextColor(Color.parseColor(COLOR_LABEL_2));
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusTextView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusBadge.addView(statusTextView);
        topHud.addView(statusBadge);

        statusBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                toggleServerState();
            }
        });

        View topSpacer2 = new View(this);
        topHud.addView(topSpacer2, new LinearLayout.LayoutParams(dpToPx(8), 1));

        // 2c. IP Pill
        LinearLayout ipPill = new LinearLayout(this);
        ipPill.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        GradientDrawable ipPillBg = new GradientDrawable();
        ipPillBg.setColor(Color.parseColor("#4D000000"));
        ipPillBg.setCornerRadius(dpToPx(99));
        ipPill.setBackground(ipPillBg);

        ipTextView = new TextView(this);
        ipTextView.setText(getWifiIpAddress() + ":" + settingsManager.getPort());
        ipTextView.setTextColor(Color.parseColor("#E6FFFFFF"));
        ipTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ipTextView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        ipPill.addView(ipTextView);
        topHud.addView(ipPill);

        View topSpacer3 = new View(this);
        topHud.addView(topSpacer3, new LinearLayout.LayoutParams(0, 1, 1f));

        // 2d. Guides Button
        guideBtn = new TextView(this);
        guideBtn.setText("▦");
        guideBtn.setTextColor(Color.WHITE);
        guideBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        guideBtn.setGravity(Gravity.CENTER);
        GradientDrawable guideBg = new GradientDrawable();
        guideBg.setShape(GradientDrawable.OVAL);
        guideBg.setColor(Color.parseColor("#4D000000"));
        guideBtn.setBackground(guideBg);
        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(dpToPx(34), dpToPx(34));
        guideParams.setMargins(0, 0, dpToPx(8), 0);
        topHud.addView(guideBtn, guideParams);
        guideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                cycleGuides();
            }
        });

        // 2e. Settings Button
        settingsBtn = new TextView(this);
        settingsBtn.setText("⚙");
        settingsBtn.setTextColor(Color.WHITE);
        settingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        settingsBtn.setGravity(Gravity.CENTER);
        GradientDrawable settingsBg = new GradientDrawable();
        settingsBg.setShape(GradientDrawable.OVAL);
        settingsBg.setColor(Color.parseColor("#4D000000"));
        settingsBtn.setBackground(settingsBg);
        topHud.addView(settingsBtn, new LinearLayout.LayoutParams(dpToPx(34), dpToPx(34)));
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                showSettingsDialog();
            }
        });

        // ── 3. Resolution/FPS Top-Right Overlay ──
        resFpsPill = new LinearLayout(this);
        resFpsPill.setOrientation(LinearLayout.HORIZONTAL);
        resFpsPill.setGravity(Gravity.CENTER_VERTICAL);
        resFpsPill.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        GradientDrawable indicatorBg = new GradientDrawable();
        indicatorBg.setColor(Color.parseColor("#73000000"));
        indicatorBg.setCornerRadius(dpToPx(99));
        resFpsPill.setBackground(indicatorBg);

        resPillText = new TextView(this);
        resPillText.setTextColor(Color.WHITE);
        resPillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        resPillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        resPillText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                cycleResolution();
            }
        });

        TextView separator = new TextView(this);
        separator.setText(" • ");
        separator.setTextColor(Color.parseColor("#66FFFFFF"));
        separator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        fpsPillText = new TextView(this);
        fpsPillText.setTextColor(Color.WHITE);
        fpsPillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fpsPillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        fpsPillText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                cycleFramerate();
            }
        });

        resFpsPill.addView(resPillText);
        resFpsPill.addView(separator);
        resFpsPill.addView(fpsPillText);

        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        indicatorParams.gravity = Gravity.TOP | Gravity.END;
        indicatorParams.setMargins(0, dpToPx(76), dpToPx(16), 0);
        rootLayout.addView(resFpsPill, indicatorParams);

        // ── 4. Bottom Controls Glass Panel ──
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(24));
        
        GradientDrawable bottomBg = new GradientDrawable();
        bottomBg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        bottomBg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        bottomBg.setColors(new int[]{Color.TRANSPARENT, Color.parseColor("#99000000"), Color.parseColor("#E6000000")});
        bottomPanel.setBackground(bottomBg);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        rootLayout.addView(bottomPanel, bottomParams);

        // 4a. Waveform View
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
                        ? (float) (Math.sin(relativeX * Math.PI) * dpToPx(12) * Math.sin(phase * 1.5))
                        : (float) (Math.sin(relativeX * Math.PI) * dpToPx(2));

                    float y = (float) (midY + Math.sin(relativeX * 10 + phase) * amplitude);
                    path.lineTo(x, y);
                }

                paint.setColor(Color.parseColor(COLOR_ACCENT));
                canvas.drawPath(path, paint);
                phase += 0.08f;
                postInvalidateDelayed(33);
            }
        };
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(20));
        bottomPanel.addView(voiceWaveform, waveLp);

        // 4b. Single-line Telemetry text view
        telemetryTextView = new TextView(this);
        telemetryTextView.setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4));
        telemetryTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        telemetryTextView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        telemetryTextView.setTextColor(Color.parseColor("#B3FFFFFF"));
        telemetryTextView.setGravity(Gravity.CENTER);
        
        GradientDrawable teleBg = new GradientDrawable();
        teleBg.setColor(Color.parseColor("#4D000000"));
        teleBg.setCornerRadius(dpToPx(99));
        telemetryTextView.setBackground(teleBg);
        
        LinearLayout.LayoutParams teleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        teleLp.gravity = Gravity.CENTER_HORIZONTAL;
        teleLp.setMargins(0, dpToPx(4), 0, dpToPx(8));
        bottomPanel.addView(telemetryTextView, teleLp);

        // 4c. Filter Dial Scroll View
        filterDialScroll = new HorizontalScrollView(this);
        filterDialScroll.setHorizontalScrollBarEnabled(false);
        filterDialScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        filterDialLayout = new LinearLayout(this);
        filterDialLayout.setOrientation(LinearLayout.HORIZONTAL);
        filterDialLayout.setGravity(Gravity.CENTER_VERTICAL);
        filterDialScroll.addView(filterDialLayout);

        startSpacer = new View(this);
        filterDialLayout.addView(startSpacer);

        filterViews = new TextView[filterLabels.length];
        for (int i = 0; i < filterLabels.length; i++) {
            final int index = i;
            final String label = filterLabels[i];

            LinearLayout itemContainer = new LinearLayout(this);
            itemContainer.setOrientation(LinearLayout.VERTICAL);
            itemContainer.setGravity(Gravity.CENTER_HORIZONTAL);

            final TextView filterText = new TextView(this);
            filterText.setText(label);
            filterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            filterText.setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(2));

            final View dot = new View(this);
            int dotSize = dpToPx(4);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotLp.setMargins(0, dpToPx(2), 0, 0);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dot.setBackground(dotBg);

            if (index == currentFilterIndex) {
                filterText.setTextColor(Color.parseColor("#FFCC00"));
                filterText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                dotBg.setColor(Color.parseColor("#FFCC00"));
            } else {
                filterText.setTextColor(Color.parseColor("#80FFFFFF"));
                filterText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                dotBg.setColor(Color.TRANSPARENT);
            }

            itemContainer.addView(filterText);
            itemContainer.addView(dot, dotLp);

            itemContainer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    currentFilterIndex = index;
                    if (cameraStreamer != null) {
                        cameraStreamer.setFilterEffect(filterEffects[index]);
                    }
                    updateFilterDialSelection();
                    centerFilterItem(itemContainer);
                }
            });

            filterViews[i] = filterText;
            filterDialLayout.addView(itemContainer);
        }

        endSpacer = new View(this);
        filterDialLayout.addView(endSpacer);

        LinearLayout.LayoutParams dialLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(40));
        dialLp.setMargins(0, 0, 0, dpToPx(10));
        bottomPanel.addView(filterDialScroll, dialLp);

        // 4d. Shutter Controls Row
        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setOrientation(LinearLayout.HORIZONTAL);
        shutterRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomPanel.addView(shutterRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Flash Light (Left)
        flashContainer = createCircleButton("Light", false, COLOR_ORANGE);
        TextView flashIcon = getCircleButtonIcon(flashContainer);
        if (flashIcon != null) flashIcon.setText("⚡");
        flashContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                if (cameraStreamer != null) {
                    cameraStreamer.toggleTorch();
                    updateTorchButtonState();
                }
            }
        });
        shutterRow.addView(flashContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Shutter Button (Center)
        shutterBtn = new FrameLayout(this);
        shutterBtn.setClipChildren(false);
        shutterBtn.setClipToPadding(false);
        
        outerRing = new View(this);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setStroke(dpToPx(4), Color.WHITE);
        outerRing.setBackground(ringBg);
        shutterBtn.addView(outerRing, new FrameLayout.LayoutParams(dpToPx(76), dpToPx(76)));

        innerFill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setShape(GradientDrawable.OVAL);
        fillBg.setColor(Color.WHITE);
        innerFill.setBackground(fillBg);
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(dpToPx(62), dpToPx(62));
        fillLp.gravity = Gravity.CENTER;
        shutterBtn.addView(innerFill, fillLp);

        shutterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                toggleServerState();
            }
        });
        shutterRow.addView(shutterBtn, new LinearLayout.LayoutParams(dpToPx(76), dpToPx(76)));

        // Camera Flip (Right)
        switchCamContainer = createCircleButton("Flip", false, COLOR_ACCENT);
        TextView switchCamIcon = getCircleButtonIcon(switchCamContainer);
        if (switchCamIcon != null) switchCamIcon.setText("⟳");
        switchCamContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                if (cameraStreamer != null) {
                    cameraStreamer.switchCamera();
                    showToast("Camera switched");
                }
            }
        });
        shutterRow.addView(switchCamContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        rootLayout.addView(previewMuteOverlay, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Permission Overlay ──
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
        permContent.addView(permText);

        TextView grantBtn = new TextView(this);
        grantBtn.setText("Grant Permission");
        grantBtn.setTextColor(Color.WHITE);
        grantBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
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
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, dpToPx(24), 0, 0);
        permContent.addView(grantBtn, btnParams);

        permissionOverlay.addView(permContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        rootLayout.addView(permissionOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);

        // Bind TextureView surface listener
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                previewSurface = new Surface(surfaceTexture);
                isSurfaceReady = true;
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraStreamer.startPreview();
                }
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

        // Init socket and camera services
        socketServer = new SocketServer(this);
        cameraStreamer = new CameraStreamer(this, socketServer, this);

        checkAndRequestPermissions();
        updateResolutionFPSIndicator();
        updateShutterButtonState();
    }

    private void toggleServerState() {
        if (socketServer == null) return;
        if (socketServer.isServerRunning()) {
            socketServer.stop();
            updateStatusText("Offline");
        } else {
            socketServer.start();
            updateStatusText("Ready for OBS connection");
        }
        updateShutterButtonState();
    }

    private void applyScreenOnPolicy() {
        if (settingsManager.getKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updateFoldLayout(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (width == lastLayoutWidth && height == lastLayoutHeight) return;

        lastLayoutWidth = width;
        lastLayoutHeight = height;

        if (textureView == null || topHud == null || bottomPanel == null) return;

        // Viewfinder is full screen
        FrameLayout.LayoutParams textureParams = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        textureParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        textureParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        textureParams.gravity = Gravity.CENTER;
        textureView.setLayoutParams(textureParams);

        // Top bar overlay
        FrameLayout.LayoutParams topParams = (FrameLayout.LayoutParams) topHud.getLayoutParams();
        topParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        topParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        topParams.gravity = Gravity.TOP;
        topHud.setLayoutParams(topParams);

        // Bottom controls overlay
        FrameLayout.LayoutParams bottomParams = (FrameLayout.LayoutParams) bottomPanel.getLayoutParams();
        bottomParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        bottomParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        bottomParams.gravity = Gravity.BOTTOM;
        bottomParams.setMargins(0, 0, 0, 0);
        bottomPanel.setLayoutParams(bottomParams);

        // Adjust Filter Dial Spacers for Centering
        if (startSpacer != null && endSpacer != null) {
            int w = width / 2 - dpToPx(40);
            startSpacer.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
            endSpacer.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        }

        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
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
        updateStatusText("Ready for OBS connection");
        ipTextView.setText(getWifiIpAddress() + ":" + settingsManager.getPort());
        if (isSurfaceReady && cameraStreamer != null) {
            cameraStreamer.startPreview();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        telemetryRunnable.run();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            socketServer.start();
            if (isSurfaceReady && cameraStreamer != null) {
                cameraStreamer.startPreview();
            }
        }
        updateShutterButtonState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isDimMode) exitDimMode();
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
        if (ipTextView != null) {
            ipTextView.setText(getWifiIpAddress() + ":" + settingsManager.getPort());
        }
    }

    // ── StreamListener implementation ──
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
                updateStatusText("LIVE");
                updateShutterButtonState();
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
                updateStatusText("Ready for OBS connection");
                updateShutterButtonState();
            }
        });
    }

    @Override
    public int getBatteryPercentage() {
        return currentBatteryPercent;
    }

    // ── PreviewSurfaceProvider implementation ──
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
                
                if (text.startsWith("LIVE") || text.startsWith("Active")) {
                    statusTextView.setText("LIVE");
                    statusTextView.setTextColor(Color.parseColor(COLOR_RED));
                    
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setColor(Color.parseColor("#26FF453A")); // 15% red
                    badgeBg.setCornerRadius(dpToPx(99));
                    statusBadge.setBackground(badgeBg);
                    
                    GradientDrawable ledBg = new GradientDrawable();
                    ledBg.setShape(GradientDrawable.OVAL);
                    ledBg.setColor(Color.parseColor(COLOR_RED));
                    statusLed.setBackground(ledBg);
                } else if (text.startsWith("Ready") || text.startsWith("Waiting")) {
                    statusTextView.setText("READY");
                    statusTextView.setTextColor(Color.parseColor(COLOR_ACCENT));
                    
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setColor(Color.parseColor("#26" + COLOR_ACCENT.substring(1))); // 15% accent
                    badgeBg.setCornerRadius(dpToPx(99));
                    statusBadge.setBackground(badgeBg);
                    
                    GradientDrawable ledBg = new GradientDrawable();
                    ledBg.setShape(GradientDrawable.OVAL);
                    ledBg.setColor(Color.parseColor(COLOR_ACCENT));
                    statusLed.setBackground(ledBg);
                } else {
                    statusTextView.setText("OFFLINE");
                    statusTextView.setTextColor(Color.parseColor(COLOR_LABEL_2));
                    
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setColor(Color.parseColor("#14FFFFFF"));
                    badgeBg.setCornerRadius(dpToPx(99));
                    statusBadge.setBackground(badgeBg);
                    
                    GradientDrawable ledBg = new GradientDrawable();
                    ledBg.setShape(GradientDrawable.OVAL);
                    ledBg.setColor(Color.parseColor(COLOR_LABEL_2));
                    statusLed.setBackground(ledBg);
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

    // ── Shutter Button States ──
    private void updateShutterButtonState() {
        if (innerFill == null || socketServer == null) return;
        boolean isLive = socketServer.isStreaming();
        boolean isReady = socketServer.isServerRunning() && !isLive;

        GradientDrawable gd = new GradientDrawable();
        if (isLive) {
            gd.setColor(Color.parseColor(COLOR_RED));
            gd.setCornerRadius(dpToPx(8));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) innerFill.getLayoutParams();
            lp.width = dpToPx(32);
            lp.height = dpToPx(32);
            innerFill.setLayoutParams(lp);
            startShutterPulse();
        } else {
            gd.setColor(isReady ? Color.parseColor(COLOR_ACCENT) : Color.WHITE);
            gd.setCornerRadius(dpToPx(31));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) innerFill.getLayoutParams();
            lp.width = dpToPx(62);
            lp.height = dpToPx(62);
            innerFill.setLayoutParams(lp);
            stopShutterPulse();
        }
        innerFill.setBackground(gd);
    }

    private void startShutterPulse() {
        if (shutterBtn == null) return;
        if (shutterAnimator != null) shutterAnimator.cancel();
        
        shutterAnimator = ObjectAnimator.ofPropertyValuesHolder(
                shutterBtn,
                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 0.92f),
                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 0.92f)
        );
        shutterAnimator.setDuration(850);
        shutterAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shutterAnimator.setRepeatMode(ValueAnimator.REVERSE);
        shutterAnimator.start();
    }

    private void stopShutterPulse() {
        if (shutterAnimator != null) {
            shutterAnimator.cancel();
            shutterAnimator = null;
        }
        if (shutterBtn != null) {
            shutterBtn.setScaleX(1.0f);
            shutterBtn.setScaleY(1.0f);
        }
    }

    // ── Res/FPS cycles ──
    private void cycleResolution() {
        String currentRes = settingsManager.getResolution();
        int currentIndex = -1;
        for (int i = 0; i < resolutions.length; i++) {
            if (resolutions[i].equals(currentRes)) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex = (currentIndex + 1) % resolutions.length;
        String nextRes = resolutions[nextIndex];
        settingsManager.setResolution(nextRes);
        updateResolutionFPSIndicator();

        if (cameraStreamer != null && cameraStreamer.isStreaming()) {
            int[] wxh = parseResolution(nextRes);
            cameraStreamer.startStreaming(currentStreamFormat, wxh[0], wxh[1]);
        }
        adjustAspectRatio(currentVideoWidth, currentVideoHeight);
    }

    private void cycleFramerate() {
        int currentFps = settingsManager.getFramerate();
        int currentIndex = -1;
        for (int i = 0; i < framerates.length; i++) {
            if (framerates[i] == currentFps) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex = (currentIndex + 1) % framerates.length;
        int nextFps = framerates[nextIndex];
        settingsManager.setFramerate(nextFps);
        updateResolutionFPSIndicator();
    }

    private void updateResolutionFPSIndicator() {
        if (resPillText == null || fpsPillText == null) return;
        String currentRes = settingsManager.getResolution();
        resPillText.setText(resolutionLabel(currentRes));
        fpsPillText.setText(String.valueOf(settingsManager.getFramerate()));
    }

    // ── Filter dial selection ──
    private void updateFilterDialSelection() {
        if (filterViews == null) return;
        for (int i = 0; i < filterLabels.length; i++) {
            TextView filterText = filterViews[i];
            LinearLayout parent = (LinearLayout) filterText.getParent();
            View dot = parent.getChildAt(1);
            GradientDrawable dotBg = (GradientDrawable) dot.getBackground();

            if (i == currentFilterIndex) {
                filterText.setTextColor(Color.parseColor("#FFCC00"));
                filterText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                filterText.setScaleX(1.12f);
                filterText.setScaleY(1.12f);
                if (dotBg != null) dotBg.setColor(Color.parseColor("#FFCC00"));
            } else {
                filterText.setTextColor(Color.parseColor("#80FFFFFF"));
                filterText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                filterText.setScaleX(1.0f);
                filterText.setScaleY(1.0f);
                if (dotBg != null) dotBg.setColor(Color.TRANSPARENT);
            }
        }
    }

    private void centerFilterItem(final View itemContainer) {
        if (filterDialScroll == null) return;
        filterDialScroll.post(new Runnable() {
            @Override
            public void run() {
                int scrollX = itemContainer.getLeft() - (filterDialScroll.getWidth() - itemContainer.getWidth()) / 2;
                filterDialScroll.smoothScrollTo(scrollX, 0);
            }
        });
    }

    // ── Controls UI State triggers ──
    private void updateTorchButtonState() {
        if (flashContainer == null || cameraStreamer == null) return;
        boolean active = cameraStreamer.isTorchEnabled();
        styleCircleButton(flashContainer, active, COLOR_ORANGE);
    }

    private void togglePreviewMute() {
        isPreviewMuted = !isPreviewMuted;
        if (previewMuteOverlay != null) {
            previewMuteOverlay.setVisibility(isPreviewMuted ? View.VISIBLE : View.GONE);
        }
        if (textureView != null) {
            textureView.setAlpha(isPreviewMuted ? 0f : 1f);
        }
    }

    private void toggleUiVisibility() {
        isUiHidden = !isUiHidden;
        float targetAlpha = isUiHidden ? 0f : 1f;
        int visibility = isUiHidden ? View.GONE : View.VISIBLE;
        
        topHud.animate().alpha(targetAlpha).setDuration(250).start();
        bottomPanel.animate().alpha(targetAlpha).setDuration(250).start();
        resFpsPill.animate().alpha(targetAlpha).setDuration(250).start();
        
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                topHud.setVisibility(visibility);
                bottomPanel.setVisibility(visibility);
                resFpsPill.setVisibility(visibility);
            }
        }, 250);
    }

    private void enterDimMode() {
        isDimMode = true;
        
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f; // 1% brightness
        getWindow().setAttributes(lp);

        dimOverlay = new FrameLayout(this);
        dimOverlay.setBackgroundColor(Color.parseColor("#F7000000")); // 97% black
        dimOverlay.setClickable(true);
        dimOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitDimMode();
            }
        });

        dimTextContainer = new LinearLayout(this);
        dimTextContainer.setOrientation(LinearLayout.VERTICAL);
        dimTextContainer.setGravity(Gravity.CENTER);

        TextView label = new TextView(this);
        label.setText("Streaming Active");
        label.setTextColor(Color.parseColor(COLOR_GREEN));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setAlpha(0.4f);
        dimTextContainer.addView(label);

        TextView sublabel = new TextView(this);
        sublabel.setText("Tap anywhere to restore screen");
        sublabel.setTextColor(Color.parseColor(COLOR_LABEL_2));
        sublabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sublabel.setAlpha(0.4f);
        
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dpToPx(8), 0, 0);
        dimTextContainer.addView(sublabel, subLp);

        dimOverlay.addView(dimTextContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        
        rootLayout.addView(dimOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dimHandler.post(dimRunnable);
        showToast("Screen dimmed (OLED saver)");
    }

    private void exitDimMode() {
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

    // ── Telemetry Updates ──
    private void updateTelemetryDisplay() {
        if (telemetryTextView == null) return;
        String rate = (socketServer != null && socketServer.isStreaming()) ? currentTxSpeedText : "0 Kb/s";
        String total = (socketServer != null && socketServer.isStreaming()) ? totalTxText : "0.0 MB";
        String temp = String.format(Locale.US, "%.1f°C", currentBatteryTemp);
        String battery = currentBatteryPercent + "%";
        
        telemetryTextView.setText(rate + "  •  " + total + "  •  " + temp + "  •  " + battery);
    }

    private void updateTrafficStats() {
        long currentBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid());
        long now = System.currentTimeMillis();
        
        if (lastSpeedCheckTime > 0) {
            long durationMs = now - lastSpeedCheckTime;
            if (durationMs > 0) {
                long txDiff = currentBytes - lastTxBytes;
                if (txDiff < 0) txDiff = 0;
                
                double speedKbps = (txDiff / 1024.0) / (durationMs / 1000.0);
                if (speedKbps > 1000) {
                    currentTxSpeedText = String.format(Locale.US, "%.1f Mb/s", speedKbps / 1024.0);
                } else {
                    currentTxSpeedText = String.format(Locale.US, "%.0f Kb/s", speedKbps);
                }
                
                double totalMb = currentBytes / (1024.0 * 1024.0);
                totalTxText = String.format(Locale.US, "%.1f MB", totalMb);
            }
        }
        lastTxBytes = currentBytes;
        lastSpeedCheckTime = now;
    }

    private void toggleStatusBlink() {
        if (socketServer != null && socketServer.isStreaming()) {
            blinkState = !blinkState;
            if (statusLed != null) {
                statusLed.setVisibility(blinkState ? View.VISIBLE : View.INVISIBLE);
            }
        } else {
            if (statusLed != null) {
                statusLed.setVisibility(View.VISIBLE);
            }
        }
    }

    private void cycleFilter() {
        currentFilterIndex = (currentFilterIndex + 1) % filterLabels.length;
        if (cameraStreamer != null) {
            cameraStreamer.setFilterEffect(filterEffects[currentFilterIndex]);
        }
        updateFilterDialSelection();
        if (filterViews != null && filterViews[currentFilterIndex] != null) {
            centerFilterItem((View) filterViews[currentFilterIndex].getParent());
        }
    }

    private void cycleGuides() {
        currentGuideMode = (currentGuideMode + 1) % 4;
        if (guidesOverlay != null) {
            guidesOverlay.setVisibility(currentGuideMode == 0 ? View.GONE : View.VISIBLE);
            guidesOverlay.invalidate();
        }
        if (guideContainer != null) {
            styleCircleButton(guideContainer, currentGuideMode > 0, COLOR_ACCENT);
        }
    }

    // ── Settings Dialog ──
    private void showSettingsDialog() {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#CC000000"));
        overlay.setClickable(true);
        overlay.setFocusable(true);

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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dpToPx(340), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        overlay.addView(scrollView, cardParams);

        View dragPill = new View(this);
        GradientDrawable pillBg2 = new GradientDrawable();
        pillBg2.setColor(Color.parseColor("#33FFFFFF"));
        pillBg2.setCornerRadius(dpToPx(3));
        dragPill.setBackground(pillBg2);
        LinearLayout.LayoutParams pillLp2 = new LinearLayout.LayoutParams(dpToPx(36), dpToPx(5));
        pillLp2.setMargins(0, 0, 0, dpToPx(16));
        card.addView(dragPill, pillLp2);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.parseColor(COLOR_LABEL));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dpToPx(20));
        card.addView(title, titleParams);

        // ── Accent Theme Selection Section ──
        addSectionLabel(card, "THEME ACCENT");
        LinearLayout dotsLayout = new LinearLayout(this);
        dotsLayout.setOrientation(LinearLayout.HORIZONTAL);
        dotsLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.setMargins(0, dpToPx(8), 0, dpToPx(16));

        final View[] dotViews = new View[accentColors.length];
        final int[] tempSelectedAccentIdx = {selectedAccentIndex};
        for (int i = 0; i < accentColors.length; i++) {
            final int index = i;
            final View dot = new View(this);
            int size = (index == tempSelectedAccentIdx[0]) ? dpToPx(32) : dpToPx(24);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(size, size);
            dotLp.setMargins(dpToPx(6), 0, dpToPx(6), 0);

            final GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(Color.parseColor(accentColors[i]));
            if (index == tempSelectedAccentIdx[0]) {
                dotBg.setStroke(dpToPx(2), Color.WHITE);
            }
            dot.setBackground(dotBg);

            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    tempSelectedAccentIdx[0] = index;
                    for (int j = 0; j < dotViews.length; j++) {
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dotViews[j].getLayoutParams();
                        int newSize = (j == tempSelectedAccentIdx[0]) ? dpToPx(32) : dpToPx(24);
                        lp.width = newSize;
                        lp.height = newSize;
                        dotViews[j].setLayoutParams(lp);

                        GradientDrawable bg = (GradientDrawable) dotViews[j].getBackground();
                        if (bg != null) {
                            if (j == tempSelectedAccentIdx[0]) {
                                bg.setStroke(dpToPx(2), Color.WHITE);
                            } else {
                                bg.setStroke(0, Color.TRANSPARENT);
                            }
                        }
                    }
                }
            });

            dotViews[i] = dot;
            dotsLayout.addView(dot, dotLp);
        }
        card.addView(dotsLayout, dotsLp);

        // ── Port Section ──
        addSectionLabel(card, "NETWORK PORT");
        final android.widget.EditText portInput = new android.widget.EditText(this);
        portInput.setText(String.valueOf(settingsManager.getPort()));
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setTextColor(Color.WHITE);
        portInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        portInput.setTypeface(Typeface.MONOSPACE);
        portInput.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor(COLOR_SURFACE));
        inputBg.setStroke(dpToPx(1), Color.parseColor(COLOR_SEPARATOR));
        inputBg.setCornerRadius(dpToPx(RADIUS_SM));
        portInput.setBackground(inputBg);
        
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        portParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(portInput, portParams);

        // ── Resolution Section ──
        addSectionLabel(card, "RESOLUTION");
        HorizontalScrollView resScroll = new HorizontalScrollView(this);
        resScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout resRow = new LinearLayout(this);
        resRow.setOrientation(LinearLayout.HORIZONTAL);
        resScroll.addView(resRow);
        
        LinearLayout.LayoutParams resScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resScrollParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(resScroll, resScrollParams);

        final List<String> resList = getSupportedResolutions();
        final String[] resOptions = resList.toArray(new String[0]);
        final TextView[] resButtons = new TextView[resOptions.length];
        final int[] selectedResIdx = {0};
        String currentRes = settingsManager.getResolution();
        for (int i = 0; i < resOptions.length; i++) {
            if (currentRes.equals(resOptions[i])) selectedResIdx[0] = i;
        }

        for (int i = 0; i < resOptions.length; i++) {
            final int index = i;
            final String res = resOptions[i];
            final TextView btn = new TextView(this);
            btn.setText(resolutionLabel(res));
            styleModernButton(btn, index == selectedResIdx[0], COLOR_ACCENT);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    selectedResIdx[0] = index;
                    for (int j = 0; j < resButtons.length; j++) {
                        styleModernButton(resButtons[j], j == index, COLOR_ACCENT);
                    }
                }
            });
            resButtons[i] = btn;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dpToPx(8), 0);
            resRow.addView(btn, lp);
        }

        // ── Framerate Section ──
        addSectionLabel(card, "FRAMERATE");
        HorizontalScrollView fpsScroll = new HorizontalScrollView(this);
        fpsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsScroll.addView(fpsRow);
        
        LinearLayout.LayoutParams fpsScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fpsScrollParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(fpsScroll, fpsScrollParams);

        final int[] fpsOptions = {15, 24, 30, 60};
        final TextView[] fpsButtons = new TextView[fpsOptions.length];
        final int[] selectedFpsIdx = {2}; // default 30
        int currentFps = settingsManager.getFramerate();
        for (int i = 0; i < fpsOptions.length; i++) {
            if (fpsOptions[i] == currentFps) selectedFpsIdx[0] = i;
        }

        for (int i = 0; i < fpsOptions.length; i++) {
            final int index = i;
            final int fps = fpsOptions[i];
            final TextView btn = new TextView(this);
            btn.setText(fps + " FPS");
            styleModernButton(btn, index == selectedFpsIdx[0], COLOR_ACCENT);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    selectedFpsIdx[0] = index;
                    for (int j = 0; j < fpsButtons.length; j++) {
                        styleModernButton(fpsButtons[j], j == index, COLOR_ACCENT);
                    }
                }
            });
            fpsButtons[i] = btn;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dpToPx(8), 0);
            fpsRow.addView(btn, lp);
        }

        // ── Bitrate Section ──
        addSectionLabel(card, "BITRATE");
        HorizontalScrollView bitrateScroll = new HorizontalScrollView(this);
        bitrateScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bitrateRow = new LinearLayout(this);
        bitrateRow.setOrientation(LinearLayout.HORIZONTAL);
        bitrateScroll.addView(bitrateRow);

        LinearLayout.LayoutParams bitrateScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bitrateScrollParams.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(bitrateScroll, bitrateScrollParams);

        final int[] bitrates = {1000000, 2000000, 3000000, 5000000, 10000000, 15000000, 20000000, 30000000};
        final String[] bitrateLabels = {"1 Mbps", "2 Mbps", "3 Mbps", "5 Mbps", "10 Mbps", "15 Mbps", "20 Mbps", "30 Mbps"};
        final TextView[] bitrateButtons = new TextView[bitrates.length];
        final int[] selectedBitrateIdx = {2}; // default 3 Mbps
        int currentBitrate = settingsManager.getBitrate();
        for (int i = 0; i < bitrates.length; i++) {
            if (bitrates[i] == currentBitrate) selectedBitrateIdx[0] = i;
        }

        for (int i = 0; i < bitrates.length; i++) {
            final int index = i;
            final TextView btn = new TextView(this);
            btn.setText(bitrateLabels[i]);
            styleModernButton(btn, index == selectedBitrateIdx[0], COLOR_ACCENT);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    selectedBitrateIdx[0] = index;
                    for (int j = 0; j < bitrateButtons.length; j++) {
                        styleModernButton(bitrateButtons[j], j == index, COLOR_ACCENT);
                    }
                }
            });
            bitrateButtons[i] = btn;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dpToPx(8), 0);
            bitrateRow.addView(btn, lp);
        }

        // ── OLED Screen Saver Row (New Display row) ──
        addSectionLabel(card, "DISPLAY");
        LinearLayout displayRow = new LinearLayout(this);
        displayRow.setOrientation(LinearLayout.HORIZONTAL);
        displayRow.setGravity(Gravity.CENTER_VERTICAL);
        displayRow.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        GradientDrawable displayBg = new GradientDrawable();
        displayBg.setColor(Color.parseColor(COLOR_SURFACE));
        displayBg.setStroke(dpToPx(1), Color.parseColor(COLOR_SEPARATOR));
        displayBg.setCornerRadius(dpToPx(RADIUS_SM));
        displayRow.setBackground(displayBg);
        displayRow.setClickable(true);
        displayRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                rootLayout.removeView(overlay);
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        enterDimMode();
                    }
                }, 300);
            }
        });

        TextView displayLabel = new TextView(this);
        displayLabel.setText("Dim Screen (OLED Saver)");
        displayLabel.setTextColor(Color.WHITE);
        displayLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        displayRow.addView(displayLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView displayChevron = new TextView(this);
        displayChevron.setText("☽  ❯");
        displayChevron.setTextColor(Color.parseColor(COLOR_LABEL_2));
        displayChevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        displayRow.addView(displayChevron);
        
        LinearLayout.LayoutParams displayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        displayLp.setMargins(0, dpToPx(6), 0, dpToPx(16));
        card.addView(displayRow, displayLp);

        // ── Preference Switches ──
        addSectionLabel(card, "PREFERENCES");
        LinearLayout prefsBox = new LinearLayout(this);
        prefsBox.setOrientation(LinearLayout.VERTICAL);
        prefsBox.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        GradientDrawable prefsBg = new GradientDrawable();
        prefsBg.setColor(Color.parseColor(COLOR_SURFACE));
        prefsBg.setCornerRadius(dpToPx(RADIUS_MD));
        prefsBg.setStroke(dpToPx(1), Color.parseColor(COLOR_SEPARATOR));
        prefsBox.setBackground(prefsBg);

        final androidx.appcompat.widget.SwitchCompat screenSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        screenSwitch.setText("Keep Screen On");
        screenSwitch.setTextColor(Color.WHITE);
        screenSwitch.setChecked(settingsManager.getKeepScreenOn());
        screenSwitch.setPadding(0, dpToPx(8), 0, dpToPx(8));
        prefsBox.addView(screenSwitch);

        final androidx.appcompat.widget.SwitchCompat flipHSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        flipHSwitch.setText("Flip Horizontal");
        flipHSwitch.setTextColor(Color.WHITE);
        flipHSwitch.setChecked(settingsManager.getFlipHorizontal());
        flipHSwitch.setPadding(0, dpToPx(8), 0, dpToPx(8));
        prefsBox.addView(flipHSwitch);

        final androidx.appcompat.widget.SwitchCompat flipVSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        flipVSwitch.setText("Flip Vertical");
        flipVSwitch.setTextColor(Color.WHITE);
        flipVSwitch.setChecked(settingsManager.getFlipVertical());
        flipVSwitch.setPadding(0, dpToPx(8), 0, dpToPx(8));
        prefsBox.addView(flipVSwitch);

        final androidx.appcompat.widget.SwitchCompat faceAfSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        faceAfSwitch.setText("Face Auto-Focus");
        faceAfSwitch.setTextColor(Color.WHITE);
        faceAfSwitch.setChecked(settingsManager.getFaceAutoFocus());
        faceAfSwitch.setPadding(0, dpToPx(8), 0, dpToPx(8));
        prefsBox.addView(faceAfSwitch);

        card.addView(prefsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Action Buttons Row ──
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actRowLp.setMargins(0, dpToPx(24), 0, 0);
        card.addView(actionRow, actRowLp);

        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("Cancel");
        styleModernButton(cancelBtn, false, COLOR_LABEL_3);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                rootLayout.removeView(overlay);
            }
        });
        actionRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        saveParams.setMargins(dpToPx(12), 0, 0, 0);
        
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                String portStr = portInput.getText().toString().trim();
                if (portStr.isEmpty()) {
                    showToast("Invalid Port!");
                    return;
                }
                int newPort = Integer.parseInt(portStr);
                if (newPort < 1024 || newPort > 65535) {
                    showToast("Port must be 1024-65535");
                    return;
                }

                int oldPort = settingsManager.getPort();
                int selectedBitrate = bitrates[selectedBitrateIdx[0]];
                int selectedFps = fpsOptions[selectedFpsIdx[0]];
                boolean keepScreenOn = screenSwitch.isChecked();
                String selectedRes = resOptions[selectedResIdx[0]];
                boolean flipHorizontal = flipHSwitch.isChecked();
                boolean flipVertical = flipVSwitch.isChecked();
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
                
                settingsManager.setAccentColorIndex(tempSelectedAccentIdx[0]);
                applyAccentColor(tempSelectedAccentIdx[0]);

                if (cameraStreamer != null) {
                    cameraStreamer.setFaceAutoFocusEnabled(faceAf);
                }

                applyScreenOnPolicy();

                ipTextView.setText(getWifiIpAddress() + ":" + newPort);
                int[] wxh = parseResolution(selectedRes);
                cameraStreamer.setTargetResolution(wxh[0], wxh[1]);
                adjustAspectRatio(currentVideoWidth, currentVideoHeight);

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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void addSectionLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.parseColor(COLOR_LABEL_3));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.setMargins(0, 0, 0, dpToPx(4));
        parent.addView(label, lp);
    }

    private void applyAccentColor(int index) {
        selectedAccentIndex = index;
        COLOR_ACCENT = accentColors[index];
        updateShutterButtonState();
        updateResolutionFPSIndicator();
        updateFilterDialSelection();
        if (ipTextView != null) {
            ipTextView.setTextColor(Color.parseColor(COLOR_ACCENT));
        }
    }

    private String getAccentColorHex() {
        return accentColors[selectedAccentIndex];
    }

    // ── Dimension Utility Helpers ──
    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private int spToPx(float sp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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

                int sensorOrientation = 90;
                int activeFacing = CameraCharacteristics.LENS_FACING_BACK;
                if (cameraStreamer != null) {
                    activeFacing = cameraStreamer.getLensFacing();
                }
                try {
                    CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                    String[] cameraIdList = manager.getCameraIdList();
                    if (cameraIdList.length > 0) {
                        String targetCameraId = cameraIdList[0];
                        for (String id : cameraIdList) {
                            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if (facing != null && facing == activeFacing) {
                                targetCameraId = id;
                                break;
                            }
                        }
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
                        Integer orient = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        if (orient != null) sensorOrientation = orient;
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

                DisplayMetrics metrics = new DisplayMetrics();
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
                if (activeFacing == CameraCharacteristics.LENS_FACING_FRONT) {
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

                if (activeFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    flipHorizontal = !flipHorizontal;
                }

                if (flipHorizontal) matrix.postScale(-1, 1);
                if (flipVertical) matrix.postScale(1, -1);

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
        List<String> supported = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList.length == 0) return supported;

            String targetCameraId = cameraIdList[0];
            for (String id : cameraIdList) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    targetCameraId = id;
                    break;
                }
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
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

    private String getWifiIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return "127.0.0.1";
    }

    private String getDeviceIpAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(intf.getName()).append(": ").append(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP addresses", e);
        }
        if (sb.length() == 0) {
            return "IP: 127.0.0.1";
        }
        return sb.toString();
    }

    private String resolutionLabel(String res) {
        if ("3840x2160".equals(res)) return "4K";
        if ("2560x1440".equals(res)) return "2K";
        if ("1920x1080".equals(res)) return "HD";
        if ("1280x720".equals(res)) return "720";
        if ("640x480".equals(res)) return "480";
        return res;
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
