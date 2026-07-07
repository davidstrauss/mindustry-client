package mindustry.client.tng.gen;

/**
 * A tile-space rectangle with a bottom-left origin. Integer, deliberately minimal: this package is
 * UI-free (see the generator invariants) so it does not depend on {@code arc.math.geom.Rect}.
 */
public final class IntRect{
    public int x, y, w, h;

    public IntRect(){}

    public IntRect(int x, int y, int w, int h){
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    @Override
    public String toString(){
        return "IntRect(" + x + "," + y + " " + w + "x" + h + ")";
    }
}
