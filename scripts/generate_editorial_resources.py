#!/usr/bin/env python3
"""Materialize the pinned, reviewed STATIC editorial copy as Android resources.
No runtime lookup/substitution of user values or translation of diagnostics.
Dynamic composition/count/import overlays are deliberately not advertised as complete.
"""
import hashlib, json, re, zipfile
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / 'docs/localization/tracker-localization-12-locales-editorial.zip'
assert hashlib.sha256(PACK.read_bytes()).hexdigest() == 'eca102490bb89f163141813043e59e351d6bad8d1c0b659027371d25a52cc310'
with zipfile.ZipFile(PACK) as z:
    manifest = json.loads(z.read('localization-package-manifest.json'))
    for f in manifest['files']:
        assert hashlib.sha256(z.read(f['path'])).hexdigest() == f['sha256'], f['path']
    table = json.loads(z.read('localized-ui-table.json'))
rows = {};resources = [];ambiguous = set()
for unit in table['translations']:
    for index,v in enumerate(unit['variants']):
        source = v['source_text']
        if '$' in source or '%' in source: continue
        key = 'editorial_' + re.sub('[^a-z0-9_]','_',unit['draft_key'].lower()) + '_v'+str(index)
        resources.append((key,source,v['translations']))
        if source in rows and rows[source][1] != v['translations']: ambiguous.add(source)
        rows.setdefault(source,(key,v['translations'],unit['draft_key']))
for source in ambiguous: del rows[source]

def android(text):
    return escape(text.replace('\\','\\\\').replace("'","\\'").replace('"','\\"').replace('\n','\\n').replace('\r','\\r'))

for locale in ['ru'] + table['locales']:
    qualifier = {'ru':'values','pt-BR':'values-pt-rBR','zh-Hans':'values-b+zh+Hans'}.get(locale,'values-'+locale)
    folder = ROOT/'app/src/main/res'/qualifier; folder.mkdir(parents=True,exist_ok=True)
    values = [(key,source if locale == 'ru' else translations[locale]) for key,source,translations in resources]
    content = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated from the pinned reviewed editorial base; see scripts/generate_editorial_resources.py. -->\n<resources>\n'
    content += ''.join(f'    <string name="{key}" formatted="false">{android(text)}</string>\n' for key,text in values)
    (folder/'editorial_strings.xml').write_text(content+'</resources>\n')

# This is a source migration only. Each call retains an explicit resource ID;
# there is no reverse-string translation engine in the product.
changed = {}
for p in (ROOT/'app/src/main/kotlin/dev/notai/tracker/android/ui').glob('*.kt'):
    s = p.read_text(); before = s
    pattern = r'\b(Text|PillButton|SectionLabel|SectionTitle|SheetHeader|Group|Labeled|Toggle)\("([^"\n]+)"'
    def replace(m):
        if m[2] not in rows: return m[0]
        return f'{m[1]}(stringResource(R.string.{rows[m[2]][0]})'
    s = re.sub(pattern,replace,s)
    if s != before:
        if 'import androidx.compose.ui.res.stringResource\n' not in s:
            s = s.replace('package dev.notai.tracker.android.ui\n','package dev.notai.tracker.android.ui\n\nimport androidx.compose.ui.res.stringResource\n',1)
        if 'import dev.notai.tracker.android.R\n' not in s:
            s = s.replace('package dev.notai.tracker.android.ui\n','package dev.notai.tracker.android.ui\n\nimport dev.notai.tracker.android.R\n',1)
        p.write_text(s);changed[p.name] = s.count('stringResource(R.string.editorial_')
mapping = {source:{'resource':key,'draft_key':draft} for source,(key,_,draft) in rows.items()}
(ROOT/'docs/localization/android-static-resource-map.json').write_text(json.dumps(mapping,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'static_resources_per_locale':len(resources),'locales':12,'ambiguous_sources_left_for_explicit_mapping':sorted(ambiguous),'changed_calls_by_file':changed},ensure_ascii=False))
