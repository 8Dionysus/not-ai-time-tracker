#!/usr/bin/env python3
"""Check authored research descriptions, pinned inputs and optional APK assets.

Historical source text is read from the accepted Git revision for comparison only;
it is never copied into this report or into the build inputs.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREVIOUS = '058564b94bcbc9fd8682b2dd39965c5ac6ceb520'
ISCO = 'research/projections/isco_full_projection_v2.json'


def strings(value, path=''):
    if isinstance(value, dict):
        for key, item in value.items():
            yield from strings(item, path + '/' + key)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from strings(item, path + '/' + str(index))
    elif isinstance(value, str):
        yield path, value


def duty_list(text):
    return bool(re.search(r'(?:tasks include|tasks would include)\s*[-:]\s*', text, re.I)
                or len(re.findall(r'(?:^|\n)\s*\([a-z]\)', text)) >= 3)


def research_decisions(value):
    if isinstance(value, dict):
        return {('researchTaskSummaryEn' if k == 'sourceTaskExcerptEn' else k): research_decisions(v)
                for k, v in value.items()
                if k not in {'officialTaskText', 'researchActionSummary', 'summaryProvenance', 'taskTextPolicy'}}
    if isinstance(value, list):
        return [research_decisions(v) for v in value]
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, help='Additionally check the actual eight bundled assets')
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'core/src/main/resources/full-catalog-manifest.json').read_text())
    source = json.loads((ROOT / ISCO).read_text())
    previous = json.loads(subprocess.check_output(['git', 'show', f'{PREVIOUS}:{ISCO}'], cwd=ROOT))
    assert research_decisions(source) == research_decisions(previous), 'Research mapping/locators changed'
    current_strings = list(strings(source))
    # Catch copied portions as well as headings/lettered lists, including neighbouring fields.
    words = lambda s: re.findall(r"[a-z]+", s.lower())
    old_runs = set()
    for path, text in strings(previous):
        if path.endswith('/officialTaskText') and duty_list(text):
            tokens = words(text)
            old_runs.update(tuple(tokens[i:i+20]) for i in range(len(tokens)-19))
    fields = Counter(path.rsplit('/', 1)[-1] for path, _ in current_strings)
    assert fields['officialTaskText'] == fields['sourceTaskExcerptEn'] == 0
    assert fields['researchActionSummary'] == 86 and fields['researchTaskSummaryEn'] == 452
    codes = set()

    def reviews(value):
        if isinstance(value, dict):
            if 'researchActionSummary' in value:
                assert value['researchActionSummary'] and value['summaryProvenance']['officialText'] is False
                codes.add(value['code'])
            for item in value.values():
                reviews(item)
        elif isinstance(value, list):
            for item in value:
                reviews(item)

    reviews(source)
    assert len(codes) == 60
    assert len(source['candidateDispositions']) == 353
    assert len(source['proposedActions']) == 137
    assert len(source['componentActionProposals']) == 18
    assert len(source['familyAndWorkflowRecords']) == 12
    assert len(source['typedSourceRelations']) == 159
    projection_manifest = json.loads((ROOT / 'research/projections/manifest.json').read_text())
    checked = []
    apk = zipfile.ZipFile(args.apk) if args.apk else None
    try:
        for entry in manifest['files']:
            path = ROOT / 'research' / entry['path']
            data = path.read_bytes()
            assert len(data) == entry['bytes'] and hashlib.sha256(data).hexdigest() == entry['sha256'], path
            value = json.loads(data) if path.suffix == '.json' else data.decode('utf-8')
            assert not [(p, len(s)) for p, s in strings(value) if duty_list(s)], path
            for pointer, text in strings(value):
                tokens = words(text)
                assert not any(tuple(tokens[i:i+20]) in old_runs for i in range(len(tokens)-19)), (path, pointer)
            if entry['path'].startswith('projections/'):
                other = next(x for x in projection_manifest['files'] if x['file'] == path.name)
                assert other['bytes'] == entry['bytes'] and other['sha256'] == entry['sha256']
            if apk:
                assert apk.read('assets/research/' + entry['path']) == data, path
            checked.append({'path': entry['path'], 'bytes': len(data), 'sha256': entry['sha256']})
    finally:
        if apk:
            apk.close()
    print(json.dumps({'schema': 'tracker_authored_descriptions_check_v1', 'previousCommit': PREVIOUS,
                      'unitSummaries': 86, 'unitCategories': 60, 'candidateSummaries': 452,
                      'longDutyListsRemaining': 0, 'allResearchDecisionsAndLocatorsUnchanged': True,
                      'files': checked, 'apkAssetsChecked': bool(args.apk)}, indent=2))


if __name__ == '__main__':
    main()
