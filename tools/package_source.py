"""Упаковка исходников без кэшей, ключей и локальных настроек."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
if __name__=='__main__':
    ET.parse(ROOT/'app/src/main/AndroidManifest.xml')
    target=ROOT/'SavemyBox-source.zip'
    excluded={'.tools','.upstream','.gradle','build','__pycache__','.git'}
    files=[p for p in ROOT.rglob('*') if p.is_file() and not any(x in excluded for x in p.relative_to(ROOT).parts) and p.suffix not in {'.zip','.jks','.keystore','.aar'} and p.name not in {'local.properties','libbox.json'}]
    with ZipFile(target,'w',ZIP_DEFLATED) as archive:
        for path in sorted(files): archive.write(path,Path('singbox-fork')/path.relative_to(ROOT))
    with ZipFile(target) as archive:
        assert archive.testzip() is None
        assert 'singbox-fork/build.cmd' in archive.namelist()
        assert 'singbox-fork/README.md' in archive.namelist()
    print(f'Manifest XML: OK\nArchive: {len(files)} files, {target.stat().st_size} bytes\nSHA256: {hashlib.sha256(target.read_bytes()).hexdigest()}')
