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
    void inputRowAtBottomOutputAboveSmelter(){
        Schematic s = gen.generate(req("silicon", 12, 8, 0f)).schematic;
        // smelters sit at y=1 (one input belt row at y=0 beneath them)
        for(Stile t : s.tiles){
            if(t.block == Blocks.siliconSmelter) assertEquals(1, t.y, "smelter row directly above the input belt row");
        }
        // there is a full input belt row along y=0 under the smelter columns
        int bottomBelts = 0;
        for(Stile t : s.tiles) if(t.block == Blocks.conveyor && t.y == 0) bottomBelts++;
        assertEquals(8, bottomBelts, "2 input belts under each of 4 smelters");
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
    void generalizesBeyondSilicon(){
        // metaglass is produced by the kiln (2x2), proving the generator keys off content, not silicon.
        GenResult res = gen.generate(req("metaglass", 12, 8, 0f));
        assertTrue(res.ok, res.message);
        assertTrue(count(res.schematic, Blocks.kiln) > 0, "kiln array generated for metaglass");
        assertEquals(0, count(res.schematic, Blocks.siliconSmelter), "no silicon special-casing");
    }
}
