#!/bin/sh
# Launch the custom Mindustry client with the bundled OpenJDK 21 runtime.
# Game data lives under the flatpak sandbox: ~/.var/app/io.github.mindustrytng.Client
export MINDUSTRY_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/mindustry-tng"
exec /app/jre/bin/java -jar /app/bin/Mindustry.jar "$@"
