"""Lists every SharedPreferences key the sources read or write, with its type, resolving `const val` names per file."""
import os, re, sys
root = sys.argv[1]
call = re.compile(r'(?P<recv>[A-Za-z_.()?!]*?)\??\.(?P<op>get|put)(?P<ty>Int|Long|Float|Boolean|StringSet|String)\(\s*(?P<key>"[^"]*"|[A-Za-z_][A-Za-z_0-9.]*|"[^"]*\$[^"]*")')
const = re.compile(r'(?:private\s+|internal\s+)?const\s+val\s+([A-Z_a-z0-9]+)\s*=\s*"([^"]*)"')
valstr = re.compile(r'(?:private\s+)?val\s+([A-Za-z_0-9]+)\s*=\s*"([^"]*)"')
rows = {}
for dp, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith('.kt'):
            continue
        p = os.path.join(dp, f)
        t = open(p, encoding='utf-8').read()
        names = dict(valstr.findall(t))
        names.update(dict(const.findall(t)))
        for i, line in enumerate(t.split('\n'), 1):
            for m in call.finditer(line):
                key = m.group('key')
                recv = m.group('recv')
                ty = m.group('ty')
                if ty == 'String' and m.group('op') == 'get' and ('R.' in key or key.startswith('R')):
                    continue
                if 'extra' in recv.lower() or 'intent' in recv.lower() or 'bundle' in recv.lower() or recv in ('i', 'o', 'x', 'b', 'last', 'data', 'ctx', 'context', 'c', 'act', 'this', 'app', 'ui', 's', 'res', 'resources', 'getIntent()', 'r', 'j', 'json', 'obj', 'item', 'it', 'a', 'v'):
                    continue
                if key.startswith('"'):
                    k = key.strip('"')
                else:
                    k = names.get(key.split('.')[-1], '?' + key)
                rows.setdefault((k, ty), []).append('%s:%d' % (os.path.relpath(p, root).replace('\\', '/'), i))
for (k, ty), where in sorted(rows.items()):
    print('%-30s %-10s %s' % (k, ty, ' '.join(where[:3])))
print(len(rows), 'pairs', file=sys.stderr)
