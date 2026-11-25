#!/usr/bin/env python3
"""Audit acceptance criteria against named tests and owning slices."""
import argparse
import re
import subprocess
from collections import Counter
from pathlib import Path

DEFINITION = re.compile(r'^- (AC-([A-Z]+)-(\d+))\b(?: \((M\d+\.\d+)\))?')
JAVA_METHOD = re.compile(
    r'@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b(?:\s*\([^)]*\))?'
    r'(?:\s|@\w+(?:\s*\([^)]*\))?)*(?:public\s+|protected\s+|private\s+)?'
    r'void\s+(ac_[a-z]+_\d+(?:_\w*)?)\s*\('
)
JAVA_COMMENTS = re.compile(r'//[^\n]*|/\*.*?\*/', re.DOTALL)
PYTHON_TEST = re.compile(r'^\s*def (test_ac_[a-z]+_\d+(?:_\w*)?)\s*\(', re.MULTILINE)
SLICE_IN_SUBJECT = re.compile(r'\((M\d+\.\d+)[,)]')


def committed_slices(root):
    """Slices named as `(Mn.m)` or `(Mn.m, ...)` in a commit subject of the checked-out history, or None."""
    try:
        log = subprocess.run(['git', '-C', str(root), 'log', '--format=%s'], text=True, capture_output=True)
    except OSError:
        return None
    if log.returncode != 0:
        return None
    return set(SLICE_IN_SUBJECT.findall(log.stdout))


def audit(spec, root, committed):
    if not spec.is_dir():
        print(f'{spec}: spec directory does not exist')
        return 1
    definitions = []
    for path in sorted(spec.rglob('*.md')):
        for line_number, line in enumerate(path.read_text().splitlines(), 1):
            match = DEFINITION.match(line)
            if match:
                definitions.append((path, line_number, match.group(1), match.group(4)))

    if not definitions:
        print(f'{spec}: no criteria found')
        return 1

    names = set()
    for path in root.rglob('*.java'):
        parts = path.relative_to(root).parts
        if 'target' not in parts and any(parts[i:i + 2] == ('src', 'test') for i in range(len(parts) - 1)):
            names.update(JAVA_METHOD.findall(JAVA_COMMENTS.sub('', path.read_text())))
    for path in (root / '.github' / 'scripts').glob('test_*.py'):
        names.update(name[5:] for name in PYTHON_TEST.findall(path.read_text()))

    counts = Counter(item[2] for item in definitions)
    tested = pending = failing = 0
    for path, line, criterion, owner in definitions:
        prefix = criterion.lower().replace('-', '_')
        covered = any(name == prefix or name.startswith(prefix + '_') for name in names)
        reasons = []
        if counts[criterion] > 1:
            reasons.append('duplicate definition')
        if not covered and not owner:
            reasons.append('no named test or owning slice')
        elif not covered and owner in committed:
            reasons.append(f'no named test and owning slice {owner} is committed')
        if reasons:
            failing += 1
            print(f'{path}:{line} {criterion}: {", ".join(reasons)}')
        elif covered:
            tested += 1
        else:
            pending += 1
    print(f'{len(definitions)} criteria: {tested} tested, {pending} pending on a slice, {failing} failing')
    return int(failing > 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spec', type=Path, default=Path('docs/spec'))
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--committed-slices', help='comma-separated slices to treat as committed instead of git log')
    args = parser.parse_args()
    if args.committed_slices is not None:
        committed = {item.strip() for item in args.committed_slices.split(',') if item.strip()}
    else:
        committed = committed_slices(args.root)
        if committed is None:
            print(f'{args.root}: cannot read git history to find committed slices')
            return 1
    return audit(args.spec, args.root, committed)


if __name__ == '__main__':
    raise SystemExit(main())
