import os
import sys
import urllib.request
import zipfile
import subprocess
import shutil

# Configurations
WORKSPACE_DIR = os.path.dirname(os.path.abspath(__file__))
ANDROID_DIR = os.path.join(WORKSPACE_DIR, "android-sdk")
CMD_TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
GRADLE_URL = "https://services.gradle.org/distributions/gradle-8.5-bin.zip"
JAVA_HOME = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"

# Set JDK environment path
os.environ["JAVA_HOME"] = JAVA_HOME
os.environ["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + os.environ["PATH"]

def download_and_extract(url, dest_dir):
    if not os.path.exists(dest_dir):
        os.makedirs(dest_dir)
    
    filename = url.split('/')[-1]
    zip_path = os.path.join(dest_dir, filename)
    
    print(f"Downloading {url}...")
    urllib.request.urlretrieve(url, zip_path)
    
    print(f"Extracting {zip_path}...")
    with zipfile.ZipFile(zip_path, 'r') as zip_ref:
        zip_ref.extractall(dest_dir)
        
    os.remove(zip_path)

def setup_sdk():
    print("Setting up Android SDK...")
    sdk_root = os.path.join(ANDROID_DIR, "sdk")
    cmd_tools_dir = os.path.join(sdk_root, "cmdline-tools")
    
    if not os.path.exists(cmd_tools_dir):
        download_and_extract(CMD_TOOLS_URL, sdk_root)
        # The zip extracts as 'cmdline-tools'. We need it inside 'cmdline-tools/latest' for sdkmanager to work
        extracted_tools = os.path.join(sdk_root, "cmdline-tools")
        temp_dest = os.path.join(sdk_root, "temp-tools")
        os.rename(extracted_tools, temp_dest)
        
        os.makedirs(cmd_tools_dir)
        shutil.move(temp_dest, os.path.join(cmd_tools_dir, "latest"))
        
    # Write environment variable
    os.environ["ANDROID_HOME"] = sdk_root
    
    # Run sdkmanager to download platforms and build-tools
    sdkmanager = os.path.join(cmd_tools_dir, "latest", "bin", "sdkmanager.bat")
    
    print("Accepting Android SDK licenses...")
    # Pipe 'y' to accept all licenses
    license_proc = subprocess.Popen([sdkmanager, "--licenses"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
    license_proc.communicate(input=b"y\ny\ny\ny\ny\ny\ny\n")
    
    print("Installing Android build dependencies (platform-34, build-tools-34.0.0)...")
    subprocess.run([
        sdkmanager,
        "platforms;android-34",
        "build-tools;34.0.0",
        "platform-tools"
    ], check=True, shell=True)

def setup_gradle():
    print("Setting up Gradle...")
    gradle_dest = os.path.join(ANDROID_DIR, "gradle")
    gradle_bin = os.path.join(gradle_dest, "gradle-8.5", "bin", "gradle.bat")
    
    if not os.path.exists(gradle_dest):
        download_and_extract(GRADLE_URL, gradle_dest)
        
    return gradle_bin

def build_project(gradle_bin):
    print("Building project...")
    android_project_path = os.path.join(WORKSPACE_DIR, "android-client")
    
    # Run gradle wrapper generation in the project directory
    subprocess.run([gradle_bin, "wrapper"], cwd=android_project_path, check=True, shell=True)
    
    # Run gradle assembleDebug to build the debug APK
    gradlew = os.path.join(android_project_path, "gradlew.bat")
    print("Compiling APK...")
    subprocess.run([gradlew, "assembleDebug"], cwd=android_project_path, check=True, shell=True)

if __name__ == "__main__":
    try:
        setup_sdk()
        gradle_path = setup_gradle()
        build_project(gradle_path)
        
        apk_path = os.path.join(WORKSPACE_DIR, "android-client", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
        if os.path.exists(apk_path):
            print("\n" + "="*50)
            print("SUCCESS! APK successfully generated at:")
            print(apk_path)
            print("="*50)
        else:
            print("\nError: Build completed but APK was not found at target path.")
            sys.exit(1)
            
    except Exception as e:
        print(f"\nBuild script encountered an error: {e}")
        sys.exit(1)
