package mindustry.client.tng.gen;

/**
 * Produces a factory-layout {@link mindustry.game.Schematic} for a {@link GenRequest}.
 *
 * <p><b>Contract (see the implementation-guide invariants):</b>
 * <ul>
 *   <li><b>Pure.</b> {@code generate} must not mutate world state ({@code Vars.world}/{@code Vars.state}),
 *       touch GL, or call into UI. This is what lets it run on a background thread. It is enforced
 *       structurally by keeping this package free of {@code arc.graphics} / {@code mindustry.gen.Call}
 *       imports.</li>
 *   <li><b>Deterministic.</b> The same request with the same {@link GenRequest#seed} must yield a
 *       byte-identical schematic.</li>
 *   <li><b>Model-driven.</b> No live simulation runs here; any predicted load is the model's estimate.</li>
 * </ul>
 */
public interface SchematicGenerator{
    GenResult generate(GenRequest req);
}
