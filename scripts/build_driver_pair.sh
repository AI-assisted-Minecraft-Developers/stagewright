#!/usr/bin/env bash
# Build and inspect a local candidate pair. Does not push or publish to a remote repository.
set -euo pipefail
stagewright_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
worlddriver_root="$(cd "${1:?usage: build_driver_pair.sh /path/to/worlddriver}" && pwd)"
log_root="$stagewright_root/build/driver-pair-logs"
mkdir -p "$log_root"

candidate_version() {
    python3 - "$1" <<'VERSION'
import os
import re
import sys
from pathlib import Path
props = {}
for line in (Path(sys.argv[1]) / 'gradle.properties').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        props[key.strip()] = value.strip()
base = props.get('mod_base_version')
if base is None:
    version_script = (Path(sys.argv[1]) / 'gradle/version.gradle').read_text()
    base = re.search(r"def base = '([^']+)'", version_script).group(1)
print(f"{base}-build.{os.environ.get('BUILD_NUMBER', 'local')}+{props['minecraft_version']}")
VERSION
}
stagewright_candidate="$(candidate_version "$stagewright_root")"
worlddriver_candidate="$(candidate_version "$worlddriver_root")"
# A CI step that builds against the pair afterwards reads the versions here instead of deriving them.
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf 'stagewright_version=%s\nworlddriver_version=%s\n' \
        "$stagewright_candidate" "$worlddriver_candidate" >> "$GITHUB_OUTPUT"
fi

run_step() {
    local repository="$1" label="$2"
    shift 2
    printf '%s -> %s\n' "$label" "$log_root/$label.log"
    if (cd "$repository" && "$@") > "$log_root/$label.log" 2>&1; then
        return 0
    else
        printf 'FAILED: %s\n' "$log_root/$label.log" >&2
        return 1
    fi
}

run_step "$stagewright_root" engine ./gradlew -p engine check publishToMavenLocal
run_step "$stagewright_root" plugin ./gradlew -p gradle-plugin check publishToMavenLocal
run_step "$stagewright_root" contracts ./gradlew :stagewright-api:publishToMavenLocal :stagewright-attached:publishToMavenLocal
run_step "$worlddriver_root" driver-bootstrap ./gradlew :common:publishToMavenLocal -PworlddriverBootstrap "-Pstagewright_version=$stagewright_candidate"
run_step "$stagewright_root" framework ./gradlew build publishToMavenLocal "-Pworlddriver_version=$worlddriver_candidate"
run_step "$stagewright_root" cli ./gradlew -p cli check jar
run_step "$worlddriver_root" driver ./gradlew build :common:generatePomFileForMavenJavaPublication :fabric:generatePomFileForMavenJavaPublication :neoforge:generatePomFileForMavenJavaPublication "-Pstagewright_version=$stagewright_candidate"
run_step "$stagewright_root" framework-packaging python3 scripts/check_packaging.py --build "--gradle-arg=-Pworlddriver_version=$worlddriver_candidate"
run_step "$stagewright_root" framework-boundaries python3 scripts/check_architecture.py --jars
run_step "$worlddriver_root" driver-packaging python3 scripts/check_packaging.py
run_step "$worlddriver_root" driver-boundaries python3 scripts/check_architecture.py --jars
run_step "$worlddriver_root" driver-budget python3 scripts/check_source_budget.py
run_step "$worlddriver_root" driver-remap python3 scripts/check_remap_safety.py
printf 'Local build and packaging passed. Joint acceptance still requires all six game gates and stagewrightCoverage.\n'
