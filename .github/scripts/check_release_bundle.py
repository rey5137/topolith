#!/usr/bin/env python3
"""Verify the staged Maven Central release bundle produced by a release dry run."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

EXPECTED_GROUP = 'io.github.rey5137'
EXPECTED_VERSION = '0.1.0'
POM_NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

PUBLISHED_ARTIFACTS = {
    'topolith-parent': 'pom',
    'topolith-bom': 'pom',
    'topolith-core': 'jar',
    'topolith-spring-boot': 'jar',
    'topolith-spring-cloud': 'jar',
    'topolith-processor': 'jar',
    'topolith-spring-test': 'jar',
}

REQUIRED_CHECKSUM_EXTS = ('.md5', '.sha1')
OPTIONAL_CHECKSUM_EXTS = ('.sha256', '.sha512')


def text_of(elem, path):
    node = elem.find(path, POM_NS)
    if node is None:
        node = elem.find(path.replace('m:', ''))
    return (node.text or '').strip() if node is not None else ''


def check_pom(pom_path, expected_artifact_id, expected_version):
    try:
        tree = ET.parse(pom_path)
    except ET.ParseError as exc:
        raise ValueError(f'{pom_path}: invalid XML: {exc}') from exc
    root = tree.getroot()
    group_id = text_of(root, 'm:groupId') or text_of(root, 'm:parent/m:groupId')
    artifact_id = text_of(root, 'm:artifactId')
    version = text_of(root, 'm:version') or text_of(root, 'm:parent/m:version')
    if group_id != EXPECTED_GROUP:
        raise ValueError(f'{pom_path}: expected groupId {EXPECTED_GROUP}, got {group_id!r}')
    if artifact_id != expected_artifact_id:
        raise ValueError(f'{pom_path}: expected artifactId {expected_artifact_id}, got {artifact_id!r}')
    if version != expected_version:
        raise ValueError(f'{pom_path}: expected version {expected_version}, got {version!r}')

    for required in ('name', 'description', 'url'):
        if not text_of(root, f'm:{required}'):
            raise ValueError(f'{pom_path}: missing required element <{required}>')
    if root.find('m:developers/m:developer', POM_NS) is None and root.find('developers/developer') is None:
        raise ValueError(f'{pom_path}: missing <developers>')
    if root.find('m:scm', POM_NS) is None and root.find('scm') is None:
        raise ValueError(f'{pom_path}: missing <scm>')

    licenses = root.findall('m:licenses/m:license', POM_NS) or root.findall('licenses/license')
    if not licenses:
        raise ValueError(f'{pom_path}: missing <licenses>')
    has_apache = any(
        'Apache' in text_of(lic, 'm:name') and '2.0' in text_of(lic, 'm:name')
        for lic in licenses
    )
    if not has_apache:
        raise ValueError(f'{pom_path}: license must be Apache-2.0')


def check_bundle(staging_dir, expected_version=EXPECTED_VERSION):
    staging_path = Path(staging_dir)
    if not staging_path.is_dir():
        raise ValueError(f'{staging_path}: staging directory does not exist')

    group_dir = staging_path / Path(*EXPECTED_GROUP.split('.'))
    if not group_dir.is_dir():
        raise ValueError(f'{group_dir}: group directory {EXPECTED_GROUP} not found in staging directory')

    for p in staging_path.iterdir():
        if p.is_dir() and p.name != EXPECTED_GROUP.split('.')[0]:
            raise ValueError(f'{p}: unexpected top-level group directory')

    found_artifacts = sorted(p.name for p in group_dir.iterdir() if p.is_dir())
    for artifact_id in found_artifacts:
        if 'sample' in artifact_id or artifact_id not in PUBLISHED_ARTIFACTS:
            raise ValueError(f'unexpected or sample artifact staged: {artifact_id}')
        if not artifact_id.startswith('topolith-'):
            raise ValueError(f'artifactId {artifact_id} does not start with topolith-')

    missing_artifacts = set(PUBLISHED_ARTIFACTS) - set(found_artifacts)
    if missing_artifacts:
        raise ValueError(f'missing expected published artifacts: {sorted(missing_artifacts)}')

    for artifact_id, packaging in PUBLISHED_ARTIFACTS.items():
        version_dir = group_dir / artifact_id / expected_version
        if not version_dir.is_dir():
            raise ValueError(f'{version_dir}: version directory missing for {artifact_id}')

        base = f'{artifact_id}-{expected_version}'
        expected_primary = [f'{base}.pom']
        if packaging == 'jar':
            expected_primary.extend([
                f'{base}.jar',
                f'{base}-sources.jar',
                f'{base}-javadoc.jar',
            ])

        for filename in expected_primary:
            file_path = version_dir / filename
            if not file_path.is_file():
                raise ValueError(f'{file_path}: missing artifact file')
            asc_path = version_dir / f'{filename}.asc'
            if not asc_path.is_file():
                raise ValueError(f'{asc_path}: missing signature (.asc) for {filename}')

        for filename in expected_primary:
            for ext in REQUIRED_CHECKSUM_EXTS:
                chk = version_dir / f'{filename}{ext}'
                if not chk.is_file():
                    raise ValueError(f'{chk}: missing checksum {ext} for {filename}')

        present_optional_checksums = [
            ext for ext in OPTIONAL_CHECKSUM_EXTS
            if any((version_dir / f'{filename}{ext}').is_file() for filename in expected_primary)
        ]
        for filename in expected_primary:
            for ext in present_optional_checksums:
                chk = version_dir / f'{filename}{ext}'
                if not chk.is_file():
                    raise ValueError(f'{chk}: missing checksum {ext} for {filename}')

        check_pom(version_dir / f'{base}.pom', artifact_id, expected_version)


def main(argv=None):
    args = argv if argv is not None else sys.argv[1:]
    if len(args) not in (1, 2):
        print('Usage: check_release_bundle.py <staging-dir> [<version>]', file=sys.stderr)
        return 2
    version = args[1] if len(args) == 2 else EXPECTED_VERSION
    try:
        check_bundle(Path(args[0]), version)
    except ValueError as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1
    print(f'Release bundle OK: {len(PUBLISHED_ARTIFACTS)} artifacts at {version} verified in {args[0]}')
    return 0

if __name__ == '__main__':
    sys.exit(main())
