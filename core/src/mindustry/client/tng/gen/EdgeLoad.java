package mindustry.client.tng.gen;

/**
 * Model-derived predicted utilization of a single transport edge, for the failure/heatmap overlay.
 *
 * <p><b>Predicted, never measured.</b> No live simulation runs in the client (see invariant 2 in the
 * implementation guide); {@link #utilization} is what the generator's model expects the edge to carry,
 * normalized so {@code 1.0} means saturated. Measured throughput lives only in the offline test harness.
 *
 * <p>{@code (x, y)} is the tile the edge leaves from; {@link #dir} is the outgoing direction
 * (0=right, 1=up, 2=left, 3=down, matching Mindustry's rotation convention).
 */
public final class EdgeLoad{
    public int x, y;
    public int dir;
    public float utilization;

    public EdgeLoad(){}

    public EdgeLoad(int x, int y, int dir, float utilization){
        this.x = x;
        this.y = y;
        this.dir = dir;
        this.utilization = utilization;
    }
}
