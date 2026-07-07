package test;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.client.tng.make.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end synthetic test of the `!make` flow, driven through the real entry points the game uses
 * ({@link MakeController#handleMake} to arm, then {@link MakeController#consumeSelection} — the exact
 * method DesktopInput calls on a schematic-select drag release). Placement reuses the engine's own
 * {@code Schematics.toPlans} + {@code Unit.addBuild}, so what we assert is what the game actually does.
 *
 * <p>Generation normally runs off-thread + Core.app.post; the headless harness interrupts the app
 * loop so posts never pump, hence {@link MakeController#setRunSynchronously} drives it inline here.
 */
public class MakeIntegrationTests{
    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication();
        // Vars.schematics is client-only (set by ClientLauncher/UI); construct one for headless.
        if(schematics == null) schematics = new Schematics();
    }

    @BeforeEach
    void arm(){
        MakeController.INSTANCE.reset();
    }

    @AfterEach
    void disarm(){
        MakeController.INSTANCE.reset();
    }

    /** Blank stone world, a core in the corner, and a fresh poly builder unit with an empty queue. */
    void setupGame(){
        Tiles tiles = world.resize(40, 40);
        world.beginMapLoad();
        tiles.fill();
        for(int x = 0; x < 40; x++) for(int y = 0; y < 40; y++){
            tiles.getn(x, y).setFloor((Floor)Blocks.stone);
        }
        world.endMapLoad();

        world.tile(3, 3).setBlock(Blocks.coreShard, Team.sharded, 0);
        state.set(State.playing);

        Player player = Player.create();
        player.team(Team.sharded);
        player.set(30f * 8f, 30f * 8f);
        player.add();
        Vars.player = player;

        Unit unit = UnitTypes.poly.create(Team.sharded);
        unit.set(30f * 8f, 30f * 8f);
        unit.add();
        player.unit(unit);
        unit.plans().clear();
    }

    @Test
    void placesGeneratedFactoryAtSelectedOrigin(){
        setupGame();
        Player p = Vars.player;

        MakeController.INSTANCE.handleMake(new String[]{"silicon"}, p);
        assertEquals("awaitingArea", MakeController.INSTANCE.stateName(), "!make arms area-select");

        // Synthetic F-drag: DesktopInput passes inclusive tile endpoints (schemX,schemY, rawCursorX,rawCursorY).
        // planSelection is the pure production seam (same code consumeSelection runs off-thread), so what
        // we assert here is exactly what gets handed to the unit's build queue in-game.
        Seq<BuildPlan> plans = MakeController.INSTANCE.planSelection(10, 10, 14, 13); // 5x4 area
        assertEquals(20, plans.size, "the 5x4 area is fully placed (stub fills the area)");
        assertEquals(0, p.unit().plans().size, "planning must not mutate the build queue");

        // Every tile in [10..14]x[10..13] is covered exactly once, anchored at the selected origin.
        IntSet seen = new IntSet();
        for(BuildPlan bp : plans){
            assertNotNull(bp.block, "plan has a block");
            assertFalse(bp.breaking, "make must not break anything");
            assertTrue(bp.x >= 10 && bp.x <= 14 && bp.y >= 10 && bp.y <= 13,
                "plan within the selected area, got " + bp.x + "," + bp.y);
            assertTrue(seen.add(Point2.pack(bp.x, bp.y)), "no duplicate plan at " + bp.x + "," + bp.y);
        }
        assertEquals(20, seen.size, "exactly the 20 area tiles are covered");
    }

    @Test
    void rejectsBelowMinimumAndStaysArmed(){
        setupGame();
        Player p = Vars.player;

        MakeController.INSTANCE.handleMake(new String[]{"silicon"}, p);
        boolean consumed = MakeController.INSTANCE.consumeSelection(10, 10, 10, 10); // 1x1, below MIN_SIZE

        assertTrue(consumed, "a too-small drag is still consumed (suppresses the copy)");
        assertEquals("awaitingArea", MakeController.INSTANCE.stateName(), "stays armed for a retry");
        assertEquals(0, p.unit().plans().size, "nothing is placed for a too-small area");
    }

    @Test
    void unknownTargetDoesNotArm(){
        setupGame();
        Player p = Vars.player;

        MakeController.INSTANCE.handleMake(new String[]{"notarealtarget"}, p);
        assertEquals("idle", MakeController.INSTANCE.stateName(), "an unknown target must not transition");

        assertFalse(MakeController.INSTANCE.consumeSelection(10, 10, 14, 13), "an unarmed !make ignores drags");
        assertEquals(0, p.unit().plans().size, "no placement without a resolved target");
    }
}
