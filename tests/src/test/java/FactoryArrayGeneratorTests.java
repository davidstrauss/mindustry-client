package test;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.client.tng.gen.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Layout tests for {@link FactoryArrayGenerator} — asserts on the generated schematic directly. */
public class FactoryArrayGeneratorTests{
    static SchematicGenerator gen;

    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication(); // content must be loaded (crafter lookup, block specs)
        gen = new FactoryArrayGenerator();
    }

    static GenRequest req(String target, int w, int h, float rate){
        GenRequest r = new GenRequest();
        r.target = target;
        r.areaW = w; r.areaH = h;
        r.rate = rate;
        r.seed = 1L;
        return r;
    }

    int count(Schematic s, mindustry.world.Block block){
        int n = 0;
        for(Stile t : s.tiles) if(t.block == block) n++;
        return n;
    }

    @Test
    void tilesSmeltersWithBeltsAndPower(){
        GenResult res = gen.generate(req("silicon", 12, 8, 0f));
        assertTrue(res.ok, res.message);
        Schematic s = res.schematic;

        assertEquals(4, count(s, Blocks.siliconSmelter), "12 wide / stride 3 = 4 smelters in a row");
        assertTrue(count(s, Blocks.powerNode) >= 4, "every smelter reached by power");
        assertTrue(count(s, Blocks.conveyor) > 0, "input + output belts present");

        for(Stile t : s.tiles){
            assertTrue(t.x >= 0 && t.y >= 0 && t.x + t.block.size <= s.width && t.y + t.block.size <= s.height,
                t.block.name + " footprint within schematic bounds at " + t.x + "," + t.y);
        }
    }

    @Test
    void noSelfOverlap(){
        Schematic s = gen.generate(req("silicon", 12, 8, 0f)).schematic;
        IntSet occ = new IntSet();
        for(Stile t : s.tiles){
            for(int dx = 0; dx < t.block.size; dx++){
                for(int dy = 0; dy < t.block.size; dy++){
                    assertTrue(occ.add(Point2.pack(t.x + dx, t.y + dy)),
                        "two blocks overlap at " + (t.x + dx) + "," + (t.y + dy) + " (" + t.block.name + ")");
                }
            }
        }
    }

    @Test
    void inputRowBelowSharedOutputLaneAbove(){
        Schematic s = gen.generate(req("silicon", 12, 8, 0f)).schematic;
        // smelters sit at y=1 (one input belt row at y=0 beneath them); shared output lane at y=3
        for(Stile t : s.tiles){
            if(t.block == Blocks.siliconSmelter) assertEquals(1, t.y, "smelter row directly above the input belt row");
        }
        int bottomBelts = 0, laneBelts = 0;
        for(Stile t : s.tiles){
            if(t.block == Blocks.conveyor && t.y == 0) bottomBelts++;
            // output lane spans the full width at y = smelterY(1) + size(2) = 3, flowing right (rot 0)
            if(t.y == 3 && (t.block == Blocks.conveyor || t.block == Blocks.titaniumConveyor || t.block == Blocks.armoredConveyor)){
                laneBelts++;
                assertEquals(0, t.rotation, "output lane flows to the exit edge (+x)");
            }
        }
        assertEquals(8, bottomBelts, "2 input belts under each of 4 smelters");
        assertEquals(s.width, laneBelts, "one continuous output lane across the full width");
        // schematic is compact: input(1) + smelter(2) + lane(1) = 4 tall regardless of a taller selection
        assertEquals(4, s.height, "compact single-row footprint");
    }

    @Test
    void outputBeltTierScalesWithLoad(){
        // basic belt 6.5/s carries 4 smelters (6/s); titanium 10/s carries 6 (9/s); armored 11/s carries 7 (10.5/s).
        assertEquals(Blocks.conveyor, laneBlock(gen.generate(req("silicon", 12, 8, 0f)).schematic), "4 smelters fit a basic belt");
        assertEquals(Blocks.titaniumConveyor, laneBlock(gen.generate(req("silicon", 18, 8, 0f)).schematic), "6 smelters need titanium");
        assertEquals(Blocks.armoredConveyor, laneBlock(gen.generate(req("silicon", 21, 8, 0f)).schematic), "7 smelters need armored");
    }

    /** The block used by the output lane (y=3). */
    mindustry.world.Block laneBlock(Schematic s){
        for(Stile t : s.tiles) if(t.y == 3) return t.block;
        return null;
    }

    @Test
    void rateLimitsSmelterCount(){
        // silicon smelter = 1.5/s; asking for 3/s should place 2 even though 4 fit.
        Schematic s = gen.generate(req("silicon", 12, 8, 3f)).schematic;
        assertEquals(2, count(s, Blocks.siliconSmelter), "rate 3/s / 1.5 per smelter = 2 smelters");
    }

    @Test
    void tooSmallAreaIsReportedWithMinViable(){
        GenResult res = gen.generate(req("silicon", 3, 3, 0f)); // height 3 < min 4
        assertFalse(res.ok, "area below minimum must fail");
        assertNotNull(res.minViable, "min viable size reported");
        assertEquals(4, res.minViable.h, "silicon smelter needs 1 input + 2 + >=1 output rows tall");
    }

    @Test
    void powerNodesCarryExplicitLinks(){
        // Explicit Point2[] links make the array self-power in SP and MP (auto-link is off on net clients).
        Schematic s = gen.generate(req("silicon", 12, 8, 0f)).schematic;
        int nodes = 0;
        for(Stile t : s.tiles){
            if(t.block != Blocks.powerNode) continue;
            nodes++;
            assertTrue(t.config instanceof Point2[], "power node carries a Point2[] link config");
            Point2[] links = (Point2[])t.config;
            boolean toSmelter = false;
            for(Point2 p : links) if(p.x == -2 && p.y == 0) toSmelter = true; // -size, to its 2x2 smelter
            assertTrue(toSmelter, "node links to its own smelter (offset -2,0)");
        }
        assertEquals(4, nodes, "one node per smelter");
    }

    @Test
    void buildsTwoStageChainForSurgeAlloy(){
        // surge smelter consumes 3 silicon / 75 ticks => 2.4 silicon/s per smelter; silicon smelter = 1.5/s.
        // copper/lead/titanium are raw (no crafter) => external, no feeder. Silicon is the one crafted input.
        GenResult res = gen.generate(req("surge-alloy", 24, 20, 0f));
        assertTrue(res.ok, res.message);
        Schematic s = res.schematic;

        int surge = count(s, Blocks.surgeSmelter);
        int silicon = count(s, Blocks.siliconSmelter);
        assertTrue(surge >= 1, "surge smelters placed");
        assertTrue(silicon >= 1, "silicon feeder stage placed (silicon is a crafted input)");
        assertEquals((int)Math.ceil(surge * 2.4 / 1.5), silicon, "silicon feeder sized to surge demand");

        // whole chain fits the area, no overlaps between stages (footprint = stile pos + sizeOffset)
        IntSet occ = new IntSet();
        for(Stile t : s.tiles){
            int fx = t.x + t.block.sizeOffset, fy = t.y + t.block.sizeOffset;
            assertTrue(fx >= 0 && fy >= 0 && fx + t.block.size <= s.width && fy + t.block.size <= s.height, "in bounds: " + t.block.name);
            for(int dx = 0; dx < t.block.size; dx++) for(int dy = 0; dy < t.block.size; dy++)
                assertTrue(occ.add(Point2.pack(fx + dx, fy + dy)), "stage overlap at " + (fx+dx) + "," + (fy+dy));
        }
        assertTrue(s.width <= 24 && s.height <= 20, "chain fits the selected area");
    }

    @Test
    void generalizesBeyondSilicon(){
        // metaglass is produced by the kiln (2x2), proving the generator keys off content, not silicon.
        GenResult res = gen.generate(req("metaglass", 12, 8, 0f));
        assertTrue(res.ok, res.message);
        assertTrue(count(res.schematic, Blocks.kiln) > 0, "kiln array generated for metaglass");
        assertEquals(0, count(res.schematic, Blocks.siliconSmelter), "no silicon special-casing");
    }
}
