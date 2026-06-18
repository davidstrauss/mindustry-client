# Mindustry TNG — Developing

A custom Mindustry client based on **Foo's client** (`mindustry-antigrief/mindustry-client`, branch `v8`),
extended with **algorithmic build automation** (optimal mining, core-adjacent processing/unit-building,
turret defense layout).

## Repository model

This repo was seeded from Foo's client. The original remote is kept as `upstream`:

```bash
git remote -v
# upstream  https://github.com/mindustry-antigrief/mindustry-client.git
```

Add your own fork as `origin` to push your work, and pull client updates from `upstream`:

```bash
git remote add origin <your-fork-url>
git fetch upstream && git merge upstream/v8   # pull in client updates
```

> Build requires **JDK 17 exactly** — `settings.gradle` aborts on anything else.
> The devcontainer ships the right JDK, so use it.

## Dev container (Podman, Fedora Atomic)

The host uses **Podman** (no Docker) and **GNOME/Wayland**. The `.devcontainer` is set up for that.

### One-time VS Code setup

Point the Dev Containers extension at Podman (Settings → `Dev › Containers: Docker Path` = `podman`,
or in `settings.json`):

```json
"dev.containers.dockerPath": "podman"
```

Then **Reopen in Container**. Or use the CLI:

```bash
npm install -g @devcontainers/cli   # if you don't have it
devcontainer up --docker-path podman --workspace-folder .
devcontainer exec --docker-path podman --workspace-folder . bash
```

### GUI — native Wayland (preferred)

The container mounts your `XDG_RUNTIME_DIR` (Wayland + audio sockets) and `/dev/dri`, and defaults to
`SDL_VIDEODRIVER=wayland` (Arc's desktop backend is SDL2). The whole runtime dir is mounted so the
Wayland socket keeps the ownership/permissions SDL requires (`--userns=keep-id` aligns uids).

If a window fails to open, fall back to XWayland by overriding the driver for one run:

```bash
SDL_VIDEODRIVER=x11 ./gradlew desktop:run
```

(The X11 socket is mounted and `DISPLAY`/`XAUTHORITY` are passed through for exactly this fallback.)

## Build / run / test

All inside the container, from the repo root:

```bash
./gradlew desktop:run          # build + launch the client (GUI)
./gradlew desktop:dist         # -> desktop/build/libs/Mindustry.jar
./gradlew tools:pack           # repack sprites (after editing sprites)
./gradlew test                 # run the test suite
```

First run downloads a lot of Gradle/Arc dependencies — the `~/.gradle` cache is a persisted
named volume, so subsequent rebuilds are fast.

## Flatpak

The Flatpak **packages the jar built above** and runs it on a bundled OpenJDK 21 runtime
(a JDK-17-built jar runs fine on 21). `flatpak-builder` is not installed natively on the host,
so we use the Flatpak'd builder.

```bash
# one-time, on the HOST:
flatpak install -y flathub org.flatpak.Builder

# 1) build the jar (in the container)
./gradlew desktop:dist

# 2) build + install the flatpak (on the host)
./flatpak/build.sh
flatpak run io.github.mindustrytng.Client
```

Manifest & assets live in [`flatpak/`](flatpak/). The app icon is currently `core/assets/icons/icon_64.png`
(64×64) — swap in a larger square PNG and update the manifest for a crisper store icon.

## Algorithmic build automation

Scaffold lives in [`core/src/mindustry/client/tng/AutoBuild.kt`](core/src/mindustry/client/tng/AutoBuild.kt),
wired into `mindustry.client.Client` (`initialize()` + `update()`).

- Toggle in-game with the client command **`!tng on` / `!tng off` / `!tng`** (status).
- Persisted under the setting key `tng-autobuild`.
- Three modules — `MiningPlanner`, `ProcessingPlanner`, `DefensePlanner` — are currently **no-op
  stubs**. They are meant to emit `BuildPlan`s into `player.unit().plans` (not place blocks directly),
  reusing the existing `client/navigation/` machinery (`BuildPath`, `MinePath`, `Navigation.follow`,
  `AStarNavigatorOptimised`). See the KDoc in `AutoBuild.kt` for the per-module TODOs.
