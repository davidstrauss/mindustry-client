# RECON.md — `!make` + area-select feature reconnaissance

Findings for the `!make` command + area-select design (Implementation Guide §1, Task R).
All symbols verified against the current working tree on branch `v8`. Fork:
`mindustry-antigrief/mindustry-client` (a Foo's-client descendant — a mature `!`-command
system and overlay helpers already exist, so most of the guide collapses to *extend*, not build).

**Bottom line:** Nearly every subsystem the guide assumed we'd build already exists. The two
design decisions this recon forces (both flagged for the user) are:
1. **Area-select mechanism** — `F` is *not* free. Recommend reusing the existing
   `schematic_select` (F) drag while in an `awaitingArea` mode, mirroring the fork's existing
   `MiningPlanner.consumeSelection` hook, rather than adding a new bind.
2. **Paste anchoring** — the paste buffer is hard-bound to the cursor; a fixed-origin anchor is
   not reachable via public fields. Place at release point + let vanilla take over (accepted), or
   bypass the buffer with `toPlans(schem, originX, originY)` for an exact origin (optional).

---

## 1. Client command system — EXISTS, extend it (Task A collapses)

| Need | Symbol | Location |
|---|---|---|
| Command registry | `ClientVars.clientCommandHandler` (arc `CommandHandler`, prefix `"!"`, setting `fooprefix`) | `client/ClientVars.java:57` |
| Registration DSL | `fun register(format, description="", runner: CommandRunner<Player>)` → `clientCommandHandler.register(...)` | `client/Commands.kt:916` |
| Where commands are registered | `fun setupCommands()`, invoked once via `mainExecutor.execute(::setupCommands)` | `client/Commands.kt:52`, `client/Client.kt:49` |
| Example command | `register("go [x] [y]", ...) { args, player -> ... }` | `client/Commands.kt:134` |
| Chat send call site (network) | `Call.sendChatMessage(msg)` | `ui/fragments/ChatFragment.java:493` |
| **Suppression (highest-risk point)** | `handleClientCommand(String, boolean)` — `Call.sendChatMessage` reached **only** when `response.type == ResponseType.noCommand`; a matched `!` command falls into the `else` and is never networked | `ui/fragments/ChatFragment.java:485-534` (decision at `:488`) |
| Local (non-networked) message | `ui.chatfrag.addMsg(String)` (also `addMessage(String)`, full-form `addMessage(msg,sender,bg,prefix,unformatted)`) | `ui/fragments/ChatFragment.java:646/651/624` |

Arg parsing is arc `CommandHandler` native syntax: `<required>`, `[optional]`, trailing `...`.

**How to add `!make`:** inside `setupCommands()` in `client/Commands.kt`, add
`register("make <args...>", "<bundle key>") { args, player -> ... }`. It is automatically
`!`-gated and suppressed from the network; surface output via `ui.chatfrag.addMsg(...)`.
**No interception plumbing, no `ClientCommands` class, no send-site edit needed** — guide §3 (Task A)
is already done by the fork.

Multi-step interaction precedent: no per-command "next click" callback exists. The fork uses either
a persistent mode (`Navigation.follow`, `Commands.kt:172`) or a shared `ClientVars.lastSentPos`
hand-off (`DesktopInput.java:444`). For `!make` we use a small mode state machine (Task C) + the
schematic-drag hook (see §2/§3).

## 2. Keybinds — `Binding` is a CLASS (not enum); `F` is TAKEN

- `input/Binding.java:7` — `public class Binding{` holding `public static final KeyBind` fields built
  via `KeyBind.add(name, defaultValue, category?)` (arc `KeyBind`). Examples:
  `respawn = KeyBind.add("respawn", KeyCode.v)` (`:16`),
  `schematicSelect = KeyBind.add("schematic_select", KeyCode.f)` (`:34`).
- **Auto-generated rebind UI:** `ui/dialogs/KeybindDialog.java:84` iterates `KeyBind.all`; any new
  `KeyBind.add(...)` appears as a rebindable row automatically (needs bundle key
  `keybind.<name>.name`). *(Guide says "enum" — it's a class; mechanism is otherwise identical.)*
- **`F` is NOT free** — bound twice: `schematic_select` = plain `F` (`Binding.java:34`) and
  `find` = Ctrl+F (`Binding.java:129`, gated by `findModifier`=`controlLeft`).
- Key-state API accepts a `KeyBind` directly (via `Core.input`):
  `keyTap(KeyBind)` / `keyDown(KeyBind)` / `keyRelease(KeyBind)` (arc `Input.java:200/195/205`).
  Real use: `Core.input.keyDown(Binding.schematicSelect)` (`DesktopInput.java:214`).
- Cancel/Esc: `Binding.menu` (=escape, `Binding.java:89`, read `Control.java:742`);
  in-game deselect/cancel-placement uses `Binding.deselect` (=mouseRight,
  `DesktopInput.java:984/1135`). Unbound-by-default `cancelOrders` exists (`Binding.java:48`).

## 3. Draw hooks + drag-select to mirror — READY-MADE

- **World-space draw hook:** override `DesktopInput.drawTop()` (`DesktopInput.java:190`, base
  `InputHandler.java:1572`), invoked in world space by `OverlayRenderer.drawTop()`
  (`graphics/OverlayRenderer.java:205`). (`drawBottom()` at `:225`/`:1568` also available.)
  `Events.run(Trigger.update/draw)` also present in the client package
  (`client/UnimportantMenuTaskHandler.kt:15`, `client/ClientLogic.kt:276`).
- **Existing schematic drag-select (the exact thing to mirror):**
  - Fields: `schemX, schemY` tile coords (`InputHandler.java:55`); `rawCursorX/Y =
    World.toTile(Core.input.mouseWorld().x/.y)` (`DesktopInput.java:964`).
  - Start on `keyTap(Binding.schematicSelect)` → set `schemX/schemY` (`DesktopInput.java:1001-1004`).
  - Draw while `keyDown(...)`: `drawSelection(schemX, schemY, cursorX, cursorY, Vars.maxSchematicSize)`
    (`DesktopInput.java:214`); impl fills `Pal.accent` @0.3 alpha via `Fill.crect` (or strokes
    `Lines.rect` in vanilla mode) — `InputHandler.java:1877-1918`.
  - Finalize on `keyRelease(...)`: `lastSchematic = schematics.create(schemX, schemY, rawCursorX,
    rawCursorY)` (`DesktopInput.java:1019`), then reset `schemX/schemY = -1`.
- **★ Existing TNG precedent (directly reusable):** `DesktopInput.java:1015` already calls
  `MiningPlanner.INSTANCE.consumeSelection(schemX, schemY, rawCursorX, rawCursorY)` in that release
  path (impl `AutoBuild.kt:278`). This is the fork's established way to repurpose the F-drag
  rectangle — `!make` should hook the same site.
- Coord conversion: `World.toTile(float)` = `Math.round(coord/tilesize)` (`core/World.java:175`);
  `tilesize=8` (`Vars.java:137`); `Core.input.mouseWorld()/mouseWorldX()/mouseWorldY()` (arc `Input`).
  Helpers `tileX(float)/tileY(float)` (`InputHandler.java:2444/2452`). **Do not hand-roll `x/tilesize`.**

## 4. Schematic paste, model, core, threading

- **Paste API:** `Vars.control.input.useSchematic(Schematic)` → `useSchematic(schem, true)`
  (`InputHandler.java:1626`); desktop impl populates `selectPlans` from
  `schematics.toPlans(schem, schematicX, schematicY, checkHidden)` (`DesktopInput.java:904`).
- **⚠ Anchoring limitation (affects Task D / guide §6):** the paste selection is **hard-bound to the
  cursor**. Every frame `pollInput` (`DesktopInput.java:972-982`) computes
  `shiftX = rawCursorX - schematicX` and translates every plan, so the selection snaps to the mouse
  tile — there is **no field to pin it to a fixed origin**. Options:
  - **(recommended, v1)** hand to `useSchematic` on F-drag release; it appears at the release point
    (which *is* the selected area) and then tracks the cursor for vanilla nudge/rotate/confirm.
    Known offset: `toPlans` centers on the tile (`t.x + x - schem.width/2`, `Schematics.java:303`),
    so it is center-anchored, not bottom-left-origin-anchored. Accept + document (matches guide's
    "fall back to centering and note the gap").
  - **(optional, exact origin)** bypass the paste buffer: `schematics.toPlans(schem, originX,
    originY)` and add the plans directly; loses live cursor-drag repositioning.
- **Schematic model:** `game/Schematic.java:16`; ctor `Schematic(Seq<Stile>, StringMap, int width,
  int height)` (`:27`); `Stile(Block, int x, int y, Object config, byte rotation)` (`:135`, fields
  `:130-133`). Build programmatically as in `Schematics.java:406-457`. Persist via
  `Vars.schematics.add(Schematic)` (`Schematics.java:366`).
- **Core discovery:** `Vars.player.team().core()` → `CoreBuild` (`Team.java:97`, `@Nullable`);
  all cores `state.teams.get(team).cores : Seq<CoreBuild>` (`Teams.java:290`). `CoreBuild extends
  Building` (`CoreBlock.java:260`); tile coords `tileX()/tileY()` (`Building.java:1156/1160`),
  world `x/y`, footprint `core.block.size` (`Block.java:216`; odd-sized, origin math uses `size/2`).
- **Threading:** `arc.util.Threads.daemon(Runnable)` / `daemon(name, Runnable)` for heavy off-thread
  work; marshal back with `Core.app.post(Runnable)`. Established pattern:
  `Commands.kt:857` (`Threads.daemon { ... Core.app.post { ... } }`), `NetClient.java:207`,
  `Restore.kt:35`. Dedicated `ClientThread` also exists (`navigation/ClientThread.kt`).

## 5. Deltas from the Implementation Guide

1. **Task A (§3) — mostly done.** No `ClientCommands` class, no send-site edit, no suppression
   plumbing. Just `register("make <args...>", ...)` in `Commands.kt:setupCommands()`. Suppression is
   already correct at `ChatFragment.java:488`.
2. **`Binding` is a class, not an enum** — same auto-rebind mechanism; use `KeyBind.add(...)`.
3. **`F` is taken** (schematic_select + Ctrl+F find). **Recommended: no new bind** — enter
   `awaitingArea` mode on `!make`, and consume the next `schematic_select` (F) drag as the make-area,
   suppressing the normal schematic copy. This mirrors the existing
   `MiningPlanner.consumeSelection` hook at `DesktopInput.java:1015` and sidesteps the key conflict.
   (Alternative per guide: add `makeAreaSelect = KeyBind.add("make_area_select", KeyCode.unset,
   "client")` and let the user bind it.)
4. **Draw overlay is ready-made** — mirror `drawTop()` + `drawSelection(...)`; don't invent a path.
5. **Paste cannot be pinned to a fixed origin** (see §4 ⚠). Accept center-on-release for v1.
6. **Target resolution generalizes beyond silicon** (user directive): resolve arbitrary craftables
   *and* units (`unit:flare:t5`-style) from the content-dump alias table; silicon is only the
   smoke-test in the Definition of Done, never special-cased in code. Min-size/rate math derives
   from the resolved target, not silicon constants.
7. **Miner overlap:** the existing miner (`AutoBuild.kt`/`MiningPlanner.consumeSelection`) occupies
   the exact F-drag hook we want to reuse. User has OK'd discarding the miner WIP — decision pending
   (see open questions).

## 6. Revised, fork-specific task shape

- **Task A** → `register("make <args...>")` in `Commands.kt`; parse target/rate/flags; resolve
  target from dump; set partial `GenRequest`; enter `awaitingArea`. (No plumbing.)
- **Task B** → *no new bind.* In `awaitingArea`, consume the `schematic_select` F-drag at the
  `DesktopInput.java:1015` release site (where the miner hook is today); draw the live rect via the
  existing `drawSelection` path already active for schematic-select.
- **Task C** → `enum MakeState { idle, awaitingArea, generating }`; on area capture snapshot
  `GenRequest`, run `generator.generate` on `Threads.daemon`, `Core.app.post` the result.
- **Task D** → `useSchematic(result.schematic)` on the main thread; accept cursor-anchoring
  (center-on-release); document the exact-origin bypass.
- **Task E/F/G** → as written; heatmap draws in `drawTop()`; flags parsed post-target; `!make help`
  generated from the dump alias table + flag registry; append invariants to `CLAUDE.md`.

## 7. Open questions for the user (blocking feature code)

1. **Discard the miner WIP?** Reusing the `DesktopInput.java:1015` area-select hook cleanly means
   removing/replacing the current `MiningPlanner.consumeSelection` wiring. User said the miner may be
   discarded — confirm we remove the uncommitted miner changes (AutoBuild.kt, MINING_TOOL.md,
   MiningPlannerTests.java, SchematicGen.kt, SchematicGenTests.java) and free that hook.
2. **Area-select mechanism:** reuse `schematic_select` (F) drag in `awaitingArea` mode
   (recommended, no conflict) vs. add a new rebindable `make_area_select` bind (unbound by default)?
