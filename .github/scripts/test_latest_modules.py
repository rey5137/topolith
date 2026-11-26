import re
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[1] / 'workflows' / 'latest-modules.yml'
RELEASE = re.compile(r'release:\s*\n\s*types:\s*\[\s*published\s*\]')
PUSH_MAIN = re.compile(r'push:\s*\n\s*branches:\s*\[\s*main\s*\]')
HOSTS = ('samples/hosts', 'samples/hosts/monolith', 'samples/hosts/hybrid', 'samples/hosts/payment-host')


def run_steps(text):
    """The YAML `run:` commands, folded or block scalars joined into one line each."""
    lines = text.splitlines()
    commands = []
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped.startswith('run:'):
            continue
        command = stripped[len('run:'):].strip()
        if command in ('>', '>-', '|', '|-'):
            command = ''
        child_indent = len(line) - len(line.lstrip()) + 1
        for following in lines[index + 1:]:
            if not following.strip() or len(following) - len(following.lstrip()) < child_indent:
                break
            command += ' ' + following.strip()
        commands.append(command)
    return commands


class LatestModulesTest(unittest.TestCase):
    def test_ac_build_10_latest_modules_rebuilds_each_host(self):
        text = WORKFLOW.read_text()
        self.assertIn('workflow_dispatch:', text)
        self.assertRegex(text, RELEASE)
        self.assertRegex(text, PUSH_MAIN)
        self.assertRegex(text, r'java-version:\s*21\b')

        commands = run_steps(text)
        installs = [index for index, command in enumerate(commands)
                    if 'install' in command and '-DskipTests' in command]
        self.assertEqual(1, len(installs), commands)
        install = commands[installs[0]]
        for host in HOSTS:
            self.assertIn(f'!{host}', install, install)
        self.assertNotRegex(install, rf'(?<!!){re.escape("samples/hosts")}', install)

        rebuilds = [index for index, command in enumerate(commands)
                    if '-f samples/hosts/pom.xml' in command and 'verify' in command]
        self.assertEqual(1, len(rebuilds), commands)
        self.assertLess(installs[0], rebuilds[0], 'hosts rebuild after the module install')
