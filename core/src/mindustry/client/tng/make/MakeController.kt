package mindustry.client.tng.make

import arc.Core
import arc.util.Log
import arc.util.Threads
import mindustry.Vars
import mindustry.client.tng.gen.GenRequest
import mindustry.client.tng.gen.GenResult
import mindustry.client.tng.gen.IntRect
import mindustry.client.tng.gen.PowerMode
import mindustry.client.tng.gen.SchematicGenerator
import mindustry.client.tng.gen.StubGenerator
import mindustry.gen.Player
import kotlin.math.abs

/**
 * Drives the `!make` command: `!make <target> [rate] [flags]` arms an [State.awaitingArea] mode; the
 * next `schematic_select` (F) drag is consumed as the build area (see DesktopInput's schematic-select
 * release path), a schematic is generated off the render thread, and the result is handed to the
 * vanilla paste buffer — from which vanilla owns preview/move/rotate/confirm/cancel.
 *
 * Invariants honoured here (see RECON.md / the implementation guide):
 *  - The generator is [SchematicGenerator]-pure; we only *call* it, off-thread, and marshal the result
 *    back with [Core.app].post.
 *  - No live simulation: any predicted load surfaced later is the model's estimate, not a measurement.
 *  - The client cannot force-build; handing a schematic to the paste buffer produces plans the server
 *    still validates.
 *
 * Desktop is the v1 path. Mobile has no `schematic_select` drag wired here (out of scope for v1).
 */
object MakeController {
    enum class State { idle, awaitingArea, generating }

    /** Minimum area extent, in tiles. FINISHME(Task E/F): derive per-target from the content dump. */
    private const val MIN_SIZE = 2

    /**
     * Small hand-maintained alias table. FINISHME(Phase 0): replace with the canonical content-dump
     * alias table so every alias traces to the dump rather than living here.
     */
    private val ALIASES = mapOf(
        "phase" to "phase-fabric",
        "surge" to "surge-alloy",
        "metaglass" to "metaglass",
    )

    private var state = State.idle
    private var pending: GenRequest? = null
    private val generator: SchematicGenerator = StubGenerator()

    /** @return the current state's name; for tests/telemetry. */
    fun stateName(): String = state.name

    // ---- Task A: command entry -------------------------------------------------------------------

    /** `!make` handler. Never networked (client command); all output is local. */
    fun handleMake(args: Array<String>, player: Player) {
        val first = args.getOrNull(0)?.lowercase()
        if (first == null || first == "help") { printHelp(player); return }
        if (first == "cancel") {
            val was = state
            reset()
            player.sendMessage(if (was == State.idle) "[lightgray][TNG][] nothing to cancel." else "[accent][TNG][] make cancelled.")
            return
        }

        val canonical = resolveTarget(args[0])
        if (canonical == null) {
            // Do NOT transition on an unknown target.
            player.sendMessage("[scarlet][TNG][] unknown target '${args[0]}'. Try an item id (e.g. [accent]silicon[], [accent]graphite[]) or [accent]unit:<name>[]. [lightgray](!make help)[]")
            return
        }

        val req = GenRequest()
        req.target = canonical
        req.rate = parseRate(args)
        applyFlags(req, args, player) // minimal; full validation is Task F
        req.seed = 0L                 // deterministic default. FINISHME(Task F): seed source

        pending = req
        state = State.awaitingArea
        player.sendMessage("[accent][TNG][] make [white]$canonical[]: press [accent]schematic-select[] (default [accent]F[]) and drag to select the build area. [lightgray](!make cancel to abort)[]")
    }

    // ---- Task B/C/D: area capture -> off-thread generate -> paste handoff -------------------------

    /**
     * Called from the `schematic_select` release path. Mirrors the retired miner hook's contract:
     * @return true iff `!make` was armed and this drag was consumed as the build area (so the caller
     *   must suppress the normal schematic copy).
     */
    fun consumeSelection(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (state != State.awaitingArea) return false
        val req = pending ?: run { reset(); return false }

        val area = normalizeArea(x1, y1, x2, y2)
        if (area.w < MIN_SIZE || area.h < MIN_SIZE) {
            // Consume the drag (suppress the copy) but stay armed so the user can drag again.
            local("[scarlet][TNG][] area too small (min ${MIN_SIZE}x$MIN_SIZE); selected ${area.w}x${area.h}. Drag again.")
            return true
        }

        req.areaX = area.x; req.areaY = area.y; req.areaW = area.w; req.areaH = area.h
        // coreSide auto-detect is Task F; leave whatever --core set (null = auto/none for now).

        // Snapshot: the camera may move freely during generation; anchor stays on the captured tiles.
        val snapshot = req
        state = State.generating
        Threads.daemon("tng-make-gen") {
            val result = try {
                generator.generate(snapshot)
            } catch (e: Throwable) {
                Log.err("[TNG] make generation failed", e)
                GenResult.fail("generation error: ${e.message}")
            }
            Core.app.post { onGenerated(result) }
        }
        return true
    }

    /** Main-thread continuation after generation. */
    private fun onGenerated(r: GenResult) {
        if (state != State.generating) return // reset/cancelled while generating
        if (!r.ok || r.schematic == null) {
            local("[scarlet][TNG][] ${r.message ?: "generation failed"}")
            reset()
            return
        }
        // Hand to the vanilla paste buffer; vanilla owns preview/move/rotate/confirm from here.
        // Known limitation (RECON.md §4): the paste selection tracks the cursor; it cannot be pinned
        // to a fixed origin. It appears at the release point and follows the mouse for nudge/confirm.
        Vars.control.input.useSchematic(r.schematic)
        local("[accent][TNG][] ${r.message} — move/rotate and click to place.")
        reset()
    }

    private fun reset() {
        state = State.idle
        pending = null
    }

    // ---- Pure helpers (unit-tested) --------------------------------------------------------------

    /** Normalize a drag (inclusive tile endpoints) to a bottom-left-origin rectangle. */
    fun normalizeArea(x1: Int, y1: Int, x2: Int, y2: Int): IntRect =
        IntRect(minOf(x1, x2), minOf(y1, y2), abs(x2 - x1) + 1, abs(y2 - y1) + 1)

    private val RATE = Regex("^([0-9]*\\.?[0-9]+)(?:/(min|sec|s))?$")

    /** Parse the first non-flag numeric token after the target as a per-second rate; 0 if absent. */
    fun parseRate(args: Array<String>): Float {
        for (i in 1 until args.size) {
            val a = args[i]
            if (a.startsWith("--")) continue
            val m = RATE.matchEntire(a.lowercase()) ?: continue
            val v = m.groupValues[1].toFloat()
            return if (m.groupValues[2] == "min") v / 60f else v // sec/s/"" -> per-second
        }
        return 0f
    }

    /**
     * Resolve a user token to a canonical target id, generalized beyond any single resource:
     * an item id (with a few aliases), or a unit as `unit:<name>`. @return null if unresolved.
     */
    fun resolveTarget(raw: String): String? {
        val s = raw.lowercase().trim()
        if (s.startsWith("unit:")) {
            val name = s.removePrefix("unit:").substringBefore(':')
            return if (Vars.content.units().find { it.name == name } != null) "unit:$name" else null
        }
        val alias = ALIASES[s] ?: s
        Vars.content.items().find { it.name == alias }?.let { return it.name }
        Vars.content.units().find { it.name == alias }?.let { return "unit:${it.name}" }
        return null
    }

    // ---- Flags (minimal for Task A; full parse/validation is Task F) -----------------------------

    private fun applyFlags(req: GenRequest, args: Array<String>, player: Player) {
        for (i in 1 until args.size) {
            val a = args[i]
            if (!a.startsWith("--")) continue
            val body = a.removePrefix("--")
            val key = body.substringBefore('=')
            val value = if (body.contains('=')) body.substringAfter('=') else null
            when (key) {
                "no-bridges" -> req.allowBridges = false
                "tier" -> req.conveyorTierCap = value
                "power" -> req.power = if (value == "onboard") PowerMode.onboard else PowerMode.external
                "core" -> req.coreSide = coreSide(value)
                "max" -> { /* footprint cap; FINISHME(Task F): apply vs F selection, tighter wins */ }
                else -> player.sendMessage("[lightgray][TNG][] ignoring unknown flag [accent]--$key[]")
            }
        }
    }

    /** Map n/e/s/w (or 0..3) to a rotation side; null if unrecognized. */
    private fun coreSide(v: String?): Int? = when (v?.lowercase()) {
        "0", "e", "east" -> 0
        "1", "n", "north" -> 1
        "2", "w", "west" -> 2
        "3", "s", "south" -> 3
        else -> null
    }

    private fun printHelp(player: Player) {
        player.sendMessage(
            "[accent][TNG][] [white]!make <target> [rate] [flags][]\n" +
                "[lightgray]target[]: an item id ([accent]silicon[], [accent]graphite[], [accent]phase[]) or [accent]unit:<name>[]\n" +
                "[lightgray]rate[]: e.g. [accent]2/min[], [accent]5/sec[] (0/omitted = fill the area)\n" +
                "[lightgray]flags[]: --no-bridges  --tier=<block>  --power=external|onboard  --core=n|e|s|w  --max=WxH\n" +
                "Then press [accent]schematic-select[] (default F) and drag the build area.\n" +
                "[lightgray]Note: this produces plans; in multiplayer the server still validates every placement.[]"
        )
    }

    /** Local, non-networked chat line. Tolerates a headless environment (no UI) for tests. */
    private fun local(msg: String) {
        val frag = Vars.ui?.chatfrag ?: return
        frag.addMsg(msg)
    }
}
