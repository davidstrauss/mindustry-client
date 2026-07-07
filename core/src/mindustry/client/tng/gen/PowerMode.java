package mindustry.client.tng.gen;

/**
 * How a generated layout is powered.
 *
 * <p>{@code external}: the layout assumes power is delivered from outside the selected area
 * (the generator lays no generators/nodes for its own supply). {@code onboard}: the layout must
 * generate and distribute its own power within the area.
 */
public enum PowerMode{
    external,
    onboard
}
