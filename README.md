# AppWebCamClone 📸

> Turn your iOS or Android device into a high-quality, low-latency webcam stream for OBS Studio.
>
> Developed by **[ByNoiZzeR](https://github.com/ByNoiZzeR)**

---

AppWebCamClone is a custom webcam streaming system designed to work seamlessly with OBS Studio. It includes an Android client, an iOS client, and automated CI/CD workflows to compile, package, and deploy builds.

## 🚀 Key Features

*   **Ultra-High Resolution Support**: Supports **4K UHD (3840x2160)**, **2K QHD (2560x1440)**, 1080p, 720p, and 480p streaming.
*   **Smooth Framerates**: Custom framerate settings supporting **up to 60 FPS** for professional, fluid capture.
*   **High Bitrate Encoding**: Adjustable bitrates **up to 30.0 Mbps** for crisp, artifact-free image quality.
*   **OLED Burn-in Protection**: Includes a floating "Dim Mode" overlay that dims the phone screen during active streams to protect your OLED screen.
*   **Framing Guides**: Built-in overlays (3x3 grid, crosshair, and TikTok 9:16 crop guide) to help you compose your shots.
*   **Low Latency & Low Overhead**: Hardware-accelerated encoding (using Apple's `VideoToolbox` framework) and optimized socket transmission.

---

## 📱 Clients

### iOS Client (`ios-client`)
Written in SwiftUI and Swift, featuring:
*   Real-time camera view and controls (flip camera, toggle torch, autofocus, camera filters).
*   Automatic hardware-accelerated H.264/H.265 (HEVC) compression.
*   Interactive configuration screen (port, resolution, format, framerate, bitrate, preferences).

### Android Client (`android-client`)
Native Java application built to support Android devices as webcam sources.

---

## ⚙️ CI/CD & Automated Builds

This project has an automated GitHub Actions pipeline configured for the iOS application. 
*   **iOS Builder**: Pushing changes to `master` automatically triggers a macOS runner to compile the Xcode project, package it into a clean unsigned `.ipa`, and upload it to the repository's releases and the `ipa-build` branch.

---

## 🛠 Setup & Installation

### iOS Client Sideloading
1. Download the latest `ios-client.ipa` from the repository's **Releases** or the **`ipa-build`** branch.
2. Sideload the IPA onto your device using tools like **Sideloadly** or **AltStore**.
   * *Tip*: If you encounter Sideloadly signature conflicts (e.g. `Guru Meditation Invalid file`), toggle the **Anisette Authentication** mode in Sideloadly's Advanced Options between *Local* and *Remote*.

### Usage
1. Open the app on your device.
2. Connect your computer and your phone to the same Wi-Fi network.
3. Add the stream as a custom media source in OBS Studio using the IP address and Port displayed at the top of the mobile screen.
