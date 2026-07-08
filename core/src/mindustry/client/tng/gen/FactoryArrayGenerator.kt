package mindustry.client.tng.gen

import arc.math.geom.Point2
import arc.struct.Seq
import arc.struct.StringMap
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Schematic
import mindustry.game.Schematic.Stile
import mindustry.type.Item
import mindustry.world.Block
import mindustry.world.blocks.distribution.Conveyor
import mindustry.world.blocks.environment.Floor
import mindustry.world.blocks.production.GenericCrafter
import mindustry.world.consumers.ConsumeItems

/**
 * Generic factory-array [SchematicGenerator] (see the make-generator design): given a target item, it
 * finds the crafter that produces it and tiles an array of that crafter fed by belts, keying off the
 * crafter's own size + I/O read from live content (never hardcoded) — so it generalizes to any crafter.
 *
 * **Multi-stage (v3).** For a mid/late target whose inputs are themselves crafted (e.g. surge alloy needs
 * silicon), the generator recurses: each crafted input becomes a feeder stage sized to the demanded rate
 * (parent_count × input_per_craft/s), raw inputs (mined) stay external. The stages are stacked as bands in
 * the area — deepest feeder at the bottom, target on top — each band internally correct (see below).
 *
 * **Per-stage layout.** All flow is one direction; correctness rests on the Mindustry rule that a conveyor
 * accepts items only from its back/sides, never its front: an input-belt row below points UP into the
 * smelters (front-facing → can't be back-dumped), a shared horizontal output lane above carries product to
 * the exit edge (its underside is a side → accepts), adjacent smelters reject each other's product, and a
 * 1-wide lane after each smelter holds an auto-linking power node. The output lane's belt tier is sized to
 * the row's throughput (basic 6.5 → titanium 10 → armored 11 items/s).
 *
 * Pure: reads only block specs + the request; no world/GL/UI access, so it is off-thread-safe and
 * deterministic. FINISHME: automatic inter-stage belt routing (risers/bridges carrying each intermediate
 * from its feeder band into the next stage's input taps) — this cycle sizes and co-places the whole chain;
 * the intermediate is currently left as an external hookup with a one-line note. Also: multi-row/multi-lane
 * packing, coal/sand 2:1 zipper, coreSide orientation.
 */
class FactoryArrayGenerator : SchematicGenerator {
    private data class Stage(val crafter: GenericCrafter, val item: Item, val count: Int, val perSec: Float)

    override fun generate(req: GenRequest): GenResult {
        val item: Item = Vars.content.items().find { it.name == req.target }
            ?: return GenResult.fail("make: '${req.target}' is not a craftable item (units not supported yet).")
        val root = findCrafter(item)
            ?: return GenResult.fail("make: no known factory produces ${item.localizedName}.")

        val rootStride = root.size + 1
        val rootPerSec = outputPerSecond(root, item)
        val tiers = outputTiers(req.conveyorTierCap)
        val rootLaneCap = Math.floor((tiers.last().second / rootPerSec).toDouble()).toInt().coerceAtLeast(1)
        val rootFit = req.areaW / rootStride
        if (rootFit < 1) {
            return GenResult.tooSmall(IntRect(0, 0, rootStride, root.size + 2),
                "make: area too narrow for ${root.localizedName} (min width $rootStride).")
        }
        val want = if (req.rate > 0f) Math.ceil((req.rate / rootPerSec).toDouble()).toInt() else rootFit

        // Shrink the target count until the whole chain (all stacked bands, incl. wider feeders) fits.
        var targetCount = want.coerceIn(1, minOf(rootFit, rootLaneCap))
        var stages = planChain(root, item, targetCount, req)
        while ((chainHeight(stages) > req.areaH || maxStageWidth(stages) > req.areaW) && targetCount > 1) {
            targetCount--
            stages = planChain(root, item, targetCount, req)
        }
        val totalH = chainHeight(stages)
        val totalW = maxStageWidth(stages)
        if (totalH > req.areaH || totalW > req.areaW) {
            return GenResult.tooSmall(IntRect(0, 0, totalW, totalH),
                "make: area too small for the ${item.localizedName} chain (min ${totalW}x$totalH; selected ${req.areaW}x${req.areaH}).")
        }

        // Lay stages bottom->top: deepest feeder at y=0, target on top.
        val tiles = Seq<Stile>()
        var yOff = 0
        val ordered = stages.asReversed()
        for ((i, stage) in ordered.withIndex()) {
            layStage(stage, yOff, tiles)
            yOff += stage.crafter.size + 2
            if (i < ordered.size - 1) yOff += BAND_GAP
        }

        val schem = Schematic(tiles, StringMap(), maxStageWidth(stages), totalH)
        return GenResult.of(schem, describe(item, stages))
    }

    // ---- chain planning -------------------------------------------------------------------------

    /** Root stage first, then a feeder stage per crafted input (recursively); raw inputs are external. */
    private fun planChain(root: GenericCrafter, item: Item, rootCount: Int, req: GenRequest): List<Stage> {
        val out = ArrayList<Stage>()
        planStage(root, item, rootCount, req, out, HashSet(), 0)
        return out
    }

    private fun planStage(crafter: GenericCrafter, item: Item, count: Int, req: GenRequest,
                          out: MutableList<Stage>, visited: MutableSet<String>, depth: Int) {
        out.add(Stage(crafter, item, count, outputPerSecond(crafter, item)))
        if (depth >= MAX_DEPTH || !visited.add(item.name)) return
        for (stack in inputItems(crafter)) {
            if (isMined(stack.item)) continue                         // mineable => supply it externally
            if (stack.item.name in visited) continue                  // cycle guard
            val feeder = findCrafter(stack.item) ?: continue          // no crafter => raw => external
            val demandPerSec = count * stack.amount * 60f / crafter.craftTime
            val feederPerSec = outputPerSecond(feeder, stack.item)
            val feederCount = Math.ceil((demandPerSec / feederPerSec).toDouble()).toInt().coerceAtLeast(1)
            planStage(feeder, stack.item, feederCount, req, out, visited, depth + 1)
        }
    }

    private fun chainHeight(stages: List<Stage>): Int =
        stages.sumOf { it.crafter.size + 2 } + (stages.size - 1) * BAND_GAP

    private fun maxStageWidth(stages: List<Stage>): Int =
        stages.maxOf { (it.count - 1) * (it.crafter.size + 1) + it.crafter.size + 1 }

    // ---- per-stage layout -----------------------------------------------------------------------

    private fun layStage(stage: Stage, yOff: Int, tiles: Seq<Stile>) {
        val crafter = stage.crafter
        val s = crafter.size
        val stride = s + 1
        val smelterY = yOff + 1
        val laneY = yOff + 1 + s
        val outTier = outputTiers(null).firstOrNull { it.second >= stage.count * stage.perSec }?.first
            ?: outputTiers(null).last().first
        val usedW = (stage.count - 1) * stride + s + 1

        for (c in 0 until stage.count) {
            val sx = c * stride
            tiles.add(Stile(crafter, sx, smelterY, null, ROT_UP))
            for (dx in 0 until s) tiles.add(Stile(Blocks.conveyor, sx + dx, yOff, null, ROT_UP))
            // Power node in the gap lane, with EXPLICIT relative links (a Point2[] config): to its own
            // smelter (bottom-left, offset -s) and to the next node (offset +stride). Explicit links work
            // in singleplayer AND multiplayer — auto-link is disabled on net clients — so the delivered
            // array is internally powered end-to-end and the player only hooks one external source to it.
            val links = ArrayList<Point2>()
            links.add(Point2(-s, 0))                                  // -> this stage's smelter
            if (c < stage.count - 1) links.add(Point2(stride, 0))     // -> next node in the row
            tiles.add(Stile(Blocks.powerNode, sx + s, smelterY, links.toTypedArray(), 0))
        }
        for (x in 0 until usedW) tiles.add(Stile(outTier, x, laneY, null, ROT_RIGHT))
    }

    // ---- content queries ------------------------------------------------------------------------

    private fun findCrafter(item: Item): GenericCrafter? =
        Vars.content.blocks()
            .filter { it is GenericCrafter && produces(it, item) }
            .map { it as GenericCrafter }
            .minWithOrNull(compareBy({ it.size }, { buildCost(it) }))

    private fun produces(gc: GenericCrafter, item: Item): Boolean {
        if (gc.outputItem?.item == item) return true
        return gc.outputItems?.any { it.item == item } ?: false
    }

    private fun outputPerSecond(gc: GenericCrafter, item: Item): Float {
        val amount = gc.outputItem?.takeIf { it.item == item }?.amount
            ?: gc.outputItems?.firstOrNull { it.item == item }?.amount ?: 1
        return amount * 60f / gc.craftTime
    }

    private fun inputItems(gc: GenericCrafter) =
        gc.consumers.filterIsInstance<ConsumeItems>().firstOrNull()?.items?.toList() ?: emptyList()

    /**
     * True if [item] can be obtained by mining (some floor/ore drops it), so it is supplied externally
     * rather than built as a feeder stage — even when a crafter could also produce it (e.g. coal has a
     * centrifuge but is normally mined). Silicon has no ore, so it stays a feeder.
     */
    private fun isMined(item: Item): Boolean =
        Vars.content.blocks().find { (it as? Floor)?.itemDrop == item } != null

    private fun outputTiers(cap: String?): List<Pair<Block, Float>> {
        val all = listOf(Blocks.conveyor, Blocks.titaniumConveyor, Blocks.armoredConveyor)
            .map { it to (it as Conveyor).displayedSpeed }
            .sortedBy { it.second }
        val capSpeed = cap?.let { name -> all.firstOrNull { it.first.name == name }?.second } ?: return all
        return all.filter { it.second <= capSpeed + 1e-3f }.ifEmpty { listOf(all.first()) }
    }

    private fun buildCost(b: Block): Int {
        var sum = 0
        for (r in b.requirements) sum += r.amount
        return sum
    }

    private fun describe(target: Item, stages: List<Stage>): String {
        val root = stages.first()
        val feeders = stages.drop(1)
        return buildString {
            append("${target.localizedName}: ${root.count} ${root.crafter.localizedName} ≈ ${fmt(root.count * root.perSec)}/s")
            if (feeders.isNotEmpty()) {
                append(" + feeders[")
                append(feeders.joinToString(", ") { "${it.count} ${it.crafter.localizedName}" })
                append("] (connect intermediate belts)")
            }
        }
    }

    private fun fmt(v: Float): String = (Math.round(v * 10f) / 10f).toString()

    companion object {
        private const val ROT_RIGHT: Byte = 0
        private const val ROT_UP: Byte = 1
        private const val BAND_GAP = 1
        private const val MAX_DEPTH = 4
    }
}
