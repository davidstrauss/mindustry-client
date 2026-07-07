package test;

import mindustry.client.tng.gen.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import org.junit.jupiter.api.*;

import java.io.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task S acceptance tests for the {@link SchematicGenerator} boundary, exercised via {@link StubGenerator}.
 * These assert the contract every generator must satisfy — footprint fits the requested area, and the
 * output is deterministic for a fixed request + seed — independent of the (future) real generator.
 */
public class SchematicGeneratorTests{
    static SchematicGenerator gen;

    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication(); // registers content so Schematics.write can resolve block ids
        gen = new StubGenerator();
    }

    static GenRequest req(int w, int h){
        GenRequest r = new GenRequest();
        r.target = "silicon"; // stub ignores it; present to prove target-agnosticism
        r.areaX = 10;
        r.areaY = 20;
        r.areaW = w;
        r.areaH = h;
        r.seed = 42L;
        return r;
    }

    @Test
    public void footprintFitsRequestedArea(){
        int[][] sizes = {{1, 1}, {3, 5}, {7, 2}, {10, 10}, {20, 13}, {maxSchematicSize + 50, maxSchematicSize + 50}};
        for(int[] s : sizes){
            int w = s[0], h = s[1];
            GenResult res = gen.generate(req(w, h));

            assertTrue(res.ok, "expected ok for " + w + "x" + h);
            assertNotNull(res.schematic, "schematic present when ok for " + w + "x" + h);

            Schematic schem = res.schematic;
            assertTrue(schem.width >= 1 && schem.height >= 1, "non-empty footprint for " + w + "x" + h);
            assertTrue(schem.width <= w, "width " + schem.width + " must fit requested " + w);
            assertTrue(schem.height <= h, "height " + schem.height + " must fit requested " + h);
            assertTrue(schem.width <= maxSchematicSize && schem.height <= maxSchematicSize,
                "footprint within schematic cap for " + w + "x" + h);

            // every tile lies within the declared footprint
            for(Stile st : schem.tiles){
                assertTrue(st.x >= 0 && st.x < schem.width && st.y >= 0 && st.y < schem.height,
                    "tile (" + st.x + "," + st.y + ") within " + schem.width + "x" + schem.height);
            }
        }
    }

    @Test
    public void deterministicForSameRequestAndSeed() throws IOException{
        byte[] a = msch(gen.generate(req(9, 6)).schematic);
        byte[] b = msch(gen.generate(req(9, 6)).schematic);
        assertArrayEquals(a, b, "same request + seed must serialize to byte-identical msch");
    }

    /** Serialize a schematic to raw msch bytes, tags excluded so only structural content is compared. */
    static byte[] msch(Schematic schem) throws IOException{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Schematics.write(schem, out, false);
        return out.toByteArray();
    }
}
