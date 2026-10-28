#!/usr/bin/env python3
"""Extract only verified dependency JARs, never server configuration or credentials."""
import argparse
import hashlib
import io
from pathlib import Path
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PAPER_SHA = '416b7d105b1d0192df4afa4b29d2800d9dbeda092a82fbc5b511248a54adde15'
PAPER_URL = f'https://fill-data.papermc.io/v1/objects/{PAPER_SHA}/paper-26.3-227.jar'
REQUIRED = ['Towny-0.103.2.0.jar', 'SiegeWar-3.7.0.jar', 'VaultUnlocked-2.20.3.jar', 'EssentialsX-2.22.1-dev+27-e70bdb8.jar']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plugins', type=Path, required=True)
    parser.add_argument('--paper', type=Path)
    parser.add_argument('--download-paper', action='store_true')
    args = parser.parse_args()
    target = ROOT / 'dev'
    (target / 'plugins').mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.plugins) as archive:
        for filename in REQUIRED:
            raw = archive.read('plugins/' + filename)
            with zipfile.ZipFile(io.BytesIO(raw)) as jar:
                if 'plugin.yml' not in jar.namelist():
                    raise ValueError(f'Invalid plugin JAR: {filename}')
            output = target / 'plugins' / filename
            if output.exists() and output.read_bytes() != raw:
                raise ValueError(f'Refusing to replace different existing JAR: {filename}')
            output.write_bytes(raw)
            print(filename, hashlib.sha256(raw).hexdigest())
    output = target / 'paper.jar'
    if args.paper:
        with zipfile.ZipFile(args.paper) as jar:
            if 'META-INF/MANIFEST.MF' not in jar.namelist() or b'Main-Class:' not in jar.read('META-INF/MANIFEST.MF'):
                raise ValueError('Paper file is not an executable JAR; check whether it is a renamed plugins.zip')
        if args.paper.resolve() != output.resolve():
            if output.exists() and output.read_bytes() != args.paper.read_bytes():
                raise ValueError('Refusing to overwrite existing Paper JAR')
            shutil.copy2(args.paper, output)
    elif args.download_paper:
        if output.exists():
            if hashlib.sha256(output.read_bytes()).hexdigest() != PAPER_SHA:
                raise ValueError('Existing Paper does not match pinned build; not overwritten')
        else:
            request = urllib.request.Request(PAPER_URL, headers={'User-Agent': 'TownyDiscordReports/1.0 local-development'})
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
            if hashlib.sha256(raw).hexdigest() != PAPER_SHA:
                raise ValueError('Paper checksum mismatch')
            output.write_bytes(raw)
    properties = target / 'server.properties'
    if not properties.exists():
        properties.write_text('server-port=25565\nonline-mode=true\nlevel-name=tdr-test-world\nlevel-type=minecraft:flat\nview-distance=3\nsimulation-distance=3\nmax-players=4\nenable-rcon=false\nenable-query=false\n', encoding='utf-8')
    eula = target / 'eula.txt'
    if not eula.exists():
        eula.write_text('eula=false\n', encoding='utf-8')
    print('Prepared JARs only. Review Minecraft EULA before setting dev/eula.txt to eula=true.')

if __name__ == '__main__':
    main()
