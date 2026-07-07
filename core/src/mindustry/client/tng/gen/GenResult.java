package mindustry.client.tng.gen;

import mindustry.game.*;

/**
 * The outcome of a {@link SchematicGenerator#generate} call.
 *
 * <p>{@link #schematic} is present iff {@link #ok}. On failure, exactly one of the diagnostic fields is
 * meaningful: {@link #minViable} for an area-too-small failure, or {@link #predictedLoad} for a
 * constraint-conflict / under-target failure (the model's predicted saturation, for the heatmap
 * overlay). {@link #message} is always a one-line human summary — the command layer surfaces it locally.
 *
 * <p>{@code Schematic} is a pure game-model type (no graphics / no {@code Call}), so this stays within
 * the UI-free contract of this package.
 */
public final class GenResult{
    public boolean ok;
    /** Present iff {@link #ok}. */
    public Schematic schematic;
    /** One-line summary or failure reason. */
    public String message;
    /** Set when the failure is "area too small": the minimum viable W×H. */
    public IntRect minViable;
    /** Model-derived predicted per-edge utilization for the heatmap; may be {@code null}. */
    public EdgeLoad[] predictedLoad;

    /** Success: a schematic plus a one-line summary. */
    public static GenResult of(Schematic schematic, String message){
        GenResult r = new GenResult();
        r.ok = true;
        r.schematic = schematic;
        r.message = message;
        return r;
    }

    /** Failure with a plain message (no specific diagnostic). */
    public static GenResult fail(String message){
        GenResult r = new GenResult();
        r.ok = false;
        r.message = message;
        return r;
    }

    /** Failure because the selected area is below the minimum viable footprint. */
    public static GenResult tooSmall(IntRect minViable, String message){
        GenResult r = fail(message);
        r.minViable = minViable;
        return r;
    }
}
