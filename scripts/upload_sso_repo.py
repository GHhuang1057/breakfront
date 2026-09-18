#!/usr/bin/env python3
"""Upload GeekHonize SSO to the standalone GHhuang1057/geekhonize-sso repo.

- Verbatim source from G:/sxsm-auth-cf (EXCLUDING migrate_users.sql = real PII)
- Generated config/docs from G:/BF_MC/geekhonize-sso-staging
- Refuses to upload anything matching a secret/PII pattern.
"""
import sys, os, base64, re
sys.path.insert(0, 'G:/BF_MC/scripts')
from gh_api import _req, TOKEN

REPO = 'GHhuang1057/geekhonize-sso'
SRC = 'G:/sxsm-auth-cf'
STAGE = 'G:/BF_MC/geekhonize-sso-staging'

# (local_path, repo_path)
VERBATIM = [
    ('src/index.ts', 'src/index.ts'),
    ('schema.sql', 'schema.sql'),
    ('package.json', 'package.json'),
    ('package-lock.json', 'package-lock.json'),
    ('public/index.html', 'public/index.html'),
]
STAGED = [
    ('README.md', 'README.md'),
    ('docs/api.md', 'docs/api.md'),
    ('.gitignore', '.gitignore'),
    ('.dev.vars.example', '.dev.vars.example'),
    ('wrangler.toml', 'wrangler.toml'),
    ('.github/workflows/deploy.yml', '.github/workflows/deploy.yml'),
]

# Secret / PII patterns that must NEVER be uploaded.
# (Placeholders like re_xxxx… or JWT_SECRET=change-me… are explicitly allowed.)
FORBIDDEN = [
    re.compile(r'6f0ec0bc-5559-4fd4-a571-d15215631bc0'),  # real D1 id
    re.compile(r'huang1057@outlook\.com'),                  # real email
    re.compile(r'\$argon2id\$'),                            # real password hashes
    re.compile(r'sk-[A-Za-z0-9]{20,}'),                    # generic api key
]

def read_text(p):
    with open(p, 'r', encoding='utf-8') as f:
        return f.read()

def scan(path, text):
    for rx in FORBIDDEN:
        m = rx.search(text)
        if m:
            raise SystemExit(f'REFUSED: forbidden pattern {rx.pattern!r} in {path} -> {m.group(0)[:30]!r}')
    # Resend key: only refuse a *real-looking* key (not the all-x placeholder)
    for m in re.finditer(r're_([A-Za-z0-9]{20,})', text):
        if set(m.group(1)) != {'x'}:
            raise SystemExit(f'REFUSED: possible Resend key in {path} -> re_{m.group(1)[:12]}…')

def get_sha(path):
    try:
        j = _req('GET', f'https://api.github.com/repos/{REPO}/contents/{path}')
        return j.get('sha')
    except Exception:
        return None

def upsert(path, content, message):
    b64 = base64.b64encode(content.encode('utf-8')).decode('ascii')
    sha = get_sha(path)
    data = {'message': message, 'content': b64, 'branch': 'main'}
    if sha:
        data['sha'] = sha
    return _req('PUT', f'https://api.github.com/repos/{REPO}/contents/{path}', data)

uploaded = []
# verbatim (text files)
for local, repo_path in VERBATIM:
    lp = os.path.join(SRC, local)
    text = read_text(lp)
    scan(local, text)
    r = upsert(repo_path, text, f'chore: add {repo_path} (verbatim from geekhonize-auth)')
    uploaded.append((repo_path, r.get('commit', {}).get('sha')))
    print('uploaded', repo_path)

# staged (generated)
for local, repo_path in STAGED:
    lp = os.path.join(STAGE, local)
    text = read_text(lp)
    scan(local, text)
    r = upsert(repo_path, text, f'docs: add {repo_path}')
    uploaded.append((repo_path, r.get('commit', {}).get('sha')))
    print('uploaded', repo_path)

print('TOTAL', len(uploaded), 'files')
for p, sha in uploaded:
    print('  ', p, sha)
