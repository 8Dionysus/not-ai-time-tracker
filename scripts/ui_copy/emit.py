"""Write values*/ui_strings.xml from catalog.json + translations (trans/*.json).
Translation file format: {"strings": {key: {locale: text}}, "plurals": {key: {locale: {quantity: text}}}}
"""
import glob, json, os, re, sys
from xml.sax.saxutils import escape
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ROOT = os.path.join(REPO, 'app/src/main/res')
HERE = os.path.join(REPO, 'localization/ui-copy')
LOCALES = {'en': 'values-en', 'es': 'values-es', 'fr': 'values-fr', 'pt-BR': 'values-pt-rBR', 'de': 'values-de', 'it': 'values-it',
           'ar': 'values-ar', 'zh-Hans': 'values-b+zh+Hans', 'ja': 'values-ja', 'ko': 'values-ko', 'hi': 'values-hi'}
QUANT = {'ru': ['one', 'few', 'many', 'other'], 'en': ['one', 'other'], 'de': ['one', 'other'], 'hi': ['one', 'other'],
         'es': ['one', 'many', 'other'], 'fr': ['one', 'many', 'other'], 'pt-BR': ['one', 'many', 'other'], 'it': ['one', 'many', 'other'],
         'ar': ['zero', 'one', 'two', 'few', 'many', 'other'], 'zh-Hans': ['other'], 'ja': ['other'], 'ko': ['other']}

def android(text):
    t = text.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')
    t = escape(t)
    if t.startswith('@') or t.startswith('?'): t = '\\' + t
    # aapt trims and collapses unquoted whitespace: quote fragments that start or end with a space.
    if t != t.strip() or '  ' in t: t = '"' + t + '"'
    return t

def load_trans():
    out = {'strings': {}, 'plurals': {}}
    for f in sorted(glob.glob(os.path.join(HERE, 'translations', '*.json'))):
        d = json.load(open(f))
        for k, v in d.get('strings', {}).items(): out['strings'].setdefault(k, {}).update(v)
        for k, v in d.get('plurals', {}).items(): out['plurals'].setdefault(k, {}).update(v)
    return out

def plural_forms(locale, forms):
    """forms: dict quantity->text; fill required quantities from other/one."""
    res = {}
    for q in QUANT[locale]:
        res[q] = forms.get(q) or (forms.get('one') if q == 'one' else None) or forms.get('other') or forms.get('many') or next(iter(forms.values()))
    return res

def write(folder, locale, strings, plurals):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Generated from Kotlin source copy; see localization/ui-copy/README.md. -->',
             '<resources xmlns:tools="http://schemas.android.com/tools">']
    for k in sorted(strings):
        lines.append('    <string name="%s"%s>%s</string>' % (k, '' if re.search(r'%\d\$', strings[k]) else ' formatted="false"', android(strings[k])))
    for k in sorted(plurals):
        lines.append('    <plurals name="%s">' % k)
        for q, t in plural_forms(locale, plurals[k]).items():
            lines.append('        <item quantity="%s">%s</item>' % (q, android(t)))
        lines.append('    </plurals>')
    lines.append('</resources>')
    os.makedirs(os.path.join(ROOT, folder), exist_ok=True)
    open(os.path.join(ROOT, folder, 'ui_strings.xml'), 'w', encoding='utf-8').write('\n'.join(lines) + '\n')

if __name__ == '__main__':
    cat = json.load(open(os.path.join(HERE, 'catalog.json')))
    tr = load_trans()
    ru_s = {k: v['ru'] for k, v in cat['strings'].items()}
    ru_p = {k: {'one': '%1$d ' + v['ru'][0], 'few': '%1$d ' + v['ru'][1], 'many': '%1$d ' + v['ru'][2], 'other': '%1$d ' + v['ru'][2]} for k, v in cat['plurals'].items()}
    write('values', 'ru', ru_s, ru_p)
    missing = {}
    for loc, folder in LOCALES.items():
        s = {k: tr['strings'][k][loc] for k in ru_s if loc in tr['strings'].get(k, {})}
        p = {k: tr['plurals'][k][loc] for k in ru_p if loc in tr['plurals'].get(k, {})}
        missing[loc] = len(ru_s) - len(s) + len(ru_p) - len(p)
        write(folder, loc, s, p)
    print('missing per locale', missing)
