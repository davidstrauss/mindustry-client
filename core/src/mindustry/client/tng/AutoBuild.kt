package mindustry.client.tng

import arc.Core
import arc.util.Log
import mindustry.Vars.player
import mindustry.Vars.state
import mindustry.client.ClientVars.clientCommandHandler
import mindustry.gen.Player

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
        if (!enabled) return
        if (state?.isGame != true) return
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
    }
}

/** One automation concern (mining, processing, defense). */
interface AutoModule {
    val name: String

    /** Invoked every frame while [AutoBuild.enabled]. Should be cheap / rate-limited internally. */
    fun update()
}

/**
 * Assign/queue miners to optimal ore tiles by distance & throughput.
 * TODO: rank ore tiles (Vars.indexer / world ore tiles) by core distance and
 * demand, then drive mining via MinePath or emit drill BuildPlans.
 */
object MiningPlanner : AutoModule {
    override val name = "mining"
    override fun update() { /* scaffold: no-op */ }
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
