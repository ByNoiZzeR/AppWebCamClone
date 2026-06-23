import subprocess
import sys
import os
import shutil

def run_command(cmd, cwd=None):
    try:
        # Use shell=True for windows compatibility
        res = subprocess.run(cmd, cwd=cwd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, shell=True)
        return res.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Error executing command: {' '.join(cmd) if isinstance(cmd, list) else cmd}")
        print(f"Stdout: {e.stdout}")
        print(f"Stderr: {e.stderr}")
        sys.exit(1)

def main():
    print("====================================================")
    print("iOS Client IPA Build Automator")
    print("====================================================")

    # 1. Check for local modifications using git status
    print("Checking repository status...")
    status = run_command(["git", "status", "--porcelain"])
    
    if status:
        print("\nLocal changes detected:")
        print(status)
        
        # Prompt user to commit and push changes
        commit_msg = input("\nEnter commit message for these changes (or press Enter to use 'Automated UI/build updates'): ").strip()
        if not commit_msg:
            commit_msg = "Automated UI/build updates"
            
        print("\nStaging changes...")
        run_command(["git", "add", "."])
        
        print(f"Committing changes with message: '{commit_msg}'...")
        run_command(["git", "commit", "-m", f'"{commit_msg}"'])
        
        print("Pushing changes to master branch to trigger cloud compiler...")
        run_command(["git", "push", "origin", "master"])
        print("Push complete! Cloud compiler triggered.")
    else:
        print("No local changes detected. Triggering rebuild on origin master...")
        choice = input("No modifications. Push an empty commit to trigger a new cloud build? (y/n): ").strip().lower()
        if choice == 'y':
            print("Triggering rebuild via empty commit...")
            run_command(["git", "commit", "--allow-empty", "-m", '"Trigger automated cloud build"'])
            run_command(["git", "push", "origin", "master"])
            print("Cloud compiler triggered.")
        else:
            print("Skipping trigger, will poll/download the most recent build.")

    # 2. Run the get_ipa.py script to poll and download the IPA
    print("\nStarting get_ipa.py to poll and download the compiled IPA...")
    print("====================================================")
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    get_ipa_script = os.path.join(script_dir, "get_ipa.py")
    
    repo = "ByNoiZzeR/AppWebCamClone"
    # Execute get_ipa.py and show its output interactively
    proc = subprocess.Popen([sys.executable, get_ipa_script, repo], stdout=sys.stdout, stderr=sys.stderr, shell=True)
    proc.wait()
    
    if proc.returncode != 0:
        print("\nError: get_ipa.py execution failed.")
        sys.exit(1)

    # 3. Copy the downloaded IPA from get_ipa's destination to releases/OBS-Webcam-Clone-unsigned.ipa
    local_ipa = os.path.join(script_dir, "ios-client", "ios-client", "ios-client-unsigned.ipa")
    releases_dir = os.path.join(script_dir, "releases")
    release_ipa = os.path.join(releases_dir, "OBS-Webcam-Clone-unsigned.ipa")
    
    if not os.path.exists(releases_dir):
        os.makedirs(releases_dir)
        
    if os.path.exists(local_ipa):
        print(f"\nCopying downloaded IPA to releases folder...")
        shutil.copy2(local_ipa, release_ipa)
        print(f"SUCCESS! iOS IPA successfully saved to:")
        print(f"-> {os.path.abspath(release_ipa)}")
    else:
        print(f"\nError: Compiled IPA file was not found at expected location: {local_ipa}")
        sys.exit(1)

    print("====================================================")

if __name__ == "__main__":
    main()
