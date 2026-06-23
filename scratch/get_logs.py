import urllib.request
import json
import sys

def main():
    run_id = "27989527703"
    print(f"Fetching jobs for run {run_id}...")
    
    # 1. Get Jobs
    req = urllib.request.Request(
        f"https://api.github.com/repos/ByNoiZzeR/droidcam-obs-plugin/actions/runs/{run_id}/jobs",
        headers={"User-Agent": "Mozilla/5.0"}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
            jobs = data.get("jobs", [])
            if not jobs:
                print("No jobs found.")
                return
            job = jobs[0]
            job_id = job["id"]
            print(f"Found job: {job['name']} (ID: {job_id})")
    except Exception as e:
        print(f"Failed to fetch jobs: {e}")
        return

    # 2. Get Logs
    # Note: download logs via API requires authorization header, but let's see if we get a redirect or error.
    log_url = f"https://api.github.com/repos/ByNoiZzeR/droidcam-obs-plugin/actions/jobs/{job_id}/logs"
    print(f"Requesting logs from {log_url}...")
    req_log = urllib.request.Request(
        log_url,
        headers={"User-Agent": "Mozilla/5.0"}
    )
    try:
        with urllib.request.urlopen(req_log) as resp:
            log_data = resp.read()
            print("Logs fetched successfully:")
            print(log_data[-2000:].decode('utf-8', errors='ignore'))
    except Exception as e:
        print(f"Could not fetch logs directly: {e}")
        print("Note: Log download requires authentication.")

if __name__ == "__main__":
    main()
