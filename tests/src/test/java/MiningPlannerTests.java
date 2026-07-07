package test;

import arc.struct.*;
import mindustry.*;
import mindustry.client.tng.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.production.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Headless integration tests for the TNG algorithmic mining tool ({@link MiningPlanner}). */
public class MiningPlannerTests{
    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication();
    }

    /** Build a blank stone world, a core in a corner, and a player builder unit. */
    void setupGame(){
        setupGame(content.items().toArray(Item.class)); // stock everything by default
    }

    /** Same, but the core (and the tool's "ever loaded" memory) holds only [stocked] items. */
    void setupGame(Item... stocked){
        Tiles tiles = world.resize(40, 40);
        world.beginMapLoad();
        tiles.fill();
        for(int x = 0; x < 40; x++) for(int y = 0; y < 40; y++){
            tiles.getn(x, y).setFloor((Floor)Blocks.stone);
        }
        world.endMapLoad();

        world.tile(3, 3).setBlock(Blocks.coreShard, Team.sharded, 0);
        world.tile(3, 3).build.items.clear();   // don't inherit a prior test's stock
        for(Item item : stocked) world.tile(3, 3).build.items.set(item, 5000);
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

        MiningPlanner.INSTANCE.clearLearnedMaterials();   // don't leak materials across tests
        MiningPlanner.INSTANCE.trackCoreMaterials();
    }

    Seq<BuildPlan> plans = new Seq<>();

    int countOf(Class<?> blockType){
        int n = 0;
        for(BuildPlan p : plans){
            if(p.block != null && blockType.isInstance(p.block)) n++;
        }
        return n;
    }

    /** The item a drill plan would actually mine — mirrors Drill.countOre's dominant pick. */
    Item minedBy(BuildPlan p){
        Block b = p.block;
        int s = b.size, off = b.sizeOffset;
        ObjectIntMap<Item> counts = new ObjectIntMap<>();
        for(int fx = p.x + off; fx < p.x + off + s; fx++){
            for(int fy = p.y + off; fy < p.y + off + s; fy++){
                Tile t = world.tile(fx, fy);
                Item d = t == null ? null : t.drop();
                if(d != null) counts.increment(d, 0, 1);
            }
        }
        Item best = null; long bestKey = -1;
        for(ObjectIntMap.Entry<Item> e : counts){
            // engine priority: non-lowPriority, then higher count, then higher id
            long key = ((e.key.lowPriority ? 0L : 1L) << 40) | ((long)e.value << 12) | e.key.id;
            if(key > bestKey){ bestKey = key; best = e.key; }
        }
        return best;
    }

    boolean anyDrillOn(Item drop){
        for(BuildPlan p : plans) if(p.block instanceof Drill && minedBy(p) == drop) return true;
        return false;
    }

    void run(int x1, int y1, int x2, int y2){
        MiningPlanner.INSTANCE.arm(null);
        plans = MiningPlanner.INSTANCE.planSelection(x1, y1, x2, y2);
    }

    @Test
    void minesOreAndPlacesEnergyFreePumps(){
        setupGame();
        for(int x = 10; x <= 25; x++){
            for(int y = 12; y <= 25; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);
            for(int y = 10; y <= 11; y++) world.tile(x, y).setFloor((Floor)Blocks.water);
        }

        run(10, 10, 25, 25);

        assertTrue(countOf(Drill.class) > 0, "expected drills over the copper field");
        assertTrue(anyDrillOn(Items.copper), "drills should be mining copper");
        assertTrue(countOf(Pump.class) > 0, "expected pumps over the water");
        for(BuildPlan p : plans){
            if(p.block instanceof Pump) assertFalse(p.block.hasPower, "pump should be energy-free, got " + p.block.name);
        }
    }

    @Test
    void avoidsSandUnderThreshold(){
        setupGame();
        // ~50% sand / 50% copper -> below 95% threshold -> must NOT mine sand
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++){
            if(x <= 17) world.tile(x, y).setFloor((Floor)Blocks.sand);
            else world.tile(x, y).setOverlay(Blocks.oreCopper);
        }

        run(10, 10, 25, 25);

        assertTrue(anyDrillOn(Items.copper), "should mine the copper half");
        assertFalse(anyDrillOn(Items.sand), "must not place drills that mine sand below 95% sand");
    }

    @Test
    void minesSandWhenOverwhelminglySand(){
        setupGame();
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++){
            world.tile(x, y).setFloor((Floor)Blocks.sand);
        }

        run(10, 10, 25, 25);

        assertTrue(countOf(Drill.class) > 0, "expected drills");
        assertTrue(anyDrillOn(Items.sand), "should mine sand when the area is >95% sand");
    }

    @Test
    void minesOnlyRequestedElement(){
        setupGame();
        // half copper, half lead — ask for lead only
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++){
            world.tile(x, y).setOverlay(x <= 17 ? Blocks.oreCopper : Blocks.oreLead);
        }

        MiningPlanner.INSTANCE.arm("lead");
        plans = MiningPlanner.INSTANCE.planSelection(10, 10, 25, 25);

        assertTrue(anyDrillOn(Items.lead), "should mine the requested lead");
        assertFalse(anyDrillOn(Items.copper), "should not mine copper when only lead requested");
    }

    Block drillBlockUsed(){
        for(BuildPlan p : plans) if(p.block instanceof Drill) return p.block;
        return null;
    }

    @Test
    void copperUsesBasicDrillWhenNotVolumeReady(){
        setupGame(Items.copper); // only copper loaded -> no blast drill, no plastanium belt
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);

        run(10, 10, 25, 25);

        assertTrue(countOf(Drill.class) > 0, "expected drills");
        assertSame(Blocks.mechanicalDrill, drillBlockUsed(),
            "copper without blast+plastanium must use only the basic mechanical drill");
    }

    @Test
    void copperUsesBigDrillWhenVolumeReady(){
        setupGame(); // everything stocked -> blast drill + plastanium available
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);

        run(10, 10, 25, 25);

        assertTrue(countOf(Drill.class) > 0, "expected drills");
        assertNotSame(Blocks.mechanicalDrill, drillBlockUsed(),
            "copper with volume minimums available should use a bigger, higher-throughput drill");
    }

    @Test
    void sizesStubToMultipleLanesForHighVolume(){
        // copper only -> basic drills + only the basic conveyor (4.2/s) affordable; a big field
        // saturates well past one lane, so the output stub must be several lanes wide.
        setupGame(Items.copper);
        for(int x = 8; x <= 31; x++) for(int y = 8; y <= 31; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);

        run(8, 8, 31, 31); // no core inside selection -> stub path

        IntSet beltXs = new IntSet(), beltYs = new IntSet();
        int belts = 0;
        for(BuildPlan p : plans){
            if(p.block == Blocks.conveyor || p.block == Blocks.armoredConveyor){ belts++; beltXs.add(p.x); beltYs.add(p.y); }
        }
        assertTrue(belts > 0, "expected belt stub");
        // lanes stack along one axis; >1 distinct coordinate on that axis means multiple lanes
        assertTrue(Math.max(beltXs.size, beltYs.size) > 1, "high-volume field should yield a multi-lane stub");
    }

    @Test
    void doesNotCarpetEmptyRegionsWithBelts(){
        // L-shaped ore: a left strip and a bottom strip, but the upper-right quadrant is bare.
        // The bounding box still covers the whole selection, so the OLD full-width collector
        // lanes + full-height trunk would carpet the empty upper-right with dead belts. After
        // trimming, no belt should appear there (no drill feeds it, the trunk hugs the west edge).
        setupGame(Items.copper);
        for(int x = 10; x <= 29; x++) for(int y = 10; y <= 29; y++){
            if(x <= 13 || y <= 13) world.tile(x, y).setOverlay(Blocks.oreCopper);
        }

        run(10, 10, 29, 29); // core at (3,3) -> trunk on the west edge, exit south

        assertTrue(countOf(Drill.class) > 0, "expected drills over the ore");
        assertTrue(anyDrillOn(Items.copper), "should mine copper");
        for(BuildPlan p : plans){
            if(p.block == Blocks.conveyor || p.block == Blocks.armoredConveyor || p.block == Blocks.junction){
                assertFalse(p.x >= 22 && p.y >= 22,
                    "no belt should be laid in the bare upper-right region, got " + p.block.name + " at " + p.x + "," + p.y);
            }
        }
    }

    @Test
    void usesChainedBridgesForVolumeWhenStuckOnBasicBelts(){
        // copper + lead loaded (so item bridges are affordable) but NO titanium -> the fastest
        // plain belt is the basic conveyor (4.2/s). A big field saturates well past one basic belt,
        // so the trunk must use chained item bridges (~11/s per column) instead of many parallel
        // basic lanes -- narrower near the core. Bridges link to a neighbour to form the chain.
        setupGame(Items.copper, Items.lead);
        for(int x = 8; x <= 31; x++) for(int y = 8; y <= 31; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);

        run(8, 8, 31, 31);

        int bridges = 0, linked = 0;
        for(BuildPlan p : plans){
            if(p.block == Blocks.itemBridge){
                bridges++;
                if(p.config instanceof arc.math.geom.Point2) linked++;
            }
        }
        assertTrue(bridges > 0, "high-volume field on basic belts should build a chained-bridge trunk");
        assertTrue(linked > 0, "bridges should be linked into a chain (relative Point2 config)");
        // every bridge link must be axis-aligned and within range (or it would not connect)
        int range = ((mindustry.world.blocks.distribution.ItemBridge)Blocks.itemBridge).range;
        for(BuildPlan p : plans){
            if(p.block == Blocks.itemBridge && p.config instanceof arc.math.geom.Point2 pt){
                assertTrue((pt.x == 0 || pt.y == 0) && Math.abs(pt.x) + Math.abs(pt.y) <= range,
                    "bridge link must be straight and in range, got " + pt.x + "," + pt.y);
            }
        }
    }

    @Test
    void runsOnRealMapWithoutCrashing(){
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
        Team team = Team.sharded;
        Player player = Player.create();
        player.team(team); player.set(100f, 100f); player.add(); Vars.player = player;
        Unit u = UnitTypes.poly.create(team); u.set(100f, 100f); u.add(); player.unit(u);
        var coreb = Vars.state.teams.get(team).core();
        if(coreb != null) for(Item i : content.items()) coreb.items.set(i, 5000);
        MiningPlanner.INSTANCE.clearLearnedMaterials();
        MiningPlanner.INSTANCE.trackCoreMaterials();

        int ox = -1, oy = -1;
        for(int x = 0; x < world.width() && ox < 0; x++){
            for(int y = 0; y < world.height(); y++){
                Item d = world.tile(x, y).drop();
                if(d != null && d != Items.sand){ ox = x; oy = y; break; }
            }
        }
        if(ox < 0) return;
        int x1 = Math.max(0, ox - 8), y1 = Math.max(0, oy - 8);
        int x2 = Math.min(world.width() - 1, ox + 8), y2 = Math.min(world.height() - 1, oy + 8);

        MiningPlanner.INSTANCE.arm(null);
        assertDoesNotThrow(() -> MiningPlanner.INSTANCE.planSelection(x1, y1, x2, y2));
    }

    /** Simulate copper+lead being delivered into the core (an inbound increase, not just presence). */
    void deliverStarters(){
        world.tile(3, 3).build.items.add(Items.copper, 100);
        world.tile(3, 3).build.items.add(Items.lead, 100);
        MiningPlanner.INSTANCE.trackCoreMaterials();
    }

    @Test
    void gatesOtherOresUntilStartersDelivered(){
        // all stocked (so titanium drills are affordable) BUT nothing has been DELIVERED into
        // the core yet — the initial allocation doesn't count — so non-starter ores must wait.
        setupGame();
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++)
            world.tile(x, y).setOverlay(x <= 17 ? Blocks.oreCopper : Blocks.oreTitanium);

        run(10, 10, 25, 25);

        assertTrue(anyDrillOn(Items.copper), "copper (a starter) should be mined");
        assertFalse(anyDrillOn(Items.titanium), "titanium must wait until copper and lead are actively delivered");
    }

    @Test
    void minesOtherOresOnceStartersDelivered(){
        setupGame();        // baseline established
        deliverStarters();  // copper + lead now flowing into the core
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++)
            world.tile(x, y).setOverlay(x <= 17 ? Blocks.oreCopper : Blocks.oreTitanium);

        run(10, 10, 25, 25);

        assertTrue(anyDrillOn(Items.copper), "copper should be mined");
        assertTrue(anyDrillOn(Items.titanium), "titanium should be mined once the starters are delivered");
    }

    @Test
    void buildsSeparateNetworkPerOre(){
        setupGame(); // all materials
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++)
            world.tile(x, y).setOverlay(x <= 17 ? Blocks.oreCopper : Blocks.oreLead);

        run(10, 10, 25, 25);

        assertTrue(anyDrillOn(Items.copper), "should mine copper");
        assertTrue(anyDrillOn(Items.lead), "should mine lead (its own network)");
    }

    @Test
    void launchesToCoreWithMassDriver(){
        setupGame(); // all materials -> mass driver affordable, core (3,3) within range
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);

        run(10, 10, 25, 25);

        int drivers = 0; boolean senderLinked = false;
        for(BuildPlan p : plans){
            if(p.block == Blocks.massDriver){ drivers++; if(p.config instanceof Integer) senderLinked = true; }
        }
        assertTrue(drivers >= 2, "expected a sender + receiver mass driver");
        assertTrue(senderLinked, "sender should link to the receiver via config");
    }

    @Test
    void wiresPowerToConsumers(){
        setupGame(); // all materials -> powered big drills + mass drivers
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);

        run(10, 10, 25, 25);

        int poles = 0;
        for(BuildPlan p : plans) if(p.block == Blocks.powerNode) poles++;
        assertTrue(poles > 0, "expected power poles wiring the power-consuming blocks");
    }

    @Test
    void buildsAroundExistingDrill(){
        setupGame();
        for(int x = 10; x <= 25; x++) for(int y = 10; y <= 25; y++){
            world.tile(x, y).setOverlay(Blocks.oreCopper);
        }
        world.tile(17, 17).setBlock(Blocks.laserDrill, Team.sharded, 0);
        Building existing = world.tile(17, 17).build;

        run(10, 10, 25, 25);

        for(BuildPlan p : plans){
            assertFalse(p.breaking, "tool must not break anything");
            if(p.block != null){
                Tile t = world.tile(p.x, p.y);
                if(t != null) assertNotSame(existing, t.build, "must not overlap the existing drill");
            }
        }
        assertSame(existing, world.tile(17, 17).build, "existing drill must remain");
    }
}
