package test;

import arc.util.*;
import mindustry.client.tng.gen.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.power.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-simulation flow test: builds a generated layout as real blocks in a headless world, supplies its
 * inputs + power via sandbox source blocks, ticks the real engine, and asserts the product actually comes
 * out the other end. This verifies the layout in the game's own simulation (belt directions, adjacency,
 * power) rather than just structurally — the "maximize engine reuse for accuracy" bar.
 */
public class MakeSimTests{
    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication();
    }

    /** Tick every building's proximity once, then run the world update N times (fixed delta). */
    void updateBlocks(int times){
        for(Tile t : world.tiles) if(t.build != null && t.isCenter()) t.build.updateProximity();
        for(int i = 0; i < times; i++){
            Time.update();
            for(Tile t : world.tiles) if(t.build != null && t.isCenter()) t.build.update();
        }
    }

    GenRequest req(String target, int w, int h){
        GenRequest r = new GenRequest();
        r.target = target; r.areaW = w; r.areaH = h;
        return r;
    }

    @Test
    void siliconLayoutActuallyProducesSilicon(){
        Time.setDeltaProvider(() -> 1f);
        Tiles tiles = world.resize(60, 60);
        world.beginMapLoad();
        tiles.fill();
        for(int x = 0; x < 60; x++) for(int y = 0; y < 60; y++) tiles.getn(x, y).setFloor((Floor)Blocks.stone);
        world.endMapLoad();
        state.set(State.playing);
        state.rules.limitMapArea = false;

        Schematic s = new FactoryArrayGenerator().generate(req("silicon", 6, 5)).schematic; // 2 smelters
        int ox = 20, oy = 20;

        // Build the generated layout as real blocks.
        for(Stile t : s.tiles) world.tile(ox + t.x, oy + t.y).setBlock(t.block, Team.sharded, t.rotation);

        // Feed each input belt (bottom row, local y=0) from below with an item source: coal on even
        // columns, sand on odd — each 2-wide smelter straddles one of each, matching its 1 coal : 2 sand recipe.
        for(Stile t : s.tiles){
            if(t.block == Blocks.conveyor && t.y == 0){
                Tile src = world.tile(ox + t.x, oy - 1);
                src.setBlock(Blocks.itemSource, Team.sharded, 1); // face up into the input belt
                src.build.configureAny((t.x % 2 == 0) ? Items.coal : Items.sand);
            }
        }

        // Power: wire a source + all smelters into one PowerGraph directly (the power-test idiom), so this
        // test isolates ITEM flow; whether the generator's power *nodes* auto-link in-game is tracked
        // separately (pasted nodes have no link config).
        Tile psrc = world.tile(ox - 2, oy);
        psrc.setBlock(Blocks.powerSource, Team.sharded);
        PowerGraph pg = new PowerGraph();
        pg.add(psrc.build);
        for(Stile t : s.tiles) if(t.block == Blocks.siliconSmelter) pg.add(world.tile(ox + t.x, oy + t.y).build);

        // Sink: a container just past the output lane's right end catches the silicon.
        int laneEndX = s.width - 1, laneY = 3;
        Tile sink = world.tile(ox + laneEndX + 1, oy + laneY);
        sink.setBlock(Blocks.container, Team.sharded);

        for(Tile t : world.tiles) if(t.build != null && t.isCenter()) t.build.updateProximity();
        for(int i = 0; i < 1200; i++){
            Time.update();
            pg.update();
            for(Tile t : world.tiles) if(t.build != null && t.isCenter()) t.build.update();
        }

        // The layout must actually produce silicon and deliver it through its own belts to the exit.
        int silicon = sink.build.items.get(Items.silicon);
        assertTrue(silicon > 0, "silicon should flow through the layout's belts into the sink; got " + silicon);
    }
}
