"""Check the distributed binary, source build and public command index."""
import hashlib
from pathlib import Path
import re
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
published = root / 'dist/milzzynodes.jar'
expected = 'ff4b6f5c8addeb5ab7062c561aef0e2ce2a71062b721058cd8491271ebfd10b3'
if hashlib.sha256(published.read_bytes()).hexdigest() != expected:
    raise SystemExit('The release JAR does not match the supplied dev.5.11 binary.')

with ZipFile(published) as original, ZipFile(root / 'build/milzzynodes.jar') as rebuilt:
    if set(original.namelist()) != set(rebuilt.namelist()):
        raise SystemExit('Names in the rebuilt JAR differ from the release.')
    differences = [p for p in original.namelist() if original.read(p) != rebuilt.read(p)]
    if differences:
        raise SystemExit('Rebuild the release with JDK 21. Differing payloads: ' + ', '.join(differences))
    for name in ('config.yml', 'plugin.yml'):
        if original.read(name) != (root / 'docs/defaults' / name).read_bytes():
            raise SystemExit('Bundled default differs from docs/defaults/' + name)
    descriptor = original.read('plugin.yml').decode()
    commands = descriptor.split('\ncommands:\n', 1)[1].split('\npermissions:\n', 1)[0]
    declared = set(re.findall(r'^  ([a-z0-9]+):$', commands, re.M))

index = (root / 'README.md').read_text()
manual = (root / 'docs/COMMANDS.md').read_text()
listed = set(re.findall(r'^\| \[`/([a-z0-9]+)`\]\(docs/', index, re.M))
documented = set(re.findall(r'^### ([a-z0-9]+)$', manual, re.M))
if listed != declared or documented != declared:
    raise SystemExit('The command index and manual must cover precisely the declared roots.')
print(f'Verified {len(declared)} command roots, matching JAR contents, both defaults and release SHA-256.')
