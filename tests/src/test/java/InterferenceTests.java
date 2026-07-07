package test;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.client.tng.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the reusable {@link Interference} contamination-avoidance library. */
public class InterferenceTests{
    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication();
    }

    void blankWorld(){
        Tiles tiles = world.resize(20, 20);
        world.beginMapLoad();
        tiles.fill();
        for(int x = 0; x < 20; x++) for(int y = 0; y < 20; y++) tiles.getn(x, y).setFloor((Floor)Blocks.stone);
        world.endMapLoad();
        state.set(State.playing);
    }

    @Test
    void detectsSideItemContamination(){
        blankWorld();
        world.tile(5, 6).setBlock(Blocks.router, Team.sharded, 0); // 1x1 foreign item source, north of (5,5)
        IntSet ours = new IntSet();

        // flowing east: north & south are the perpendicular sides -> (5,6) is a side -> contaminated
        assertTrue(Interference.INSTANCE.sideContaminated(5, 5, 0, true, ours));
        // flowing north: (5,6) is the front (flow axis), not a side -> not side-contaminated
        assertFalse(Interference.INSTANCE.sideContaminated(5, 5, 1, true, ours));
    }

    @Test
    void ignoresOwnTiles(){
        blankWorld();
        world.tile(5, 6).setBlock(Blocks.router, Team.sharded, 0);
        IntSet ours = new IntSet();
        ours.add(Point2.pack(5, 6)); // it's ours -> not foreign -> no contamination
        assertFalse(Interference.INSTANCE.sideContaminated(5, 5, 0, true, ours));
    }

    @Test
    void safeConveyorPicksArmoredOnlyWhenRiskyAndAffordable(){
        assertSame(Blocks.armoredConveyor,
            Interference.INSTANCE.safeConveyor(Blocks.conveyor, true, b -> true));
        assertSame(Blocks.conveyor,
            Interference.INSTANCE.safeConveyor(Blocks.conveyor, false, b -> true));
        assertSame(Blocks.conveyor,
            Interference.INSTANCE.safeConveyor(Blocks.conveyor, true, b -> false));
    }

    @Test
    void liquidSourceDetectedSeparatelyFromItems(){
        blankWorld();
        world.tile(5, 6).setBlock(Blocks.conduit, Team.sharded, 0); // 1x1 foreign liquid carrier
        IntSet ours = new IntSet();

        assertTrue(Interference.INSTANCE.adjacentForeignSource(5, 5, false, ours), "liquid source should be seen");
        assertFalse(Interference.INSTANCE.adjacentForeignSource(5, 5, true, ours), "a conduit is not an item source");
    }
}
