package mindustry.client.tng

import arc.math.geom.Point2
import arc.struct.IntSet
import mindustry.Vars.world
import mindustry.content.Blocks
import mindustry.gen.Building
import mindustry.world.Block

/**
 * Reusable, design-agnostic helpers for placing transport (item belts / liquid
 * conduits) and other blocks WITHOUT cross-contaminating neighbouring structures, in
 * either direction:
 *   1. our flow leaking **into** a foreign building, or
 *   2. a foreign building leaking **into** our flow.
 *
 * Handles both modalities — items and fluids — symmetrically.
 *
 * Stateless: the caller passes the set of tiles it owns (packed via [Point2]) so only
 * *foreign* buildings are ever flagged, plus an `affordable` predicate so this library
 * never reaches into any particular planner's economy model. Reuse it anywhere a design
 * needs to make accommodations around existing builds.
 */
object Interference {
    /** Rotation deltas, matching conveyor/conduit rotation: 0=E, 1=N, 2=W, 3=S. */
    private val d4 = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0), intArrayOf(0, -1))

    /** A real, foreign (not-ours) building on this tile, else null. */
    fun foreignBuild(x: Int, y: Int, ours: IntSet): Building? {
        if (ours.contains(Point2.pack(x, y))) return null
        return world.tile(x, y)?.build
    }

    private fun outputs(b: Building, item: Boolean) =
        if (item) b.block.outputsItems() else b.block.outputsLiquid

    /**
     * Would transport flowing toward [rot] at (x,y) be SIDE-contaminated — is one of its
     * two perpendicular neighbours a foreign building that outputs the relevant material
     * (items if [item], else liquid) and could shove it into our side?
     * Front/back are the intended flow axis and are not checked here.
     */
    fun sideContaminated(x: Int, y: Int, rot: Int, item: Boolean, ours: IntSet): Boolean {
        for (s in intArrayOf((rot + 1) % 4, (rot + 3) % 4)) {
            val b = foreignBuild(x + d4[s][0], y + d4[s][1], ours) ?: continue
            if (outputs(b, item)) return true
        }
        return false
    }

    /** Any of the 4 neighbours a foreign building that outputs the relevant material? */
    fun adjacentForeignSource(x: Int, y: Int, item: Boolean, ours: IntSet): Boolean {
        for (d in d4) {
            val b = foreignBuild(x + d[0], y + d[1], ours) ?: continue
            if (outputs(b, item)) return true
        }
        return false
    }

    /** Foreign building directly AHEAD of transport at (x,y) flowing toward [rot] (we'd dump into / hit it). */
    fun forwardForeign(x: Int, y: Int, rot: Int, ours: IntSet): Building? =
        foreignBuild(x + d4[rot][0], y + d4[rot][1], ours)

    /**
     * Side-load-proof variant of an item conveyor when [sideRisk] and affordable: the
     * armored conveyor refuses side input. Otherwise the original block unchanged.
     */
    fun safeConveyor(base: Block, sideRisk: Boolean, affordable: (Block) -> Boolean): Block =
        if (sideRisk && base !== Blocks.armoredConveyor && affordable(Blocks.armoredConveyor)) Blocks.armoredConveyor else base

    /** Liquid counterpart: the plated (armored) conduit refuses side input. */
    fun safeConduit(base: Block, sideRisk: Boolean, affordable: (Block) -> Boolean): Block =
        if (sideRisk && base !== Blocks.platedConduit && affordable(Blocks.platedConduit)) Blocks.platedConduit else base

    // Crossing (junction) and hopping (bridge) blocks for routing past foreign structures
    // without mixing — null when their materials haven't been loaded into the core yet.
    fun itemJunction(affordable: (Block) -> Boolean): Block? = Blocks.junction.takeIf(affordable)
    fun itemBridge(affordable: (Block) -> Boolean): Block? = Blocks.itemBridge.takeIf(affordable)
    fun liquidJunction(affordable: (Block) -> Boolean): Block? = Blocks.liquidJunction.takeIf(affordable)
    fun liquidBridge(affordable: (Block) -> Boolean): Block? = Blocks.bridgeConduit.takeIf(affordable)
}
