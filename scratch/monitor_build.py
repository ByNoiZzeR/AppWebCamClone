import urllib.request
import json
import time
import sys
import subprocess

def get_local_sha():
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"]).decode("utf-8").strip()
    except Exception as e:
        print(f"Error getting local SHA: {e}")
        return None

def get_latest_runs():
    url = "https://api.github.com/repos/ByNoiZzeR/droidcam-obs-plugin/actions/runs?per_page=5"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            return data.get("workflow_runs", [])
    except Exception as e:
        print(f"Error fetching runs: {e}")
    return []

def main():
    local_sha = get_local_sha()
    if not local_sha:
        print("Could not retrieve local Git SHA. Exiting.")
        sys.exit(1)
        
    print(f"Local Git SHA: {local_sha}")
    print("Waiting for GitHub Action run with matching SHA to start...")
    
    target_run = None
    while True:
        runs = get_latest_runs()
        for run in runs:
            if run["head_sha"] == local_sha:
                target_run = run
                break
        if target_run:
            break
        sys.stdout.write(".")
        sys.stdout.flush()
        time.sleep(5)
        
    run_id = target_run["id"]
    print(f"\nFound matching workflow run! Run ID: {run_id}")
    print(f"URL: {target_run['html_url']}")
    
    last_status = None
    while True:
        # Fetch the specific run details to update status
        url = f"https://api.github.com/repos/ByNoiZzeR/droidcam-obs-plugin/actions/runs/{run_id}"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            with urllib.request.urlopen(req) as resp:
                run = json.loads(resp.read().decode('utf-8'))
                status = run["status"]
                conclusion = run["conclusion"]
                
                current_status = f"Status: {status} | Conclusion: {conclusion}"
                if current_status != last_status:
                    print(f"\n{current_status}")
                    last_status = current_status
                    
                if status == "completed":
                    print(f"Workflow finished with conclusion: {conclusion}")
                    if conclusion != "success":
                        sys.exit(1)
                    else:
                        sys.exit(0)
        except Exception as e:
            print(f"Error fetching run details: {e}")
            
        sys.stdout.write(".")
        sys.stdout.flush()
        time.sleep(5)

if __name__ == "__main__":
    main()
