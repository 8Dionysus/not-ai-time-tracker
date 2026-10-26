"""Move Cyrillic Kotlin literals to Android resources.
usage: transform.py catalog.json file.kt...   (rewrites files in place, merges catalog)
Catalog: {"strings": {key: {"ru": text, "ctx": [..]}}, "plurals": {key: {"ru": [one, few, many], "ctx": [..]}}}
"""
import hashlib, json, os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from klex import scan, lit, CYR, expr_end

def skip_string(src, i):
    if src.startswith('"""', i):
        j = src.find('"""', i + 3)
        while src.startswith('""""', j): j += 1
        return j + 3
    return lit(src, i)[0]

def call_args(src, i):
    """src[i] == '('. Returns (end_exclusive, [arg source strings])."""
    depth = 0; args = []; start = i + 1; j = i
    while True:
        c = src[j]
        if c == '"': j = skip_string(src, j); continue
        if c == "'": j = (src.find("'", j + 2) if src[j+1] == '\\' else j + 2) + 1; continue
        if c in '([{': depth += 1
        elif c in ')]}':
            depth -= 1
            if depth == 0:
                args.append(src[start:j]); return j + 1, args
        elif c == ',' and depth == 1:
            args.append(src[start:j]); start = j + 1
        j += 1

def kunescape(t):
    out = []; i = 0
    while i < len(t):
        if t[i] == '\\':
            n = t[i+1]
            if n == 'u': out.append(chr(int(t[i+2:i+6], 16))); i += 6; continue
            out.append({'n': '\n', 't': '\t', '"': '"', '$': '$', '\\': '\\', "'": "'", 'r': '\r', 'b': '\b'}[n]); i += 2; continue
        out.append(t[i]); i += 1
    return ''.join(out)

def key_for(prefix, text):
    return prefix + hashlib.sha1(text.encode()).hexdigest()[:10]

PLURAL = re.compile(r'(?<![A-Za-z0-9_.])(?:Format\.)?plural\(')
LITERAL_ONLY = re.compile(r'^\s*"([^"$\\]*)"\s*$')

def transform(src, cat, ctx):
    # 1. plural calls with three literal forms
    out = []; i = 0
    while True:
        m = PLURAL.search(src, i)
        if not m: out.append(src[i:]); break
        # skip if inside a string literal or the definition
        if src[max(0, m.start()-4):m.start()].endswith('fun '):
            out.append(src[i:m.end()]); i = m.end(); continue
        end, args = call_args(src, m.end() - 1)
        forms = [LITERAL_ONLY.match(a) for a in args[1:]]
        if len(args) == 4 and all(forms) and any(CYR.search(f.group(1)) for f in forms):
            ru = [kunescape(f.group(1)) for f in forms]
            k = key_for('p_', '|'.join(ru))
            e = cat['plurals'].setdefault(k, {'ru': ru, 'ctx': []})
            if ctx not in e['ctx']: e['ctx'].append(ctx)
            out.append(src[i:m.start()]); out.append('L.p(R.plurals.%s, %s)' % (k, args[0].strip())); i = end
        else:
            out.append(src[i:m.end()]); i = m.end()
    src = ''.join(out)
    # 2. literals
    lits = scan(src)
    res = []; last = 0
    for s, e, parts in lits:
        if not any(k == 'text' and CYR.search(v) for k, v in parts):
            # A template without Russian of its own may still hold Russian literals inside ${...}.
            if any(k == 'expr' and CYR.search(v) for k, v in parts):
                rebuilt = '"' + ''.join(v if k == 'text' else ('$' + v if k == 'name' else '${' + transform(v, cat, ctx) + '}') for k, v in parts) + '"'
                res.append(src[last:s]); res.append(rebuilt); last = e
            continue
        res.append(src[last:s]); res.append(replace_literal(parts, cat, ctx)); last = e
    res.append(src[last:])
    return ''.join(res)

def replace_literal(parts, cat, ctx):
    text = []; args = []
    has_args = any(k != 'text' for k, _ in parts)
    for k, v in parts:
        if k == 'text':
            t = kunescape(v)
            text.append(t.replace('%', '%%') if has_args else t)
        else:
            code = v if k == 'name' else transform(v, cat, ctx)
            args.append(code.strip())
            text.append('%%%d$s' % len(args))
    t = ''.join(text)
    key = key_for('t_', t)
    e = cat['strings'].setdefault(key, {'ru': t, 'ctx': []})
    if ctx not in e['ctx']: e['ctx'].append(ctx)
    return 'L.s(R.string.%s%s)' % (key, ''.join(', ' + a for a in args))

def ensure_imports(src):
    pkg = re.search(r'^package ([\w.]+)', src, re.M).group(1)
    if pkg == 'dev.notai.tracker.android': return src
    need = [imp for imp in ('import dev.notai.tracker.android.R', 'import dev.notai.tracker.android.L') if not re.search('^' + re.escape(imp) + '$', src, re.M)]
    if not need: return src
    m = re.search(r'^package [\w.]+\s*\n', src, re.M)
    return src[:m.end()] + '\n' + '\n'.join(need) + '\n' + src[m.end():]

if __name__ == '__main__':
    path = sys.argv[1]
    cat = json.load(open(path)) if os.path.exists(path) else {'strings': {}, 'plurals': {}}
    for f in sys.argv[2:]:
        src = open(f, encoding='utf-8').read()
        name = os.path.basename(f)
        lines = src.split('\n')
        new = transform(src, cat, name)
        if new != src:
            open(f, 'w', encoding='utf-8').write(ensure_imports(new))
    json.dump(cat, open(path, 'w'), ensure_ascii=False, indent=1)
    print(len(cat['strings']), 'strings', len(cat['plurals']), 'plurals')
