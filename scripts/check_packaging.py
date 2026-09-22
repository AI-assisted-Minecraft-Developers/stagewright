#!/usr/bin/env python3
"""Check what every built StageWright artifact owes the person who receives it.

Inspects jars and generated POMs that already exist; with --build it first runs the Gradle
tasks that produce them (jar assembly and POM generation only, never a publish):

    python3 scripts/check_packaging.py --build

Checked:
  - every jar, -sources jar included, carries META-INF/COPYING and META-INF/COPYING.LESSER,
    byte-identical to the repository's own;
  - every generated POM declares LGPL-3.0-only with its URL, the project URL and the SCM URL;
  - a jar holding third-party classes carries that code's license text (minimal-json, Rhino,
    gson, Error Prone annotations), and the CLI jar a notice naming each of them.

Exit 0 when all hold, 1 with one line per violation otherwise.
"""
import argparse
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROJECT_URL = 'https://github.com/AI-assisted-Minecraft-Developers/stagewright'
LICENSE_NAME = 'LGPL-3.0-only'
LICENSE_URL = 'https://www.gnu.org/licenses/lgpl-3.0.txt'

# Every directory holding a module whose jars are published or handed to a user.
MODULES = ['api', 'common', 'fabric', 'neoforge', 'attached', 'junit', 'engine', 'cli', 'gradle-plugin']

POMS = [
    'api/build/publications/mavenJava/pom-default.xml',
    'common/build/publications/mavenJava/pom-default.xml',
    'fabric/build/publications/mavenJava/pom-default.xml',
    'neoforge/build/publications/mavenJava/pom-default.xml',
    'attached/build/publications/mavenJava/pom-default.xml',
    'junit/build/publications/mavenJava/pom-default.xml',
    'engine/build/publications/mavenJava/pom-default.xml',
    'gradle-plugin/build/publications/pluginMaven/pom-default.xml',
    'gradle-plugin/build/publications/mcStageWrightPluginMarkerMaven/pom-default.xml',
]

# A class path prefix found in a jar, and the license text that must travel with it.
THIRD_PARTY = [
    ('net/magicterra/stagewright/engine/json/JsonParser.class', 'META-INF/licenses/minimal-json-MIT.txt'),
    ('dev/latvian/mods/rhino/', 'META-INF/licenses/MPL-2.0.txt'),
    ('com/google/gson/', 'META-INF/licenses/Apache-2.0.txt'),
    ('com/google/errorprone/', 'META-INF/licenses/Apache-2.0.txt'),
]


BUILD_COMMANDS = [
    ['./gradlew', '--console=plain', 'jar', 'sourcesJar', 'remapJar', 'remapSourcesJar', 'shadowJar',
     'generatePomFileForMavenJavaPublication'],
    ['./gradlew', '--console=plain', '-p', 'engine', 'jar', 'sourcesJar',
     'generatePomFileForMavenJavaPublication'],
    ['./gradlew', '--console=plain', '-p', 'cli', 'jar'],
    ['./gradlew', '--console=plain', '-p', 'gradle-plugin', 'jar', 'generatePomFileForPluginMavenPublication',
     'generatePomFileForMcStageWrightPluginMarkerMavenPublication'],
]


def built_jars(module):
    # transformProduction* jars are architectury's intermediate inputs to the loader jars, never
    # handed to anyone; what they feed is checked in its own right.
    build = ROOT / module / 'build'
    return sorted(p for d in ('libs', 'devlibs') for p in (build / d).glob('*.jar')
                  if '-transformProduction' not in p.name)


def check_jars(problems):
    copying = (ROOT / 'COPYING').read_bytes()
    lesser = (ROOT / 'COPYING.LESSER').read_bytes()
    for module in MODULES:
        jars = built_jars(module)
        if not jars:
            problems.append(f'{module}: no jar built')
            continue
        for jar in jars:
            rel = jar.relative_to(ROOT)
            with zipfile.ZipFile(jar) as z:
                listed = z.namelist()
                names = set(listed)
                for entry, want in (('META-INF/COPYING', copying), ('META-INF/COPYING.LESSER', lesser)):
                    if entry not in names:
                        problems.append(f'{rel}: missing {entry}')
                    elif listed.count(entry) > 1:
                        problems.append(f'{rel}: {entry} appears {listed.count(entry)} times')
                    elif z.read(entry) != want:
                        problems.append(f'{rel}: {entry} differs from the repository copy')
                if jar.name.endswith('-sources.jar'):
                    continue
                for marker, text in THIRD_PARTY:
                    if any(n.startswith(marker) for n in names) and text not in names:
                        problems.append(f'{rel}: ships {marker} without {text}')
                if module == 'cli':
                    # A library merged into the fat jar that this list does not know about is one
                    # whose notice nobody has written.
                    known = ('net/magicterra/', 'META-INF/versions/') + tuple(m for m, _ in THIRD_PARTY)
                    for n in sorted(names):
                        if n.endswith('.class') and not n.startswith(known):
                            problems.append(f'{rel}: {n} belongs to no library with a notice')
                            break
                    if 'META-INF/THIRD-PARTY-NOTICES' not in names:
                        problems.append(f'{rel}: missing META-INF/THIRD-PARTY-NOTICES')
                    else:
                        notices = z.read('META-INF/THIRD-PARTY-NOTICES').decode('utf-8')
                        for lib in ('minimal-json', 'Rhino', 'Gson', 'Error Prone'):
                            if lib not in notices:
                                problems.append(f'{rel}: THIRD-PARTY-NOTICES does not name {lib}')


def check_poms(problems):
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    for rel in POMS:
        pom = ROOT / rel
        if not pom.exists():
            problems.append(f'{rel}: not generated')
            continue
        project = ET.parse(pom).getroot()
        names = [e.text for e in project.findall('m:licenses/m:license/m:name', ns)]
        urls = [e.text for e in project.findall('m:licenses/m:license/m:url', ns)]
        if names != [LICENSE_NAME] or urls != [LICENSE_URL]:
            problems.append(f'{rel}: license is {names} {urls}, want [{LICENSE_NAME}] [{LICENSE_URL}]')
        if project.findtext('m:url', namespaces=ns) != PROJECT_URL:
            problems.append(f'{rel}: <url> is not {PROJECT_URL}')
        if project.findtext('m:scm/m:url', namespaces=ns) != PROJECT_URL:
            problems.append(f'{rel}: <scm><url> is not {PROJECT_URL}')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--build', action='store_true', help='run the jar and POM tasks first')
    args = parser.parse_args()
    if args.build:
        for cmd in BUILD_COMMANDS:
            subprocess.run(cmd, cwd=ROOT, check=True)

    problems = []
    check_jars(problems)
    check_poms(problems)
    for p in problems:
        print(p)
    print(f'check_packaging: {"FAIL, " + str(len(problems)) + " problem(s)" if problems else "OK"}')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
