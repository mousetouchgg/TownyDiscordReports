#!/usr/bin/env python3
"""Check the staged Git snapshot or export an audited, source-only Git working copy."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
WRAPPER_SHA256 = '423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9'
ROOT_FILES = {
    '.gitignore', '.gitattributes', 'AGENTS.md', 'README.md', 'LICENSE', 'LICENSE.md',
    'THIRD_PARTY_NOTICES.md', 'build.gradle', 'settings.gradle', 'gradlew', 'gradlew.bat',
    'compose.yml', '.env.example', '.githooks/pre-commit',
    'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'src/main/resources/config.yml', 'src/main/resources/plugin.yml',
    'src/main/resources/schema-v1.sql', 'src/integration/resources/plugin.yml',
}
SKIP_DIRS = {'.git', '.cache', '.gradle', 'dev', 'build', 'public-repo', '__pycache__',
             '.aws', '.ssh', '.agents', '.codex', '.idea', '.vscode', '.venv', 'venv'}
# Only signatures are reported, never the matched value or the containing line.
SECRET_RULES = [
    ('Discord Webhook', re.compile(r'https://(?:canary\.|ptb\.)?discord(?:app)?\.com/api(?:/v\d+)?/webhooks/\d{16,}/[A-Za-z0-9_-]{20,}')),
    ('private key', re.compile(r'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----')),
    ('AWS access key', re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b')),
    ('GitHub token', re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b')),
    ('credential in URL', re.compile(r'https?://[^\s/@:]+:[^\s/@]+@')),
    ('local user path', re.compile(r'(?:/' + r'home/[^/\s]+/|[A-Za-z]:\\Users\\[^\\\s]+\\)')),
]


def allowed(name):
    """Explicit publication scope; runtime files remain forbidden even with git add -f."""
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts:
        return False
    if name in ROOT_FILES:
        return True
    if any(part in SKIP_DIRS for part in path.parts):
        return False
    if name.startswith(('src/main/java/', 'src/test/java/', 'src/integration/java/')):
        return path.suffix == '.java'
    if name.startswith('scripts/'):
        return path.suffix == '.py'
    if name.startswith('docs/'):
        return path.suffix in {'.md', '.txt'}
    return False


def inspect(name, data, symlink=False):
    problems = []
    if not allowed(name):
        problems.append('outside publication allowlist')
    if symlink:
        problems.append('symbolic link')
    if name == 'gradle/wrapper/gradle-wrapper.jar':
        if hashlib.sha256(data).hexdigest() != WRAPPER_SHA256:
            problems.append('unexpected Gradle Wrapper checksum')
        return problems
    if len(data) > 1024 * 1024:
        problems.append('unexpected file over 1 MiB')
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        return problems + ['unexpected binary file']
    for label, rule in SECRET_RULES:
        if rule.search(text):
            problems.append(label)
    if name in {'src/main/resources/config.yml', '.env.example'}:
        # A valid public template always leaves this value blank, even for fake URLs.
        for line in text.splitlines():
            match = re.match(r'\s*(?:webhook-url\s*:|TDR_WEBHOOK_URL\s*=)\s*(.*)', line)
            if match and match.group(1).split('#', 1)[0].strip() not in {'', "''", '""'}:
                problems.append('non-empty webhook template')
    return problems


def report(entries):
    errors = []
    for name, data, symlink in entries:
        for problem in inspect(name, data, symlink):
            errors.append(f'{name}: {problem}')
    if errors:
        print('Publication check FAILED (values redacted):', file=sys.stderr)
        for error in errors:
            print('  ' + error, file=sys.stderr)
        return False
    print(f'Publication check passed: {len(entries)} files; no known secret signatures.')
    return True


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args])


def staged_entries(root):
    entries = []
    for item in git(root, 'ls-files', '--stage', '-z').split(b'\0'):
        if not item:
            continue
        metadata, raw_name = item.split(b'\t', 1)
        mode, oid, stage = metadata.decode().split()
        if stage != '0':
            raise ValueError('Resolve Git conflicts before publication')
        name = raw_name.decode('utf-8')
        entries.append((name, git(root, 'cat-file', 'blob', oid), mode != '100644' and mode != '100755'))
    if not entries:
        raise ValueError('Git index is empty; stage the intended source files first')
    return entries


def source_entries(root):
    entries = []
    for directory, folders, filenames in os.walk(root, followlinks=False):
        folders[:] = [n for n in folders if n not in SKIP_DIRS and not (Path(directory) / n).is_symlink()]
        for filename in filenames:
            path = Path(directory) / filename
            name = path.relative_to(root).as_posix()
            if allowed(name):
                if path.is_symlink():
                    raise ValueError(f'Source symlink is not publishable: {name}')
                entries.append((name, path.read_bytes(), False))
    return sorted(entries)


def export(output, remote):
    output = output.resolve()
    if output.exists():
        raise ValueError('Output already exists; existing files and Git history will not be overwritten')
    if output == ROOT or ROOT.is_relative_to(output):
        raise ValueError('Output must not contain the source workspace')
    if remote and not re.fullmatch(r'https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?', remote):
        raise ValueError('Use a GitHub HTTPS URL without embedded credentials')
    entries = source_entries(ROOT)
    if not report(entries):
        return 1
    output.mkdir(parents=True)
    for name, data, _ in entries:
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        if os.access(ROOT / name, os.X_OK):
            path.chmod(0o755)
    subprocess.run(['git', 'init', '--initial-branch=main', str(output)], check=True)
    if remote:
        git(output, 'remote', 'add', 'origin', remote)
    git(output, 'config', 'core.hooksPath', '.githooks')
    git(output, 'add', '--all')
    if not report(staged_entries(output)):
        return 1
    git(output, 'diff', '--cached', '--check')
    print(f'Source-only repository prepared: {output}')
    print('Files staged on main. No commit or push has been performed.')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('check', help='scan every staged file, including already tracked files')
    copy = sub.add_parser('export', help='create a new, source-only local Git repository')
    copy.add_argument('--output', type=Path, default=ROOT / 'public-repo')
    copy.add_argument('--remote')
    args = parser.parse_args()
    try:
        if args.command == 'export':
            return export(args.output, args.remote)
        if not report(staged_entries(ROOT)):
            return 1
        git(ROOT, 'diff', '--cached', '--check')
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError):
        # No subprocess output or exception messages: they could include credentials.
        print('Publication operation failed. Check the Git index, output path, and file permissions.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
