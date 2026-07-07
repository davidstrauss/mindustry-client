package mindustry.client.tng.gen

import arc.struct.Seq
import arc.struct.StringMap
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Schematic
import mindustry.game.Schematic.Stile
import mindustry.type.Item
import mindustry.world.Block
import mindustry.world.blocks.distribution.Conveyor
import mindustry.world.blocks.production.GenericCrafter

/**
 * Generic factory-array [SchematicGenerator] (see the make-generator design): given a target item, it
 * finds the crafter that produces it, then tiles the selected area with an array of that crafter fed by
 * belts, generalizing beyond silicon by keying off the crafter's own size + I/O (read from live content,
 * never hardcoded).
 *
 * **v2 layout — single row, shared output lane.** All flow is one direction; correctness rests on the
 * Mindustry rule that a conveyor accepts items only from its back/sides, never its front:
 *  - one input-belt row below the smelters points UP *into* them (front-facing) → delivers coal/sand and
 *    cannot be back-dumped with product;
 *  - a single horizontal **output collection lane** just above the row runs to the exit edge; each smelter
 *    dumps its product up into the lane (the lane's underside is a side → accepts), and the lane's belt
 *    tier is sized to the whole row's output (basic 6.5/s → titanium 10 → armored 11);
 *  - horizontally adjacent smelters reject each other's product (they consume inputs, not the product), and
 *    a 1-wide lane after each smelter holds a power node (auto-links in range).
 * The player feeds inputs at the bottom edge and collects product at the exit edge; power is external
 * (invariant: the client can't force build — these are plans the server validates).
 *
 * Pure: reads only block specs + the request; no world/GL/UI access, so it is off-thread-safe and
 * deterministic. FINISHME (later cycles): multi-row / multi-lane packing to use tall areas and exceed one
 * lane's capacity, coal/sand zipper (2:1 sand), and coreSide orientation.
 */
class FactoryArrayGenerator : SchematicGenerator {
    override fun generate(req: GenRequest): GenResult {
        val item: Item = Vars.content.items().find { it.name == req.target }
            ?: return GenResult.fail("make: '${req.target}' is not a craftable item (units not supported yet).")

        val crafter = findCrafter(item)
            ?: return GenResult.fail("make: no known factory produces ${item.localizedName}.")

        val s = crafter.size
        val stride = s + 1                 // smelter width + 1 power/gap lane
        val minW = stride                  // need at least one smelter + its power lane
        val minH = s + 2                   // 1 input row + smelter + 1 output-lane row
        if (req.areaW < minW || req.areaH < minH) {
            return GenResult.tooSmall(
                IntRect(0, 0, minW, minH),
                "make: area too small for ${crafter.localizedName} (min ${minW}x$minH; selected ${req.areaW}x${req.areaH})."
            )
        }

        val perSec = outputPerSecond(crafter, item)
        val tiers = outputTiers(req.conveyorTierCap)
        val laneCap = Math.floor((tiers.last().second / perSec).toDouble()).toInt().coerceAtLeast(1)

        val fitWidth = req.areaW / stride
        val want = if (req.rate > 0f) Math.ceil((req.rate / perSec).toDouble()).toInt() else fitWidth
        // One shared lane; cap at what the fastest allowed belt can carry (multi-lane is a later cycle).
        val count = want.coerceIn(1, minOf(fitWidth, laneCap))

        val outTier = tiers.firstOrNull { it.second >= count * perSec }?.first ?: tiers.last().first
        val inBelt = Blocks.conveyor
        val power = Blocks.powerNode
        val tiles = Seq<Stile>()

        val smelterY = 1                   // one input row below at y=0
        val laneY = smelterY + s           // shared output lane directly above the row
        val usedW = (count - 1) * stride + s + 1

        for (c in 0 until count) {
            val sx = c * stride
            tiles.add(Stile(crafter, sx, smelterY, null, ROT_UP))
            for (dx in 0 until s) {
                tiles.add(Stile(inBelt, sx + dx, 0, null, ROT_UP))     // input belt: points up into smelter
            }
            // power node in the gap lane; range covers neighbours so the row auto-wires.
            tiles.add(Stile(power, sx + s, smelterY, null, 0))
        }
        // Shared output lane across the full used width, flowing right to the exit edge.
        for (x in 0 until usedW) {
            tiles.add(Stile(outTier, x, laneY, null, ROT_RIGHT))
        }

        val schem = Schematic(tiles, StringMap(), usedW, laneY + 1)

        val achieved = count * perSec
        val wantForRate = if (req.rate > 0f) Math.ceil((req.rate / perSec).toDouble()).toInt() else count
        val msg = buildString {
            append("${item.localizedName}: $count ${crafter.localizedName} on ${(outTier as? Conveyor)?.let { fmt(it.displayedSpeed) } ?: "belt"}/s belt ≈ ${fmt(achieved)}/s")
            if (wantForRate > count) append(" (target ${fmt(req.rate)}/s needs $wantForRate; fits $count here)")
        }
        return GenResult.of(schem, msg)
    }

    /** Choose the crafter that produces [item]: prefer the smallest footprint, then the cheapest to build. */
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

    /** Item-belt tiers by throughput (ascending), honoring an optional tier ceiling by block id. */
    private fun outputTiers(cap: String?): List<Pair<Block, Float>> {
        val all = listOf(Blocks.conveyor, Blocks.titaniumConveyor, Blocks.armoredConveyor)
            .map { it to (it as Conveyor).displayedSpeed }
            .sortedBy { it.second }
        val capSpeed = cap?.let { name -> all.firstOrNull { it.first.name == name }?.second } ?: return all
        return all.filter { it.second <= capSpeed + 1e-3f }.ifEmpty { listOf(all.first()) }
    }

    /** Total item cost to build (for tie-breaking toward the cheaper/basic crafter). */
    private fun buildCost(b: Block): Int {
        var sum = 0
        for (r in b.requirements) sum += r.amount
        return sum
    }

    private fun fmt(v: Float): String = (Math.round(v * 10f) / 10f).toString()

    companion object {
        private const val ROT_RIGHT: Byte = 0 // +x
        private const val ROT_UP: Byte = 1    // +y
    }
}
