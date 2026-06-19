package mindustry.client.tng

import arc.Core
import arc.struct.ObjectIntMap
import arc.struct.ObjectSet
import arc.util.Log
import mindustry.Vars.content
import mindustry.Vars.maxSchematicSize
import mindustry.Vars.player
import mindustry.Vars.state
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
import mindustry.world.blocks.production.Drill
import mindustry.world.blocks.production.Pump
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

        clientCommandHandler.register(
            "mine",
            "[maxtech]",
            "Arm the TNG mining tool, then drag-select an area to fill with optimal drills/pumps. " +
                "[maxtech] caps the best drill (name like 'laser' or a tier number); default is the best you can build."
        ) { args, player: Player ->
            player.sendMessage(MiningPlanner.arm(args.getOrNull(0)))
        }
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
    private const val SAND_MAJORITY = 0.85f

    /** Every item ever observed in the local player's core — basis for the build cap. */
    private val coreSeen = ObjectSet<Item>()

    /** Armed by `!mine`; consumed by the next drag-selection. */
    private var armed = false
    /** Upper tech bound (max [Drill.tier] allowed). [Int.MAX_VALUE] = best buildable. */
    private var capTier = Int.MAX_VALUE
    private var capLabel = "best buildable"

    /** On-demand tool — nothing to do per frame. */
    override fun update() { /* no-op: driven by the !mine command + drag-select */ }

    /** Record items currently in the local player's core. Called every frame (even while OFF). */
    fun trackCoreMaterials() {
        val core = player?.core() ?: return
        core.items.each { item, amount -> if (amount > 0) coreSeen.add(item) }
    }

    /** Arm the tool; [arg] optionally caps the best drill (name substring or tier number). */
    fun arm(arg: String?): String {
        capTier = Int.MAX_VALUE
        capLabel = "best buildable"
        if (arg != null && arg.isNotBlank()) {
            val tier = arg.toIntOrNull()
            if (tier != null) {
                capTier = tier
                capLabel = "tier <= $tier"
            } else {
                val match = allDrills().minByOrNull { biasedNameDistance(arg, it) }
                if (match == null || biasedNameDistance(arg, match) > 1_000_000) {
                    return "[scarlet][TNG][] unknown drill '$arg' — try a name like 'laser'/'blast' or a tier number."
                }
                capTier = match.tier
                capLabel = "<= ${match.localizedName}"
            }
        }
        armed = true
        return "[accent][TNG][] mining tool armed (cap: $capLabel). [lightgray]Drag-select the mining area.[]"
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
            val count = planArea(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
            player?.sendMessage(
                if (count > 0) "[accent][TNG][] queued [stat]$count[] mining structures."
                else "[accent][TNG][] nothing minable in selection."
            )
        } catch (e: Exception) {
            Log.err("[TNG] mining tool failed", e)
            player?.sendMessage("[scarlet][TNG][] mining error: ${e.message}")
        }
        return true
    }

    // ------------------------------------------------------------------ planning

    private fun planArea(rx1: Int, ry1: Int, rx2: Int, ry2: Int): Int {
        val plr = player ?: return 0
        val unit = plr.unit() ?: return 0
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

        // --- 2. sand-avoidance: only mine sand if it overwhelmingly dominates ---
        val sandMode = minable > 0 && sandTiles.toFloat() / minable >= SAND_MAJORITY
        val dominant: Item? = when {
            sandMode -> Items.sand
            oreTiles > 0 -> {
                var best: Item? = null
                var bestN = -1
                for (e in oreCounts) if (e.value > bestN) { bestN = e.value; best = e.key }
                best
            }
            else -> null
        }

        var placed = 0

        // --- 3. drills for the target (ore or sand) ---
        if (dominant != null) {
            val best = bestDrillLayout(x1, y1, x2, y2, team, sandMode, dominant)
            if (best != null) {
                for (p in best.placements) {
                    unit.addBuild(BuildPlan(p[0], p[1], 0, best.drill, null))
                    placed++
                }
            }
        }

        // --- 4. pumps for liquids (prefer 1x1) ---
        placed += planPumps(x1, y1, x2, y2, team, unit)

        return placed
    }

    private class Layout(val drill: Drill, val placements: List<IntArray>, val throughput: Float)

    /** Evaluate every affordable, in-cap drill and return the highest-throughput layout. */
    private fun bestDrillLayout(
        x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team, sandMode: Boolean, dominant: Item,
    ): Layout? {
        var best: Layout? = null
        for (drill in allDrills()) {
            if (drill.tier > capTier) continue          // ceiling: explicit / build cap
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
                        val boosted = boostMul > 1f && footprintWaterAdjacent(bx, by, size, x1, y1, x2, y2)
                        throughput += score * rate * (if (boosted) boostMul else 1f)
                    }
                }
                bx += size
            }
            by += size
        }
        if (placements.isEmpty()) return null
        return Layout(drill, placements, throughput)
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
                    target++
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
    private fun planPumps(x1: Int, y1: Int, x2: Int, y2: Int, team: mindustry.game.Team, unit: mindustry.gen.Unit): Int {
        // Any liquid present?
        var hasLiquid = false
        forEachTile(x1, y1, x2, y2) { t -> if (t.floor().liquidDrop != null) hasLiquid = true }
        if (!hasLiquid) return 0

        val pump = allPumps().filter { affordable(it) }
            .minWithOrNull(compareBy({ it.hasPower }, { it.size }))   // power-free first, then 1x1
            ?: return 0   // can't afford any pump

        var placed = 0
        val size = pump.size
        var by = y1
        while (by + size - 1 <= y2) {
            var bx = x1
            while (bx + size - 1 <= x2) {
                val px = bx - pump.sizeOffset
                val py = by - pump.sizeOffset
                // Pump.canPlaceOn (via validPlace) enforces a uniform liquid under the footprint.
                if (Build.validPlace(pump, team, px, py, 0)) {
                    unit.addBuild(BuildPlan(px, py, 0, pump, null))
                    placed++
                }
                bx += size
            }
            by += size
        }
        return placed
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
