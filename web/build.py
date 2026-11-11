#!/usr/bin/env python3
"""Write go/sw.js (offline file list + version from the files' contents) and zip go/ for upload."""
import hashlib, json, os, sys, zipfile
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'go')
files, h = [], hashlib.sha256()
for d, _, names in sorted(os.walk(root)):
    for n in sorted(names):
        p = os.path.join(d, n); rel = os.path.relpath(p, root).replace(os.sep, '/')
        if rel in ('sw.js', 'sw.template.js', 'config.json') or rel.endswith('.map') or 'LICENSE' in rel or n.startswith('.'): continue
        files.append(rel); h.update(rel.encode()); h.update(open(p, 'rb').read())
version = h.hexdigest()[:10]
shell = ['./'] + [f for f in files if f != 'index.html'] + ['index.html']
sw = open(os.path.join(root, 'sw.template.js')).read().replace('__VERSION__', version).replace('__FILES__', json.dumps(shell, indent=0))
open(os.path.join(root, 'sw.js'), 'w').write(sw)
print('version', version, len(shell), 'files')
if len(sys.argv) > 1:
    out = sys.argv[1]
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for d, _, names in sorted(os.walk(root)):
            for n in sorted(names):
                p = os.path.join(d, n); rel = os.path.relpath(p, root)
                if n == 'sw.template.js': continue
                z.write(p, 'go/' + rel.replace(os.sep, '/'))
        readme = os.path.join(os.path.dirname(root), 'README.txt')
        if os.path.exists(readme): z.write(readme, 'README.txt')
    print('zip', out, os.path.getsize(out))
