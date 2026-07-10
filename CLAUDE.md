# Mindustry TNG

A custom Mindustry client based on **Foo's client** (`mindustry-antigrief/mindustry-client`, branch
`v8`), extended with algorithmic build automation. The active feature is the **`!make` factory
generator** in `core/src/mindustry/client/tng/` (an earlier mining tool was removed; git history has
it if ever needed).

## Build / run / test

> Requires **JDK 17 exactly** — `settings.gradle` aborts on anything else. The devcontainer ships it.

```bash
./gradlew desktop:run          # build + launch the client (GUI)
./gradlew desktop:dist         # -> desktop/build/libs/Mindustry.jar
./gradlew tools:pack           # repack sprites (after editing sprites)
./gradlew test                 # run the test suite
./gradlew :tests:test --tests "test.Make*" --tests "test.FactoryArrayGeneratorTests"   # tng suites
```

- IDE diagnostics are unreliable here; trust the Gradle build.
- If Gradle fails with `Could not pack tree ... classpath-snapshot` (container filesystem quirk),
  add `--no-build-cache`.

### Dev container (Podman, Fedora Atomic)

The host uses **Podman** (no Docker) and GNOME/Wayland. Point the VS Code Dev Containers extension
at Podman (`"dev.containers.dockerPath": "podman"`), then Reopen in Container; or:

```bash
devcontainer up --docker-path podman --workspace-folder .
devcontainer exec --docker-path podman --workspace-folder . bash
```

**GUI renders through XWayland, not Wayland**: the bundled `libSDL2.so` lacks the Wayland video
driver, so the container sets `SDL_VIDEODRIVER=x11`, mounts `/tmp/.X11-unix`, and passes
`DISPLAY`/`XAUTHORITY` (`--userns=keep-id` aligns uids). Don't set `SDL_VIDEODRIVER=wayland` — it
fails with `SdlError: wayland not available`. `XDG_RUNTIME_DIR` is mounted only for audio.

### Flatpak

Packages the jar built above on a bundled OpenJDK 21 runtime. `flatpak-builder` runs via Flatpak:

```bash
flatpak install -y flathub org.flatpak.Builder   # one-time, on the HOST
./gradlew desktop:dist                           # 1) build the jar (in the container)
./flatpak/build.sh                               # 2) build + install (on the host)
flatpak run io.github.mindustrytng.Client
```

Manifest/assets in `flatpak/`. App icon is `core/assets/icons/icon_64.png` — swap in a larger
square PNG + update the manifest for a crisper store icon.

## The `!make` feature

`!make <target> [rate] [flags]` arms an area-select; the next `schematic_select` (F) drag is
consumed as the build area, a factory layout for `<target>` is generated **off the render thread**,
and the resulting plans are added to the player's build queue at the captured origin.

Layout:

- `client/tng/gen/` — the pure generator boundary: `SchematicGenerator` (interface),
  `GenRequest`/`GenResult`/`IntRect`/`EdgeLoad`/`PowerMode` (value types), `StubGenerator`,
  `FactoryArrayGenerator` (the real one: recipe-graph chain planning + banded row layout).
- `client/tng/make/MakeController.kt` — command/state machine (`idle → awaitingArea → generating`),
  target/rate/flag parsing, off-thread dispatch, plan placement.
- `client/tng/Interference.kt` — standalone contamination-avoidance library for transport routing
  (kept for the upcoming belt-routing work).
- Entry points: `register("make ...")` in `client/Commands.kt`; F-drag hook at the
  `schematic_select` release in `input/DesktopInput.java` (`MakeController.consumeSelection`,
  returns true ⇒ suppress the normal schematic copy). Desktop is the v1 path; mobile is out of scope.

### Invariants (do not break)

1. **The generator is pure.** `SchematicGenerator.generate` reads only the request + loaded content
   (block specs); no world, GL, or UI access — it must be safe off the render thread and in headless
   tests. The controller snapshots the request (`GenRequest.copy()`) before handing it off-thread.
2. **Deterministic.** Same request + same seed ⇒ byte-identical schematic (asserted in
   `SchematicGeneratorTests`). No wall-clock, no unseeded randomness, stable iteration orders.
3. **Content-driven, never hardcoded.** Crafter choice, I/O rates, sizes, belt tiers, and
   mined-vs-crafted classification are read from `Vars.content`. Silicon is only the smoke-test
   target; nothing may special-case it. Targets generalize to any craftable item and (planned)
   `unit:<name>` specs.
4. **Maximize engine reuse for accuracy.** Placement maps through the engine's own
   `schematics.toPlans` (note: it *centers* on the tile — anchor via `areaX + width/2`). Correctness
   is verified by building layouts in a real headless world and ticking the actual simulation
   (`MakeSimTests`), not only structurally.
5. **The client never force-builds.** Output is `BuildPlan`s the server still validates; client
   commands (`!` prefix) are never networked (suppression at `ChatFragment.handleClientCommand`).
6. **No live simulation at generation time.** Any predicted load/throughput surfaced to the user is
   the model's estimate, not a measurement.

### Test layers (`tests/src/test/java/`)

- `SchematicGeneratorTests` — boundary contract: fits-the-area, determinism.
- `FactoryArrayGeneratorTests` — structural: counts, overlap, belt tiers, chain sizing.
- `MakeControllerTests` / `MakeIntegrationTests` — parsing, state machine, plan anchoring.
- `MakeSimTests` — full engine simulation: builds the layout headless, feeds inputs/power via
  sandbox blocks, ticks, asserts the product reaches the sink.
- `InterferenceTests` — the routing-support library.

### Known gaps (FINISHMEs, roughly in priority order)

- **Inter-stage belt routing**: multi-stage chains (e.g. surge alloy) stack internally-correct
  bands, but the intermediate product is not routed into the next stage's inputs (a left-collect +
  up-riser attempt stalled throughput; needs a dedicated routing pass). Player connects it manually.
- **Input distribution**: the input row is bare up-facing conveyors; supply routing (incl. the
  coal/sand 2:1 zipper for silicon) is external.
- Multi-row/multi-lane packing; `coreSide` orientation; `--max` flag; seed source; per-target
  min-size; unit targets; replacing the hand-maintained alias table in `MakeController` with the
  content dump; heatmap/preview overlay.

## Fork facts worth knowing (from the original recon)

- **Client command system exists**: arc `CommandHandler` at `ClientVars.clientCommandHandler`,
  prefix `!`; register via `register(format, description) { args, player -> }` inside
  `Commands.kt:setupCommands()`. Matched commands are never sent to the network. Local chat output:
  `ui.chatfrag.addMsg(...)`.
- **`Binding` is a class, not an enum** — `KeyBind.add(name, default)`; new binds auto-appear in the
  rebind UI (bundle key `keybind.<name>.name`). Plain `F` = `schematic_select`, Ctrl+F = `find`, so
  `!make` reuses the F-drag rather than adding a bind.
- **Paste buffer is cursor-bound** — there is no fixed-origin anchor in `useSchematic`; `!make`
  bypasses it by adding plans directly via `toPlans(schem, cx, cy)` + `unit.addBuild`.
- **Threading pattern**: `Threads.daemon { work; Core.app.post { apply } }` (established across the
  fork). A dedicated `ClientThread` also exists under `client/navigation/`.
- **Coord conversion**: use `World.toTile` / `InputHandler.tileX/tileY`; `tilesize = 8`. Don't
  hand-roll `x / tilesize`.
- **World-space overlay drawing**: override `DesktopInput.drawTop()` (called by
  `OverlayRenderer.drawTop()`); the drag rectangle uses the existing `drawSelection` path.
- **Conveyor acceptance rule** (the basis of the layout geometry): a conveyor accepts items from its
  back and sides, never its front — input belts point *into* consumers, output lanes are collected
  from the side, and adjacent crafters can't back-dump into each other's inputs.
- **Power on net clients**: auto-link is disabled in multiplayer, so generated power nodes carry
  explicit `Point2[]` link configs; the array must self-power end-to-end from one external hookup.
