"""trans/NN.txt -> trans/NN.json. Line: key ‖ en ‖ es ‖ fr ‖ pt-BR ‖ de ‖ it ‖ ar ‖ zh-Hans ‖ ja ‖ ko ‖ hi.  \\n = newline.
Plural line: key ‖ en-one/en-other ‖ ...  with forms separated by ' ¦ ' and prefixed q= e.g. one=%1$d day ¦ other=%1$d days"""
import json, sys, re, os
LOC = ['en','es','fr','pt-BR','de','it','ar','zh-Hans','ja','ko','hi']
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
cat = json.load(open(os.path.join(REPO, 'localization/ui-copy/catalog.json')))
if not sys.argv[1:]: sys.argv += sorted(__import__('glob').glob(os.path.join(REPO, 'localization/ui-copy/translations/*.txt')))
for path in sys.argv[1:]:
    out = {'strings': {}, 'plurals': {}}
    for n, line in enumerate(open(path, encoding='utf-8'), 1):
        line = line.rstrip('\n')
        if not line.strip() or line.startswith('#'): continue
        # Cells are separated by ' ‖ '; only that single space belongs to the separator, so copy may start or end with a space.
        raw = line.split('‖')
        cells = [raw[0].strip()] + [c[1:] if c.startswith(' ') else c for c in raw[1:]]
        cells = [cells[0]] + [c[:-1] if c.endswith(' ') and i < len(cells) - 2 else c for i, c in enumerate(cells[1:])]
        key, vals = cells[0], cells[1:]
        assert len(vals) == 11, (path, n, key, len(vals))
        vals = [v.replace('\\n', '\n') for v in vals]
        if key in cat['plurals']:
            forms = []
            for v in vals:
                d = {}
                for part in v.split('¦'):
                    q, t = part.strip().split('=', 1); d[q.strip()] = t.strip()
                forms.append(d)
            out['plurals'][key] = dict(zip(LOC, forms))
        else:
            assert key in cat['strings'], (path, key)
            ru = cat['strings'][key]['ru']
            need = sorted(set(re.findall(r'%\d\$s', ru)))
            for loc, v in zip(LOC, vals):
                got = sorted(set(re.findall(r'%\d\$s', v)))
                assert got == need, (path, n, key, loc, need, got)
                assert ('%%' in ru) == ('%%' in v) or '%' not in ru, (path, key, loc)
            out['strings'][key] = dict(zip(LOC, vals))
    json.dump(out, open(path[:-4] + '.json', 'w'), ensure_ascii=False, indent=0)
    print(path, len(out['strings']), len(out['plurals']))
