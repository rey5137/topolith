import re
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / 'docs' / 'site'
SITE_DOCS = SITE / 'docs'
GUIDE = SITE_DOCS / 'guide'
MKDOCS = SITE / 'mkdocs.yml'
DOCS_WORKFLOW = ROOT / '.github' / 'workflows' / 'docs.yml'
CI_WORKFLOW = ROOT / '.github' / 'workflows' / 'ci.yml'
BANNER = SITE_DOCS / 'assets' / 'banner.png'
INDEX = SITE_DOCS / 'index.md'
DIAGNOSTICS = SITE_DOCS / 'diagnostics.md'
CATALOG = ROOT / 'docs' / 'spec' / 'reference' / '90-catalog.md'
RFC = ROOT / 'rfc' / '0001-architecture.md'

GETTING_STARTED = GUIDE / 'getting-started.md'
LEGACY_ADOPTION = GUIDE / 'legacy-adoption.md'
CONFIGURATION = GUIDE / 'configuration.md'

SHELL_LANGS = {'bash', 'sh', 'shell', 'console', 'text', 'mermaid'}
MARKER_RE = re.compile(r'^<!--\s*snippet:\s*([^#\s]+)#([A-Za-z0-9_.-]+)\s*-->$')
BACKTICK_OPEN_RE = re.compile(r'^(`{3,})(.*)$')
TILDE_FENCE_RE = re.compile(r'^~{3,}')
STEP_HEADING_RE = re.compile(r'^##\s+([1-7])\.\s+(.+)$', re.MULTILINE)
RFC_STEP_ROW_RE = re.compile(r'^\|\s*([1-7])\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|$', re.MULTILINE)

REQUIRED_GETTING_STARTED_HEADINGS = (
    '## 1. Import the Platform BOM',
    '## 2. Declare a Module (`*-api` and `*-module`)',
    '## 3. Configure the Annotation Processor',
    '## 4. Choose Modes in a Host (`monolith` vs `hybrid` vs `none`)',
    '## 5. Running Monolith vs Hybrid',
    '## 6. Testing with `topolith-spring-test`',
)

REQUIRED_CONFIGURATION_HEADINGS = (
    '## Modes and Status Vocabulary',
    '## Configuration Properties',
    '## Compile-Time Annotation Processor Option',
    '## API Version Readiness and Actuator Status',
)

REQUIRED_CONFIG_KEYS = (
    'topolith.modules.<id>.mode',
    'topolith.standalone',
    'topolith.modules.<id>.remote.url',
    'topolith.modules.<id>.remote.service-id',
    'topolith.modules.<id>.remote.connect-timeout',
    'topolith.modules.<id>.remote.read-timeout',
    'topolith.modules.<id>.remote.http-interface.<contract FQN>',
    'topolith.modules.<id>.expose.enabled',
)

NAV_PAGE_RE = re.compile(r'^\s*-\s+(?:[^:#]+:\s+)?([A-Za-z0-9_./-]+\.md)\s*$')
TL_CODE_RE = re.compile(r'\bTL\d{4}\b')

CATALOG_TERMS = ('Internal', 'External', 'None', 'Restart pending')


def normalize_block(text):
    lines = text.splitlines()
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines:
        return ''
    dedented = textwrap.dedent('\n'.join(lines))
    return '\n'.join(line.rstrip() for line in dedented.splitlines())


def parse_rfc_adoption_steps(rfc_text):
    """Parses the `| # | Step | Why |` table in RFC 0001 §4.3 and derives key terms from each step cell."""
    match = re.search(r'###\s+4\.3\b.*?(?=\n###\s+|\n##\s+|\Z)', rfc_text, re.DOTALL)
    if not match:
        raise AssertionError('RFC 0001 §4.3 section not found')
    section = match.group(0)
    rows = RFC_STEP_ROW_RE.findall(section)
    steps = []
    for num_str, step_cell, _ in rows:
        code_spans = re.findall(r'`([^`]+)`', step_cell)
        unquoted = re.sub(r'`[^`]+`', ' ', step_cell)
        cap_words = [
            word for word in re.findall(r'\b[A-Z][A-Za-z0-9-]*\b', unquoted)
            if word not in {'Split', 'Add', 'Move', 'Fixed', 'Upgrade'}
        ]
        terms = list(dict.fromkeys(code_spans + cap_words))
        if not terms:
            terms = [step_cell.split()[0]]
        steps.append((int(num_str), step_cell, terms))
    return steps


def extract_guide_snippets(md_text, source_name='<memory>'):
    """Parses fenced blocks in markdown, enforcing snippet markers on every non-shell fence and rejecting ~~~."""
    lines = md_text.splitlines()
    snippets = []
    pending_marker = None
    index = 0
    while index < len(lines):
        stripped = lines[index].strip()
        if TILDE_FENCE_RE.match(stripped):
            raise AssertionError(f'{source_name}:{index + 1}: tilde (~~~) code fences are forbidden in docs/site/docs')
        marker_match = MARKER_RE.match(stripped)
        if marker_match:
            if pending_marker is not None:
                raise AssertionError(f'{source_name}:{index + 1}: duplicate or unused snippet marker')
            pending_marker = (marker_match.group(1), marker_match.group(2), index + 1)
            index += 1
            continue
        fence_match = BACKTICK_OPEN_RE.match(stripped)
        if fence_match:
            ticks = fence_match.group(1)
            info = fence_match.group(2).strip()
            lang = info.split()[0].lower() if info else ''
            open_line = index + 1
            index += 1
            body = []
            while index < len(lines) and lines[index].strip() != ticks:
                if TILDE_FENCE_RE.match(lines[index].strip()):
                    raise AssertionError(
                        f'{source_name}:{index + 1}: tilde (~~~) code fences are forbidden in docs/site/docs'
                    )
                body.append(lines[index])
                index += 1
            if index >= len(lines):
                raise AssertionError(f'{source_name}:{open_line}: unclosed code fence')
            if lang not in SHELL_LANGS:
                if pending_marker is None:
                    raise AssertionError(
                        f'{source_name}:{open_line}: {lang or "unlabelled"} block missing preceding '
                        f'<!-- snippet: path#tag -->'
                    )
                rel_path, tag, _ = pending_marker
                snippets.append((rel_path, tag, lang, normalize_block('\n'.join(body)), open_line))
            elif pending_marker is not None:
                raise AssertionError(
                    f'{source_name}:{open_line}: shell ({lang}) block must not have a snippet marker'
                )
            pending_marker = None
            index += 1
            continue
        if stripped and pending_marker is not None:
            raise AssertionError(
                f'{source_name}:{pending_marker[2]}: snippet marker must immediately precede a fenced block'
            )
        index += 1
    if pending_marker is not None:
        raise AssertionError(f'{source_name}:{pending_marker[2]}: trailing snippet marker without fenced block')
    return snippets


def comment_pattern_for(path_str, kind, tag):
    escaped = re.escape(tag)
    if path_str.endswith('.java'):
        return re.compile(rf'^\s*//\s*snippet:{kind}\s+{escaped}\s*$')
    if path_str.endswith(('.yml', '.yaml', '.properties')):
        return re.compile(rf'^\s*#\s*snippet:{kind}\s+{escaped}\s*$')
    if path_str.endswith('.xml'):
        return re.compile(rf'^\s*<!--\s*snippet:{kind}\s+{escaped}\s*-->\s*$')
    raise AssertionError(f'unsupported source file type for snippet: {path_str}')


def is_any_snippet_comment(path_str, line):
    if path_str.endswith('.java'):
        return bool(re.match(r'^\s*//\s*snippet:(?:start|end)\s+\S+\s*$', line))
    if path_str.endswith(('.yml', '.yaml', '.properties')):
        return bool(re.match(r'^\s*#\s*snippet:(?:start|end)\s+\S+\s*$', line))
    if path_str.endswith('.xml'):
        return bool(re.match(r'^\s*<!--\s*snippet:(?:start|end)\s+\S+\s*-->\s*$', line))
    return False


def allowed_sample_path(rel_path):
    if not rel_path.startswith('samples/'):
        return False
    if rel_path.endswith('/pom.xml'):
        return True
    return '/src/main/' in rel_path or '/src/test/' in rel_path


def extract_source_region(source_text, rel_path, tag):
    start_re = comment_pattern_for(rel_path, 'start', tag)
    end_re = comment_pattern_for(rel_path, 'end', tag)
    lines = source_text.splitlines()
    collecting = False
    collected = []
    found = False
    for line in lines:
        if not collecting:
            if start_re.match(line):
                if found:
                    raise AssertionError(f'{rel_path}#{tag}: duplicate snippet:start')
                collecting = True
                found = True
        else:
            if end_re.match(line):
                collecting = False
            elif not is_any_snippet_comment(rel_path, line):
                collected.append(line)
    if not found:
        raise AssertionError(f'{rel_path}#{tag}: missing snippet:start')
    if collecting:
        raise AssertionError(f'{rel_path}#{tag}: missing snippet:end')
    return normalize_block('\n'.join(collected))


def verify_markdown_snippets(md_text, source_name='<memory>', root=ROOT):
    snippets = extract_guide_snippets(md_text, source_name)
    for rel_path, tag, _, block, line_no in snippets:
        if not allowed_sample_path(rel_path):
            raise AssertionError(f'{source_name}:{line_no}: snippet path {rel_path} is not a tested samples/ file')
        target = root / rel_path
        if not target.is_file():
            raise AssertionError(f'{source_name}:{line_no}: snippet file {rel_path} does not exist')
        region = extract_source_region(target.read_text(), rel_path, tag)
        if block != region:
            raise AssertionError(
                f'{source_name}:{line_no}: snippet {rel_path}#{tag} does not match source.\n'
                f'--- doc ---\n{block}\n--- source ---\n{region}'
            )
    return snippets


def parse_markdown_table(text, terms):
    """Extracts {term: meaning} from a 2+ column markdown table for the requested first-column terms."""
    found = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith('|') or not stripped.endswith('|'):
            continue
        cells = [cell.strip().strip('`*').strip() for cell in stripped.strip('|').split('|')]
        if len(cells) < 2:
            continue
        for term in terms:
            if cells[0].lower() == term.lower():
                found[term] = cells[1].strip()
    return found


class DocsTest(unittest.TestCase):
    def test_ac_build_11_guide_pages_and_seven_adoption_steps_present(self):
        for page in (GETTING_STARTED, LEGACY_ADOPTION, CONFIGURATION):
            self.assertTrue(page.is_file(), f'missing {page}')

        getting_started = GETTING_STARTED.read_text()
        for heading in REQUIRED_GETTING_STARTED_HEADINGS:
            self.assertIn(heading, getting_started, f'getting-started.md missing {heading}')

        rfc_steps = parse_rfc_adoption_steps(RFC.read_text())
        self.assertEqual([1, 2, 3, 4, 5, 6, 7], [num for num, _, _ in rfc_steps], rfc_steps)

        adoption = LEGACY_ADOPTION.read_text()
        guide_steps = [(int(num), title.strip()) for num, title in STEP_HEADING_RE.findall(adoption)]
        self.assertEqual([1, 2, 3, 4, 5, 6, 7], [num for num, _ in guide_steps], guide_steps)

        for (rfc_num, rfc_cell, terms), (guide_num, guide_title) in zip(rfc_steps, guide_steps):
            self.assertEqual(rfc_num, guide_num)
            self.assertTrue(terms, f'no terms derived from RFC step {rfc_num}: {rfc_cell}')
            for term in terms:
                self.assertIn(
                    term, guide_title,
                    f'legacy-adoption.md step {guide_num} heading "{guide_title}" missing RFC term "{term}" '
                    f'(from RFC §4.3: "{rfc_cell}")',
                )

        config_text = CONFIGURATION.read_text()
        for heading in REQUIRED_CONFIGURATION_HEADINGS:
            self.assertIn(heading, config_text, f'configuration.md missing {heading}')
        for key in REQUIRED_CONFIG_KEYS:
            self.assertIn(key, config_text, f'configuration.md missing {key}')

    def test_ac_build_12_each_snippet_matches_marked_source_region(self):
        total = 0
        pages = sorted(SITE_DOCS.rglob('*.md'))
        self.assertTrue({GETTING_STARTED, LEGACY_ADOPTION, CONFIGURATION} <= set(pages), pages)
        for page in pages:
            name = str(page.relative_to(SITE_DOCS))
            snippets = verify_markdown_snippets(page.read_text(), name)
            if page in (GETTING_STARTED, LEGACY_ADOPTION, CONFIGURATION):
                self.assertGreater(len(snippets), 0, f'{name} has no verified snippets')
            total += len(snippets)
        self.assertGreaterEqual(total, 5)

        with self.assertRaisesRegex(AssertionError, 'shell .mermaid. block must not have'):
            verify_markdown_snippets(
                '<!-- snippet: samples/pom.xml#x -->\n```mermaid\nflowchart LR\n```\n', 'marked.md')
        self.assertEqual([], verify_markdown_snippets('```mermaid\nflowchart LR\n```\n', 'diagram.md'))

        for lang_fence in ('```java', '```yml', '```json', '```kotlin', '```java title=Example.java', '```'):
            with self.assertRaisesRegex(AssertionError, 'missing preceding <!-- snippet:'):
                verify_markdown_snippets(f'{lang_fence}\nclass Example {{}}\n```\n', 'unmarked.md')

        with self.assertRaisesRegex(AssertionError, 'tilde.*forbidden'):
            verify_markdown_snippets('~~~java\nclass Example {}\n~~~\n', 'tilde.md')

        with self.assertRaisesRegex(AssertionError, 'missing snippet:start'):
            verify_markdown_snippets(
                '<!-- snippet: samples/hosts/monolith/src/main/resources/application.yml#no-such-tag -->\n'
                '```yaml\ntopolith: {}\n```\n',
                'missing-tag.md',
            )

        with self.assertRaisesRegex(AssertionError, 'does not match source'):
            verify_markdown_snippets(
                '<!-- snippet: samples/hosts/monolith/src/main/resources/application.yml#monolith-modes -->\n'
                '```yaml\ntopolith:\n  modules:\n    user:\n      mode: none\n```\n',
                'mismatch.md',
            )

        with self.assertRaisesRegex(AssertionError, 'missing snippet:end'):
            extract_source_region(
                '// snippet:start unclosed\nclass Example {}\n',
                'samples/payment/payment-api/src/main/java/Example.java',
                'unclosed',
            )

    def test_ac_cat_01_configuration_reference_uses_catalog_mode_meanings(self):
        catalog_meanings = parse_markdown_table(CATALOG.read_text(), CATALOG_TERMS)
        self.assertEqual(set(CATALOG_TERMS), set(catalog_meanings.keys()), catalog_meanings)
        for term, meaning in catalog_meanings.items():
            self.assertTrue(meaning, f'empty catalog meaning for {term}')

        config_meanings = parse_markdown_table(CONFIGURATION.read_text(), CATALOG_TERMS)
        self.assertEqual(catalog_meanings, config_meanings)

    def test_ac_build_16_mkdocs_is_strict_and_lists_every_page_in_nav(self):
        config = MKDOCS.read_text()
        self.assertRegex(config, r'(?m)^strict:\s*true\s*$')
        self.assertRegex(config, r'(?m)^docs_dir:\s*docs\s*$')
        nav_text = config.split('\nnav:', 1)[1] if '\nnav:' in config else ''
        nav_pages = [m.group(1) for m in map(NAV_PAGE_RE.match, nav_text.splitlines()) if m]
        self.assertEqual(len(nav_pages), len(set(nav_pages)), f'duplicate nav entries: {nav_pages}')
        pages = {str(page.relative_to(SITE_DOCS)) for page in SITE_DOCS.rglob('*.md')}
        self.assertTrue(pages, 'no pages under docs/site/docs')
        self.assertEqual(pages, set(nav_pages), 'pages and nav entries differ')
        for page in ('index.md', 'guide/getting-started.md', 'guide/legacy-adoption.md', 'guide/configuration.md'):
            self.assertIn(page, pages)

    def test_ac_build_16_diagnostics_page_lists_exactly_the_catalog_codes(self):
        catalog_codes = set(TL_CODE_RE.findall(CATALOG.read_text()))
        self.assertGreaterEqual(len(catalog_codes), 31, catalog_codes)
        catalog_severity = {}
        for line in CATALOG.read_text().splitlines():
            cells = [cell.strip() for cell in line.strip().strip('|').split('|')]
            if len(cells) >= 3 and re.fullmatch(r'TL\d{4}', cells[0]):
                catalog_severity[cells[0]] = cells[-2]
        self.assertEqual(catalog_codes, set(catalog_severity))
        page_codes = set(TL_CODE_RE.findall(DIAGNOSTICS.read_text()))
        self.assertEqual(catalog_codes, page_codes)
        rows = [line for line in DIAGNOSTICS.read_text().splitlines() if re.match(r'^\|\s*`TL\d{4}`', line)]
        self.assertEqual(len(catalog_codes), len(rows), 'each code needs exactly one table row')
        for row in rows:
            cells = [cell.strip() for cell in row.strip('|').split('|')]
            self.assertEqual(4, len(cells), row)
            self.assertTrue(all(cells), f'empty cell in {row}')
            code = TL_CODE_RE.search(cells[0]).group(0)
            self.assertEqual(catalog_severity[code], cells[2], f'{code} severity differs from the catalog')

    def test_ac_build_16_workflows_build_strictly_and_deploy_from_main(self):
        docs = DOCS_WORKFLOW.read_text()
        self.assertRegex(docs, r"branches:\s*\[main\]")
        self.assertIn("'docs/site/**'", docs)
        self.assertIn('workflow_dispatch', docs)
        self.assertIn('mkdocs build --strict -f docs/site/mkdocs.yml', docs)
        self.assertIn('path: target/docs-site', docs)
        self.assertIn('actions/deploy-pages', docs)
        self.assertIn("if: github.ref == 'refs/heads/main'", docs.split('\n  deploy:', 1)[1])
        self.assertIn('pip install -r docs/site/requirements.txt', docs)
        self.assertIn(
            '../../target/docs-site', MKDOCS.read_text(), 'site_dir must match the path the workflow uploads')

        ci = CI_WORKFLOW.read_text()
        self.assertRegex(ci, r'(?m)^  docs-site:\s*$')
        job = ci.split('\n  docs-site:', 1)[1]
        job = re.split(r'\n  [A-Za-z0-9_-]+:\s*\n', job, maxsplit=1)[0]
        self.assertIn('mkdocs build --strict -f docs/site/mkdocs.yml', job)
        self.assertIn('pip install -r docs/site/requirements.txt', job)
        self.assertIn("python-version: '3.13'", job)
        self.assertIn('cache-dependency-path: docs/site/requirements.txt', job)
        self.assertNotIn('paths:', job)

    def test_ac_build_16_home_page_shows_the_cover_banner(self):
        self.assertTrue(BANNER.is_file(), f'missing {BANNER}')
        self.assertGreater(BANNER.stat().st_size, 1000)
        self.assertEqual(b'\x89PNG\r\n\x1a\n', BANNER.read_bytes()[:8])
        lines = INDEX.read_text().splitlines()
        self.assertTrue(lines[0].startswith('# '), lines[:1])
        self.assertIn('](assets/banner.png)', '\n'.join(lines[1:4]))
        self.assertIn('docs/site/docs/assets/banner.png', (ROOT / 'README.md').read_text())
