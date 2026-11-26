import importlib.util
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RELEASE_YML = ROOT / '.github' / 'workflows' / 'release.yml'
CI_YML = ROOT / '.github' / 'workflows' / 'ci.yml'
ROOT_POM = ROOT / 'pom.xml'
CHANGELOG = ROOT / 'CHANGELOG.md'
CHECK_SCRIPT = Path(__file__).resolve().parent / 'check_release_bundle.py'

POM_NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def load_check_module():
    spec = importlib.util.spec_from_file_location('check_release_bundle', CHECK_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make_fake_pom(group_id, artifact_id, version, license_name='Apache License, Version 2.0'):
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group_id}</groupId>
  <artifactId>{artifact_id}</artifactId>
  <version>{version}</version>
  <name>{artifact_id}</name>
  <description>Fake POM for {artifact_id}</description>
  <url>https://github.com/rey5137/topolith</url>
  <licenses>
    <license>
      <name>{license_name}</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0</url>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>rey5137</id>
      <name>Rey Pham</name>
    </developer>
  </developers>
  <scm>
    <url>https://github.com/rey5137/topolith</url>
    <connection>scm:git:https://github.com/rey5137/topolith.git</connection>
    <developerConnection>scm:git:ssh://git@github.com/rey5137/topolith.git</developerConnection>
  </scm>
</project>
"""


def populate_valid_bundle(staging_dir, version='0.1.0'):
    module = load_check_module()
    group_dir = Path(staging_dir) / 'io' / 'github' / 'rey5137'
    for artifact_id, packaging in module.PUBLISHED_ARTIFACTS.items():
        vdir = group_dir / artifact_id / version
        vdir.mkdir(parents=True, exist_ok=True)
        base = f'{artifact_id}-{version}'
        pom_file = vdir / f'{base}.pom'
        pom_file.write_text(make_fake_pom('io.github.rey5137', artifact_id, version))
        files = [f'{base}.pom']
        if packaging == 'jar':
            for suffix in ('.jar', '-sources.jar', '-javadoc.jar'):
                f = vdir / f'{base}{suffix}'
                f.write_bytes(b'fake-jar-bytes')
                files.append(f'{base}{suffix}')
        for filename in files:
            (vdir / f'{filename}.asc').write_text('-----BEGIN PGP SIGNATURE-----\nfake\n-----END PGP SIGNATURE-----\n')
            for ext in ('.md5', '.sha1', '.sha256', '.sha512'):
                (vdir / f'{filename}{ext}').write_text('deadbeef')


class ReleaseTest(unittest.TestCase):
    def test_ac_build_13_root_pom_release_profile_and_ci_dry_run(self):
        tree = ET.parse(ROOT_POM)
        root = tree.getroot()
        profile = root.find("m:profiles/m:profile[m:id='release']", POM_NS)
        self.assertIsNotNone(profile, 'release profile missing in root pom.xml')

        plugins = {
            p.find('m:artifactId', POM_NS).text: p
            for p in profile.findall('m:build/m:plugins/m:plugin', POM_NS)
        }
        for required in (
            'maven-source-plugin',
            'maven-javadoc-plugin',
            'maven-gpg-plugin',
            'central-publishing-maven-plugin',
        ):
            self.assertIn(required, plugins, f'missing {required} in release profile')
            version = plugins[required].find('m:version', POM_NS)
            self.assertIsNotNone(version, f'{required} must declare a version')
            self.assertTrue((version.text or '').strip(), f'{required} version is empty')

        gpg_signer = plugins['maven-gpg-plugin'].find('m:configuration/m:signer', POM_NS)
        self.assertIsNotNone(gpg_signer)
        self.assertEqual('bc', gpg_signer.text)

        central_cfg = plugins['central-publishing-maven-plugin'].find('m:configuration', POM_NS)
        self.assertIsNotNone(central_cfg)
        self.assertEqual('central', central_cfg.find('m:publishingServerId', POM_NS).text)
        self.assertEqual('false', central_cfg.find('m:autoPublish', POM_NS).text)
        excludes = [e.text for e in central_cfg.findall('m:excludeArtifacts/m:excludeArtifact', POM_NS)]
        self.assertIn('topolith-samples', excludes)

        ci_text = CI_YML.read_text()
        self.assertIn('release-dry-run:', ci_text)
        self.assertIn('-Prelease', ci_text)
        self.assertIn('check_release_bundle.py', ci_text)
        self.assertNotIn('-Drevision=0.1.0', ci_text)
        release_text = (CI_YML.parent / 'release.yml').read_text()
        self.assertIn('check_release_bundle.py target/central-staging "${RELEASE_VERSION}"', release_text)
        self.assertNotIn('version="0.1.0"', release_text)

    def test_ac_build_13_check_release_bundle_validates_coordinates_signatures_license_and_samples(self):
        checker = load_check_module()

        with tempfile.TemporaryDirectory() as tmp:
            populate_valid_bundle(tmp)
            checker.check_bundle(tmp)
            self.assertEqual(0, checker.main([tmp]))

        with tempfile.TemporaryDirectory() as tmp:
            populate_valid_bundle(tmp, version='0.2.0')
            self.assertEqual(0, checker.main([tmp, '0.2.0']))
            self.assertEqual(1, checker.main([tmp]))

        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, r'group directory io\.github\.rey5137 not found'):
                checker.check_bundle(tmp)
            self.assertEqual(1, checker.main([tmp]))

        with tempfile.TemporaryDirectory() as tmp:
            populate_valid_bundle(tmp)
            asc = Path(tmp) / 'io/github/rey5137/topolith-core/0.1.0/topolith-core-0.1.0.jar.asc'
            asc.unlink()
            with self.assertRaisesRegex(ValueError, r'missing signature \(\.asc\)'):
                checker.check_bundle(tmp)
            self.assertEqual(1, checker.main([tmp]))

        with tempfile.TemporaryDirectory() as tmp:
            populate_valid_bundle(tmp)
            javadoc = Path(tmp) / 'io/github/rey5137/topolith-core/0.1.0/topolith-core-0.1.0-javadoc.jar'
            javadoc.unlink()
            with self.assertRaisesRegex(ValueError, r'topolith-core-0\.1\.0-javadoc\.jar: missing artifact file'):
                checker.check_bundle(tmp)
            self.assertEqual(1, checker.main([tmp]))

        with tempfile.TemporaryDirectory() as tmp:
            populate_valid_bundle(tmp)
            sha1 = Path(tmp) / 'io/github/rey5137/topolith-core/0.1.0/topolith-core-0.1.0.jar.sha1'
            sha1.unlink()
            with self.assertRaisesRegex(ValueError, r'missing checksum \.sha1 for topolith-core-0\.1\.0\.jar'):
                checker.check_bundle(tmp)
            self.assertEqual(1, checker.main([tmp]))

        with tempfile.TemporaryDirectory() as tmp:
            populate_valid_bundle(tmp)
            sample_dir = Path(tmp) / 'io/github/rey5137/topolith-samples/0.1.0'
            sample_dir.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, r'unexpected or sample artifact staged: topolith-samples'):
                checker.check_bundle(tmp)
            self.assertEqual(1, checker.main([tmp]))

        with tempfile.TemporaryDirectory() as tmp:
            populate_valid_bundle(tmp)
            pom = Path(tmp) / 'io/github/rey5137/topolith-core/0.1.0/topolith-core-0.1.0.pom'
            pom.write_text(make_fake_pom('io.github.rey5137', 'topolith-core', '${revision}'))
            with self.assertRaisesRegex(ValueError, r"expected version 0\.1\.0, got '\$\{revision\}'"):
                checker.check_bundle(tmp)
            self.assertEqual(1, checker.main([tmp]))

        with tempfile.TemporaryDirectory() as tmp:
            populate_valid_bundle(tmp)
            pom = Path(tmp) / 'io/github/rey5137/topolith-core/0.1.0/topolith-core-0.1.0.pom'
            pom.write_text(make_fake_pom('io.github.rey5137', 'topolith-core', '0.1.0', license_name='MIT License'))
            with self.assertRaisesRegex(ValueError, r'license must be Apache-2\.0'):
                checker.check_bundle(tmp)
            self.assertEqual(1, checker.main([tmp]))

    def test_ac_build_14_release_workflow_and_changelog_0_1_0(self):
        workflow = RELEASE_YML.read_text()
        self.assertRegex(workflow, r"tags:\s*\n\s*-\s*['\"]?v\*['\"]?")
        self.assertIn('workflow_dispatch:', workflow)
        self.assertRegex(workflow, r'dry_run:\s*\n(?:\s+.*\n)*?\s+type:\s*boolean')
        self.assertRegex(workflow, r'dry_run:\s*\n(?:\s+.*\n)*?\s+default:\s*true')
        self.assertNotIn('pull_request', workflow)
        self.assertIn(
            "github.event_name == 'workflow_dispatch' && inputs.dry_run != true"
            " && !startsWith(github.ref, 'refs/tags/v')",
            workflow,
        )
        self.assertIn(r'^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$', workflow)
        self.assertIn('git fetch origin main', workflow)
        self.assertIn('git merge-base --is-ancestor "$GITHUB_SHA" origin/main', workflow)
        self.assertIn('server-id: central', workflow)
        self.assertIn('environment: maven-central', workflow)
        self.assertIn('server-username: CENTRAL_USERNAME', workflow)
        self.assertIn('server-password: CENTRAL_TOKEN', workflow)
        self.assertIn('CENTRAL_USERNAME: ${{ secrets.CENTRAL_USERNAME }}', workflow)
        self.assertIn('CENTRAL_TOKEN: ${{ secrets.CENTRAL_TOKEN }}', workflow)
        self.assertIn('MAVEN_GPG_KEY: ${{ secrets.GPG_PRIVATE_KEY }}', workflow)
        self.assertIn('MAVEN_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}', workflow)
        self.assertIn('-Prelease', workflow)
        self.assertIn('deploy', workflow)
        self.assertIn('-DskipPublishing=true', workflow)
        self.assertIn('CHANGELOG.md', workflow)

        changelog = CHANGELOG.read_text()
        self.assertIn('## [Unreleased]', changelog)
        self.assertRegex(changelog, r'(?m)^## \[0\.1\.0\] - \d{4}-\d{2}-\d{2}\b')
        self.assertIn('### Added', changelog)
        self.assertIn('### Notes for adopters', changelog)
        self.assertIn('@Incubating', changelog)
        self.assertIn('topolith-bom', changelog)
        self.assertIn('@TopolithMock(id =', changelog)
        self.assertIn('[Unreleased]: https://github.com/rey5137/topolith/compare/v0.1.0...HEAD', changelog)
        self.assertIn('[0.1.0]: https://github.com/rey5137/topolith/releases/tag/v0.1.0', changelog)


if __name__ == '__main__':
    unittest.main()
