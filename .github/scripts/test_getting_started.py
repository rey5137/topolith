import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GUIDE = ROOT / 'docs/site/docs/guide/getting-started.md'
SCRIPT = ROOT / '.github/scripts/getting_started.sh'


def guide_commands(guide):
    section = re.search(r'^## 5\. .*?\n(.*?)(?=^## 6\.)', guide, re.MULTILINE | re.DOTALL)
    if not section:
        raise AssertionError('Getting-started section 5 not found')
    blocks = re.findall(r'^```bash\s*\n(.*?)^```', section.group(1), re.MULTILINE | re.DOTALL)
    commands = [line.strip() for block in blocks for line in block.splitlines() if line.strip().startswith('./mvnw')]
    if not commands:
        raise AssertionError('No Maven commands found in section 5 bash blocks')
    return commands


def check_commands(guide, script):
    for command in guide_commands(guide):
        if command not in script:
            raise AssertionError(f'Missing guide command: {command}')


class GettingStartedTest(unittest.TestCase):
    def test_guide_commands_match_script(self):
        guide = GUIDE.read_text()
        script = SCRIPT.read_text()
        check_commands(guide, script)
        with self.assertRaisesRegex(AssertionError, 'Missing guide command'):
            check_commands(guide.replace('./mvnw -q install -DskipTests', './mvnw -q install -Dchanged'), script)

    def test_empty_section_fails(self):
        with self.assertRaisesRegex(AssertionError, 'No Maven commands'):
            guide_commands('## 5. Running\n```bash\necho hello\n```\n## 6. Testing\n')


if __name__ == '__main__':
    unittest.main()
