#!/usr/bin/env bash
# Build & install the Mindustry TNG Flatpak from a locally-built jar.
#
# Prereqs (run once on the host; flatpak-builder is NOT installed natively here,
# so we use the Flatpak'd builder):
#   flatpak install -y flathub org.flatpak.Builder
#
# The game jar must already exist (build it in the devcontainer):
#   ./gradlew desktop:dist   ->   desktop/build/libs/Mindustry.jar
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MANIFEST="io.github.mindustrytng.Client.yml"
JAR="$REPO_ROOT/desktop/build/libs/Mindustry.jar"

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: $JAR not found. Build it first: ./gradlew desktop:dist" >&2
  exit 1
fi

# Ensure the runtime + SDK + JDK extension are present (from flathub).
flatpak install -y --user flathub \
  org.freedesktop.Platform//25.08 \
  org.freedesktop.Sdk//25.08 \
  org.freedesktop.Sdk.Extension.openjdk21//25.08 || true

cd "$SCRIPT_DIR"
flatpak run org.flatpak.Builder \
  --user --install --force-clean \
  --install-deps-from=flathub \
  build-dir "$MANIFEST"

echo
echo "Done. Run it with:  flatpak run io.github.mindustrytng.Client"
