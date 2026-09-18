#!/usr/bin/env python3
"""Reusable GitHub contents/API helper for the breakfront launcher forks.

All HTTP goes through the live SOCKS proxy 127.0.0.1:10808 (socks5h).
Token is pulled from git credential fill (user GHhuang1057).
"""
import os
import sys
import json
import base64
import subprocess
import urllib.request
import urllib.error

PROXY = "socks5h://127.0.0.1:10808"
API = "https://api.github.com"

# install PySocks-backed opener
try:
    import socks  # noqa
    from urllib.request import build_opener, ProxyHandler, HTTPSHandler
    _opener = build_opener(ProxyHandler({"http": PROXY, "https": PROXY}))
except Exception as e:
    print("PySocks unavailable, trying direct:", e, file=sys.stderr)
    _opener = None


def _get_token():
    """Read GitHub token from git credential fill."""
    try:
        proc = subprocess.run(
            ["git", "credential", "fill"],
            input=b"protocol=https\nhost=github.com\n\n",
            capture_output=True, timeout=30,
        )
        text = proc.stdout.decode("utf-8", "replace")
        for line in text.splitlines():
            if line.lower().startswith("password="):
                return line.split("=", 1)[1].strip()
    except Exception as e:
        print("token fetch via git failed:", e, file=sys.stderr)
    # 环境变量兜底（CI 用 GH_TOKEN；本机靠 git credential）。绝不把令牌写进本文件。
    return os.environ.get("GH_TOKEN", "")


TOKEN = _get_token()
if not TOKEN:
    print("ERROR: no GitHub token (git credential fill failed and GH_TOKEN unset)", file=sys.stderr)


def _req(method, url, data=None, is_json=True):
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "bf-launcher-script",
    }
    body = None
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    opener = _opener or urllib.request.build_opener()
    try:
        with opener.open(req, timeout=60) as r:
            raw = r.read().decode("utf-8", "replace")
            if not raw:
                return {}
            return json.loads(raw)
    except urllib.error.HTTPError as e:
        err = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {e.code} {method} {url}: {err[:500]}")


def get_contents(repo, path, ref=None):
    """Return dict with 'content' (decoded str), 'sha', 'path'."""
    url = f"{API}/repos/{repo}/contents/{path}"
    if ref:
        url += f"?ref={ref}"
    j = _req("GET", url)
    content = base64.b64decode(j["content"]).decode("utf-8", "replace")
    return {"content": content, "sha": j["sha"], "path": j["path"], "raw": j}


def put_contents(repo, path, content, sha, message, branch="main"):
    url = f"{API}/repos/{repo}/contents/{path}"
    b64 = base64.b64encode(content.encode("utf-8")).decode("ascii")
    data = {"message": message, "content": b64, "sha": sha, "branch": branch}
    return _req("PUT", url, data)


def trigger_workflow(repo, workflow, ref="main", inputs=None):
    url = f"{API}/repos/{repo}/actions/workflows/{workflow}/dispatches"
    data = {"ref": ref}
    if inputs:
        data["inputs"] = inputs
    return _req("POST", url, data)


def list_runs(repo, workflow=None, per_page=10):
    if workflow:
        url = f"{API}/repos/{repo}/actions/workflows/{workflow}/runs?per_page={per_page}"
    else:
        url = f"{API}/repos/{repo}/actions/runs?per_page={per_page}"
    return _req("GET", url)


def get_run(repo, run_id):
    return _req("GET", f"{API}/repos/{repo}/actions/runs/{run_id}")


if __name__ == "__main__":
    # quick self-test: print whoami
    try:
        me = _req("GET", f"{API}/user")
        print("authenticated as:", me.get("login"))
    except Exception as e:
        print("whoami failed:", e)
