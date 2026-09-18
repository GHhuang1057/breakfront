#!/usr/bin/env python3
"""Poll a GitHub Actions run until it finishes; print conclusion + key steps."""
import sys, time
sys.path.insert(0, 'G:/BF_MC/scripts')
from gh_api import _req

repo = sys.argv[1]
run_id = int(sys.argv[2])
timeout = int(sys.argv[3]) if len(sys.argv) > 3 else 1500  # seconds
interval = 30

deadline = time.time() + timeout
while time.time() < deadline:
    try:
        r = _req('GET', f'https://api.github.com/repos/{repo}/actions/runs/{run_id}')
        status = r.get('status')
        concl = r.get('conclusion')
    except Exception as e:
        print('poll error:', e)
        time.sleep(interval)
        continue
    print(f'[{time.strftime("%H:%M:%S")}] status={status} conclusion={concl}')
    if status == 'completed':
        print('FINAL', status, concl)
        # dump failed steps if any
        if concl != 'success':
            try:
                jobs = _req('GET', f'https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs?per_page=50')
                for j in jobs.get('jobs', []):
                    for st in j.get('steps', []):
                        if st.get('conclusion') in ('failure', 'cancelled'):
                            print('FAILED STEP:', j.get('name'), '->', st.get('name'))
            except Exception as e:
                print('jobs fetch error:', e)
        sys.exit(0)
    time.sleep(interval)
print('TIMEOUT waiting for run', run_id)
