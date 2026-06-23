import urllib.request
import time
import sys
import os
import zipfile

def download_file(url, destination):
    print(f"Downloading {url}...")
    try:
        # Show progress bar during download
        def report(block_num, block_size, total_size):
            read_so_far = block_num * block_size
            if total_size > 0:
                percent = min(100, (read_so_far * 100) / total_size)
                sys.stdout.write(f"\rProgress: {percent:.1f}% ({read_so_far/(1024*1024):.1f}MB of {total_size/(1024*1024):.1f}MB)")
            else:
                sys.stdout.write(f"\rDownloaded: {read_so_far/(1024*1024):.1f}MB")
            sys.stdout.flush()

        urllib.request.urlretrieve(url, destination, reporthook=report)
        print("\nDownload complete!")
        return True
    except Exception as e:
        print(f"\nError downloading: {e}")
        return False

def verify_ipa(path):
    """Verify the downloaded file is a valid IPA (ZIP with Payload/*.app)."""
    print("Verifying IPA integrity...")
    if not os.path.isfile(path):
        print(f"Error: File not found: {path}")
        return False

    size = os.path.getsize(path)
    if size < 1024:
        print(f"Error: File is too small ({size} bytes). Likely not a valid IPA.")
        return False

    try:
        with zipfile.ZipFile(path, 'r') as zf:
            if zf.testzip() is not None:
                print("Error: ZIP archive is corrupted.")
                return False
            namelist = zf.namelist()
            has_payload = any(name.startswith("Payload/") for name in namelist)
            has_app = any(name.endswith(".app/") or "/.app/" in name for name in namelist)
            if not has_payload:
                print("Error: Missing Payload/ directory. Not a valid IPA.")
                return False
            if not has_app:
                print("Error: Missing .app bundle inside Payload/. Not a valid IPA.")
                return False
            print(f"IPA looks valid. Size: {size/(1024*1024):.2f}MB, Files: {len(namelist)}")
            return True
    except zipfile.BadZipFile:
        print("Error: File is not a valid ZIP archive (likely a corrupted or HTML redirect).")
        return False
    except Exception as e:
        print(f"Error verifying IPA: {e}")
        return False

def main():
    print("====================================================")
    print("iOS Client IPA Downloader (Unsigned)")
    print("====================================================")
    
    # Check if repo was passed as argument, otherwise prompt
    if len(sys.argv) > 1:
        repo = sys.argv[1].strip()
    else:
        repo = input("Enter your GitHub repository (format: username/repository): ").strip()
        
    if not repo or "/" not in repo:
        print("Invalid format. Expected: username/repository (e.g., john-doe/my-webcam-clone)")
        return
        
    url = f"https://raw.githubusercontent.com/{repo}/ipa-build/ios-client-unsigned.ipa"
    destination = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ios-client", "ios-client", "ios-client-unsigned.ipa")
    
    print(f"Polling branch URL: {url}")
    print("Press Ctrl+C to cancel.")
    print("-" * 50)
    
    attempt = 0
    while True:
        attempt += 1
        try:
            # Check if file exists at URL by sending a HEAD request
            req = urllib.request.Request(url, method="HEAD")
            with urllib.request.urlopen(req) as resp:
                if resp.status == 200:
                    size = resp.headers.get('Content-Length')
                    size_str = f" ({int(size)/(1024*1024):.1f}MB)" if size else ""
                    print(f"\n[Attempt {attempt}] Found compiled IPA on the cloud!{size_str}")
                    print("Starting download...")
                    break
        except urllib.error.HTTPError as e:
            if e.code == 404:
                # Still building
                sys.stdout.write(f"\r[Attempt {attempt}] Cloud compiler is still building... (retrying in 15s)")
                sys.stdout.flush()
            else:
                print(f"\nHTTP Error: {e.code} - {e.reason}")
        except Exception as e:
            print(f"\nConnection error: {e}")
            
        time.sleep(15)
        
    # Download the IPA
    success = download_file(url, destination)
    if not success:
        print("Failed to download the file. Please check your connection.")
        return
    
    # Verify integrity
    if not verify_ipa(destination):
        print("WARNING: Downloaded file may be corrupted or invalid.")
        print("Try deleting it and running this script again.")
        return
    
    print("-" * 50)
    print("SUCCESS!")
    print(f"The compiled iOS application is now saved to your PC at:")
    print(f"-> {os.path.abspath(destination)}")
    print("You can now drag and drop this file into Sideloadly or AltStore to install it.")
    print("====================================================")

if __name__ == "__main__":
    main()
