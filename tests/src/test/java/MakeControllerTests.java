package test;

import mindustry.client.tng.gen.*;
import mindustry.client.tng.make.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task A/B acceptance tests for the pure parsing/normalization helpers of {@link MakeController}.
 * The full command → area → generate → paste flow is exercised by the run/verify step (it needs a
 * live input handler and UI); these tests pin the logic that must be correct regardless of that.
 */
public class MakeControllerTests{
    static MakeController make;

    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication(); // content must be loaded for resolveTarget
        make = MakeController.INSTANCE;
    }

    @Test
    public void parsesRateAsPerSecond(){
        assertEquals(0f, make.parseRate(new String[]{"silicon"}), 1e-6, "no rate token → 0 (fill area)");
        assertEquals(5f, make.parseRate(new String[]{"silicon", "5"}), 1e-6, "bare number → per-second");
        assertEquals(5f, make.parseRate(new String[]{"silicon", "5/sec"}), 1e-6);
        assertEquals(2f / 60f, make.parseRate(new String[]{"silicon", "2/min"}), 1e-6, "/min normalized to /sec");
        assertEquals(3.5f, make.parseRate(new String[]{"silicon", "3.5/s"}), 1e-6);
        assertEquals(3f / 60f, make.parseRate(new String[]{"silicon", "--no-bridges", "3/min"}), 1e-6, "flags are skipped");
    }

    @Test
    public void normalizesDragToBottomLeftOrigin(){
        IntRect a = make.normalizeArea(5, 5, 5, 5);
        assertEquals(5, a.x); assertEquals(5, a.y); assertEquals(1, a.w); assertEquals(1, a.h);

        // endpoints are inclusive; order-independent
        IntRect b = make.normalizeArea(10, 20, 3, 4);
        IntRect c = make.normalizeArea(3, 4, 10, 20);
        for(IntRect r : new IntRect[]{b, c}){
            assertEquals(3, r.x, "origin x = min");
            assertEquals(4, r.y, "origin y = min");
            assertEquals(8, r.w, "width = |dx| + 1");
            assertEquals(17, r.h, "height = |dy| + 1");
        }
    }

    @Test
    public void resolvesTargetsBeyondSilicon(){
        assertEquals("silicon", make.resolveTarget("silicon"));
        assertEquals("silicon", make.resolveTarget("SILICON"), "case-insensitive");
        assertEquals("phase-fabric", make.resolveTarget("phase"), "alias resolves");
        assertEquals("unit:flare", make.resolveTarget("unit:flare"), "unit form resolves");
        assertNull(make.resolveTarget("notarealthing"), "unknown target → null (no transition)");
    }
}
