#!/usr/bin/env python3
"""Enforce production dependency boundaries. Pass --jars after assembling loader jars."""
import argparse
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_TOKENS = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*', re.S)


def code(path):
    return JAVA_TOKENS.sub(lambda m: m.group() if m.group().startswith(('"', "'")) else ' ',
                           path.read_text(encoding='utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jars', action='store_true')
    args = parser.parse_args()
    errors = []
    pure_modules = ('attached', 'engine', 'gradle-plugin', 'cli', 'junit')
    for module in ('api', *pure_modules, 'common', 'fabric', 'neoforge'):
        source = ROOT / module / 'src/main/java'
        for path in source.rglob('*.java'):
            relative = path.relative_to(ROOT)
            text = code(path)
            if module in pure_modules:
                for dependency in ('net.minecraft.', 'net.fabricmc.', 'net.neoforged.'):
                    if dependency in text:
                        errors.append(f'{relative}: game dependency in a JVM module: {dependency}')
            if module == 'common' and '/stagewright/driver/' in path.as_posix():
                continue
            if 'net.magicterra.worlddriver.' in text:
                errors.append(f'{relative}: WorldDriver dependency belongs in common driver/')
    if args.jars:
        for module in ('fabric', 'neoforge'):
            jars = list((ROOT / module / 'build/libs').glob('*.jar'))
            jars = [p for p in jars if not any(tag in p.name for tag in ('-sources', '-dev-shadow'))]
            if not jars:
                errors.append(f'{module}: no assembled loader jars')
            for jar in jars:
                with zipfile.ZipFile(jar) as archive:
                    for name in archive.namelist():
                        if name.startswith(('net/magicterra/worlddriver/', 'dev/latvian/mods/rhino/', 'io/netty/')):
                            errors.append(f'{jar.name}: duplicate driver or runtime library {name}')
                    names = set(archive.namelist())
                    for own in ('harness/StageWrightHarness', 'scene/Scene', 'contract/DriverBinding'):
                        name = 'net/magicterra/stagewright/' + own + '.class'
                        if name not in names:
                            errors.append(f'{jar.name}: missing framework contract {name}')
    if errors:
        for error in errors:
            print('architecture: ' + error)
        return 1
    print('architecture boundaries OK' + (' (sources and jars)' if args.jars else ' (sources)'))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
