package mindustry.client.tng

import arc.Core
import arc.math.Mathf
import arc.math.geom.Point2
import arc.struct.IntSet
import arc.struct.ObjectIntMap
import arc.struct.ObjectSet
import arc.struct.Seq
import arc.util.Log
import mindustry.Vars.content
import mindustry.Vars.maxSchematicSize
import mindustry.Vars.player
import mindustry.Vars.state
import mindustry.Vars.tilesize
import mindustry.Vars.world
import mindustry.client.ClientVars.clientCommandHandler
import mindustry.content.Blocks
import mindustry.content.Items
import mindustry.content.Liquids
import mindustry.entities.units.BuildPlan
import mindustry.gen.Player
import mindustry.type.Item
import mindustry.world.Block
import mindustry.world.Build
import mindustry.world.blocks.distribution.BufferedItemBridge
import mindustry.world.blocks.distribution.MassDriver
import mindustry.world.blocks.power.PowerNode
import mindustry.world.blocks.production.Drill
import mindustry.world.blocks.production.Pump
import mindustry.world.blocks.storage.CoreBlock
import mindustry.world.consumers.ConsumeLiquid

/**
 * Mindustry TNG — algorithmic build automation.
 *
 * SCAFFOLD: the wiring, the master toggle and the per-module update loop are in
 * place and proven (the `!tng` command works in-game and [update] is driven from
 * [mindustry.client.Client.update]). The actual placement heuristics for each
 * module are deliberately left as follow-up work.
 *
 * Design intent for the modules (do NOT place blocks directly): each planner
 * should emit [mindustry.entities.units.BuildPlan]s into the player unit's build
 * queue (`player.unit().plans`) so the work composes with the client's existing
 * automation and undo. Reuse what's already here rather than reinventing it:
 *   - [mindustry.client.navigation.BuildPath]  — schematic/queue builder + priority sorting
 *   - [mindustry.client.navigation.MinePath]   — auto-mining with core-fill detection
 *   - [mindustry.client.navigation.Navigation.follow] — drive the unit along a path
 *   - [mindustry.client.navigation.AStarNavigatorOptimised] — pathfinding
 */
object AutoBuild {
    /** Master toggle. Persisted via Arc settings under "tng-autobuild". */
    @JvmStatic
    var enabled = false
        private set

    /** Ordered so mining feeds processing feeds defense. */
    private val modules: List<AutoModule> = listOf(MiningPlanner, ProcessingPlanner, DefensePlanner)

    /** Called once from [mindustry.client.Client.initialize]. */
    fun init() {
        enabled = Core.settings.getBool("tng-autobuild", false)
        registerCommands()
        Log.info("[TNG] AutoBuild initialized (enabled=@, modules=@)", enabled, modules.map { it.name })
    }

    /** Called every frame from [mindustry.client.Client.update]. */
    fun update() {
        if (state?.isGame != true) return
        // Track core materials even while automation is OFF, so the on-demand mining
        // tool's "best buildable drill" cap reflects everything ever loaded into the core.
        MiningPlanner.trackCoreMaterials()
        if (!enabled) return
        if (player?.unit() == null) return
        for (m in modules) {
            try {
                m.update()
            } catch (e: Exception) {
                Log.err("[TNG] module ${m.name} failed", e)
            }
        }
    }

    private fun setEnabled(value: Boolean) {
        enabled = value
        Core.settings.put("tng-autobuild", value)
        Core.settings.forceSave()
    }

    private fun registerCommands() {
        clientCommandHandler.register(
            "tng",
            "[on|off|status]",
            "Toggle TNG algorithmic build automation (mining, processing, defense)"
        ) { args, player: Player ->
            when (args.getOrNull(0)?.lowercase()) {
                "on", "true", "1" -> {
                    setEnabled(true)
                    player.sendMessage("[accent][TNG][] automation [green]ON[]. Active modules: ${modules.joinToString { it.name }}")
                }
                "off", "false", "0" -> {
                    setEnabled(false)
                    player.sendMessage("[accent][TNG][] automation [scarlet]OFF[]")
                }
                else -> player.sendMessage(
                    "[accent][TNG][] automation is " +
                        (if (enabled) "[green]ON[]" else "[scarlet]OFF[]") +
                        " — use [accent]!tng on[]/[accent]off[]"
                )
            }
        }

        // !mine is retired: the schematic-select drag now feeds the !make tool (MakeController),
        // not MiningPlanner. The MiningPlanner engine + its unit tests are kept for reuse when the
        // miner is redeveloped on top of the new SchematicGenerator boundary. See RECON.md.
        // clientCommandHandler.register(
        //     "mine",
        //     "[args...]",
        //     "Arm the TNG mining tool, then drag-select an area. ..."
        // ) { args, player: Player ->
        //     player.sendMessage(MiningPlanner.arm(args.getOrNull(0)))
        // }
    }
}

/** One automation concern (mining, processing, defense). */
interface AutoModule {
    val name: String

    /** Invoked every frame while [AutoBuild.enabled]. Should be cheap / rate-limited internally. */
    fun update()
}

/**
 * Algorithmic mining construction tool.
 *
 * On-demand (not per-frame): the `!mine [maxtech]` command arms the tool, then the
 * next drag-selection (schematic-select) feeds its rectangle to [planArea], which
 * tiles the region with the throughput-optimal drills + pumps and emits [BuildPlan]s
 * into the player unit's build queue.
 *
 * Behaviour (per spec):
 *  - Sand is avoided: drills only target ore. The engine already deprioritises sand
 *    ([Item.lowPriority]) under any drill that also covers ore, so an ore-mode drill
 *    never wastes itself on sand. ONLY when the selection is overwhelmingly sand
 *    ([SAND_MAJORITY]) does the tool flip to sand-only mode.
 *  - Water/liquids use 1x1 pumps preferentially (smallest affordable pump first).
 *  - Drill choice maximises area throughput, bounded below by the worst *effective*
 *    drill (one that can actually mine the target) and above by [capTier] — which
 *    defaults to the best drill whose full material cost has been in the core at
 *    least once (see [coreSeen]).
 */
object MiningPlanner : AutoModule {
    override val name = "mining"

    /** Fraction of minable tiles that must be sand before flipping to sand-only mode. */
    private const val SAND_MAJORITY = 0.95f

    /** Every item ever observed in the local player's core — basis for the build cap. */
    private val coreSeen = ObjectSet<Item>()

    /** Items observed being *delivered* into the core (an inbound increase, not just presence). */
    private val delivered = ObjectSet<Item>()
    /** Previous-frame core amounts, to detect inbound increases. */
    private val lastAmounts = ObjectIntMap<Item>()
    /** False until the first core sample establishes the baseline (so initial stock isn't "delivered"). */
    private var baselined = false

    /** Armed by `!mine`; consumed by the next drag-selection. */
    private var armed = false
    /** Upper tech bound (max [Drill.tier] allowed). [Int.MAX_VALUE] = best buildable. */
    private var capTier = Int.MAX_VALUE
    private var capLabel = "best buildable"
    /** Explicit ore to mine (from the command), or null to auto-pick from the selection. */
    private var argTarget: Item? = null
    /** The item targeted for the current run; null means "any non-sand ore". */
    private var runTarget: Item? = null

    /** Tiles claimed by this run's drills/pumps, so delivery belts route AROUND them. */
    private val occupied = IntSet()
    /** Centres of placed power-consuming blocks, to wire up with power poles. */
    private val poweredTiles = ArrayList<IntArray>()
    /** Remembered delivery destination (packed tile) so a rebuild routes to the same place. */
    private var lastDestPos = -1

    /** On-demand tool — nothing to do per frame. */
    override fun update() { /* no-op: driven by the !mine command + drag-select */ }

    /** Forget the "materials ever loaded" memory — call when entering a new game; also used by tests. */
    fun clearLearnedMaterials() {
        coreSeen.clear()
        delivered.clear()
        lastAmounts.clear()
        baselined = false
    }

    /**
     * Record core state. Called every frame (even while OFF). Tracks two things:
     *  - [coreSeen]: every item ever present (the build cap's "ever afforded" basis);
     *  - [delivered]: items observed being *delivered* — an inbound increase versus the previous
     *    sample. The first sample only establishes a baseline, so the core's INITIAL allocation
     *    of copper/lead does NOT count as delivery; only a later increase (units depositing, or
     *    our belts arriving) does. This is what gates the non-starter ores.
     */
    fun trackCoreMaterials() {
        val core = player?.core() ?: return
        core.items.each { item, amount ->
            if (amount > 0) coreSeen.add(item)
            val prev = lastAmounts.get(item, 0)
            if (baselined && amount > prev) delivered.add(item)
            lastAmounts.put(item, amount)
        }
        baselined = true
    }

    /**
     * Arm the tool. Accepts a free-form arg string `[element] [maxtech]` (both optional,
     * order-tolerant): a token matching an ore name restricts mining to that ore; a drill
     * name or tier number caps the best drill used.
     */
    fun arm(arg: String?): String {
        capTier = Int.MAX_VALUE
        capLabel = "best buildable"
        argTarget = null
        if (!arg.isNullOrBlank()) {
            for (tok in arg.trim().split(Regex("\\s+"))) {
                val item = content.items().find { it.name.equals(tok, true) || it.localizedName.equals(tok, true) }
                if (item != null) { argTarget = item; continue }
                val tier = tok.toIntOrNull()
                if (tier != null) { capTier = tier; capLabel = "tier <= $tier"; continue }
                val drill = allDrills().minByOrNull { biasedNameDistance(tok, it) }
                if (drill != null && biasedNameDistance(tok, drill) <= 1) {
                    capTier = drill.tier; capLabel = "<= ${drill.localizedName}"; continue
                }
                return "[scarlet][TNG][] unknown argument '$tok' — use an ore name, a drill name, or a tier number."
            }
        }
        armed = true
        val mineLabel = argTarget?.localizedName ?: "auto"
        return "[accent][TNG][] mining tool armed (mine: $mineLabel, cap: $capLabel). [lightgray]Drag-select the area.[]"
    }

    /**
     * Called from [mindustry.input.DesktopInput] on schematic-select release.
     * @return true if the tool was armed and handled the selection (so the caller
     *   should NOT turn it into a schematic).
     */
    fun consumeSelection(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (!armed) return false
        armed = false
        try {
            val plans = planSelection(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
            val unit = player?.unit()
            if (unit != null) for (p in plans) unit.addBuild(p)
            player?.sendMessage(
                if (plans.isEmpty) "[accent][TNG][] nothing minable in selection."
                else "[accent][TNG][] queued [stat]${plans.size}[] mining structures."
            )
        } catch (e: Exception) {
            Log.err("[TNG] mining tool failed", e)
            player?.sendMessage("[scarlet][TNG][] mining error: ${e.message}")
        }
        return true
    }

    // ------------------------------------------------------------------ planning

    /**
     * Pure planner: compute the drills/pumps/belts for a selection WITHOUT enqueueing them.
     * Public so it can be unit-tested headlessly ([consumeSelection] does the actual enqueue).
     */
    fun planSelection(rx1: Int, ry1: Int, rx2: Int, ry2: Int): Seq<BuildPlan> {
        val plans = Seq<BuildPlan>()
        val plr = player ?: return plans
        val team = plr.team()

        // Clamp the selection to the schematic size limit.
        val x1 = rx1
        val y1 = ry1
        val x2 = minOf(rx2, rx1 + maxSchematicSize - 1)
        val y2 = minOf(ry2, ry1 + maxSchematicSize - 1)

        // --- 1. classify the selection: count ore vs sand, find the dominant ore ---
        trackCoreMaterials()
        var oreTiles = 0
        var sandTiles = 0
        val oreCounts = ObjectIntMap<Item>()
        forEachTile(x1, y1, x2, y2) { t ->
            val drop = t.drop() ?: return@forEachTile
            if (drop == Items.sand) {
                sandTiles++
            } else {
                oreTiles++
                oreCounts.increment(drop)
            }
        }
        val minable = oreTiles + sandTiles

        occupied.clear()
        poweredTiles.clear()

        // --- 2. build the work list: each ore gets its OWN unmixed network (clog-safe).
        //        Copper and lead ("starters") come FIRST and gate everything else: until both
        //        are reaching the core (delivered by hand-mining units or by our belts — i.e.
        //        present in core memory), don't spend materials on any other ore's network. ---
        val targets: List<Item> = when {
            argTarget != null -> listOf(argTarget!!)
            minable > 0 && sandTiles.toFloat() / minable >= SAND_MAJORITY -> listOf(Items.sand)
            else -> orderedTargets(oreCounts)
        }

        // --- 3. pumps first, so every network's belts route around them ---
        planPumps(x1, y1, x2, y2, team, plans)

        // --- 4. a separate transport+mining mesh per ore, confined to that ore's bounding
        //        box. occupied accumulates, so different ores never share a belt. ---
        for (ore in targets) {
            val sand = ore == Items.sand
            runTarget = if (sand) null else ore
            val box = oreBounds(ore, sand, x1, y1, x2, y2) ?: continue
            val best = bestDrillLayout(box[0], box[1], box[2], box[3], team, sand, ore) ?: continue
            val belt = sizeBelt(best.saturated) ?: continue
            layMesh(best.drill, belt, box[0], box[1], box[2], box[3], team, plans, sand)
        }

        // --- 6. wire power to every power-consuming block (tiny poles preferred) ---
        planPower(team, plans)

        return plans
    }

    /**
     * Order the auto-picked ores: copper first, then lead, then the rest by density. Copper and
     * lead are the bootstrap pair — until BOTH have reached the core (hand-mined by units or
     * carried by our belts, tracked in [coreSeen]), the other ores are dropped entirely, so we
     * never spend materials on a titanium/etc. network before the starters are actually flowing.
     */
    private fun orderedTargets(oreCounts: ObjectIntMap<Item>): List<Item> {
        val rest = ArrayList<Item>()
        for (e in oreCounts) if (e.key != Items.copper && e.key != Items.lead) rest.add(e.key)
        rest.sortByDescending { oreCounts.get(it, 0) }   // densest of the rest first
        val ordered = ArrayList<Item>()
        if (oreCounts.containsKey(Items.copper)) ordered.add(Items.copper)
        if (oreCounts.containsKey(Items.lead)) ordered.add(Items.lead)
        val startersDelivered = delivered.contains(Items.copper) && delivered.contains(Items.lead)
        if (startersDelivered) ordered.addAll(rest)
        return ordered
    }

    /** Record a placed block's centre if it consumes power, so [planPower] can wire it. */
    private fun recordPowered(px: Int, py: Int, block: Block) {
        if (block.hasPower && block.consumesPower && !block.outputsPower) poweredTiles.add(intArrayOf(px, py))
    }

    /**
     * Wire every power-consuming block into a grid with cheap 1×1 power nodes ("tiny poles":
     * cheapest, range 6, and they always do the job). A node auto-connects to power blocks and
     * other nodes within range, so we only drop a pole where a placed consumer isn't already
     * covered. (A power source/generator is out of scope — connect to your grid; nodes will
     * link to an existing one in range automatically.)
     */
    private fun planPower(team: mindustry.game.Team, plans: Seq<BuildPlan>) {
        if (poweredTiles.isEmpty()) return
        val node = Blocks.powerNode
        if (!affordable(node)) return
        val range = (node as PowerNode).laserRange.toInt()
        val cover = maxOf(2, range - 1)             // keep poles within range of each other
        val poles = ArrayList<IntArray>()
        for (pt in poweredTiles) {
            // already covered by a pole?
            if (poles.any { Math.abs(it[0] - pt[0]) + Math.abs(it[1] - pt[1]) <= cover }) continue
            val spot = findFreeSquareNear(node, team, pt[0], pt[1], 3) ?: continue
            plans.add(BuildPlan(spot[0], spot[1], 0, node, null))
            markOccupied(spot[0], spot[1], node)
            poles.add(spot)
        }
    }

    /** Mark a block's whole footprint as claimed so delivery belts don't cross it. */
    private fun markOccupied(px: Int, py: Int, block: Block) {
        val bx = px + block.sizeOffset
        val by = py + block.sizeOffset
        for (fx in bx until bx + block.size) for (fy in by until by + block.size) {
            occupied.add(Point2.pack(fx, fy))
        }
    }

    /**
     * Lay a connected transport + mining mesh over the selection: horizontal drill bands fill
     * the field densely, each with a shared collector lane that flows toward a slim vertical
     * **trunk reserved on the edge that faces the delivery target** (so output heads toward the
     * core, never away from it). The trunk is only as wide as the throughput needs (`lanes`),
     * consolidates everything into the column nearest the core, and hands off to the final leg.
     * Builds AROUND existing structures (skips occupied/foreign tiles) and uses armored belts
     * where a foreign structure is beside the line.
     *
     * Orientation is data-driven from [deliveryHint]: the trunk hugs the west or east edge and
     * exits south or north depending on where the core actually is. Bridging over a *foreign*
     * obstacle mid-trunk is still `[pending]`.
     */
    private fun layMesh(drill: Drill, spec: BeltSpec, x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team, plans: Seq<BuildPlan>, sandMode: Boolean) {
        val s = drill.size
        val belt = spec.laneBelt                     // plain belt for collector lanes + final leg
        val boxW = x2 - x1 + 1
        // Trunk width: enough lanes for the rate, but always leave room for at least one drill column.
        val w = maxOf(1, minOf(spec.lanes, minOf(6, maxOf(1, boxW - s))))

        // Which edge faces delivery? Reserve the trunk there and aim collectors/exit toward it.
        val dest = deliveryHint(x1, y1, x2, y2, team)
        val left = dest[0] <= (x1 + x2) / 2          // trunk on the west edge?
        val bottom = dest[1] <= (y1 + y2) / 2        // exit toward the south?

        val coreCol = if (left) x1 else x2           // trunk column nearest the core
        val colStep = if (left) 1 else -1            // step from the core column into the field
        fun trunkCol(i: Int) = coreCol + colStep * i // i in 0 until w; 0 == nearest the core
        val drillLo = if (left) x1 + w else x1       // drill region excludes the trunk columns
        val drillHi = if (left) x2 else x2 - w
        val toTrunk = if (left) 2 else 0             // collector/gather flow toward the trunk (W/E)
        val exitRot = if (bottom) 3 else 1           // trunk flow toward the exit row (S/N)
        val exitY = if (bottom) y1 else y2
        // a trunk column is "farther" than another when it sits deeper into the field (off the core)
        fun farther(a: Int, b: Int) = if (left) a > b else a < b

        // 1. drill bands (bottom→top), each with a collector lane on its north edge shared with
        //    the band above (s drill rows + 1 lane row), round-robin-assigned to a trunk column.
        //    Record, per lane, which trunk column it feeds and how far from the trunk its
        //    farthest drill reaches — a band with no drills gets no lane (parsimony).
        val laneTargetCol = HashMap<Int, Int>()
        val laneFarEnd = HashMap<Int, Int>()        // farthest drilled column from the trunk, per lane row
        var by = y1; var band = 0
        while (by + s <= y2) {
            val ly = by + s
            var bx = drillLo
            while (bx + s - 1 <= drillHi) {
                if (footprintTarget(drill, bx, by, s, sandMode) > 0) {
                    val px = bx - drill.sizeOffset; val py = by - drill.sizeOffset
                    if (Build.validPlace(drill, team, px, py, 0)) {
                        plans.add(BuildPlan(px, py, 0, drill, null)); markOccupied(px, py, drill); recordPowered(px, py, drill)
                        val far = if (left) bx + s - 1 else bx          // the drill edge farthest from the trunk
                        laneFarEnd[ly] = laneFarEnd[ly]?.let { if (left) maxOf(it, far) else minOf(it, far) } ?: far
                    }
                }
                bx += s
            }
            if (laneFarEnd.containsKey(ly)) laneTargetCol[ly] = trunkCol(band % w)
            by += s + 1; band++
        }

        // 2. collector lanes carry each band's output to the trunk — only from the trunk edge out
        //    to the band's farthest drill, never past it (belts beyond the last drill carry nothing).
        for ((ly, far) in laneFarEnd) {
            if (left) { var lx = far; while (lx >= drillLo) { placeBelt(lx, ly, toTrunk, belt, team, plans); lx-- } }
            else      { var lx = far; while (lx <= drillHi) { placeBelt(lx, ly, toTrunk, belt, team, plans); lx++ } }
        }

        // 3. trunk: w vertical columns flowing to the exit row. A lane crosses the columns
        //    deeper than its target (junction — no mixing) then turns to the exit at its target;
        //    the exit row gathers all columns into the core-nearest column, which feeds delivery.
        //    Each column runs only from the exit up to the farthest band it must carry — a lane
        //    feeds (or crosses) column i only when i is at/deeper than the lane's target — so
        //    shallow columns stop short instead of running dead to the top (parsimony).
        fun distFromExit(y: Int) = if (bottom) y - y1 else y2 - y
        fun colIndex(c: Int) = colStep * (c - coreCol)
        val topDist = IntArray(w)
        for ((ly, tgtCol) in laneTargetCol) {
            val d = distFromExit(ly)
            for (i in colIndex(tgtCol) until w) if (d > topDist[i]) topDist[i] = d
        }
        // The exit row stays plain belt (gather + hand-off to the final leg, unchanged). The
        // vertical carry above it is the trunk medium: a chained item bridge when [trunkIsBridge]
        // (one column carries ~11/s, far narrower than parallel basic lanes), else plain belt.
        // Bridges are linked exit-first so each upstream bridge points at the next bridge toward
        // the exit, hopping over the junction crossings (range 4 covers the gaps).
        val step = if (bottom) 1 else -1
        for (i in 0 until w) {
            val cx = trunkCol(i)
            var lastBridge = -1                       // last (downstream) bridge placed in this column
            var ty = exitY
            while (ty in y1..y2 && distFromExit(ty) <= topDist[i]) {
                val tgt = laneTargetCol[ty]
                when {
                    ty == exitY && i != 0 -> placeBelt(cx, exitY, toTrunk, belt, team, plans)             // gather to core col
                    ty == exitY -> placeBelt(cx, exitY, exitRot, belt, team, plans)                       // core col turns to the final leg
                    tgt != null && farther(cx, tgt) -> placeBlock(cx, ty, 0, Blocks.junction, team, plans) // lane crosses (bridge hops over)
                    spec.trunkIsBridge -> if (placeBridge(cx, ty, lastBridge, team, plans)) lastBridge = Point2.pack(cx, ty)
                    else -> placeBelt(cx, ty, exitRot, belt, team, plans)                                 // flow to exit
                }
                ty += step
            }
        }

        // 4. final leg — belts are PREFERRED (per spec). Route to an in-selection core by belt.
        // Launching is only viable once thorium is being mined (the late-game signal that the
        // mass-driver economy is up); even then, belts win when the core is in the selection.
        // Otherwise drop a belt stub out of the area toward the nearest core.
        val core = findCoreInSelection(x1, y1, x2, y2, team)
        when {
            core != null ->
                runBelt(x1, y1, x2, y2, team, plans, belt, core.tileX(), core.tileY(), reachOut = false)
            coreSeen.contains(Items.thorium) && launchToCore(coreCol, exitY, team, plans) -> { /* launched */ }
            else -> {
                val stub = stubTarget(x1, y1, x2, y2)
                runBelt(x1, y1, x2, y2, team, plans, belt, stub[0], stub[1], reachOut = true)
            }
        }
    }

    /** Where this network's output should head: an in-selection core, else the closest core, else field centre. */
    private fun deliveryHint(x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team): IntArray {
        findCoreInSelection(x1, y1, x2, y2, team)?.let { return intArrayOf(it.tileX(), it.tileY()) }
        val cx = ((x1 + x2) / 2) * tilesize.toFloat(); val cy = ((y1 + y2) / 2) * tilesize.toFloat()
        state?.teams?.closestCore(cx, cy, team)?.let { return intArrayOf(it.tileX(), it.tileY()) }
        return intArrayOf((x1 + x2) / 2, (y1 + y2) / 2)
    }

    /**
     * Launcher delivery: feed the trunk into a sender mass driver and launch to a receiver
     * mass driver beside the closest friendly core within range. Launching skips every tile
     * between, so there is zero en-route contamination and it crosses terrain a belt can't.
     * @return true if a sender+receiver pair was placed.
     *
     * v1: single sender/receiver pair (no relays for out-of-range cores, no scaled receiver
     * bank yet). NOTE: mass drivers need power — the tool does not place a power grid, so the
     * player must connect power for the launch to run.
     */
    private fun launchToCore(exitX: Int, exitY: Int, team: mindustry.game.Team, plans: Seq<BuildPlan>): Boolean {
        val md = Blocks.massDriver as MassDriver
        if (!affordable(md) || occupied.isEmpty) return false
        val st = state ?: return false

        val core = st.teams.closestCore(exitX * tilesize.toFloat(), exitY * tilesize.toFloat(), team) ?: return false

        // sender: closest placeable 3x3 to the trunk exit, so the trunk's belt feeds it
        val snd = findFreeSquareNear(md, team, exitX, exitY, 6) ?: return false
        // receiver: closest placeable 3x3 beside the core, within launch range of the sender
        val rec = findFreeSquareNear(md, team, core.tileX(), core.tileY(), 5) ?: return false
        if (Mathf.dst(snd[0] * tilesize.toFloat(), snd[1] * tilesize.toFloat(), rec[0] * tilesize.toFloat(), rec[1] * tilesize.toFloat()) > md.range) return false

        plans.add(BuildPlan(snd[0], snd[1], 0, md, Point2.pack(rec[0], rec[1])))   // sender links → receiver
        markOccupied(snd[0], snd[1], md); recordPowered(snd[0], snd[1], md)
        plans.add(BuildPlan(rec[0], rec[1], 0, md, null))                          // receiver: no link (one-way)
        markOccupied(rec[0], rec[1], md); recordPowered(rec[0], rec[1], md)
        return true
    }

    /** Closest placeable [block] footprint within [r] tiles of (cx,cy), origin nearest (cx,cy). */
    private fun findFreeSquareNear(block: Block, team: mindustry.game.Team, cx: Int, cy: Int, r: Int): IntArray? {
        var best: IntArray? = null; var bestD = Int.MAX_VALUE
        for (px in cx - r..cx + r) for (py in cy - r..cy + r) {
            if (!Build.validPlace(block, team, px, py, 0)) continue
            val d = Math.abs(px - cx) + Math.abs(py - cy)
            if (d < bestD) { bestD = d; best = intArrayOf(px, py) }
        }
        return best
    }

    /** Place one belt tile (armored if a foreign structure is beside it), building around existing. */
    private fun placeBelt(x: Int, y: Int, rot: Int, belt: Block, team: mindustry.game.Team, plans: Seq<BuildPlan>): Boolean {
        if (occupied.contains(Point2.pack(x, y))) return false
        val t = world.tile(x, y) ?: return false
        if (t.build != null) return false            // build around existing structures
        val place = Interference.safeConveyor(belt, Interference.sideContaminated(x, y, rot, true, occupied), ::affordable)
        if (!Build.validPlace(place, team, x, y, rot)) return false
        plans.add(BuildPlan(x, y, rot, place, null)); markOccupied(x, y, place)
        return true
    }

    /**
     * Place one item bridge in a trunk column, linked to [linkPos] (the next bridge toward the
     * exit, packed) so the chain carries the consolidated flow downward — and accepts collector
     * input from the side at each tile. A relative [Point2] config sets the bridge link; the
     * downstream-most bridge ([linkPos] < 0, or out of range) gets no link and simply dumps into
     * the plain belt below it. Builds around existing structures.
     */
    private fun placeBridge(x: Int, y: Int, linkPos: Int, team: mindustry.game.Team, plans: Seq<BuildPlan>): Boolean {
        val bridge = Blocks.itemBridge
        if (occupied.contains(Point2.pack(x, y))) return false
        val t = world.tile(x, y) ?: return false
        if (t.build != null || !Build.validPlace(bridge, team, x, y, 0)) return false
        val range = (bridge as BufferedItemBridge).range
        val cfg = if (linkPos >= 0 && Math.abs(Point2.x(linkPos) - x) + Math.abs(Point2.y(linkPos) - y) <= range)
            Point2(Point2.x(linkPos) - x, Point2.y(linkPos) - y) else null
        plans.add(BuildPlan(x, y, 0, bridge, cfg)); markOccupied(x, y, bridge)
        return true
    }

    /** Place a generic 1×1 block (e.g. a junction), building around existing; false if it can't. */
    private fun placeBlock(x: Int, y: Int, rot: Int, block: Block, team: mindustry.game.Team, plans: Seq<BuildPlan>): Boolean {
        if (!affordable(block) || occupied.contains(Point2.pack(x, y))) return false
        val t = world.tile(x, y) ?: return false
        if (t.build != null || !Build.validPlace(block, team, x, y, rot)) return false
        plans.add(BuildPlan(x, y, rot, block, null)); markOccupied(x, y, block)
        return true
    }

    /** Bounding box of tiles whose drop is the target (sand, or a specific ore); null if none. */
    private fun oreBounds(ore: Item, sand: Boolean, x1: Int, y1: Int, x2: Int, y2: Int): IntArray? {
        var minx = Int.MAX_VALUE; var miny = Int.MAX_VALUE; var maxx = Int.MIN_VALUE; var maxy = Int.MIN_VALUE
        for (tx in x1..x2) for (ty in y1..y2) {
            val d = world.tile(tx, ty)?.drop() ?: continue
            if (if (sand) d == Items.sand else d == ore) {
                if (tx < minx) minx = tx; if (tx > maxx) maxx = tx
                if (ty < miny) miny = ty; if (ty > maxy) maxy = ty
            }
        }
        return if (maxx < minx) null else intArrayOf(minx, miny, maxx, maxy)
    }

    private class Layout(
        val drill: Drill,
        val placements: List<IntArray>,
        /** Modeled rate (boost only where water actually borders a drill) — used to rank drills. */
        val throughput: Float,
        /** Worst-case rate with EVERY drill fully water-boosted — used to size delivery belts. */
        val saturated: Float,
    )

    /** Evaluate every affordable, in-cap drill and return the highest-throughput layout. */
    private fun bestDrillLayout(
        x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team, sandMode: Boolean, dominant: Item,
    ): Layout? {
        // Copper/lead have only two sensible mining regimes: super-early (basic mechanical
        // drill, pre-Mono) and late-game volume (4x4 blast drill + plastanium belts). Skip
        // the mid-tier drills entirely unless BOTH the volume minimums are available.
        val volumeReady = affordable(Blocks.blastDrill) && affordable(Blocks.plastaniumConveyor)
        val basicOnly = (dominant == Items.copper || dominant == Items.lead) && !volumeReady

        var best: Layout? = null
        for (drill in allDrills()) {
            if (drill.tier > capTier) continue          // ceiling: explicit / build cap
            if (basicOnly && drill !== Blocks.mechanicalDrill) continue
            if (!affordable(drill)) continue
            val layout = tileWith(drill, x1, y1, x2, y2, team, sandMode, dominant) ?: continue
            // Prefer higher throughput; on a tie prefer the cheaper (lower-tier) drill —
            // honouring "use at least the worst effective drill", never over-teching for free.
            if (best == null || layout.throughput > best.throughput ||
                (layout.throughput == best.throughput && drill.tier < best.drill.tier)
            ) {
                best = layout
            }
        }
        return best
    }

    /**
     * Tile the rectangle with [drill]; null if it captures nothing.
     *
     * Throughput models the water boost: a footprint counts at `liquidBoostIntensity`
     * when a water tile inside the selection borders it, because [planPumps] will place
     * a pump there and a pump dumps liquid straight into the adjacent drill (no conduit
     * needed). Boost is only credited for in-selection water, keeping the model and the
     * pumps we actually emit in sync.
     */
    private fun tileWith(
        drill: Drill, x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team, sandMode: Boolean, dominant: Item,
    ): Layout? {
        val size = drill.size
        val placements = ArrayList<IntArray>()
        val boostMul = waterBoost(drill)
        val rate = 60f / drill.getDrillTime(dominant)
        var throughput = 0f
        var capturedTotal = 0
        var by = y1
        while (by + size - 1 <= y2) {
            var bx = x1
            while (bx + size - 1 <= x2) {
                val score = footprintTarget(drill, bx, by, size, sandMode)
                if (score > 0) {
                    val px = bx - drill.sizeOffset
                    val py = by - drill.sizeOffset
                    // validPlace also rejects tiles occupied by existing drills/blocks,
                    // so the layout naturally builds AROUND them without removing them.
                    if (Build.validPlace(drill, team, px, py, 0)) {
                        placements.add(intArrayOf(px, py))
                        capturedTotal += score
                        val boosted = boostMul > 1f && footprintWaterAdjacent(bx, by, size, x1, y1, x2, y2)
                        throughput += score * rate * (if (boosted) boostMul else 1f)
                    }
                }
                bx += size
            }
            by += size
        }
        if (placements.isEmpty()) return null
        // saturated = every drill fully boosted (worst case the belts must carry).
        val saturated = capturedTotal * rate * boostMul
        return Layout(drill, placements, throughput, saturated)
    }

    /** [Drill.liquidBoostIntensity] if this drill is boosted by *water* specifically, else 1f. */
    private fun waterBoost(drill: Drill): Float {
        if (drill.liquidBoostIntensity <= 1f) return 1f
        for (c in drill.optionalConsumers) {
            if (c is ConsumeLiquid && c.booster && c.liquid == Liquids.water) return drill.liquidBoostIntensity
        }
        return 1f   // e.g. impact/eruption drills boost on ozone, not water
    }

    /** True if a pumpable water tile inside the selection borders the SxS footprint at (bx,by). */
    private fun footprintWaterAdjacent(bx: Int, by: Int, size: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        for (fy in by until by + size) {
            if (isPumpWater(bx - 1, fy, x1, y1, x2, y2) || isPumpWater(bx + size, fy, x1, y1, x2, y2)) return true
        }
        for (fx in bx until bx + size) {
            if (isPumpWater(fx, by - 1, x1, y1, x2, y2) || isPumpWater(fx, by + size, x1, y1, x2, y2)) return true
        }
        return false
    }

    private fun isPumpWater(tx: Int, ty: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (tx < x1 || ty < y1 || tx > x2 || ty > y2) return false   // only count water we'll actually pump
        val t = world.tile(tx, ty) ?: return false
        return t.floor().liquidDrop == Liquids.water && t.block() == Blocks.air
    }

    /**
     * Number of target tiles a [drill] footprint at (bx,by) would actually mine.
     * In sand mode, any mineable ore under the footprint would be mined *instead* of
     * sand (ore is higher priority), so such a footprint scores 0.
     */
    private fun footprintTarget(drill: Drill, bx: Int, by: Int, size: Int, sandMode: Boolean): Int {
        var target = 0
        for (fx in bx until bx + size) {
            for (fy in by until by + size) {
                val t = world.tile(fx, fy) ?: return 0    // off-map -> can't place here
                val drop = t.drop() ?: continue
                val mineable = drill.canMine(t)
                if (sandMode) {
                    if (drop != Items.sand && mineable) return 0   // would mine ore, not sand
                    if (drop == Items.sand && mineable) target++
                } else if (drop != Items.sand && mineable) {
                    if (runTarget == null || drop == runTarget) target++
                    else return 0   // explicit element: a different ore here would be mined too → reject
                }
            }
        }
        return target
    }

    /**
     * Place pumps over liquid tiles. Heavy preference for energy-free, 1x1 pumps
     * (power-free first, then smallest) — so a power-less mechanical pump wins, and
     * powered pumps are only a last resort. These pumps also boost adjacent drills.
     */
    private fun planPumps(x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team, plans: Seq<BuildPlan>) {
        // Any liquid present?
        var hasLiquid = false
        forEachTile(x1, y1, x2, y2) { t -> if (t.floor().liquidDrop != null) hasLiquid = true }
        if (!hasLiquid) return

        val pump = allPumps().filter { affordable(it) }
            .minWithOrNull(compareBy({ it.hasPower }, { it.size }))   // power-free first, then 1x1
            ?: return   // can't afford any pump

        val size = pump.size
        var by = y1
        while (by + size - 1 <= y2) {
            var bx = x1
            while (bx + size - 1 <= x2) {
                val px = bx - pump.sizeOffset
                val py = by - pump.sizeOffset
                // Pump.canPlaceOn (via validPlace) enforces a uniform liquid under the footprint.
                // Fluid-interference guard: skip a pump that would border a foreign liquid
                // network, so we don't leak our water into it (or take its liquid into our feed).
                if (Build.validPlace(pump, team, px, py, 0) &&
                    !Interference.adjacentForeignSource(px, py, false, occupied)
                ) {
                    plans.add(BuildPlan(px, py, 0, pump, null))
                    markOccupied(px, py, pump); recordPowered(px, py, pump)
                }
                bx += size
            }
            by += size
        }
    }

    // ------------------------------------------------------------------ delivery

    /** Orthogonal steps and the conveyor rotation that flows that way (0=E,1=N,2=W,3=S). */
    private val dirs = arrayOf(intArrayOf(1, 0, 0), intArrayOf(0, 1, 1), intArrayOf(-1, 0, 2), intArrayOf(0, -1, 3))

    /**
     * A transport spec for one network. [laneBelt] is the plain belt used for collector lanes and
     * the final leg (the fastest affordable tier). [trunkBelt]/[trunkIsBridge] is the medium used
     * for the vertical trunk columns: normally the same plain belt, but a **chained item bridge**
     * when we're stuck on the basic conveyor and the rate needs more than one basic belt — a bridge
     * carries ~11/s in a single column (vs ~3 parallel basic lanes), keeping the trunk narrow near
     * the core. [lanes] is the trunk column count sized to the chosen trunk medium's capacity.
     */
    private class BeltSpec(val laneBelt: Block, val trunkBelt: Block, val trunkIsBridge: Boolean, val lanes: Int)

    /**
     * Belt tiers and their carrying capacity in items/sec, ascending (plastanium stacks → high).
     * A function, NOT a val: referencing `Blocks.*` at call time avoids capturing nulls if this
     * object initializes before content is loaded (which crashed `sizeBelt` in-game).
     */
    private fun beltTiers() = listOf(
        Blocks.conveyor to 4.2f,
        Blocks.titaniumConveyor to 11f,
        Blocks.plastaniumConveyor to 45f,
    )

    /**
     * Size delivery belts to carry [throughput] — the SATURATED rate (every drill fully
     * water-boosted, the worst case) — using **plain** belts: take the fastest affordable
     * tier, then enough parallel **lanes** that tier×lanes covers the rate. Sizing for the
     * boosted output up front means the route never needs accelerating.
     */
    private fun sizeBelt(throughput: Float): BeltSpec? {
        val tiers = beltTiers()
        val affordableTiers = tiers.filter { affordable(it.first) }
        if (affordableTiers.isEmpty()) return null
        val (laneBelt, laneCap) = affordableTiers.last()         // fastest affordable plain belt
        val basicCap = tiers.first().second                      // one basic conveyor's capacity

        // Pick the trunk medium to minimise lane count (be courteous with space near cores).
        // When the fastest plain belt we can afford is still just the basic conveyor and the
        // saturated rate exceeds a single basic belt, a chained item bridge (copper+lead,
        // power-free, ~11/s) carries the flow in ONE column instead of several parallel basic
        // lanes. Otherwise the trunk is the same plain belt as the lanes.
        val useBridge = laneCap <= basicCap && throughput > basicCap && affordable(Blocks.itemBridge)
        val trunkBelt = if (useBridge) Blocks.itemBridge else laneBelt
        val trunkCap = if (useBridge) (Blocks.itemBridge as BufferedItemBridge).displayedSpeed else laneCap
        val lanes = maxOf(1, Math.ceil(throughput / trunkCap.toDouble()).toInt())
        return BeltSpec(laneBelt, trunkBelt, useBridge, lanes)
    }

    private fun findCoreInSelection(x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team): CoreBlock.CoreBuild? {
        for (tx in x1..x2) for (ty in y1..y2) {
            val b = world.tile(tx, ty)?.build ?: continue
            if (b is CoreBlock.CoreBuild && b.team == team) return b
        }
        return null
    }

    /** A point just outside the selection on the side the drill field is closest to. */
    private fun stubTarget(x1: Int, y1: Int, x2: Int, y2: Int): IntArray {
        // field centre from the occupied tiles
        var sx = 0L; var sy = 0L; var c = 0
        val it = occupied.iterator()
        while (it.hasNext) { val p = it.next(); sx += Point2.x(p); sy += Point2.y(p); c++ }
        val fcx = if (c > 0) (sx / c).toInt() else (x1 + x2) / 2
        val fcy = if (c > 0) (sy / c).toInt() else (y1 + y2) / 2
        // nearest edge → head outward through it, a few tiles past the boundary
        val dl = fcx - x1; val dr = x2 - fcx; val db = fcy - y1; val dt = y2 - fcy
        val m = minOf(dl, dr, db, dt)
        return when (m) {
            dl -> intArrayOf(x1 - 3, fcy)
            dr -> intArrayOf(x2 + 3, fcy)
            db -> intArrayOf(fcx, y1 - 3)
            else -> intArrayOf(fcx, y2 + 3)
        }
    }

    /**
     * Greedily lay a belt line from a field-edge output cell toward (tx,ty), axis-stepping,
     * stopping at our field, any existing building, or an unplaceable tile.
     * @param reachOut allow routing a few tiles past the selection boundary (for stubs).
     * @return number of belts placed.
     */
    private fun runBelt(
        x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team, plans: Seq<BuildPlan>,
        belt: Block, tx: Int, ty: Int, reachOut: Boolean,
    ): Int {
        val start = outputCell(tx, ty) ?: return 0
        var cx = start[0]; var cy = start[1]
        val loX = if (reachOut) x1 - 4 else x1; val hiX = if (reachOut) x2 + 4 else x2
        val loY = if (reachOut) y1 - 4 else y1; val hiY = if (reachOut) y2 + 4 else y2
        var placed = 0
        var guard = 0
        while (guard++ < 256) {
            if (cx < loX || cx > hiX || cy < loY || cy > hiY) break
            val t = world.tile(cx, cy) ?: break
            if (occupied.contains(Point2.pack(cx, cy))) break        // ran back into the field
            if (t.build != null) break                               // don't interfere with existing builds
            val rot = axisRotToward(cx, cy, tx, ty)
            // Use a side-load-proof (armored) belt where a foreign structure sits beside
            // our line, so neighbours can't contaminate this stream.
            val place = Interference.safeConveyor(
                belt, Interference.sideContaminated(cx, cy, rot, true, occupied), ::affordable,
            )
            if (!Build.validPlace(place, team, cx, cy, rot)) break
            plans.add(BuildPlan(cx, cy, rot, place, null))
            occupied.add(Point2.pack(cx, cy))                        // claim, so we can't loop onto it
            placed++
            if (cx == tx && cy == ty) break
            when (rot) { 0 -> cx++; 1 -> cy++; 2 -> cx--; else -> cy-- }
        }
        if (placed > 0) lastDestPos = Point2.pack(cx, cy)            // remember where it ended
        return placed
    }

    /** A free tile bordering the drill field, on the side nearest (tx,ty). */
    private fun outputCell(tx: Int, ty: Int): IntArray? {
        var best: IntArray? = null
        var bestDst = Long.MAX_VALUE
        val it = occupied.iterator()
        while (it.hasNext) {
            val p = it.next()
            val ox = Point2.x(p).toInt(); val oy = Point2.y(p).toInt()
            for (d in dirs) {
                val nx = ox + d[0]; val ny = oy + d[1]
                if (occupied.contains(Point2.pack(nx, ny))) continue
                val t = world.tile(nx, ny) ?: continue
                if (t.build != null) continue
                val dst = (nx - tx).toLong() * (nx - tx) + (ny - ty).toLong() * (ny - ty)
                if (dst < bestDst) { bestDst = dst; best = intArrayOf(nx, ny) }
            }
        }
        return best
    }

    private fun axisRotToward(sx: Int, sy: Int, tx: Int, ty: Int): Int {
        val dx = tx - sx; val dy = ty - sy
        return if (Math.abs(dx) >= Math.abs(dy)) (if (dx >= 0) 0 else 2) else (if (dy >= 0) 1 else 3)
    }

    // ------------------------------------------------------------------ helpers

    private inline fun forEachTile(x1: Int, y1: Int, x2: Int, y2: Int, body: (mindustry.world.Tile) -> Unit) {
        for (tx in x1..x2) for (ty in y1..y2) {
            val t = world.tile(tx, ty) ?: continue
            body(t)
        }
    }

    /** All floor-ore drills (excludes wall-mining beam drills, which aren't [Drill]s). */
    private fun allDrills(): List<Drill> {
        val out = ArrayList<Drill>()
        for (b in content.blocks()) if (b is Drill) out.add(b)
        return out
    }

    private fun allPumps(): List<Pump> {
        val out = ArrayList<Pump>()
        for (b in content.blocks()) if (b is Pump) out.add(b)
        return out
    }

    /** A block is buildable if every material in its cost has been in the core at least once. */
    private fun affordable(b: Block): Boolean {
        if (b.requirements.isEmpty()) return true
        for (stack in b.requirements) if (!coreSeen.contains(stack.item)) return false
        return true
    }

    /** Cheap case-insensitive name match for the [maxtech] argument. */
    private fun biasedNameDistance(arg: String, b: Block): Int {
        val a = arg.lowercase()
        val name = b.localizedName.lowercase()
        val internal = b.name.lowercase()
        return when {
            name == a || internal == a -> 0
            name.contains(a) || internal.contains(a) -> 1
            else -> 1_000_001
        }
    }
}

/**
 * Auto-place processing/factory chains near the core.
 * TODO: pick core-adjacent free tiles and emit BuildPlans for the needed
 * smelters/factories + connecting conveyors.
 */
object ProcessingPlanner : AutoModule {
    override val name = "processing"
    override fun update() { /* scaffold: no-op */ }
}

/**
 * Auto-layout turrets + supply against threat directions.
 * TODO: read spawn/threat directions (Vars.spawner) and emit turret + ammo-feed
 * BuildPlans along the threatened perimeter.
 */
object DefensePlanner : AutoModule {
    override val name = "defense"
    override fun update() { /* scaffold: no-op */ }
}
