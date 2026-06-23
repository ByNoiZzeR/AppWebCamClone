import urllib.request
import json

def main():
    run_id = "27990274902"
    print(f"Fetching jobs for run {run_id}...")
    
    # 1. Get Jobs
    req = urllib.request.Request(
        f"https://api.github.com/repos/ByNoiZzeR/droidcam-obs-plugin/actions/runs/{run_id}/jobs",
        headers={"User-Agent": "Mozilla/5.0"}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            jobs = data.get("jobs", [])
            if not jobs:
                print("No jobs found.")
                return
            for job in jobs:
                print(f"Job: {job['name']}, Status: {job['status']}, Conclusion: {job['conclusion']}")
                check_run_url = job.get("check_run_url")
                print(f"Check Run URL: {check_run_url}")
                
                # Fetch annotations
                if check_run_url:
                    annotations_url = f"{check_run_url}/annotations"
                    print(f"Fetching annotations from {annotations_url}...")
                    req_ann = urllib.request.Request(
                        annotations_url,
                        headers={"User-Agent": "Mozilla/5.0", "Accept": "application/vnd.github.v3+json"}
                    )
                    with urllib.request.urlopen(req_ann) as resp_ann:
                        annotations = json.loads(resp_ann.read().decode('utf-8'))
                        print(f"Found {len(annotations)} annotations:")
                        for idx, ann in enumerate(annotations):
                            print(f"\n[{idx+1}] Path: {ann.get('path')}")
                            print(f"Line: {ann.get('start_line')} - {ann.get('end_line')}")
                            print(f"Level: {ann.get('annotation_level')}")
                            print(f"Title: {ann.get('title')}")
                            print(f"Message: {ann.get('message')}")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    main()
