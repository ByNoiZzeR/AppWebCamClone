$tag = "2.5.0"
$url = "https://github.com/dev47apps/droidcam-obs-plugin/releases/download/$tag/DroidCamOBS.Setup.exe"
$dest = Join-Path $PSScriptRoot "DroidCamOBS.Setup.exe"

Write-Host "=================================================="
Write-Host "Downloading DroidCam OBS Plugin (v$tag) installer..."
Write-Host "Source: $url"
Write-Host "=================================================="

try {
    # Set TLS to 1.2
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
    
    if (Test-Path $dest) {
        Write-Host "Successfully downloaded to: $dest"
        Write-Host "Starting the official installer..."
        Write-Host "Please complete the setup wizard to install the plugin to OBS Studio."
        Start-Process $dest -Wait
        Write-Host "Installer finished."
    } else {
        Write-Error "Failed to verify download."
    }
} catch {
    Write-Error "Error occurred during download: $_"
}
