package com.webcamclone;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String PREFS_NAME = "webcam_clone_settings";
    private static final String KEY_PORT = "server_port";
    private static final String KEY_BITRATE = "video_bitrate";
    private static final String KEY_FRAMERATE = "video_framerate";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_RESOLUTION = "default_resolution";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getPort() {
        return prefs.getInt(KEY_PORT, 4747);
    }

    public void setPort(int port) {
        prefs.edit().putInt(KEY_PORT, port).apply();
    }

    public int getBitrate() {
        return prefs.getInt(KEY_BITRATE, 3500000); // Default 3.5 Mbps
    }

    public void setBitrate(int bitrate) {
        prefs.edit().putInt(KEY_BITRATE, bitrate).apply();
    }

    public int getFramerate() {
        return prefs.getInt(KEY_FRAMERATE, 30); // Default 30 FPS
    }

    public void setFramerate(int fps) {
        prefs.edit().putInt(KEY_FRAMERATE, fps).apply();
    }

    public boolean getKeepScreenOn() {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean keepOn) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepOn).apply();
    }

    public String getResolution() {
        return prefs.getString(KEY_RESOLUTION, "1280x720");
    }

    public void setResolution(String resolution) {
        prefs.edit().putString(KEY_RESOLUTION, resolution).apply();
    }

    public boolean getFlipHorizontal() {
        return prefs.getBoolean("flip_horizontal", false);
    }

    public void setFlipHorizontal(boolean flip) {
        prefs.edit().putBoolean("flip_horizontal", flip).apply();
    }

    public boolean getFlipVertical() {
        return prefs.getBoolean("flip_vertical", false);
    }

    public void setFlipVertical(boolean flip) {
        prefs.edit().putBoolean("flip_vertical", flip).apply();
    }

    public boolean getFaceAutoFocus() {
        return prefs.getBoolean("face_auto_focus", true);
    }

    public void setFaceAutoFocus(boolean enabled) {
        prefs.edit().putBoolean("face_auto_focus", enabled).apply();
    }

    public int getAccentColorIndex() {
        return prefs.getInt("accent_color_index", 0);
    }

    public void setAccentColorIndex(int index) {
        prefs.edit().putInt("accent_color_index", index).apply();
    }

    public float getZoomFactor() {
        return prefs.getFloat("zoom_factor", 1.0f);
    }

    public void setZoomFactor(float zoom) {
        prefs.edit().putFloat("zoom_factor", zoom).apply();
    }

    public int getExposureCompensation() {
        return prefs.getInt("exposure_compensation", 0);
    }

    public void setExposureCompensation(int ec) {
        prefs.edit().putInt("exposure_compensation", ec).apply();
    }

    public int getFocusMode() {
        return prefs.getInt("focus_mode", 0); // 0 = Auto-Continuous, 1 = Manual
    }

    public void setFocusMode(int mode) {
        prefs.edit().putInt("focus_mode", mode).apply();
    }

    public float getManualFocusDistance() {
        return prefs.getFloat("manual_focus_distance", 0.0f);
    }

    public void setManualFocusDistance(float distance) {
        prefs.edit().putFloat("manual_focus_distance", distance).apply();
    }

    public int getAwbMode() {
        return prefs.getInt("awb_mode", 1); // CONTROL_AWB_MODE_AUTO is 1 in Android CameraMetadata
    }

    public void setAwbMode(int mode) {
        prefs.edit().putInt("awb_mode", mode).apply();
    }

    public boolean getStabilizationEnabled() {
        return prefs.getBoolean("stabilization_enabled", true);
    }

    public void setStabilizationEnabled(boolean enabled) {
        prefs.edit().putBoolean("stabilization_enabled", enabled).apply();
    }
}
