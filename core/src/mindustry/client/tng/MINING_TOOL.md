# TNG Algorithmic Mining Tool — Rules & Spec

Authoritative rules for the `!mine` tool ([AutoBuild.kt](AutoBuild.kt), `MiningPlanner`).
This is the source of truth — follow it when changing the tool. Items still to do are
marked `[pending]`; everything else is already implemented (don't label those).

Verify changes: `./gradlew :tests:test --tests MiningPlannerTests`
(IDE diagnostics are unreliable here; trust the Gradle build. In-game:
`SDL_VIDEODRIVER=x11 ./gradlew desktop:run`.)

## Trigger / UX
- `!mine [element] [maxtech]` arms the tool; the **next drag-select** feeds its
  rectangle to the planner. Desktop only (hook in `DesktopInput`, schematic-select
  release).
- Args are **order-tolerant**: a token matching an ore name → mine only that ore; a
  drill name or tier number → max-tech cap. Both optional.
- Plans are emitted into `player.unit().plans` (never place blocks directly). The
  planner (`planSelection`) is a **pure** function returning `Seq<BuildPlan>`;
  `consumeSelection` does the enqueue.

## Mining selection
- **Sand avoidance:** never mine sand unless **> 95%** of minable tiles are sand
  (`SAND_MAJORITY`); then flip to sand-only. In ore mode the engine's `lowPriority`
  already keeps drills off sand; sand-only footprints are rejected if a mineable ore
  is under them (ore would override).
- **Element targeting:** optional element arg restricts mining to that one ore (or
  sand if named). Default = auto-pick the dominant ore.
- **Throughput optimisation with a tech band:** choose the highest-throughput drill
  within `[worst effective drill .. cap]`. Ties → the cheaper (lower-tier) drill
  ("use at least the worst effective drill").
- **Max-tech cap default** = the best drill whose **full material cost has been in
  the core at least once** ("ever loaded", tracked every frame). Explicit arg caps by
  drill name/tier.
- **Water boost is modelled:** a drill bordered by an in-selection water tile (where a
  pump will sit) is credited ×`liquidBoostIntensity`. Only *water* boosters count
  (not ozone drills).
- **Copper/lead have only two regimes:** super-early (basic mechanical drill, pre-Mono)
  and late-game volume. Skip the mid-tier drills for copper/lead entirely unless BOTH
  the volume minimums — the 4×4 blast drill AND plastanium belts — are buildable.
- **Starter-first ordering + gating:** copper, then lead, are mined FIRST. Every other ore
  is *dropped entirely* until BOTH copper and lead are actively **delivered** to the core
  (`delivered` set), so we never spend materials on a titanium/etc. network before the
  starters are flowing (`orderedTargets`). Delivery means an inbound **increase** in core
  stock (units depositing, or our belts arriving) — NOT mere presence: the core's initial
  allocation of copper/lead is baselined and does not count. An explicit `!mine <ore>`
  bypasses the gate.

## Pumps & plumbing
- **Heavy preference for energy-free, 1×1 pumps:** power-free first, then smallest →
  the mechanical pump wins. Powered pumps are a last resort. Conduits/belts are
  power-free anyway.
- Pumps go on liquid tiles and also boost adjacent drills (a pump dumps liquid
  straight into a neighbouring drill — no conduit needed).

## Do not interfere with existing structures
- **Never remove or overwrite** anything: no break plans, ever.
- Build **around** existing buildings — `Build.validPlace` rejects occupied tiles, so
  existing 3×3/4×4 drills are accommodated, not replaced.
- Belts **stop at** existing buildings and never cross our own placed drills/pumps
  (tracked in the `occupied` set). Don't interfere with existing paths.
- **No cross-contamination, both directions**, via the reusable [Interference](Interference.kt)
  library (items *and* fluids):
  - belts run as **armored conveyor** on tiles where a foreign structure sits beside the
    line (`Interference.safeConveyor`) — neighbours can't side-load our stream;
  - belts stop before a foreign building ahead, so we never dump our material into it;
  - pumps skip tiles bordering a foreign **liquid** network (`adjacentForeignSource`),
    so our water can't leak into theirs (or theirs into our boost feed).
  - The library also exposes junction (cross-without-mixing) and bridge (hop-over) blocks
    for items and liquids, affordability-gated — see `[pending]` routing below.

## Delivery — connected transport + mining mesh (`layMesh`)
- **Efficient coverage, few belts back.** Drill bands fill the ore densely; each band has a
  shared collector lane that flows into a **slim vertical trunk reserved on the edge that
  faces the delivery target** — so output heads *toward* the core, never a fixed corner.
- **No dead belts — trim to the drills.** Belts are laid only where they actually carry
  something, so a sparse/L-shaped cluster inside a big bounding box never gets carpeted: a
  band that placed **no drills** gets **no collector lane**; a collector lane runs only from
  the trunk edge out to the band's **farthest drill** (never past it); and each trunk column
  runs only from the exit up to the **farthest band it must carry or cross** (a lane feeds or
  junction-crosses column *i* only when *i* is at/deeper than the lane's target), so shallow
  columns stop short instead of running dead to the top.
- **Orientation is data-driven** (`deliveryHint`): the trunk hugs the west **or** east edge
  and exits south **or** north depending on where the core actually is (in-selection core,
  else closest core, else field centre). Collectors flow toward that edge; the exit row
  gathers all trunk columns into the core-nearest column, which feeds the final leg.
- **Multi-ore = a separate network per ore.** `planSelection` runs one mesh per ore,
  each confined to that ore's bounding box (`oreBounds`), with `occupied` accumulating so
  **different ores never share a belt** (clog-safe). Never mix items on a belt unless there
  is no unmixed path to the core at all. Explicit `!mine <ore>` = just that ore;
  >95% sand = sand-only.
- **Trunk width = lane count, kept minimal — courteous near cores.** `lanes` parallel trunk columns
  (always leaving room for ≥1 drill column); bands are assigned round-robin to a column and **cross
  the deeper columns through junctions** (no mixing) before turning to the exit. Lane count is sized
  to the **trunk medium's** capacity, so the fastest affordable medium yields the fewest columns.
- **Chained-bridge trunk when stuck on basic belts.** Collector lanes and the final leg use the
  fastest affordable *plain* belt; the vertical trunk uses the same belt **unless** we're still on
  the basic conveyor (no titanium) *and* the saturated rate exceeds one basic belt (4.2/s) — then
  each trunk column is a **chain of item bridges** (`bridge-conveyor`: copper+lead, power-free,
  ~11/s) instead of ~3 parallel basic lanes, so the trunk stays narrow near the core. Bridges are
  linked exit-first (each upstream bridge points at the next bridge toward the exit), **hop over the
  junction crossings** (range 4), accept collector input from the side at each tile, and the
  downstream-most bridge dumps into the plain exit row. Titanium/plastanium, when affordable, just
  use a plain belt (same/greater capacity, simpler).
- **Final leg prefers belts.** Belt to an in-selection core; else launch (only when thorium
  is mined — see below); else a belt stub out of the area toward the nearest core.
- **Belt sizing = width × speed for the *saturated* rate.** Size for what fully
  water-boosted drills would put out (worst case, esp. 3×3/4×4), using **plain** belts:
  fastest affordable tier (basic → titanium "blue" → plastanium), then enough lanes that
  tier×lanes covers it — the route never needs accelerating.
- **`[pending]` Bridge over foreign obstacles.** Junctions are now used for our own lane
  crossings, but a *foreign* building mid-route still leaves a lane gap — routing should
  drop an **item bridge / phase conveyor** to hop it. (Can't junction-cross a foreign belt
  without overwriting it, which is forbidden — so hopping via bridge is the move.)
- **Belts vs bridges (trunk):** the trunk picks the medium that minimises lane count — a
  power-free item-bridge chain when stuck on basic belts and volume exceeds one belt (see
  "Chained-bridge trunk" above). `[pending]` extend the same belt-vs-bridge choice to the
  collector lanes and final leg, and to hopping *foreign* obstacles mid-route.
- **`[pending]` Output mode (selectable per run):** aerial delivery (leave output, build
  nothing) vs dump onto adjacent belts.

## Power (`planPower`)
- Every power-consuming block placed (laser/blast/impact/eruption drills, rotary/impulse
  pumps, mass drivers) is wired with **tiny 1×1 power nodes** (`powerNode`) — preferred
  because they're the cheapest and always do the job. A pole is dropped only where a
  consumer isn't already within range of one; nodes auto-link to each other and to an
  existing grid in range.
- **`[pending]` Power source.** No generator is placed — connect the field to your grid
  (nodes link to an in-range source automatically). Auto-generation is out of scope.

## Delivery via launcher (`launchToCore`)
- **Belts are preferred; launching is only viable once thorium is being mined** (`coreSeen`
  contains thorium — the late-game signal the mass-driver economy is up). Even then, an
  in-selection core is served by belt, not launched.
- When used: the trunk exit feeds a **sender mass driver** placed nearest the exit, which
  **launches** to a **receiver mass driver** beside the closest friendly core in range.
  Launching skips every intermediate tile → **zero en-route contamination**, and crosses
  terrain a belt can't.
- **`[pending]` Relays for out-of-range cores** and a **scalable receiver bank** sized to
  volume (the holmes-g `mass-driver-N-core-receiver` schematics) — v1 is a single pair.

## Other candidate ideas from external schematics (holmes-g/schematics)
- **`[pending]` Water extractors for boost.** When no natural water borders the field,
  place a **water extractor** (power-fed) to supply boost water, so drills still hit the
  boosted rate anywhere — not only next to lakes.
- **`[pending]` Overdrive projector/dome.** A second accelerator beyond water; covers the
  whole field (drills + transport). Optional throughput multiplier.
- **`[pending]` Double / contiguous packing.** Back-to-back drill rows sharing a
  collector/driver — denser than our single-band-per-lane layout.
