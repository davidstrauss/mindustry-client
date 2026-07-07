package mindustry.client.tng.gen

import arc.struct.Seq
import arc.struct.StringMap
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Schematic
import mindustry.game.Schematic.Stile
import mindustry.type.Item
import mindustry.world.Block
import mindustry.world.blocks.production.GenericCrafter

/**
 * Generic factory-array [SchematicGenerator] (see the make-generator design): given a target item, it
 * finds the crafter that produces it, then tiles the selected area with an array of that crafter fed by
 * belts, generalizing beyond silicon by keying off the crafter's own size + I/O (read from live content,
 * never hardcoded).
 *
 * **v1 layout — single up-flowing row.** All flow goes one direction (+Y). This is correct because a
 * Mindustry conveyor accepts items only from its back/sides, never its front:
 *  - one input-belt row below the smelters points UP *into* them (front-facing) → delivers coal/sand and
 *    cannot be back-dumped with output;
 *  - output belts above point UP *away* (back-facing the smelter) → accept the dumped product and carry it
 *    to the top edge;
 *  - horizontally adjacent smelters reject each other's output (they consume inputs, not the product).
 * A 1-wide lane after each smelter holds a power node (nodes auto-link in range). The player feeds inputs
 * at the bottom edge and collects product at the top edge; power is external (invariant: client can't force
 * build — these are plans the server validates).
 *
 * Pure: reads only block specs + the request; no world/GL/UI access, so it is safe off-thread and
 * deterministic. FINISHME: multi-row packing for tall areas, belt-tier selection by throughput, coal/sand
 * lane separation, coreSide orientation, power lane sizing — tracked as later revision cycles.
 */
class FactoryArrayGenerator : SchematicGenerator {
    override fun generate(req: GenRequest): GenResult {
        val item: Item = Vars.content.items().find { it.name == req.target }
            ?: return GenResult.fail("make: '${req.target}' is not a craftable item (units not supported yet).")

        val crafter = findCrafter(item)
            ?: return GenResult.fail("make: no known factory produces ${item.localizedName}.")

        val s = crafter.size
        val stride = s + 1           // smelter width + 1 power/gap lane
        val minW = stride            // need at least one smelter + its power lane
        val minH = s + 2             // 1 input row + smelter + >=1 output row
        if (req.areaW < minW || req.areaH < minH) {
            return GenResult.tooSmall(
                IntRect(0, 0, minW, minH),
                "make: area too small for ${crafter.localizedName} (min ${minW}x$minH; selected ${req.areaW}x${req.areaH})."
            )
        }

        val perSec = outputPerSecond(crafter, item)
        val fit = req.areaW / stride                       // smelters that fit in one row
        val want = if (req.rate > 0f) Math.ceil((req.rate / perSec).toDouble()).toInt() else fit
        val count = want.coerceIn(1, fit)

        val conveyor = Blocks.conveyor
        val power = Blocks.powerNode
        val tiles = Seq<Stile>()

        val smelterY = 1                                   // one input row below at y=0
        val outTop = req.areaH - 1                         // output belts run up to the top edge

        for (c in 0 until count) {
            val sx = c * stride
            tiles.add(Stile(crafter, sx, smelterY, null, ROT_UP))          // Stile x,y = block bottom-left
            for (dx in 0 until s) {
                val col = sx + dx
                tiles.add(Stile(conveyor, col, 0, null, ROT_UP))           // input belt (points up into smelter)
                for (y in smelterY + s..outTop) {
                    tiles.add(Stile(conveyor, col, y, null, ROT_UP))       // output belts up to the top edge
                }
            }
            tiles.add(Stile(power, sx + s, smelterY, null, 0))             // power node in the gap lane
        }

        val usedW = (count - 1) * stride + s + 1           // last smelter's power lane included
        val schem = Schematic(tiles, StringMap(), usedW, req.areaH)

        val achieved = count * perSec
        val msg = buildString {
            append("${item.localizedName}: $count ${crafter.localizedName} ≈ ${fmt(achieved)}/s")
            if (req.rate > 0f && achieved + 1e-3f < req.rate) append(" (target ${fmt(req.rate)}/s needs ${Math.ceil((req.rate / perSec).toDouble()).toInt()}; area fits $count)")
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

    /** Total item cost to build (for tie-breaking toward the cheaper/basic crafter). */
    private fun buildCost(b: Block): Int {
        var sum = 0
        for (r in b.requirements) sum += r.amount
        return sum
    }

    private fun fmt(v: Float): String = (Math.round(v * 10f) / 10f).toString()

    companion object {
        private const val ROT_UP: Byte = 1  // 0=+x,1=+y,2=-x,3=-y
    }
}
