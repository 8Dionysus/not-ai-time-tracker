"""Minimal Kotlin string-literal lexer: finds top-level "..." literals (not raw), with template parts."""
import re, sys, json
CYR = re.compile('[А-Яа-яЁё]')

def scan(src):
    """Yield (start, end, parts) for each top-level double-quoted literal. parts: list of ('text', s) | ('expr', code) | ('name', ident)."""
    i, n = 0, len(src)
    out = []
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            while j >= 0 and src.startswith('""""', j): j += 1
            i = n if j < 0 else j + 3; continue
        if c == "'":
            # char literal
            if src[i+1] == '\\': j = src.find("'", i + 2)
            else: j = i + 2
            i = j + 1; continue
        if c == '"':
            end, parts = lit(src, i)
            out.append((i, end, parts)); i = end; continue
        i += 1
    return out

def lit(src, i):
    """src[i] == '"'. Returns (end_index_exclusive, parts)."""
    parts = []; buf = []; i += 1
    while True:
        c = src[i]
        if c == '\\':
            buf.append(src[i:i+2]) if src[i+1] != 'u' else buf.append(src[i:i+6])
            i += 2 if src[i+1] != 'u' else 6; continue
        if c == '"':
            if buf: parts.append(('text', ''.join(buf)))
            return i + 1, parts
        if c == '$' and src[i+1] == '{':
            if buf: parts.append(('text', ''.join(buf))); buf = []
            j = expr_end(src, i + 2)
            parts.append(('expr', src[i+2:j])); i = j + 1; continue
        if c == '$' and (src[i+1].isalpha() or src[i+1] == '_'):
            if buf: parts.append(('text', ''.join(buf))); buf = []
            m = re.match(r'[A-Za-z_][A-Za-z0-9_]*', src[i+1:])
            parts.append(('name', m.group(0))); i += 1 + len(m.group(0)); continue
        buf.append(c); i += 1

def expr_end(src, i):
    depth = 0
    while True:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i):
                i = src.find('"""', i + 3) + 3; continue
            i, _ = lit(src, i); continue
        if c == "'":
            i = (src.find("'", i + 2) if src[i+1] == '\\' else i + 2) + 1; continue
        if c == '{': depth += 1
        elif c == '}':
            if depth == 0: return i
            depth -= 1
        i += 1

def unescape(t):
    return t.replace('\\"', '"').replace('\\$', '$').replace('\\n', '\n').replace('\\\\', '\\').replace("\\'", "'")

def has_cyr(parts):
    return any(k == 'text' and CYR.search(v) for k, v in parts)

if __name__ == '__main__':
    res = []
    for f in sys.argv[1:]:
        src = open(f, encoding='utf-8').read()
        for s, e, parts in scan(src):
            if has_cyr(parts):
                res.append({'file': f, 'line': src.count('\n', 0, s) + 1, 'raw': src[s:e], 'parts': parts})
    json.dump(res, sys.stdout, ensure_ascii=False, indent=0)
