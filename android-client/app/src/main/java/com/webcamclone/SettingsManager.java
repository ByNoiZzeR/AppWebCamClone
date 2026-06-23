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
}
