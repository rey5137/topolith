import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / 'ac_audit.py'
WORKFLOW = Path(__file__).resolve().parents[1] / 'workflows' / 'ci.yml'


class AuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.spec = self.root / 'spec'
        self.spec.mkdir()

    def run_audit(self, definitions, java='', python='', committed=''):
        (self.spec / 'criteria.md').write_text(definitions)
        if java:
            path = self.root / 'module/src/test/java/Example.java'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(java)
        if python:
            path = self.root / '.github/scripts/test_example.py'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(python)
        args = [sys.executable, str(SCRIPT), '--spec', str(self.spec), '--root', str(self.root)]
        if committed is not None:
            args += ['--committed-slices', committed]
        return subprocess.run(args, text=True, capture_output=True)

    def git(self, *args):
        subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True)

    def test_ac_ci_05_java_named_test(self):
        result = self.run_audit('- AC-FOO-01: text\n', '@Test void ac_foo_01_example() {}')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('1 criteria: 1 tested, 0 pending on a slice, 0 failing', result.stdout)

    def test_ac_ci_05_python_named_test(self):
        result = self.run_audit('- AC-FOO-01: text\n', python='def test_ac_foo_01_example(): pass')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('1 tested', result.stdout)

    def test_ac_ci_05_owning_slice(self):
        result = self.run_audit('- AC-FOO-01 (M1.2): text\n')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('1 pending on a slice', result.stdout)

    def test_ac_ci_06_missing_test_and_owner(self):
        result = self.run_audit('reference AC-FOO-02\n- AC-FOO-01: text\n')
        self.assertEqual(result.returncode, 1)
        self.assertIn('criteria.md:2 AC-FOO-01: no named test or owning slice', result.stdout)
        self.assertIn('1 criteria:', result.stdout)

    def test_ac_ci_06_prefix_boundary(self):
        result = self.run_audit('- AC-CI-1: text\n', '@Test void ac_ci_10_example() {}')
        self.assertEqual(result.returncode, 1)
        self.assertIn('AC-CI-1: no named test or owning slice', result.stdout)

    def test_ac_ci_04_missing_spec(self):
        self.spec.rmdir()
        result = self.run_missing_spec()
        self.assertEqual(result.returncode, 1)
        self.assertIn('spec', result.stdout.lower())

    def test_ac_ci_04_empty_spec(self):
        result = self.run_missing_spec()
        self.assertEqual(result.returncode, 1)
        self.assertIn('criteria', result.stdout.lower())

    def run_missing_spec(self):
        return subprocess.run([sys.executable, str(SCRIPT), '--spec', str(self.spec), '--root', str(self.root),
                               '--committed-slices', ''], text=True, capture_output=True)

    def test_ac_ci_06_commented_test(self):
        result = self.run_audit('- AC-FOO-01: text\n', '// @Test void ac_foo_01_example() {}')
        self.assertEqual(result.returncode, 1)

    def test_ac_ci_06_block_commented_test(self):
        result = self.run_audit('- AC-FOO-01: text\n', '/* @Test\n void ac_foo_01_example() {} */')
        self.assertEqual(result.returncode, 1)

    def test_ac_ci_05_junit_annotations(self):
        for annotation in ('Test', 'ParameterizedTest', 'RepeatedTest', 'TestFactory'):
            with self.subTest(annotation=annotation):
                java = f'@{annotation}\n@DisplayName("example")\nvoid ac_foo_01_example() {{}}'
                result = self.run_audit('- AC-FOO-01: text\n', java)
                self.assertEqual(result.returncode, 0, result.stdout)

    def test_ac_ci_06_unannotated_helper(self):
        result = self.run_audit('- AC-FOO-01: text\n', 'void ac_foo_01_example() {}')
        self.assertEqual(result.returncode, 1)

    def check_excluded_path(self, relative):
        path = self.root / relative
        path.parent.mkdir(parents=True)
        path.write_text('@Test void ac_foo_01_example() {}')
        result = self.run_audit('- AC-FOO-01: text\n')
        self.assertEqual(result.returncode, 1)

    def test_ac_ci_06_target_excluded(self):
        self.check_excluded_path('module/target/src/test/java/Example.java')

    def test_ac_ci_06_main_excluded(self):
        self.check_excluded_path('module/src/main/java/Example.java')

    def test_ac_ci_07_duplicate(self):
        result = self.run_audit('- AC-FOO-01 (M1.2): first\n- AC-FOO-01 (M1.2): second\n')
        self.assertEqual(result.returncode, 1)
        self.assertIn('criteria.md:1 AC-FOO-01: duplicate definition', result.stdout)
        self.assertIn('criteria.md:2 AC-FOO-01: duplicate definition', result.stdout)

    def test_ac_ci_03_pr_matrix(self):
        workflow = WORKFLOW.read_text()
        self.assertRegex(workflow, r'(?m)^on:\s*\n\s+pull_request:')
        self.assertRegex(workflow, r'(?m)^\s+java:\s*\[21,\s*25\]')

    def test_ac_ci_04_gate_and_failure(self):
        workflow = WORKFLOW.read_text()
        self.assertRegex(workflow, r'(?m)^\s+- run: \./mvnw\s+[^\n]*\bverify\b')
        self.assertIn('python3 .github/scripts/ac_audit.py', workflow)
        self.assertIn("python3 -m unittest discover -s .github/scripts -p 'test_*.py'", workflow)
        self.assertNotIn('continue-on-error', workflow)
        result = self.run_audit('- AC-FOO-01: text\n')
        self.assertNotEqual(result.returncode, 0)

    def test_ac_ci_08_committed_slice_needs_test(self):
        result = self.run_audit('- AC-FOO-01 (M1.2): text\n- AC-FOO-02 (M1.3): text\n', committed='M1.2')
        self.assertEqual(result.returncode, 1)
        self.assertIn('criteria.md:1 AC-FOO-01: no named test and owning slice M1.2 is committed', result.stdout)
        self.assertNotIn('AC-FOO-02', result.stdout)

    def test_ac_ci_08_committed_slice_with_test(self):
        java = 'class Example { @Test void ac_foo_01_example() {} }'
        result = self.run_audit('- AC-FOO-01 (M1.2): text\n', java=java, committed='M1.2')
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_ac_ci_08_reads_commit_subjects(self):
        self.git('init', '-q')
        for subject in ('build: skeleton (M1.2)', 'ci: gate (M1.3, AC-FOO-03)', 'docs: plan M1.4 and (M1.5 draft)'):
            self.git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '--allow-empty', '-m', subject)
        criteria = ''.join(f'- AC-FOO-0{n} (M1.{n}): text\n' for n in range(2, 6))
        result = self.run_audit(criteria, committed=None)
        self.assertEqual(result.returncode, 1)
        self.assertIn('AC-FOO-02: no named test and owning slice M1.2 is committed', result.stdout)
        self.assertIn('AC-FOO-03: no named test and owning slice M1.3 is committed', result.stdout)
        self.assertNotIn('AC-FOO-04', result.stdout)
        self.assertNotIn('AC-FOO-05', result.stdout)

    def test_ac_ci_08_fails_without_git_history(self):
        result = self.run_audit('- AC-FOO-01 (M1.2): text\n', committed=None)
        self.assertEqual(result.returncode, 1)
        self.assertIn('cannot read git history', result.stdout)

    def test_ac_ci_08_ci_fetches_full_history(self):
        self.assertRegex(WORKFLOW.read_text(), r'(?m)^\s+fetch-depth:\s*0\s*$')


if __name__ == '__main__':
    unittest.main()
