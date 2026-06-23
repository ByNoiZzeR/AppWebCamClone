import subprocess
import sys
import os
import shutil
import urllib.request
import json
import time

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

def get_latest_action_run(repo, commit_sha):
    url = f"https://api.github.com/repos/{repo}/actions/runs?branch=master"
    headers = {"User-Agent": "Mozilla/5.0"}
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            runs = data.get("workflow_runs", [])
            for run in runs:
                if run.get("head_sha") == commit_sha:
                    return run
    except Exception as e:
        pass
    return None

def monitor_cloud_build(repo, commit_sha):
    print(f"Monitoring GitHub Actions for commit {commit_sha[:7]}...")
    attempt = 0
    while True:
        attempt += 1
        run = get_latest_action_run(repo, commit_sha)
        if run is None:
            sys.stdout.write(f"\r[Attempt {attempt}] Waiting for GitHub to register the build...")
            sys.stdout.flush()
            time.sleep(5)
            continue
        
        status = run.get("status")
        conclusion = run.get("conclusion")
        
        if status == "completed":
            if conclusion == "success":
                print(f"\n\n[SUCCESS] Cloud build finished successfully!")
                return True
            else:
                print(f"\n\n[FAILURE] Cloud build failed with conclusion: {conclusion}")
                print(f"Please check the build logs at: {run.get('html_url')}")
                return False
        else:
            sys.stdout.write(f"\r[Attempt {attempt}] Cloud build in progress... (status: {status})")
            sys.stdout.flush()
            
        time.sleep(10)

def main():
    print("====================================================")
    print("iOS Client IPA Build Automator")
    print("====================================================")

    repo = "ByNoiZzeR/AppWebCamClone"

    # 1. Check for local modifications using git status
    print("Checking repository status...")
    status = run_command(["git", "status", "--porcelain"])
    
    triggered = False
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
        triggered = True
    else:
        print("No local changes detected.")
        choice = input("Push an empty commit to force a new cloud build? (y/n): ").strip().lower()
        if choice == 'y':
            print("Triggering rebuild via empty commit...")
            run_command(["git", "commit", "--allow-empty", "-m", '"Trigger automated cloud build"'])
            run_command(["git", "push", "origin", "master"])
            print("Cloud compiler triggered.")
            triggered = True
        else:
            print("Skipping trigger, will verify/download the most recent build on the branch.")

    # Get the head commit SHA we want to build or verify
    commit_sha = run_command(["git", "rev-parse", "HEAD"])
    
    # 2. If we triggered a new build, monitor it until completion
    if triggered:
        success = monitor_cloud_build(repo, commit_sha)
        if not success:
            sys.exit(1)

    # 3. Run the get_ipa.py script to download the IPA
    print("\nStarting get_ipa.py to download the compiled IPA...")
    print("====================================================")
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    get_ipa_script = os.path.join(script_dir, "get_ipa.py")
    
    # Execute get_ipa.py and show its output interactively
    proc = subprocess.Popen([sys.executable, get_ipa_script, repo], stdout=sys.stdout, stderr=sys.stderr, shell=True)
    proc.wait()
    
    if proc.returncode != 0:
        print("\nError: get_ipa.py execution failed.")
        sys.exit(1)

    # 4. Copy the downloaded IPA from get_ipa's destination to releases/OBS-Webcam-Clone-unsigned.ipa
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
