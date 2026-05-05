package org.jorigin.geotools.map;

import java.util.ArrayList;
import java.util.List;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.geometry.jts.ReferencedEnvelope;

/**
 * Helper for the Pseudo-Mercator ("PM") quadtree tile grid in
 * {@code EPSG:3857} — the de-facto standard for web tile services
 * (OSM, Google, IGN Geoplateforme WMTS "PM" / WMS-R).
 *
 * <p>Used by {@link org.jorigin.geotools.CachedRendererJFX} to split WMS-R
 * {@code GetMap} requests into 256×256 grid-aligned tiles, so the server's
 * tile cache is hit reliably and the identical URLs can be shared across
 * pan/zoom operations (URL-keyed client cache).
 *
 * <h2>Grid parameters</h2>
 * <ul>
 *   <li>Origin (top-left): {@code (-20037508.3427892, +20037508.3427892)}</li>
 *   <li>Tile size: 256 px</li>
 *   <li>Zoom 0 resolution: 156543.0339280410 m/px</li>
 *   <li>Each zoom level halves the resolution (doubles the tile count per axis)</li>
 * </ul>
 */
public final class PMTileGrid {

    /** Origin X (minimum easting) of the PM grid, in EPSG:3857. */
    public static final double ORIGIN_X = -20037508.342789244;

    /** Origin Y (maximum northing) of the PM grid, in EPSG:3857. */
    public static final double ORIGIN_Y =  20037508.342789244;

    /** Pixel resolution at zoom level 0 (meters/pixel). */
    public static final double BASE_RES_MPP = 156543.03392804097;

    /** Tile size in pixels. */
    public static final int TILE_SIZE_PX = 256;

    /** Clamp for the selected zoom level (most servers support up to ~22). */
    public static final int MAX_LEVEL = 22;

    /** Safety cap on the number of tiles returned per request. */
    public static final int MAX_TILES_PER_REQUEST = 256;

    private PMTileGrid() {}

    /**
     * Picks the PM zoom level whose pixel resolution is closest to the
     * view's pixel-to-world ratio, so tiles are fetched at the right
     * detail level without over- or under-sampling.
     *
     * @param worldPerPixel the view's resolution in world-units/pixel
     *                      (typically {@code envelope.getWidth() / viewWidth})
     * @return zoom level clamped to {@code [0, MAX_LEVEL]}
     */
    public static int levelFor(double worldPerPixel) {
        if (worldPerPixel <= 0.0 || !Double.isFinite(worldPerPixel)) {
            return 0;
        }
        int z = (int) Math.round(Math.log(BASE_RES_MPP / worldPerPixel) / Math.log(2.0));
        if (z < 0) return 0;

        return Math.min(z, MAX_LEVEL);
    }

    /**
     * get the pixel resolution (m/px) at the given zoom level.
     * @param level the zoom level
     * @return the pixel resolution (m/px) at the given zoom level
     */
    public static double resolutionAt(int level) {
        return BASE_RES_MPP / (double) (1L << level);
    }

    /**
     * Get the world-unit span covered by a single tile at the given zoom level.
     * @param level the zoom level
     * @return the world-unit span covered by a single tile at the given zoom level
     */
    public static double tileSpanAt(int level) {
        return TILE_SIZE_PX * resolutionAt(level);
    }

    /**
     * Enumerates all PM tiles at {@code level} that overlap {@code bbox}.
     * The returned tiles are grid-aligned (their extents are snapped to the
     * PM grid, independent of the exact query bbox), which is what makes
     * their URLs stable across pan/zoom and hittable in the WMS-R tile cache.
     *
     * <p>Stops early if the request would produce more than
     * {@link #MAX_TILES_PER_REQUEST} tiles (defensive cap against wrong zoom
     * levels or absurdly large bounding boxes) and returns an empty list.
     *
     * @param bbox  the query envelope; must carry an EPSG:3857 CRS
     *              (caller's responsibility to check)
     * @param level the zoom level (see {@link #levelFor(double)})
     * @return the overlapping tiles, empty if none or if capped out
     */
    public static List<PMTile> tilesForBbox(ReferencedEnvelope bbox, int level) {
        double span = tileSpanAt(level);
        // Subtract epsilon on the max side so a bbox that lands exactly on a
        // tile edge doesn't also claim the next tile over.
        double eps = span * 1e-9;
        int colMin = (int) Math.floor((bbox.getMinX() - ORIGIN_X)       / span);
        int colMax = (int) Math.floor((bbox.getMaxX() - ORIGIN_X - eps) / span);
        int rowMin = (int) Math.floor((ORIGIN_Y - bbox.getMaxY())       / span);
        int rowMax = (int) Math.floor((ORIGIN_Y - bbox.getMinY() - eps) / span);
        if (colMax < colMin || rowMax < rowMin) {
            return List.of();
        }
        long cols = (long) colMax - colMin + 1;
        long rows = (long) rowMax - rowMin + 1;
        if (cols * rows > MAX_TILES_PER_REQUEST) {
            return List.of();
        }
        CoordinateReferenceSystem crs = bbox.getCoordinateReferenceSystem();
        List<PMTile> out = new ArrayList<>((int) (cols * rows));
        for (int r = rowMin; r <= rowMax; r++) {
            double maxY = ORIGIN_Y - r * span;
            double minY = maxY - span;
            for (int c = colMin; c <= colMax; c++) {
                double minX = ORIGIN_X + c * span;
                double maxX = minX + span;
                out.add(new PMTile(level, c, r,
                        new ReferencedEnvelope(minX, maxX, minY, maxY, crs)));
            }
        }
        return out;
    }

    /**
     * A grid-aligned PM tile descriptor.
     *
     * @param level  PM zoom level
     * @param col    tile column index (0 = westmost at origin)
     * @param row    tile row index (0 = northmost at origin)
     * @param extent world envelope covered by this tile, in EPSG:3857
     */
    public record PMTile(int level, int col, int row, ReferencedEnvelope extent) {}
}
