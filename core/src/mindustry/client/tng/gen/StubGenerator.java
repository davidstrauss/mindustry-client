package mindustry.client.tng.gen;

import arc.struct.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;

import static mindustry.Vars.*;

/**
 * A placeholder {@link SchematicGenerator} that ignores {@link GenRequest#target} and {@link GenRequest#rate}
 * and returns a solid rectangle of a cheap block sized to fit the requested area.
 *
 * <p>Its only jobs are to (a) let Tasks A–G be built and reviewed against the real paste pipeline before
 * the model generator exists, and (b) exercise the purity/determinism contract. It is deterministic
 * (no randomness; iteration order fixed) and touches no world/UI state.
 */
public final class StubGenerator implements SchematicGenerator{
    @Override
    public GenResult generate(GenRequest req){
        int w = clampDim(req.areaW);
        int h = clampDim(req.areaH);

        Seq<Stile> tiles = new Seq<>(w * h);
        // Row-major, fixed order → deterministic serialization.
        for(int ly = 0; ly < h; ly++){
            for(int lx = 0; lx < w; lx++){
                tiles.add(new Stile(Blocks.conveyor, lx, ly, null, (byte)0));
            }
        }

        Schematic schem = new Schematic(tiles, new StringMap(), w, h);
        return GenResult.of(schem, "stub");
    }

    /** Clamp a requested area dimension into a valid schematic extent: at least 1, at most the schematic cap. */
    private static int clampDim(int v){
        if(v < 1) return 1;
        return Math.min(v, maxSchematicSize);
    }
}
