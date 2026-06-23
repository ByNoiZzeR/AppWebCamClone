import urllib.request
import json

def main():
    run_id = "27990477334"
    url = f"https://api.github.com/repos/ByNoiZzeR/droidcam-obs-plugin/actions/runs/{run_id}/jobs"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            job = data['jobs'][0]
            print(f"Job: {job['name']} | Conclusion: {job['conclusion']}")
            for step in job['steps']:
                print(f"  Step: {step['name']} | Conclusion: {step['conclusion']}")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    main()
