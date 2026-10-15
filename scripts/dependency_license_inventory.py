#!/usr/bin/env python3
"""Read only resolved release coordinates; report exact cached POM declarations and unknowns.
No name-based/license-family guesses, no claim of runtime traffic or rights clearance.
"""
import argparse, csv, json, pathlib, xml.etree.ElementTree as ET
p = argparse.ArgumentParser()
p.add_argument('inventory', type=pathlib.Path)
p.add_argument('--gradle-cache', type=pathlib.Path, required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
a = p.parse_args()
cache = a.gradle_cache / 'caches/modules-2/files-2.1'
def licenses(g, n, v, seen=()):
    key = (g, n, v)
    if key in seen:
        return [], None
    files = sorted((cache/g/n/v).glob('*/*.pom'))
    if not files:
        return [], None
    root = ET.parse(files[0]).getroot()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    def text(node, name):
        return node.findtext('m:'+name, namespaces=ns)
    declared = [{'name': text(x, 'name'), 'url': text(x, 'url')} for x in root.findall('m:licenses/m:license', ns)]
    if declared:
        return declared, ':'.join(key)
    parent = root.find('m:parent', ns)
    if parent is not None:
        return licenses(text(parent,'groupId'), text(parent,'artifactId'), text(parent,'version'), seen+(key,))
    return [], None
rows = []
with a.inventory.open() as f:
    for row in csv.DictReader(f, delimiter='\t'):
        coordinate = row['coordinate']
        if coordinate.count(':') != 2:
            continue
        declared, source = licenses(*coordinate.split(':'))
        rows.append({k: row[k] for k in ('coordinate', 'artifact', 'sha256')} | {
            'pom_declared_licenses': declared, 'license_source_coordinate': source,
            'status': 'DECLARED' if declared else 'UNKNOWN_REQUIRES_REVIEW'})
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps({'scope': 'resolved releaseRuntimeClasspath; POM metadata only', 'artifacts': rows}, ensure_ascii=False, indent=2)+'\n')
print(f'{len(rows)} artifacts; {sum(not r["pom_declared_licenses"] for r in rows)} unknown license declarations')
