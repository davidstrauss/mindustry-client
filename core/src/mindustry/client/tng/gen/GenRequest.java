package mindustry.client.tng.gen;

/**
 * An immutable-by-convention request to generate a factory layout for a single target.
 *
 * <p>Built incrementally by the {@code !make} command: the command fills {@link #target}, {@link #rate}
 * and the flag-driven knobs; the area-select step fills {@link #areaX}/{@link #areaY}/{@link #areaW}/
 * {@link #areaH} and (via auto-detect) {@link #coreSide}. Once handed to
 * {@link SchematicGenerator#generate}, treat it as frozen — callers snapshot it before the (off-thread)
 * generation so camera movement mid-generation cannot shift the anchor.
 *
 * <p>{@link #target} is a canonical content id, generalized beyond any single resource: a craftable
 * item ("silicon", "phase-fabric") or a unit spec ("unit:flare:t5"). Resolution against the
 * content-dump alias table happens in the command layer, not here.
 */
public final class GenRequest{
    /** Canonical target id, e.g. "silicon", "phase-fabric", "unit:flare:t5". */
    public String target;
    /** Normalized target production rate; {@code 0} means "as much as the area fits". */
    public float rate;

    /** Tile origin (bottom-left) of the selected build area. */
    public int areaX, areaY;
    /** Tile size of the selected build area. */
    public int areaW, areaH;

    /** Edge facing the core, 0..3, or {@code null} to auto-detect from core adjacency. */
    public Integer coreSide;

    public boolean allowBridges = true;
    /** Block id ceiling for conveyor tier, or {@code null} for no cap. */
    public String conveyorTierCap;
    public PowerMode power = PowerMode.external;

    /** Determinism seed: same request + same seed must yield a byte-identical schematic. */
    public long seed;
}
