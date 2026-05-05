package org.jorigin.geotools;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import javax.imageio.ImageIO;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jorigin.geotools.map.FXTiledWMSLayer;
import org.jorigin.geotools.map.FXWMTSMapLayer;
import org.jorigin.geotools.map.PMTileGrid;
import org.jorigin.geotools.map.PMTileGrid.PMTile;
import org.jorigin.geotools.renderer.GTRendererJFX;
import org.geotools.api.data.Query;
import org.geotools.api.feature.Feature;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.type.GeometryDescriptor;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.style.Fill;
import org.geotools.api.style.Graphic;
import org.geotools.api.style.GraphicalSymbol;
import org.geotools.api.style.LineSymbolizer;
import org.geotools.api.style.Mark;
import org.geotools.api.style.PointSymbolizer;
import org.geotools.api.style.PolygonSymbolizer;
import org.geotools.api.style.Rule;
import org.geotools.api.style.Style;
import org.geotools.api.style.Symbolizer;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.FeatureLayer;
import org.geotools.map.Layer;
import org.geotools.map.MapContent;
import org.geotools.ows.wms.WebMapServer;
import org.geotools.ows.wms.map.WMSLayer;
import org.geotools.ows.wms.request.GetMapRequest;
import org.geotools.ows.wmts.WebMapTileServer;
import org.geotools.ows.wmts.map.WMTSMapLayer;
import org.geotools.ows.wmts.request.GetTileRequest;
import org.geotools.referencing.CRS;
import org.geotools.tile.Tile;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.util.Duration;

/**
 * A GeoTools map renderer that draws directly onto a JavaFX {@link Canvas}
 * using the JavaFX {@link GraphicsContext} API, with a built-in caching layer
 * to reduce display latency during pan and zoom interactions.
 *
 * <h2>Rendering pipeline</h2>
 * <p>Iterates over the {@link MapContent} layers and handles each type:
 * <ul>
 *   <li>{@link FeatureLayer} — vector layers with SLD-based symbolization
 *       (polygon, line, point symbolizers); geometries are converted to
 *       JavaFX path commands via JTS {@link CoordinateSequence} traversal.</li>
 *   <li>{@link org.jorigin.geotools.map.FXTiledWMSLayer} — preferred WMS fast path:
 *       for WMS-R endpoints (tile-cached WMS), the buffer envelope is split
 *       into PM-grid-aligned tiles and one {@code GetMap} is issued per tile.
 *       Server returns cached bytes per tile; responses load in parallel via
 *       JavaFX async {@code Image}; the URL cache is shared with WMTS tiles.
 *       Requires {@code EPSG:3857}; falls back to the single-request path
 *       otherwise.</li>
 *   <li>{@link WMSLayer} — raster layers fetched from a WMS server via a
 *       {@code GetMap} request; the response image is loaded asynchronously
 *       and drawn onto the canvas when available.</li>
 *   <li>{@link FXWMTSMapLayer} — preferred WMTS fast path: each tile is
 *       fetched independently via JavaFX async {@code Image} loading
 *       (parallel HTTP) and drawn on arrival; per-tile URL cache
 *       (see {@link #wmsImageCache}) makes repeated pans hit memory only.</li>
 *   <li>{@link WMTSMapLayer} — fallback WMTS path for stock layers that don't
 *       expose their server: the {@link GridCoverage2DReader} is read on a
 *       background thread (sequential tile fetch inside the reader) and the
 *       resulting image is drawn when available.</li>
 * </ul>
 *
 * <h2>Overpaint buffer</h2>
 * <p>Each render request paints a larger world area than strictly needed
 * (controlled by {@link #OVERPAINT_FACTOR}): the rendered region extends
 * {@value #OVERPAINT_FACTOR}×width and {@value #OVERPAINT_FACTOR}×height
 * beyond the visible envelope on every side.  A {@link WritableImage} snapshot
 * of this buffer is retained after each render.
 *
 * <p>Subsequent {@link #paint} calls whose target area falls entirely within
 * the buffered region are served instantly by cropping and scaling the
 * snapshot.  A buffer refresh is only triggered when the view has consumed
 * more than half of the overpaint margin in any direction (see
 * {@link #needsBufferRefresh}).
 *
 * <h2>Tile cache (two tiers)</h2>
 * <p>WMS {@code GetMap} and WMTS {@code GetTile} responses are cached by
 * request URL across two layers:
 * <ul>
 *   <li><b>Memory</b> — access-order LRU map whose budget is expressed in
 *       <em>decoded bytes</em> (see {@link #DEFAULT_WMS_CACHE_BYTES} and
 *       {@link #CachedRendererJFX(long)}).  Byte-sized budgeting keeps real
 *       memory use predictable regardless of tile content density (ocean
 *       vs. urban PNGs decompress to the same RGBA size).  Synchronous hits.</li>
 *   <li><b>Disk</b> — persistent PNG store under
 *       {@link #DEFAULT_PERSISTENT_CACHE_DIR} (overridable via the
 *       {@link #PERSISTENT_CACHE_DIR_PROPERTY} system property).  Active by
 *       default; toggle via {@link #setPersistentCacheActive(boolean)} or
 *       globally via {@link #PERSISTENT_CACHE_PROPERTY}.  A memory miss probes
 *       the filesystem before going to the network, so the second launch of an
 *       application opens with previously-rendered tiles already available.</li>
 * </ul>
 * Because tile URLs include dimensions and bbox parameters, the key is
 * already dimension-specific.
 *
 * <h2>Thread model</h2>
 * <p>{@link #paint} must be called on the JavaFX application thread.
 * {@link #stopRendering()} may be called from any thread.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   CachedRendererJFX renderer = new CachedRendererJFX();
 *   pane.getChildren().add(renderer.getCanvas());
 *   renderer.paint(mapContent, 800, 600, envelope);
 *   // call renderer.dispose() when the pane is discarded
 * }</pre>
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 */
public class CachedRendererJFX implements GTRendererJFX {

    private static final Logger LOG = Logger.getLogger(CachedRendererJFX.class.getName());

    // -----------------------------------------------------------------------
    // Caching constants
    // -----------------------------------------------------------------------

    /**
     * Fraction of the view width/height added on each side when rendering the
     * overpaint buffer.  0.5 means the buffer covers 2× the visible width and
     * 2× the visible height, giving a full "screen" of margin in every
     * direction before a re-render is needed.
     */
    public static final double OVERPAINT_FACTOR = 0.5;

    /**
     * Default decoded-byte budget for the WMS/WMTS tile image cache: 128 MB,
     * which holds ~500 PM tiles at 256×256 RGBA once decoded.  Generous enough
     * to cover the current view, its overpaint margin, and several zoom levels
     * worth of prefetched ({@link #prefetchTimer}) neighbors without evicting
     * still-relevant entries.
     *
     * <p>Dimensioning in bytes rather than entry count
     * prevents the cache's real memory footprint from drifting with tile
     * content density. A PNG of an urban tile decodes to the same 256 KB in
     * RGBA as an ocean tile, even though their compressed sizes differ wildly.
     */
    public static final long DEFAULT_WMS_CACHE_BYTES = 128L * 1024 * 1024;

    /**
     * Approximate decoded footprint of a 256×256 RGBA tile, used to translate
     * the legacy entry-count constructor into a byte budget and as a fallback
     * when an {@link Image}'s dimensions are not yet populated.
     */
    private static final long ESTIMATED_TILE_BYTES = 256L * 256L * 4L;

    /**
     * Number of WMS response images kept in the LRU URL cache.
     *
     * @deprecated Dimensioning in entry count drifts with tile content
     *   density.  Prefer {@link #DEFAULT_WMS_CACHE_BYTES} and the byte-budget
     *   constructor {@link #CachedRendererJFX(long)}.
     */
    @Deprecated
    public static final int DEFAULT_WMS_CACHE_SIZE = 32;

    /**
     * Default location of the persistent (disk) tile cache:
     * {@code <java.io.tmpdir>/.geotools/cache}.  Survives JVM restarts so the
     * second launch of an application finds previously-fetched tiles already
     * available.  Cleared via {@link #clearPersistentCache()}.
     */
    public static final Path DEFAULT_PERSISTENT_CACHE_DIR =
            Paths.get(System.getProperty("java.io.tmpdir"), ".geotools", "cache");

    /**
     * Name of the system property that controls whether the persistent disk
     * cache is enabled by default in newly-constructed renderers.
     * <ul>
     *   <li>{@code -Dorg.geotools.renderer.cached.persistant=true} — enable (same as default).</li>
     *   <li>{@code -Dorg.geotools.renderer.cached.persistant=false} — disable globally.</li>
     *   <li>property not set — enabled (default behavior).</li>
     * </ul>
     * Read once per renderer at construction time; runtime overrides via
     * {@link #setPersistentCacheActive(boolean)} take precedence afterward.
     */
    public static final String PERSISTENT_CACHE_PROPERTY = "org.geotools.renderer.cached.persistant";

    /**
     * Name of the system property that overrides the persistent cache
     * directory.  When set to a usable filesystem path (existing directory or
     * a path that can be created), the renderer stores its disk cache there
     * instead of {@link #DEFAULT_PERSISTENT_CACHE_DIR}.  When unset, blank,
     * pointing at a non-directory, or otherwise unusable, the default applies
     * and a warning is logged.
     *
     * <p>Read once per renderer at construction time.
     */
    public static final String PERSISTENT_CACHE_DIR_PROPERTY = "org.geotools.renderer.cached.dir";

    /**
     * Multiplier applied to view width/height to obtain buffer pixel dimensions.
     * Derived from {@link #OVERPAINT_FACTOR}: view × (1 + 2 × factor).
     */
    private static final double BUFFER_SCALE_FACTOR = 1.0 + 2.0 * OVERPAINT_FACTOR;

    /**
     * Fraction of the overpaint margin that may be consumed before triggering
     * a buffer refresh.  0.5 means a refresh is triggered when the view has
     * moved more than half the original margin toward any buffer edge.
     */
    private static final double REFRESH_THRESHOLD = 0.5;

    /**
     * Maximum relative deviation between the view's pixel-to-world scale and
     * the buffer's pixel-to-world scale before the buffer is considered stale
     * and {@link #compositeFromBuffer} forces a re-render.  0.25 means a 25 %
     * scale change (zoom in/out) invalidates the buffer — below that threshold
     * the cached snapshot is reused, above it raster tiles are re-fetched at
     * the new zoom level.
     */
    private static final double SCALE_TOLERANCE = 0.25;

    /**
     * Minimum remaining overpaint margin (as a fraction of view size) that must
     * be present on every side before a buffer refresh is triggered.
     * Equals {@code OVERPAINT_FACTOR × REFRESH_THRESHOLD}.
     */
    private static final double REFRESH_MARGIN_FACTOR = OVERPAINT_FACTOR * REFRESH_THRESHOLD;

    /**
     * Idle delay before a speculative prefetch pass kicks in, in milliseconds.
     * Each {@link #paint} call restarts the timer, so the prefetch only runs
     * when the user has stopped panning/zooming for at least this long —
     * ensuring we don't compete with the main render's HTTP pool.
     */
    private static final int PREFETCH_IDLE_DELAY_MS = 500;

    /**
     * Max number of prefetch tile requests issued per zoom level per idle burst.
     * Caps network pressure and prevents the LRU cache from being overrun with
     * prefetched tiles that push out still-relevant ones at the current level.
     */
    private static final int PREFETCH_MAX_TILES_PER_LEVEL = 8;

    // -----------------------------------------------------------------------
    // Rendering constants
    // -----------------------------------------------------------------------

    /** SRS code used when the map envelope carries no CRS information. */
    private static final String FALLBACK_SRS = "EPSG:4326";

    /** MIME type requested from WMS servers. */
    private static final String WMS_FORMAT = "image/png";

    /** SRS code of the Pseudo-Mercator grid used by the client-side tiling and prefetch paths. */
    private static final String PM_GRID_SRS = "EPSG:3857";

    /** Pre-stringified tile pixel size, used in the {@code WIDTH}/{@code HEIGHT} GetMap parameters. */
    private static final String TILE_SIZE_PX_STR = String.valueOf(PMTileGrid.TILE_SIZE_PX);

    private static final FilterFactory FF = CommonFactoryFinder.getFilterFactory();

    /** Cache: maps CRS instances to their resolved SRS string so the EPSG scan runs at most once. */
    private static final ConcurrentHashMap<CoordinateReferenceSystem, String> SRS_CODE_CACHE =
            new ConcurrentHashMap<>();

    private static final Color DEFAULT_FILL         = Color.GRAY;
    private static final Color DEFAULT_STROKE       = Color.BLACK;
    private static final Color DEFAULT_POINT_FILL   = Color.BLUE;
    private static final Color DEFAULT_POINT_STROKE = Color.DARKGRAY;

    // -----------------------------------------------------------------------
    // Display canvas
    // -----------------------------------------------------------------------

    /** The canvas shown in the scene graph — add this to the pane. */
    private final Canvas displayCanvas = new Canvas();

    // -----------------------------------------------------------------------
    // Buffer canvas (off-screen rendering target)
    // -----------------------------------------------------------------------

    /**
     * Off-screen canvas onto which the expanded area is rendered.
     * Its content is snapshotted into {@link #overpaintBuffer} after each render.
     */
    private final Canvas bufferCanvas = new Canvas();

    // -----------------------------------------------------------------------
    // Rendering state
    // -----------------------------------------------------------------------

    /** Interrupted by {@link #stopRendering()} to abort feature iteration. */
    private volatile boolean stop;

    /**
     * Incremented at the start of each {@link #renderToBuffer} call.
     * WMS image callbacks compare their captured value against this field
     * to discard stale responses.
     */
    private volatile long renderGeneration;

    /**
     * Tiles whose async load is currently in flight for the <em>current</em>
     * {@link #renderGeneration}.  At the start of every new render pass we
     * call {@link Image#cancel()} on every entry — loads for a superseded
     * view are aborted, freeing up JavaFX's image-I/O thread pool and the
     * HTTP bandwidth for the new view's tiles.
     *
     * <p>Entries are also self-removed on completion (success or error) by
     * their progress / error listeners so the list doesn't grow unboundedly
     * during a long-running render.
     *
     * <p>All access happens on the FX thread, so no synchronization needed.
     * Identity-based hashing ({@link Image} doesn't override {@code equals}/
     * {@code hashCode}) gives O(1) add/remove regardless of size — important
     * because every async-tile completion calls {@code remove}, and a fast
     * pan can have dozens of tiles in flight.
     */
    private final Set<Image> inFlightTiles = new HashSet<>();

    /**
     * Debounce timer for speculative prefetch: every {@link #paint} call
     * restarts it via {@code playFromStart()}, so the prefetch only fires
     * after {@link #PREFETCH_IDLE_DELAY_MS} of user inactivity.  When it
     * fires it pre-loads tiles at levels {@code z-1} and {@code z+1} into the
     * URL cache, so the next scroll-wheel zoom finds them ready — covered
     * immediately by the adjacent-level placeholder pass in {@link #paintTiledWMSLayer}
     * and drawn crisply instead of the stretched previous-buffer fallback.
     */
    private final PauseTransition prefetchTimer =
            new PauseTransition(Duration.millis(PREFETCH_IDLE_DELAY_MS));

    // -----------------------------------------------------------------------
    // Overpaint buffer state
    // -----------------------------------------------------------------------

    /**
     * Snapshot of {@link #bufferCanvas} after the most recent completed render
     * (sync vector pass + all async WMS passes).  Reused across snapshots to
     * avoid per-render allocation when dimensions are unchanged.
     */
    private WritableImage overpaintBuffer;

    /** World envelope covered by {@link #overpaintBuffer}. */
    private ReferencedEnvelope bufferArea;

    /**
     * Snapshot of the buffer from the previous completed render, retained
     * across a {@link #refreshBuffer} call and stretched onto the new buffer
     * canvas as a non-white placeholder while fresh tiles load.  Recycled as
     * the next snapshot target by {@link #promoteCurrentBufferToPrevious}
     * (zero-alloc swap).
     */
    private WritableImage previousBuffer;

    /** World envelope covered by {@link #previousBuffer}. */
    private ReferencedEnvelope previousArea;

    // -----------------------------------------------------------------------
    // Current view (retained for re-compositing when WMS images arrive)
    // -----------------------------------------------------------------------

    private double currentW;
    private double currentH;
    private ReferencedEnvelope currentArea;
    private MapContent currentContent;

    // -----------------------------------------------------------------------
    // WMS async debounce
    // -----------------------------------------------------------------------

    /**
     * Set to {@code true} when a deferred snapshot+composite has been posted
     * via {@code Platform.runLater} but not yet executed.  Prevents multiple
     * WMS image arrivals (one per layer) from each scheduling a redundant
     * snapshot — only the first schedules work; subsequent arrivals within
     * the same cycle are folded into the already-pending update.
     *
     * <p>Reset to {@code false} at the start of each {@link #paint} call so
     * that WMS responses for a new render are never blocked by a pending
     * update from a previous one.
     */
    private boolean bufferUpdatePending;

    // -----------------------------------------------------------------------
    // Performance counters
    // -----------------------------------------------------------------------
    //
    // All counters are AtomicLong so getters may be called from any thread
    // (JMX, perf HUD, logging) even though increments happen on the FX thread.
    // They're diagnostic only — no rendering logic depends on their values.

    /** Number of tile cache lookups that returned a ready {@link Image} in {@link #drawUrlTileIntoBuffer} or {@link #paintWMSLayer}. */
    private final AtomicLong cacheHits = new AtomicLong();

    /** Number of tile cache lookups that missed and triggered an async HTTP fetch. */
    private final AtomicLong cacheMisses = new AtomicLong();

    /** Number of completed {@link #refreshBuffer} calls. */
    private final AtomicLong bufferRefreshCount = new AtomicLong();

    /** Number of successfully-completed async image loads contributing to {@link #imageLoadTotalNanos}. */
    private final AtomicLong imageLoadCount = new AtomicLong();

    /** Sum of wall-clock load durations across successful async image loads, in nanoseconds. */
    private final AtomicLong imageLoadTotalNanos = new AtomicLong();

    // -----------------------------------------------------------------------
    // WMS image cache (LRU by access order, bounded in decoded bytes)
    // -----------------------------------------------------------------------

    /**
     * Access-order LRU map from tile URL to cached {@link Image}.  Eviction is
     * not driven by {@code LinkedHashMap.removeEldestEntry} (which is
     * entry-count based) but by {@link #evictUntilUnderBudget} which walks
     * the map in LRU order until {@link #cacheBytes} is under
     * {@link #cacheMaxBytes}.  Every insertion goes through
     * {@link #putIntoCache} to keep the byte counter accurate.
     */
    private final Map<String, Image> wmsImageCache;

    /** Maximum cumulative decoded size of entries in {@link #wmsImageCache}, in bytes. */
    private final long cacheMaxBytes;

    /** Running sum of decoded sizes of entries currently in {@link #wmsImageCache}. */
    private long cacheBytes;

    /**
     * Whether successful tile fetches are also written to {@link #persistentCacheDir}
     * for cross-session reuse, and whether memory misses probe disk before HTTP.
     * Initialized from the {@link #PERSISTENT_CACHE_PROPERTY} system property
     * (default: {@code true} when the property is unset); can be flipped at
     * runtime via {@link #setPersistentCacheActive(boolean)}.
     */
    private volatile boolean persistentCacheActive = defaultPersistentCacheActive();

    /**
     * Filesystem location of the disk cache.  Initialized from the
     * {@link #PERSISTENT_CACHE_DIR_PROPERTY} system property when it points
     * at a usable directory; otherwise {@link #DEFAULT_PERSISTENT_CACHE_DIR}.
     * Mutable at runtime via {@link #setPersistentCacheDirectory(Path)}.
     */
    private volatile Path persistentCacheDir = resolvePersistentCacheDir();

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Creates a renderer using the default {@value #DEFAULT_WMS_CACHE_BYTES}-byte tile cache. */
    public CachedRendererJFX() {
        this(DEFAULT_WMS_CACHE_BYTES);
    }

    /**
     * Creates a renderer with a custom tile cache byte budget.
     *
     * @param wmsCacheBytes maximum cumulative decoded size of cached tile
     *                      images, in bytes; pass 0 to disable caching.
     *                      Typical values range from 32 MB (very light use)
     *                      to 512 MB (heavy pan/zoom sessions).
     */
    public CachedRendererJFX(long wmsCacheBytes) {
        this.cacheMaxBytes = Math.max(0L, wmsCacheBytes);
        if (cacheMaxBytes > 0) {
            // removeEldestEntry stays disabled; eviction is byte-driven via
            // evictUntilUnderBudget() invoked from putIntoCache().  The
            // initial capacity 64 is a guess at typical steady-state entry
            // count for a 128 MB budget — Java rehashes as needed.
            wmsImageCache = new LinkedHashMap<>(64, 0.75f, /* accessOrder= */ true);
        } else {
            wmsImageCache = null;
        }
        prefetchTimer.setOnFinished(e -> runPrefetch());
    }

    /**
     * Creates a renderer with an entry-count cache budget.
     *
     * @param wmsCacheSize maximum number of WMS response images to keep in
     *                     the LRU cache; pass 0 to disable caching.
     *                     Translated internally to a byte budget via
     *                     {@link #ESTIMATED_TILE_BYTES}.
     * @deprecated Dimensioning in entry count makes real memory use
     *   unpredictable.  Prefer {@link #CachedRendererJFX(long)}.
     */
    @Deprecated
    public CachedRendererJFX(int wmsCacheSize) {
        this(Math.max(0L, wmsCacheSize) * ESTIMATED_TILE_BYTES);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns the display {@link Canvas}.  Add it to the JavaFX scene graph
     * to show the rendered map.
     */
    public Canvas getCanvas() {
        return displayCanvas;
    }

    /**
     * Renders the map content onto the display canvas at the given size and
     * world envelope.
     *
     * <ul>
     *   <li>If the requested area is fully covered by the overpaint buffer,
     *       the display is updated <em>immediately</em> from the cached snapshot
     *       (zero latency — no WMS request, no vector pass).  A buffer refresh
     *       is only scheduled when the view has consumed more than half the
     *       overpaint margin in any direction.</li>
     *   <li>On a buffer miss, a full buffer render is performed synchronously
     *       for vector layers, then the display is updated from the fresh buffer.</li>
     * </ul>
     *
     * <p><strong>Must be called on the JavaFX application thread.</strong>
     *
     * @param content the map content to render
     * @param w       target width in pixels
     * @param h       target height in pixels
     * @param area    the world-coordinate envelope to display
     */
    public void paint(MapContent content, double w, double h, ReferencedEnvelope area) {
        currentW       = w;
        currentH       = h;
        currentArea    = area;
        currentContent = content;
        // Restart the idle prefetch timer — only fires if the user stops
        // interacting for PREFETCH_IDLE_DELAY_MS.
        prefetchTimer.playFromStart();
        // Cancel any pending debounce from the previous render cycle so that
        // WMS responses for this new render are not suppressed.
        bufferUpdatePending = false;

        if (w != displayCanvas.getWidth() || h != displayCanvas.getHeight()) {
            displayCanvas.setWidth(w);
            displayCanvas.setHeight(h);
        }

        if (!compositeFromBuffer(w, h, area)) {
            GraphicsContext gc = displayCanvas.getGraphicsContext2D();
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, w, h);
            refreshBuffer(content, w, h, area);
            compositeFromBuffer(w, h, area);
        } else if (needsBufferRefresh(area)) {
            // Buffer hit but margin is running thin — refresh in the background.
            refreshBuffer(content, w, h, area);
        }
    }

    /**
     * Signals the current render to stop after the current feature.
     * Safe to call from any thread.
     */
    public void stopRendering() {
        stop = true;
    }

    /**
     * Discards the overpaint buffer, forcing a full re-render on the next
     * {@link #paint} call.  Call this when the map content changes in a way
     * that makes the buffer invalid (layer added / removed, style change, etc.).
     */
    public void invalidateBuffer() {
        overpaintBuffer = null;
        bufferArea      = null;
        previousBuffer  = null;
        previousArea    = null;
    }

    /**
     * Clears the WMS image URL cache.  Subsequent WMS requests will fetch
     * fresh images from the server.  No-op if WMS caching was disabled at
     * construction time.
     */
    public void clearWmsCache() {
        if (wmsImageCache != null) {
            wmsImageCache.clear();
            cacheBytes = 0L;
        }
    }

    /**
     * Enables or disables the persistent (on-disk) tile cache.  When enabled,
     * every successful HTTP tile fetch is also written to
     * {@link #DEFAULT_PERSISTENT_CACHE_DIR} as a PNG, and memory-cache misses
     * probe the disk before issuing an HTTP request — so the second launch
     * of the application finds previously-rendered tiles instantly.
     *
     * <p>The renderer's initial state is governed by the system property
     * {@link #PERSISTENT_CACHE_PROPERTY}; this setter overrides that default
     * for the lifetime of this instance.
     *
     * <p>Disabling does not delete existing files on disk; for that, call
     * {@link #clearPersistentCache()}.  In-flight async loads are unaffected.
     * @param active {@code true} if the persistent caching has to be activated and {@code false} otherwise
     */
    public void setPersistentCacheActive(boolean active) {
        this.persistentCacheActive = active;
    }

    /**
     * Get whether the persistent disk cache is currently active.
     * @return {@code true} if the persistent cache is active and {@code false} otherwise
     */
    public boolean isPersistentCacheActive() {
        return persistentCacheActive;
    }

    /**
     * Get the filesystem directory where persistent cache entries are stored.
     * @return the persistent cache directory
     */
    public Path getPersistentCacheDirectory() {
        return persistentCacheDir;
    }

    /**
     * Overrides the persistent cache directory for this renderer.  Subsequent
     * disk reads and writes target {@code dir} instead of the previous
     * location; pre-existing files at the old directory are <em>not</em>
     * migrated and remain accessible only after another override or
     * {@link #clearPersistentCache()} on the old path.
     *
     * <p>The directory itself is not created here — that happens lazily on
     * the first write so this setter is filesystem-side-effect free.
     *
     * @param dir the new cache directory; must not be {@code null}
     * @throws IllegalArgumentException if {@code dir} is {@code null} or
     *         points at an existing entry that is not a directory
     */
    public void setPersistentCacheDirectory(Path dir) {
        if (dir == null) {
            throw new IllegalArgumentException("persistent cache directory must not be null");
        }
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new IllegalArgumentException(
                    "persistent cache path exists and is not a directory: " + dir);
        }
        this.persistentCacheDir = dir;
    }

    /**
     * Removes every file under {@link #getPersistentCacheDirectory()}
     * asynchronously, so the FX thread is never blocked on disk I/O.  The
     * directory itself is preserved.  No-op if the directory doesn't exist.
     */
    public void clearPersistentCache() {
        if (!Files.exists(persistentCacheDir)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try (var stream = Files.walk(persistentCacheDir)) {
                stream.sorted(Comparator.reverseOrder())
                      .filter(p -> !p.equals(persistentCacheDir))
                      .forEach(p -> {
                          try {
                              Files.deleteIfExists(p);
                          } catch (IOException ignored) {
                              // Best-effort: a file may be locked by another
                              // process; the next clear will get it.
                          }
                      });
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to clear persistent cache", e);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Performance counter accessors
    // -----------------------------------------------------------------------

    /**
     * Get the number of tile cache lookups that returned a ready image (user-visible
     * render paths only — prefetch and adjacent-level placeholder lookups
     * are excluded to keep the metric reflective of actual render latency).
     * @return the number of tile cache lookups
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Get the number of tile cache lookups that missed and triggered an async fetch.
     * @return the number of tile cache lookups that missed
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Get the fraction of user-visible cache lookups served from cache, in
     * {@code [0.0, 1.0]}.  Returns {@code 0.0} before the first lookup.
     * A steady-state value above ~0.7 indicates the cache is well-sized and
     * the prefetch / placeholder optimizations are pulling their weight.
     * @return The fraction of user-visible cache lookups served from cache
     */
    public double getCacheHitRate() {
        long h = cacheHits.get();
        long m = cacheMisses.get();
        long total = h + m;
        return total == 0L ? 0.0 : (double) h / (double) total;
    }

    /**
     * Get the number of completed buffer refresh cycles since construction (or last reset).
     * @return The number of completed buffer refresh cycles since construction
     */
    public long getBufferRefreshCount() {
        return bufferRefreshCount.get();
    }

    /**
     * Get the current number of async image loads in flight (includes both main-render
     * and prefetch loads).  Drops to 0 between renders when the network
     * catches up; a persistently high value suggests the server is too slow
     * or the prefetch cap is too aggressive.
     * @return the current number of async image loads in flight
     */
    public int getTilesInFlight() {
        return inFlightTiles.size();
    }

    /**
     * Get the rolling average wall-clock load time across all successfully-completed
     * async image loads, in milliseconds.  Excludes canceled/errored/stale
     * loads so it reflects the server's effective response time, not client
     * churn.  Returns {@code 0.0} before the first successful load.
     * @return the rolling average wall-clock load time across all successfully-complete
     * async image loads
     */
    public double getAverageTileLoadMs() {
        long n = imageLoadCount.get();
        if (n == 0L) {
            return 0.0;
        }
        return imageLoadTotalNanos.get() / 1_000_000.0 / (double) n;
    }

    /**
     * Get the current cumulative decoded footprint of the tile cache, in bytes.
     * @return the current cumulative decoded footprint of the tile cache, in bytes
     */
    public long getCacheBytes() {
        return cacheBytes;
    }

    /**
     * Get the configured maximum cumulative decoded footprint of the tile cache, in bytes.
     * @return the configured maximum cumulative decoded footprint of the tile cache, in bytes
     */
    public long getCacheMaxBytes() {
        return cacheMaxBytes;
    }

    /**
     * Resets all performance counters to zero.  Useful for measuring a
     * specific operation (e.g. "how many tiles and buffer refreshes did this
     * zoom-out consume?") without the noise of prior activity.
     */
    public void resetCounters() {
        cacheHits.set(0L);
        cacheMisses.set(0L);
        bufferRefreshCount.set(0L);
        imageLoadCount.set(0L);
        imageLoadTotalNanos.set(0L);
    }

    /**
     * Returns a one-line human-readable summary of all perf counters,
     * suitable for logging, a status-bar overlay, or ad-hoc debugging.
     * Example output:
     * <pre>hits=142 misses=18 hitRate=88.7% refreshes=9 inFlight=0 avgLoad=76.3ms cache=34.2/128.0MB</pre>
     * @return a one-line human-readable summary of all perf counters
     */
    public String perfSummary() {
        return String.format(Locale.ROOT,
                "hits=%d misses=%d hitRate=%.1f%% refreshes=%d inFlight=%d "
                + "avgLoad=%.1fms cache=%.1f/%.1fMB",
                getCacheHits(), getCacheMisses(), getCacheHitRate() * 100.0,
                getBufferRefreshCount(), getTilesInFlight(),
                getAverageTileLoadMs(),
                cacheBytes / (1024.0 * 1024.0),
                cacheMaxBytes / (1024.0 * 1024.0));
    }

    // -----------------------------------------------------------------------
    // Internals – cache accounting
    // -----------------------------------------------------------------------

    /**
     * Inserts {@code image} into {@link #wmsImageCache} (or replaces the
     * existing entry), updates {@link #cacheBytes}, and evicts oldest entries
     * in LRU order until the running total is back under
     * {@link #cacheMaxBytes}.
     *
     * <p>All insertion paths — main-render tile arrival
     * ({@link #drawUrlTileIntoBuffer}, {@link #paintWMSLayer}) and prefetch
     * ({@link #issuePrefetch}) — go through this method so the byte counter
     * stays accurate.
     */
    private void putIntoCache(String url, Image image) {
        if (wmsImageCache == null) {
            return;
        }
        Image previous = wmsImageCache.put(url, image);
        if (previous != null) {
            cacheBytes -= estimateDecodedBytes(previous);
        }
        cacheBytes += estimateDecodedBytes(image);
        evictUntilUnderBudget();
    }

    /**
     * Walks {@link #wmsImageCache} in LRU (eldest-first) order, removing
     * entries until {@link #cacheBytes} is within {@link #cacheMaxBytes}.
     * Each removal subtracts the evicted image's estimated size from the
     * running total.  Stops early if the map empties (defensive — shouldn't
     * happen unless an individual image exceeds the whole budget).
     */
    private void evictUntilUnderBudget() {
        if (cacheBytes <= cacheMaxBytes) {
            return;
        }
        Iterator<Map.Entry<String, Image>> it = wmsImageCache.entrySet().iterator();
        while (it.hasNext() && cacheBytes > cacheMaxBytes) {
            Map.Entry<String, Image> eldest = it.next();
            cacheBytes -= estimateDecodedBytes(eldest.getValue());
            it.remove();
        }
        if (cacheBytes < 0L) {
            // Should not happen: insertions and evictions both go through
            // estimateDecodedBytes() on a fully-loaded Image, which is
            // deterministic.  A negative balance signals an accounting bug
            // (e.g. someone bypassing putIntoCache).
            LOG.log(Level.WARNING, "Tile cache byte counter went negative ({0}); resetting", cacheBytes);
            cacheBytes = 0L;
        }
    }

    /**
     * Returns the decoded RGBA footprint of {@code image} in bytes, or a
     * {@link #ESTIMATED_TILE_BYTES} fallback when dimensions aren't yet
     * populated (shouldn't happen in practice because we only cache on
     * {@code progressProperty == 1.0}, at which point dimensions are set).
     */
    private static long estimateDecodedBytes(Image image) {
        double w = image.getWidth();
        double h = image.getHeight();
        if (w > 0 && h > 0) {
            return (long) w * (long) h * 4L;
        }
        return ESTIMATED_TILE_BYTES;
    }

    // -----------------------------------------------------------------------
    // Internals – persistent (disk) cache
    // -----------------------------------------------------------------------

    /**
     * Reads the {@link #PERSISTENT_CACHE_PROPERTY} system property and returns
     * the initial value of {@link #persistentCacheActive} for a fresh renderer.
     * Unset → {@code true}; otherwise the strict {@link Boolean#parseBoolean}
     * interpretation (only the literal {@code "true"} maps to enabled).
     */
    private static boolean defaultPersistentCacheActive() {
        String prop = System.getProperty(PERSISTENT_CACHE_PROPERTY);
        return prop == null || Boolean.parseBoolean(prop);
    }

    /**
     * Resolves the {@link #PERSISTENT_CACHE_DIR_PROPERTY} system property to a
     * usable directory path, or falls back to {@link #DEFAULT_PERSISTENT_CACHE_DIR}.
     *
     * <p>The property value is rejected (with a warning log) when:
     * <ul>
     *   <li>it is not a syntactically valid path on the running OS;</li>
     *   <li>it points at an existing filesystem entry that is not a directory.</li>
     * </ul>
     * Otherwise the path is accepted; the directory itself is created lazily
     * by the first cache write, not here, to keep class initialization free
     * of filesystem side effects.
     */
    private static Path resolvePersistentCacheDir() {
        String prop = System.getProperty(PERSISTENT_CACHE_DIR_PROPERTY);
        if (prop == null || prop.isBlank()) {
            return DEFAULT_PERSISTENT_CACHE_DIR;
        }
        try {
            Path candidate = Paths.get(prop);
            if (Files.exists(candidate) && !Files.isDirectory(candidate)) {
                LOG.log(Level.WARNING,
                        "{0}={1} points at a non-directory; using default cache dir {2}",
                        new Object[]{PERSISTENT_CACHE_DIR_PROPERTY, prop, DEFAULT_PERSISTENT_CACHE_DIR});
                return DEFAULT_PERSISTENT_CACHE_DIR;
            }
            return candidate;
        } catch (java.nio.file.InvalidPathException e) {
            LOG.log(Level.WARNING,
                    "{0}={1} is not a valid path; using default cache dir {2}",
                    new Object[]{PERSISTENT_CACHE_DIR_PROPERTY, prop, DEFAULT_PERSISTENT_CACHE_DIR});
            return DEFAULT_PERSISTENT_CACHE_DIR;
        }
    }

    /**
     * Returns the disk-cache file path for {@code url}, derived from a SHA-1
     * hash of the URL string.  The first two hex characters are used as a
     * subdirectory level so the cache scales without putting tens of
     * thousands of files in a single directory (slow on Windows / NTFS).
     */
    private Path persistentCachePath(String url) {
        String hash = sha1Hex(url);
        return persistentCacheDir.resolve(hash.substring(0, 2)).resolve(hash + ".png");
    }

    /** Computes the SHA-1 hex digest of {@code s}. */
    private static String sha1Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(s.getBytes());
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandated by the JDK spec; this should never happen.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes {@code image} to the disk cache for {@code url} on a background
     * thread (PNG-encoded).  Silently swallows I/O errors — the disk cache
     * is opportunistic, never required for correctness.
     */
    private void writeImageToDiskAsync(String url, Image image) {
        Path target = persistentCachePath(url);
        // Snapshot pixels on the FX thread (the Image API is FX-thread-affine
        // for some operations); the AWT round-trip + I/O happen off-thread.
        BufferedImage bi = SwingFXUtils.fromFXImage(image, null);
        if (bi == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                ImageIO.write(bi, "png", tmp.toFile());
                // Atomic publish: a half-written .png is never visible to
                // a concurrent reader because we move only after write completes.
                try {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsup) {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Persistent cache write failed for " + url, e);
            }
        });
    }

    /**
     * Resolves a tile URL to the URL string the async loader should actually
     * fetch.  Returns the {@code file://...} URI of the disk-cached copy when
     * one exists and the persistent cache is active; otherwise the original
     * HTTP URL. Side effect free.
     */
    private String resolveLoadUrl(String httpUrl) {
        if (!persistentCacheActive) {
            return httpUrl;
        }
        Path file = persistentCachePath(httpUrl);
        if (Files.exists(file)) {
            return file.toUri().toString();
        }
        return httpUrl;
    }

    /**
     * Releases resources held by this renderer.  After calling this method
     * the renderer must not be used.
     */
    public void dispose() {
        stop = true;
        currentArea    = null; // prevents stale onBufferUpdated from compositing after dispose
        currentContent = null;
        prefetchTimer.stop();
        cancelInFlightTiles();
        invalidateBuffer();
        clearWmsCache();
    }

    /**
     * Cancels every tile load still in progress, freeing JavaFX's image-I/O
     * pool and HTTP bandwidth for the next render's tiles.
     */
    private void cancelInFlightTiles() {
        if (inFlightTiles.isEmpty()) {
            return;
        }
        // Copy first: Image.cancel() may synchronously fire the error listener
        // which would modify inFlightTiles mid-iteration.
        Image[] snapshot = inFlightTiles.toArray(new Image[0]);
        inFlightTiles.clear();
        for (Image img : snapshot) {
            try {
                img.cancel();
            } catch (Exception ignored) {
                // Image.cancel() shouldn't throw; if it does, keep cancelling the batch.
            }
        }
    }

    /**
     * Registers an async {@link Image} load: tracks it in {@link #inFlightTiles}
     * (so it's cancellable on the next render), wires self-removing
     * progress / error listeners, optionally records its load duration in the
     * perf counters, and invokes {@code onSuccess} on the FX thread when
     * loading completes successfully and the captured {@code generation} still
     * matches {@link #renderGeneration}.
     *
     * <p>Stale or errored loads are silently dropped (entry removed from
     * {@code inFlightTiles}, no {@code onSuccess} call, no perf accounting).
     *
     * @param generation the {@link #renderGeneration} value at issue time;
     *                   later mismatch makes the load stale
     * @param recordPerf whether the load's wall-clock time should contribute
     *                   to {@link #imageLoadCount} / {@link #imageLoadTotalNanos}
     *                   (false for prefetch, which runs idle and shouldn't
     *                   skew the user-visible latency average)
     */
    private void registerAsyncLoad(Image image, long generation,
                                   boolean recordPerf, Consumer<Image> onSuccess) {
        inFlightTiles.add(image);
        final long startNanos = recordPerf ? System.nanoTime() : 0L;
        ChangeListener<Number> listener = new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Number> obs,
                                Number old, Number progress) {
                if (progress.doubleValue() < 1.0) {
                    return;
                }
                image.progressProperty().removeListener(this);
                inFlightTiles.remove(image);
                if (image.isError() || renderGeneration != generation) {
                    return;
                }
                if (recordPerf) {
                    imageLoadTotalNanos.addAndGet(System.nanoTime() - startNanos);
                    imageLoadCount.incrementAndGet();
                }
                onSuccess.accept(image);
            }
        };
        image.progressProperty().addListener(listener);
        // Safety net: errors can arrive without driving progress to 1.0
        // (e.g. our own cancel()), so the progress listener must be removed
        // here too to avoid leaking.
        image.errorProperty().addListener((obs, wasError, isError) -> {
            if (isError) {
                image.progressProperty().removeListener(listener);
                inFlightTiles.remove(image);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Internals – buffer refresh decision
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the visible area has consumed more than
     * {@link #REFRESH_THRESHOLD} of the overpaint margin on any side, indicating
     * that a buffer refresh should be triggered before the margin is exhausted.
     */
    private boolean needsBufferRefresh(ReferencedEnvelope area) {
        if (bufferArea == null) {
            return true;
        }
        double marginW = area.getWidth()  * REFRESH_MARGIN_FACTOR;
        double marginH = area.getHeight() * REFRESH_MARGIN_FACTOR;
        return (area.getMinX() - bufferArea.getMinX()) < marginW
            || (bufferArea.getMaxX() - area.getMaxX()) < marginW
            || (area.getMinY() - bufferArea.getMinY()) < marginH
            || (bufferArea.getMaxY() - area.getMaxY()) < marginH;
    }

    // -----------------------------------------------------------------------
    // Internals – buffer refresh
    // -----------------------------------------------------------------------

    /**
     * Re-renders an expanded area into the buffer canvas and snapshots the
     * synchronous (vector) result.  WMS layers update the buffer asynchronously
     * via {@link #onBufferUpdated}.
     */
    private void refreshBuffer(MapContent content, double w, double h, ReferencedEnvelope area) {
        ReferencedEnvelope expandedArea = expand(area);
        double expW = Math.ceil(w * BUFFER_SCALE_FACTOR);
        double expH = Math.ceil(h * BUFFER_SCALE_FACTOR);
        // Move the current snapshot to the "previous" slot before rebuilding.
        // renderToBuffer() will stretch it onto the new buffer canvas as a
        // placeholder, avoiding white regions while async tiles load.
        promoteCurrentBufferToPrevious();
        renderToBuffer(content, expW, expH, expandedArea);
        snapshotBuffer(expandedArea);
        bufferRefreshCount.incrementAndGet();
    }

    /**
     * Swaps {@link #overpaintBuffer} into the {@link #previousBuffer} slot and
     * recycles the old previous {@link WritableImage} as the next snapshot
     * target — so a steady stream of refreshes allocates zero extra images.
     *
     * <p>{@link #bufferArea} is cleared on purpose: it signals to
     * {@link #compositeFromBuffer} and {@link #onBufferUpdated} that the
     * current buffer is mid-rebuild and must not be composited yet.  It is
     * re-populated by {@link #snapshotBuffer} at the end of {@link #refreshBuffer}.
     */
    private void promoteCurrentBufferToPrevious() {
        if (overpaintBuffer == null || bufferArea == null) {
            return;
        }
        WritableImage recycled = previousBuffer;
        previousBuffer = overpaintBuffer;
        previousArea   = bufferArea;
        overpaintBuffer = recycled;
        bufferArea      = null;
    }

    // -----------------------------------------------------------------------
    // Internals – buffer compositing
    // -----------------------------------------------------------------------

    /**
     * Draws the sub-region of {@link #overpaintBuffer} that corresponds to
     * {@code area} onto the display canvas, scaling it to fill {@code (w, h)}.
     *
     * @return {@code true} if the buffer covered the area and the display was
     *         updated; {@code false} if the buffer was absent or insufficient.
     */
    private boolean compositeFromBuffer(double w, double h, ReferencedEnvelope area) {
        if (overpaintBuffer == null || bufferArea == null) {
            return false;
        }
        if (bufferArea.getMinX() > area.getMinX() || bufferArea.getMaxX() < area.getMaxX()
                || bufferArea.getMinY() > area.getMinY() || bufferArea.getMaxY() < area.getMaxY()) {
            return false;
        }
        // Scale-change detection: when the view's pixel-to-world ratio diverges
        // too much from the buffer's ratio (zoom in/out), serving from the cache
        // would only stretch or compress the existing snapshot — raster layers
        // (WMS/WMTS) would stay at the previous zoom level.  Force a full
        // re-render so tiles are re-fetched at the new scale.
        double bufferPixW = overpaintBuffer.getWidth();
        double bufferPixH = overpaintBuffer.getHeight();
        double viewScale   = area.getWidth()       / w;
        double bufferScale = bufferArea.getWidth() / bufferPixW;
        if (Math.abs(viewScale / bufferScale - 1.0) > SCALE_TOLERANCE) {
            return false;
        }

        double scaleX = bufferPixW / bufferArea.getWidth();
        double scaleY = bufferPixH / bufferArea.getHeight();

        // Y-axis flipped: buffer pixel Y=0 corresponds to world maxY.
        double srcX = (area.getMinX() - bufferArea.getMinX()) * scaleX;
        double srcY = (bufferArea.getMaxY() - area.getMaxY()) * scaleY;
        double srcW = area.getWidth()  * scaleX;
        double srcH = area.getHeight() * scaleY;

        displayCanvas.getGraphicsContext2D()
                .drawImage(overpaintBuffer, srcX, srcY, srcW, srcH, 0, 0, w, h);
        return true;
    }

    /**
     * Stretches {@link #previousBuffer} onto the new buffer canvas so the
     * expanded area starts out filled with the most recent known imagery
     * (possibly scaled or partially cropped) rather than white.  Cached-tile
     * draws and async-tile arrivals then overwrite in place.
     *
     * <p>The previous snapshot is drawn at its world-correct position in the
     * new canvas's pixel space: world→pixel scaling uses the new area, and
     * the destination rect is computed from the overlap of the two envelopes
     * (JavaFX clips cleanly against canvas bounds when the previous envelope
     * partially overflows the new one).  A white wipe still runs first so
     * regions outside the previous envelope don't show canvas residue from
     * an earlier size/content.
     *
     * @return {@code true} if a previous buffer was painted; {@code false} if
     *         none was available (caller should apply its own white wipe).
     */
    private boolean paintPreviousBufferAsBackground(GraphicsContext gc,
                                                    double w, double h,
                                                    ReferencedEnvelope newArea) {
        if (previousBuffer == null || previousArea == null) {
            return false;
        }
        // CRS mismatch — previous imagery is meaningless in the new projection.
        CoordinateReferenceSystem newCrs = newArea.getCoordinateReferenceSystem();
        CoordinateReferenceSystem prevCrs = previousArea.getCoordinateReferenceSystem();
        if (newCrs != null && prevCrs != null && !newCrs.equals(prevCrs)) {
            return false;
        }
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, w, h);

        double scaleX = w / newArea.getWidth();
        double scaleY = h / newArea.getHeight();
        double dx = (previousArea.getMinX() - newArea.getMinX()) * scaleX;
        double dy = (newArea.getMaxY() - previousArea.getMaxY()) * scaleY;
        double dw = previousArea.getWidth()  * scaleX;
        double dh = previousArea.getHeight() * scaleY;
        gc.drawImage(previousBuffer, dx, dy, dw, dh);
        return true;
    }

    /**
     * Snapshots {@link #bufferCanvas} into {@link #overpaintBuffer}, reusing
     * the existing {@link WritableImage} when dimensions are unchanged.
     */
    private void snapshotBuffer(ReferencedEnvelope area) {
        if (bufferCanvas.getWidth() <= 0 || bufferCanvas.getHeight() <= 0) {
            return;
        }
        overpaintBuffer = bufferCanvas.snapshot(null, overpaintBuffer);
        bufferArea = area;
    }

    // -----------------------------------------------------------------------
    // Internals – WMS async callback
    // -----------------------------------------------------------------------

    /**
     * Called each time a WMS image is drawn onto the buffer canvas (on the FX thread).
     *
     * <p>Multiple WMS layers may each trigger this callback in the same render
     * cycle.  To avoid redundant snapshots the actual re-snapshot and display
     * update are deferred to the next FX pulse via {@code Platform.runLater},
     * and only the first arrival schedules work — subsequent arrivals within
     * the same cycle are folded into the already-pending update.
     *
     * <p>The lambda reads {@link #bufferArea} and friends at execution time
     * (not at scheduling time) so it always uses the metadata that matches the
     * canvas content that was just drawn, even if {@link #paint} has been
     * called in the interval.
     */
    private void onBufferUpdated() {
        if (bufferArea == null || bufferUpdatePending) {
            return;
        }
        bufferUpdatePending = true;
        final long gen = renderGeneration;
        Platform.runLater(() -> {
            bufferUpdatePending = false;
            ReferencedEnvelope area = bufferArea;
            if (area == null || renderGeneration != gen) {
                return;
            }
            snapshotBuffer(area);
            if (currentArea != null) {
                compositeFromBuffer(currentW, currentH, currentArea);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Internals – envelope expansion
    // -----------------------------------------------------------------------

    private static ReferencedEnvelope expand(ReferencedEnvelope area) {
        double dw = area.getWidth()  * OVERPAINT_FACTOR;
        double dh = area.getHeight() * OVERPAINT_FACTOR;
        return new ReferencedEnvelope(
                area.getMinX() - dw, area.getMaxX() + dw,
                area.getMinY() - dh, area.getMaxY() + dh,
                area.getCoordinateReferenceSystem());
    }

    // -----------------------------------------------------------------------
    // Internals – rendering pipeline
    // -----------------------------------------------------------------------

    /**
     * Renders {@code content} into {@link #bufferCanvas} at the given size and
     * world envelope.  This is the core rendering loop targeting the off-screen
     * buffer canvas.
     *
     * <p>Called on the FX thread; WMS images are loaded asynchronously and
     * will trigger {@link #onBufferUpdated} when they arrive.
     */
    private void renderToBuffer(MapContent content, double w, double h, ReferencedEnvelope area) {
        stop = false;
        ++renderGeneration;
        cancelInFlightTiles();

        GraphicsContext gc = bufferCanvas.getGraphicsContext2D();

        if (w != bufferCanvas.getWidth() || h != bufferCanvas.getHeight()) {
            bufferCanvas.setWidth(w);
            bufferCanvas.setHeight(h);
        }
        // Stretched previous snapshot as placeholder; cached/async tiles
        // overwrite in place.  Fallback white wipe also clears ghost artifacts
        // from removed layers or changed styles on same-size re-renders.
        if (!paintPreviousBufferAsBackground(gc, w, h, area)) {
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, w, h);
        }

        if (content == null || area == null || area.isEmpty()) {
            return;
        }

        ScreenTransform tx = ScreenTransform.of(w, h, area);

        for (Layer layer : content.layers()) {
            if (stop || !layer.isVisible()) {
                continue;
            }
            if (layer instanceof FXTiledWMSLayer ftwl) {
                // Must come before the WMSLayer branch — FXTiledWMSLayer extends
                // WMSLayer and the check order determines dispatch.
                paintTiledWMSLayer(area, ftwl);
            } else if (layer instanceof WMSLayer wl) {
                paintWMSLayer(area, wl);
            } else if (layer instanceof FXWMTSMapLayer fxl) {
                // Must come before the WMTSMapLayer branch — FXWMTSMapLayer
                // extends WMTSMapLayer and the check order determines dispatch.
                paintWMTSTiles(area, fxl);
            } else if (layer instanceof WMTSMapLayer wtl) {
                paintWMTSLayer(area, wtl);
            } else if (layer instanceof FeatureLayer fl) {
                paintFeatureLayer(gc, area, tx, fl);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internals – coordinate transform
    // -----------------------------------------------------------------------

    /**
     * Affine mapping from world coordinates to screen pixels, with Y-axis flip.
     * Scale factors are precomputed once and reused for every coordinate pair.
     */
    private record ScreenTransform(double scaleX, double scaleY,
                                   double minX, double minY, double h) {
        static ScreenTransform of(double w, double h, ReferencedEnvelope env) {
            return new ScreenTransform(
                    w / env.getWidth(), h / env.getHeight(),
                    env.getMinX(), env.getMinY(), h);
        }
        double toScreenX(double worldX) { return (worldX - minX) * scaleX; }
        double toScreenY(double worldY) { return h - (worldY - minY) * scaleY; }
    }

    // -----------------------------------------------------------------------
    // Internals – CRS helper
    // -----------------------------------------------------------------------

    /**
     * Returns the EPSG/authority identifier for the envelope's CRS, falling
     * back to {@value #FALLBACK_SRS} if none can be resolved.
     * Results are cached so the EPSG database scan runs at most once per unique CRS.
     */
    private static String lookupSrsCode(ReferencedEnvelope env) {
        CoordinateReferenceSystem crs = env.getCoordinateReferenceSystem();
        if (crs == null) {
            return FALLBACK_SRS;
        }
        return SRS_CODE_CACHE.computeIfAbsent(crs, CachedRendererJFX::resolveSrsCode);
    }

    /**
     * Resolves a CRS to an authority-prefixed SRS string (e.g. {@code "EPSG:4326"})
     * safe to pass to {@code FilterFactoryImpl.bbox()} and WMS {@code SRS} parameters.
     *
     * <p>Shapefiles loaded from ESRI {@code .prj} files carry identifiers such as
     * {@code "GCS_WGS_1984"} — no colon, not valid WKT — that cause a
     * {@code FactoryException} in {@code FilterFactoryImpl.bbox()}.
     * {@code CRS.toSRS()} returns these names as-is; only results that already
     * contain {@code ":"} (authority-prefixed form) are accepted directly.
     * Otherwise {@code CRS.lookupIdentifier} performs a full EPSG database scan.
     */
    private static String resolveSrsCode(CoordinateReferenceSystem crs) {
        String code = CRS.toSRS(crs);
        if (code != null && code.contains(":")) {
            return code;
        }
        try {
            String id = CRS.lookupIdentifier(crs, true);
            return id != null ? id : FALLBACK_SRS;
        } catch (FactoryException ignored) {
            return FALLBACK_SRS;
        }
    }

    // -----------------------------------------------------------------------
    // Internals – WMS layer
    // -----------------------------------------------------------------------

    /**
     * Issues a WMS {@code GetMap} request and draws the response image onto
     * the buffer canvas when it arrives.  Checks the WMS image cache first
     * to avoid redundant HTTP round-trips.
     *
     * <p>The self-removing listener is the only reference keeping the
     * {@code Image} alive during loading; it removes itself on first
     * completion (success or error) to prevent retention.
     */
    private void paintWMSLayer(ReferencedEnvelope mapArea, WMSLayer wmsLayer) {
        try {
            final long generation = renderGeneration;
            final double w = bufferCanvas.getWidth();
            final double h = bufferCanvas.getHeight();
            GraphicsContext gc = bufferCanvas.getGraphicsContext2D();

            WebMapServer wms = wmsLayer.getWebMapServer();
            GetMapRequest request = wms.createGetMapRequest();
            List<org.geotools.ows.wms.Layer> layers = wmsLayer.getWMSLayers();
            List<String> styles = wmsLayer.getWMSStyles();
            for (int i = 0; i < layers.size(); i++) {
                String style = (styles != null && i < styles.size()) ? styles.get(i) : "";
                request.addLayer(layers.get(i), style);
            }
            request.setSRS(lookupSrsCode(mapArea));
            request.setBBox(String.format(Locale.ROOT, "%.8f,%.8f,%.8f,%.8f",
                    mapArea.getMinX(), mapArea.getMinY(),
                    mapArea.getMaxX(), mapArea.getMaxY()));
            request.setDimensions(String.valueOf((int) w), String.valueOf((int) h));
            request.setFormat(WMS_FORMAT);
            request.setTransparent(true);

            String urlKey = request.getFinalURL().toString();

            if (wmsImageCache != null) {
                Image cached = wmsImageCache.get(urlKey);
                if (cached != null && !cached.isError()) {
                    cacheHits.incrementAndGet();
                    gc.drawImage(cached, 0, 0, w, h);
                    onBufferUpdated();
                    return;
                }
            }
            cacheMisses.incrementAndGet();

            String loadUrl = resolveLoadUrl(urlKey);
            final boolean writeToDisk = persistentCacheActive && loadUrl.equals(urlKey);
            Image image = new Image(loadUrl, w, h, false, true, true);
            registerAsyncLoad(image, generation, true, loaded -> {
                bufferCanvas.getGraphicsContext2D().drawImage(loaded, 0, 0, w, h);
                putIntoCache(urlKey, loaded);
                if (writeToDisk) {
                    writeImageToDiskAsync(urlKey, loaded);
                }
                onBufferUpdated();
            });

        } catch (Exception e) {
            logLayerError("WMS GetMap request failed for layer", wmsLayer.getTitle(), e);
        }
    }

    private static void logLayerError(String msg, String layerTitle, Exception e) {
        LOG.log(Level.WARNING, msg + ": {0}", layerTitle);
        LOG.log(Level.FINE, msg, e);
    }

    // -----------------------------------------------------------------------
    // Internals – WMTS layer (fast tile path)
    // -----------------------------------------------------------------------

    /**
     * Fast-path WMTS renderer for {@link FXWMTSMapLayer}: builds a
     * {@link GetTileRequest} directly, enumerates the {@link Tile tiles}
     * covering the buffer envelope, and loads each tile URL through a JavaFX
     * async {@code Image} with background loading.
     *
     * <p>Advantages over the coverage-reader path:
     * <ul>
     *   <li><b>Parallel HTTP</b> — each {@code Image} uses JavaFX's image-I/O
     *       thread pool, so N tiles arrive in roughly {@code max(T_i)} instead
     *       of the {@code sum(T_i)} cost of the reader's sequential loop.</li>
     *   <li><b>Progressive rendering</b> — tiles are painted on the buffer
     *       canvas <em>as they arrive</em>, so the user sees the map fill in
     *       gradually rather than waiting for the full composite.</li>
     *   <li><b>Per-tile URL cache</b> — already-fetched tiles (pan/zoom on
     *       previously-seen tiles) are served synchronously from
     *       {@link #wmsImageCache}, no HTTP round-trip.</li>
     *   <li><b>Adjacent-level placeholder</b> — when a target tile isn't
     *       cached, {@link #drawAdjacentLevelWMTSPlaceholder} paints a
     *       cached parent (zoom-in) or children (zoom-out) tile in its
     *       position so the user sees imagery rather than white during the
     *       async fetch.</li>
     * </ul>
     *
     * <p>Each tile arrival triggers {@link #onBufferUpdated} so the display is
     * re-composited incrementally.  The {@code renderGeneration} check in the
     * listeners discards stale responses when {@code paint()} has been called
     * again in the meantime.
     */
    private void paintWMTSTiles(ReferencedEnvelope bufferEnv, FXWMTSMapLayer fxLayer) {
        final long generation = renderGeneration;
        final double bufW = bufferCanvas.getWidth();
        final double bufH = bufferCanvas.getHeight();
        final GraphicsContext gc = bufferCanvas.getGraphicsContext2D();

        Set<Tile> tiles;
        try {
            WebMapTileServer server = fxLayer.getServer();
            GetTileRequest request = server.createGetTileRequest();
            // setLayer also auto-selects a default style, so setStyle is optional.
            request.setLayer(fxLayer.getLayerInfo());
            request.setCRS(bufferEnv.getCoordinateReferenceSystem());
            request.setRequestedBBox(bufferEnv);
            request.setRequestedWidth((int) Math.max(1, bufW));
            request.setRequestedHeight((int) Math.max(1, bufH));
            tiles = request.getTiles();
        } catch (Exception e) {
            logLayerError("WMTS GetTile request failed for layer", fxLayer.getTitle(), e);
            return;
        }

        if (tiles == null || tiles.isEmpty()) {
            return;
        }

        ScreenTransform tx = ScreenTransform.of(bufW, bufH, bufferEnv);
        WebMapTileServer server = fxLayer.getServer();
        for (Tile tile : tiles) {
            // Pre-warm: if the target tile isn't cached, try to cover its
            // position with an adjacent-level cached tile (parent or children)
            // so the buffer is not white during the async fetch.  Mirrors
            // drawAdjacentLevelPlaceholder() for the WMS-tiled path.
            URL targetUrl = tile.getUrl();
            if (wmsImageCache != null && targetUrl != null
                    && wmsImageCache.get(targetUrl.toString()) == null) {
                drawAdjacentLevelWMTSPlaceholder(server, fxLayer.getLayerInfo(),
                        bufferEnv.getCoordinateReferenceSystem(), tile, tx, gc);
            }
            drawTileIntoBuffer(tile, tx, generation, gc);
        }
    }

    /**
     * Tries to pre-fill the area of the target WMTS tile with imagery drawn
     * from an adjacent-level cached tile.  Counterpart of
     * {@link #drawAdjacentLevelPlaceholder} for the WMTS fast path.
     *
     * <p>Two probes are attempted:
     * <ol>
     *   <li><b>Parent</b> — query the server for the tile at half the target's
     *       resolution covering the target's extent (the WMTS auto-selects
     *       the next-coarser matrix level).  When that parent tile is in
     *       cache, we draw the sub-rect corresponding to the target's extent,
     *       stretched up to fill the target's screen position.</li>
     *   <li><b>Children</b> — query at double resolution → up to four tiles
     *       at the next-finer matrix level.  Each cached child fills its
     *       sub-rect of the target's screen position.</li>
     * </ol>
     *
     * <p>Best-effort: if the {@code GetTileRequest} machinery throws (e.g. the
     * matrix set has no neighboring level), the method silently does nothing.
     */
    private void drawAdjacentLevelWMTSPlaceholder(WebMapTileServer server,
                                                  org.geotools.ows.wmts.model.WMTSLayer layerInfo,
                                                  CoordinateReferenceSystem crs,
                                                  Tile target,
                                                  ScreenTransform tx,
                                                  GraphicsContext gc) {
        ReferencedEnvelope tgt = target.getExtent();
        if (tgt == null) {
            return;
        }
        final double dx = tx.toScreenX(tgt.getMinX());
        final double dy = tx.toScreenY(tgt.getMaxY());
        final double dw = tgt.getWidth()  * tx.scaleX();
        final double dh = tgt.getHeight() * tx.scaleY();

        // 1) Parent (zoom-in case): half-resolution probe → next-coarser matrix.
        Set<Tile> parents = findWMTSTilesForExtent(server, layerInfo, crs, tgt,
                PMTileGrid.TILE_SIZE_PX / 2);
        for (Tile parent : parents) {
            Image cached = lookupCachedTileImage(parent);
            if (cached == null) {
                continue;
            }
            ReferencedEnvelope pExt = parent.getExtent();
            double srcScaleX = cached.getWidth()  / pExt.getWidth();
            double srcScaleY = cached.getHeight() / pExt.getHeight();
            double srcX = (tgt.getMinX() - pExt.getMinX()) * srcScaleX;
            double srcY = (pExt.getMaxY() - tgt.getMaxY()) * srcScaleY;
            double srcW = tgt.getWidth()  * srcScaleX;
            double srcH = tgt.getHeight() * srcScaleY;
            gc.drawImage(cached, srcX, srcY, srcW, srcH, dx, dy, dw, dh);
            return; // parent suffices — don't bother with children
        }

        // 2) Children (zoom-out case): double-resolution probe → next-finer matrix.
        Set<Tile> children = findWMTSTilesForExtent(server, layerInfo, crs, tgt,
                PMTileGrid.TILE_SIZE_PX * 2);
        if (children.isEmpty()) {
            return;
        }
        double dstScaleX = dw / tgt.getWidth();
        double dstScaleY = dh / tgt.getHeight();
        for (Tile child : children) {
            Image cached = lookupCachedTileImage(child);
            if (cached == null) {
                continue;
            }
            ReferencedEnvelope cExt = child.getExtent();
            double cdx = dx + (cExt.getMinX() - tgt.getMinX()) * dstScaleX;
            double cdy = dy + (tgt.getMaxY() - cExt.getMaxY()) * dstScaleY;
            double cdw = cExt.getWidth()  * dstScaleX;
            double cdh = cExt.getHeight() * dstScaleY;
            gc.drawImage(cached, cdx, cdy, cdw, cdh);
        }
    }

    /**
     * Asks the WMTS server for tiles covering {@code bbox} at the resolution
     * implied by rendering into a square of {@code requestedSize} pixels.
     * Halving or doubling that size relative to the standard tile size
     * effectively requests the next-coarser or next-finer matrix level.
     * Returns an empty set on any failure.
     */
    private static Set<Tile> findWMTSTilesForExtent(WebMapTileServer server,
                                                    org.geotools.ows.wmts.model.WMTSLayer layerInfo,
                                                    CoordinateReferenceSystem crs,
                                                    ReferencedEnvelope bbox,
                                                    int requestedSize) {
        try {
            GetTileRequest req = server.createGetTileRequest();
            req.setLayer(layerInfo);
            req.setCRS(crs);
            req.setRequestedBBox(bbox);
            req.setRequestedWidth(requestedSize);
            req.setRequestedHeight(requestedSize);
            Set<Tile> tiles = req.getTiles();
            return tiles != null ? tiles : Set.of();
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    /**
     * Returns the cached, loaded {@link Image} for {@code tile}, or
     * {@code null} when no usable entry exists ({@code null} URL, missing
     * cache entry, in-flight load whose dimensions aren't yet populated, or
     * an error-state image).
     */
    private Image lookupCachedTileImage(Tile tile) {
        if (wmsImageCache == null) {
            return null;
        }
        URL url = tile.getUrl();
        if (url == null) {
            return null;
        }
        Image img = wmsImageCache.get(url.toString());
        if (img == null || img.isError() || img.getWidth() <= 0) {
            return null;
        }
        return img;
    }

    /**
     * Draws a single WMTS tile onto {@link #bufferCanvas} at its world-extent
     * position, loading it asynchronously via JavaFX when not cached.
     *
     * <p>The URL-keyed {@link #wmsImageCache} is shared between WMS GetMap and
     * WMTS GetTile responses — both are {@code url -> Image} with URL already
     * containing all the request parameters.
     */
    private void drawTileIntoBuffer(Tile tile, ScreenTransform tx,
                                     long generation, GraphicsContext gc) {
        URL url = tile.getUrl();
        if (url == null) {
            return;
        }
        drawUrlTileIntoBuffer(url.toString(), tile.getExtent(), tx, generation, gc);
    }

    /**
     * Loads a raster tile (identified by its request URL) and draws it at its
     * world-extent position onto {@link #bufferCanvas}.  Shared between the
     * WMTS fast-path ({@link #paintWMTSTiles}) and the WMS-R tile-cache-aligned
     * path ({@link #paintTiledWMSLayer}) — both produce {@code (url, env)}
     * pairs that should flow through the same async-load, cache, and draw logic.
     *
     * <p>Cached entries are drawn synchronously; misses trigger a JavaFX async
     * {@link Image} load, and the progress listener draws on completion and
     * triggers {@link #onBufferUpdated} so the display is re-composited.
     */
    private void drawUrlTileIntoBuffer(String urlKey, ReferencedEnvelope env,
                                       ScreenTransform tx, long generation,
                                       GraphicsContext gc) {
        final double dx = tx.toScreenX(env.getMinX());
        final double dy = tx.toScreenY(env.getMaxY());
        final double dw = env.getWidth()  * tx.scaleX();
        final double dh = env.getHeight() * tx.scaleY();

        if (wmsImageCache != null) {
            Image cached = wmsImageCache.get(urlKey);
            if (cached != null && !cached.isError()) {
                cacheHits.incrementAndGet();
                gc.drawImage(cached, dx, dy, dw, dh);
                onBufferUpdated();
                return;
            }
        }
        cacheMisses.incrementAndGet();

        // Disk cache probe: if a previous session already fetched this URL,
        // load from the local file (still async) instead of going to the network.
        String loadUrl = resolveLoadUrl(urlKey);
        final boolean writeToDisk = persistentCacheActive && loadUrl.equals(urlKey);
        // backgroundLoading=true — JavaFX's image-I/O thread pool fetches the
        // URL without blocking the FX thread.
        Image image = new Image(loadUrl, 0, 0, true, true, true);
        registerAsyncLoad(image, generation, true, loaded -> {
            gc.drawImage(loaded, dx, dy, dw, dh);
            putIntoCache(urlKey, loaded);
            if (writeToDisk) {
                writeImageToDiskAsync(urlKey, loaded);
            }
            onBufferUpdated();
        });
    }

    // -----------------------------------------------------------------------
    // Internals – WMS layer (client-side PM-grid tiling for WMS-R servers)
    // -----------------------------------------------------------------------

    /**
     * Renders an {@link FXTiledWMSLayer} by splitting the buffer envelope into
     * PM-grid-aligned tiles and issuing one {@code GetMap} per tile.  Each tile
     * request uses the exact bbox / size of the PM tile the server's own tile
     * cache is keyed on, so WMS-R endpoints serve pre-rendered cached bytes
     * instead of re-rendering.
     *
     * <p>Falls back to {@link #paintWMSLayer} when:
     * <ul>
     *   <li>the view CRS is not {@code EPSG:3857} (the PM grid is undefined
     *       outside Pseudo-Mercator);</li>
     *   <li>{@link PMTileGrid#tilesForBbox} caps out (view too wide for the
     *       chosen zoom level — rare with {@link PMTileGrid#levelFor});</li>
     * </ul>
     * so the layer still renders correctly in edge cases.
     *
     * <p>Per-tile URLs are stable across pan/zoom and flow through the same
     * URL-keyed image cache as WMTS tiles — pans over previously-seen tiles
     * hit memory and draw synchronously.
     */
    private void paintTiledWMSLayer(ReferencedEnvelope bufferEnv, FXTiledWMSLayer fxLayer) {
        final long generation = renderGeneration;
        final double bufW = bufferCanvas.getWidth();
        final double bufH = bufferCanvas.getHeight();
        final GraphicsContext gc = bufferCanvas.getGraphicsContext2D();

        // PM grid is only valid in EPSG:3857 — fall back to a single GetMap
        // otherwise (lat/lon or other projections).
        String srs = lookupSrsCode(bufferEnv);
        if (!PM_GRID_SRS.equalsIgnoreCase(srs)) {
            paintWMSLayer(bufferEnv, fxLayer);
            return;
        }

        double worldPerPixel = bufferEnv.getWidth() / Math.max(1.0, bufW);
        int level = PMTileGrid.levelFor(worldPerPixel);
        List<PMTile> tiles = PMTileGrid.tilesForBbox(bufferEnv, level);
        if (tiles.isEmpty()) {
            // Too many tiles (safety cap) or no overlap — fall back to single GetMap.
            paintWMSLayer(bufferEnv, fxLayer);
            return;
        }

        try {
            WebMapServer wms = fxLayer.getWebMapServer();
            List<org.geotools.ows.wms.Layer> layers = fxLayer.getWMSLayers();
            List<String> styles = fxLayer.getWMSStyles();
            ScreenTransform tx = ScreenTransform.of(bufW, bufH, bufferEnv);

            for (PMTile tile : tiles) {
                String urlKey = buildTiledWmsUrl(wms, layers, styles, srs, tile.extent());
                // Pre-warm: if the target tile isn't cached, try to cover its
                // position with an adjacent-level cached tile (parent or child)
                // so the buffer is not white during the async fetch.  When the
                // target tile eventually arrives, it overwrites this placeholder.
                if (wmsImageCache != null && wmsImageCache.get(urlKey) == null) {
                    drawAdjacentLevelPlaceholder(wms, layers, styles, srs, tile, tx, gc);
                }
                drawUrlTileIntoBuffer(urlKey, tile.extent(), tx, generation, gc);
            }
        } catch (Exception e) {
            logLayerError("Tiled WMS GetMap request failed for layer", fxLayer.getTitle(), e);
        }
    }

    /**
     * Builds the stable {@code GetMap} URL for a single 256×256 PM tile — the
     * same URL the server returns from its tile cache for this bbox/size.
     *
     * <p>Extracted so both the main render loop ({@link #paintTiledWMSLayer})
     * and the adjacent-level placeholder lookup
     * ({@link #drawAdjacentLevelPlaceholder}) produce identical keys for the
     * same PM tile — so cache lookups actually hit.
     */
    private static String buildTiledWmsUrl(WebMapServer wms,
                                           List<org.geotools.ows.wms.Layer> layers,
                                           List<String> styles,
                                           String srs,
                                           ReferencedEnvelope env) {
        GetMapRequest request = wms.createGetMapRequest();
        for (int i = 0; i < layers.size(); i++) {
            String style = (styles != null && i < styles.size()) ? styles.get(i) : "";
            request.addLayer(layers.get(i), style);
        }
        request.setSRS(srs);
        request.setBBox(String.format(Locale.ROOT, "%.8f,%.8f,%.8f,%.8f",
                env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY()));
        request.setDimensions(
                TILE_SIZE_PX_STR, TILE_SIZE_PX_STR);
        request.setFormat(WMS_FORMAT);
        request.setTransparent(true);
        return request.getFinalURL().toString();
    }

    /**
     * Tries to pre-fill the area of the target tile with imagery drawn from an
     * adjacent-level cached tile, to avoid a blank gap while the target's
     * async fetch completes.
     *
     * <p>Two lookups are attempted:
     * <ol>
     *   <li><b>Parent tile at {@code level-1}</b> (quadtree parent containing
     *       the target as 1/4 of its area). Typical hit case: zoom-in on a
     *       region previously seen at a coarser level. We draw the 128×128
     *       sub-quadrant corresponding to {@code (col%2, row%2)} stretched to
     *       fill the target's 256×256 position — slightly blurry but far
     *       better than white.</li>
     *   <li><b>Child tiles at {@code level+1}</b> (four children tiling the
     *       target's area). Typical hit case: zoom-out after pan-exploring
     *       at a finer level. Each cached child is drawn compressed into its
     *       quarter of the target position.</li>
     * </ol>
     *
     * <p>If none is cached the method silently does nothing — the target
     * position stays whatever was already on the buffer canvas (typically
     * white from {@link #renderToBuffer}'s clear pass).
     */
    private void drawAdjacentLevelPlaceholder(WebMapServer wms,
                                              List<org.geotools.ows.wms.Layer> layers,
                                              List<String> styles,
                                              String srs,
                                              PMTile target,
                                              ScreenTransform tx,
                                              GraphicsContext gc) {
        final double dx = tx.toScreenX(target.extent().getMinX());
        final double dy = tx.toScreenY(target.extent().getMaxY());
        final double dw = target.extent().getWidth()  * tx.scaleX();
        final double dh = target.extent().getHeight() * tx.scaleY();

        // 1) Parent tile (zoom-in case).
        int parentLevel = target.level() - 1;
        if (parentLevel >= 0) {
            int parentCol = target.col() >> 1;
            int parentRow = target.row() >> 1;
            ReferencedEnvelope pe = pmTileExtent(parentLevel, parentCol, parentRow,
                    target.extent().getCoordinateReferenceSystem());
            try {
                String pUrl = buildTiledWmsUrl(wms, layers, styles, srs, pe);
                Image parent = wmsImageCache.get(pUrl);
                if (parent != null && !parent.isError() && parent.getWidth() > 0) {
                    // Source sub-rect: the quadrant inside the parent that the
                    // target occupies.  Parent image is TILE_SIZE_PX wide, the
                    // target covers half of each axis.
                    double half = parent.getWidth() / 2.0;
                    double srcX = (target.col() & 1) * half;
                    double srcY = (target.row() & 1) * half;
                    gc.drawImage(parent, srcX, srcY, half, half, dx, dy, dw, dh);
                    return; // parent suffices — don't bother with children
                }
            } catch (Exception ignored) {
                // URL build may fail for edge-case layers; placeholder is
                // best-effort, so just move on to children or skip.
            }
        }

        // 2) Child tiles (zoom-out case).  Four children at level+1 tile the
        // target; each one found in the cache fills its quarter of the target.
        int childLevel = target.level() + 1;
        if (childLevel > PMTileGrid.MAX_LEVEL) {
            return;
        }
        int baseCol = target.col() << 1;
        int baseRow = target.row() << 1;
        double halfDw = dw / 2.0;
        double halfDh = dh / 2.0;
        for (int dr = 0; dr <= 1; dr++) {
            for (int dc = 0; dc <= 1; dc++) {
                ReferencedEnvelope ce = pmTileExtent(childLevel, baseCol + dc, baseRow + dr,
                        target.extent().getCoordinateReferenceSystem());
                try {
                    String cUrl = buildTiledWmsUrl(wms, layers, styles, srs, ce);
                    Image child = wmsImageCache.get(cUrl);
                    if (child != null && !child.isError() && child.getWidth() > 0) {
                        gc.drawImage(child, dx + dc * halfDw, dy + dr * halfDh,
                                halfDw, halfDh);
                    }
                } catch (Exception ignored) {
                    // skip missing child
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internals – speculative prefetch
    // -----------------------------------------------------------------------

    /**
     * Idle-time prefetch pass: loads the PM tiles covering the current view at
     * zoom levels {@code z-1} and {@code z+1} into {@link #wmsImageCache}
     * without drawing anything.  A subsequent zoom gesture then finds its
     * tiles already cached. The adjacent-level placeholder pass
     * ({@link #drawAdjacentLevelPlaceholder}) draws them crisply instead of
     * falling back to the stretched previous buffer, and the main tile pass
     * hits the cache synchronously instead of launching async fetches.
     *
     * <p>Runs on the FX thread (fired by {@link #prefetchTimer}).  Only tiled
     * WMS layers are handled — the WMTS path's tile addressing depends on
     * server-declared matrix sets, which would need a separate implementation.
     */
    private void runPrefetch() {
        if (currentArea == null || currentContent == null || wmsImageCache == null) {
            return;
        }
        String srs = lookupSrsCode(currentArea);
        if (!PM_GRID_SRS.equalsIgnoreCase(srs)) {
            return;
        }
        double worldPerPixel = currentArea.getWidth() / Math.max(1.0, currentW);
        int level = PMTileGrid.levelFor(worldPerPixel);

        for (Layer layer : currentContent.layers()) {
            if (!layer.isVisible() || !(layer instanceof FXTiledWMSLayer fx)) {
                continue;
            }
            prefetchLevel(fx, srs, level - 1);
            prefetchLevel(fx, srs, level + 1);
        }
    }

    /**
     * Issues up to {@link #PREFETCH_MAX_TILES_PER_LEVEL} async tile loads for
     * the PM tiles covering the current view at the given zoom level, skipping
     * tiles already present in the cache.  Loaded images are inserted in the
     * URL cache on arrival but never drawn.  Prefetch loads are registered in
     * {@link #inFlightTiles} so the standard cancel pass ({@link #cancelInFlightTiles})
     * aborts them the moment the user resumes interaction.
     */
    private void prefetchLevel(FXTiledWMSLayer fxLayer, String srs, int level) {
        if (level < 0 || level > PMTileGrid.MAX_LEVEL) {
            return;
        }
        List<PMTile> tiles = PMTileGrid.tilesForBbox(currentArea, level);
        if (tiles.isEmpty()) {
            return;
        }
        WebMapServer wms = fxLayer.getWebMapServer();
        List<org.geotools.ows.wms.Layer> layers = fxLayer.getWMSLayers();
        List<String> styles = fxLayer.getWMSStyles();

        int issued = 0;
        for (PMTile tile : tiles) {
            if (issued >= PREFETCH_MAX_TILES_PER_LEVEL) {
                break;
            }
            String url;
            try {
                url = buildTiledWmsUrl(wms, layers, styles, srs, tile.extent());
            } catch (Exception ignored) {
                continue;
            }
            if (wmsImageCache.get(url) != null) {
                continue; // already in cache — nothing to prefetch
            }
            issuePrefetch(url);
            issued++;
        }
    }

    /**
     * Starts an async {@link Image} load for {@code url} that drops the
     * decoded image into {@link #wmsImageCache} but draws nothing.  Excluded
     * from the perf-load average because prefetch happens during user idle.
     */
    private void issuePrefetch(String url) {
        String loadUrl = resolveLoadUrl(url);
        final boolean writeToDisk = persistentCacheActive && loadUrl.equals(url);
        Image image = new Image(loadUrl, 0, 0, true, true, true);
        // Pass a generation that stays valid for as long as the user doesn't
        // start a new render — once they do, the image is canceled in
        // cancelInFlightTiles() and never reaches the success branch.
        registerAsyncLoad(image, renderGeneration, false, loaded -> {
            putIntoCache(url, loaded);
            if (writeToDisk) {
                writeImageToDiskAsync(url, loaded);
            }
        });
    }

    /** World envelope of the PM tile at {@code (level, col, row)}. */
    private static ReferencedEnvelope pmTileExtent(int level, int col, int row,
                                                   CoordinateReferenceSystem crs) {
        double span = PMTileGrid.tileSpanAt(level);
        double minX = PMTileGrid.ORIGIN_X + col * span;
        double maxY = PMTileGrid.ORIGIN_Y - row * span;
        return new ReferencedEnvelope(minX, minX + span, maxY - span, maxY, crs);
    }

    // -----------------------------------------------------------------------
    // Internals – WMTS layer (coverage-reader fallback)
    // -----------------------------------------------------------------------

    /**
     * Reads the WMTS coverage for the current buffer envelope on a background
     * thread (so the underlying {@code GetTile}. HTTP requests don't block the
     * FX thread) and paints the resulting image onto the buffer canvas when it
     * arrives, then triggers {@link #onBufferUpdated} so the display is re-composited.
     * Stale results — for reads whose {@code generation} no longer matches the
     * current render — are discarded on the FX thread.
     *
     * <p>No URL-level cache is applied here: the overpaint buffer itself already
     * provides the pan/zoom cache behavior.
     */
    private void paintWMTSLayer(ReferencedEnvelope bufferEnv, WMTSMapLayer wmtsLayer) {
        final long generation = renderGeneration;
        final double bufW = bufferCanvas.getWidth();
        final double bufH = bufferCanvas.getHeight();
        final GridCoverage2DReader reader = wmtsLayer.getReader();
        if (reader == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> readWmtsCoverage(reader, bufW, bufH, bufferEnv))
                .thenAccept(coverage -> {
                    if (coverage == null) {
                        return;
                    }
                    final BufferedImage bi = toBufferedImage(coverage.getRenderedImage());
                    final ReferencedEnvelope cov =
                            ReferencedEnvelope.reference(coverage.getEnvelope());
                    Platform.runLater(() -> {
                        if (renderGeneration != generation) {
                            return;
                        }
                        Image fxImage = SwingFXUtils.toFXImage(bi, null);
                        GraphicsContext gc = bufferCanvas.getGraphicsContext2D();
                        ScreenTransform tx = ScreenTransform.of(bufW, bufH, bufferEnv);
                        double dx = tx.toScreenX(cov.getMinX());
                        double dy = tx.toScreenY(cov.getMaxY());
                        double dw = cov.getWidth()  * tx.scaleX();
                        double dh = cov.getHeight() * tx.scaleY();
                        gc.drawImage(fxImage, dx, dy, dw, dh);
                        onBufferUpdated();
                    });
                });
    }

    /**
     * Synchronously reads a {@link GridCoverage2D} for the given area/size from
     * a WMTS coverage reader.  Intended to be called from a background thread.
     */
    private static GridCoverage2D readWmtsCoverage(GridCoverage2DReader reader,
                                                    double w, double h,
                                                    ReferencedEnvelope area) {
        try {
            int pixW = (int) Math.max(1, Math.round(w));
            int pixH = (int) Math.max(1, Math.round(h));
            GridGeometry2D gg = new GridGeometry2D(new GridEnvelope2D(0, 0, pixW, pixH), area);
            ParameterValue<GridGeometry2D> ggp =
                    AbstractGridFormat.READ_GRIDGEOMETRY2D.createValue();
            ggp.setValue(gg);
            return reader.read(new GeneralParameterValue[]{ggp});
        } catch (Exception e) {
            LOG.log(Level.WARNING, "WMTS read failed", e);
            return null;
        }
    }

    /**
     * Returns a {@link BufferedImage} view of {@code ri}, repainting through a
     * {@code Graphics2D} only when the underlying {@link RenderedImage} is not
     * already a {@link BufferedImage}.
     */
    private static BufferedImage toBufferedImage(RenderedImage ri) {
        if (ri instanceof BufferedImage bi) {
            return bi;
        }
        BufferedImage bi = new BufferedImage(
                ri.getWidth(), ri.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.drawRenderedImage(ri, new AffineTransform());
        g.dispose();
        return bi;
    }

    // -----------------------------------------------------------------------
    // Internals – feature layer
    // -----------------------------------------------------------------------

    private void paintFeatureLayer(GraphicsContext gc, ReferencedEnvelope mapArea,
                                   ScreenTransform tx, FeatureLayer layer) {
        var source  = layer.getFeatureSource();
        var schema  = source.getSchema();
        GeometryDescriptor geomDesc = schema.getGeometryDescriptor();
        if (geomDesc == null) {
            return;
        }

        String geomName = geomDesc.getLocalName();
        String srsCode  = lookupSrsCode(mapArea);
        Query query = new Query(
                schema.getName().toString(),
                FF.bbox(geomName,
                        mapArea.getMinX(), mapArea.getMinY(),
                        mapArea.getMaxX(), mapArea.getMaxY(),
                        srsCode));

        try {
            var features = source.getFeatures(query);
            Style style  = layer.getStyle();

            // Collect rules once per layer per render to avoid re-traversing per feature.
            List<Rule> rules = style.featureTypeStyles().stream()
                    .flatMap(fts -> fts.rules().stream())
                    .toList();

            try (FeatureIterator<?> it = features.features()) {
                while (it.hasNext() && !stop) {
                    Feature feature = it.next();
                    // Extract geometry once per feature — a feature may match
                    // multiple rules/symbolizers and extracting per-symbolizer is redundant.
                    Geometry geom = extractGeometry(feature);
                    if (geom == null) {
                        continue;
                    }
                    for (Rule rule : rules) {
                        if (!ruleApplies(rule, feature)) {
                            continue;
                        }
                        for (Symbolizer sym : rule.symbolizers()) {
                            applySymbolizer(gc, tx, geom, feature, sym);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logLayerError("Failed to load features for layer", layer.getTitle(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Internals – rule evaluation
    // -----------------------------------------------------------------------

    private static boolean ruleApplies(Rule rule, Feature feature) {
        Filter f = rule.getFilter();
        // Identity check on INCLUDE avoids a virtual dispatch for the common case.
        return f == null || f == Filter.INCLUDE || f.evaluate(feature);
    }

    // -----------------------------------------------------------------------
    // Internals – symbolizer dispatch
    // -----------------------------------------------------------------------

    private static void applySymbolizer(GraphicsContext gc, ScreenTransform tx,
                                        Geometry geom, Feature feature, Symbolizer sym) {
        if (sym instanceof PolygonSymbolizer ps) {
            paintPolygon(gc, tx, geom, ps, feature);
        } else if (sym instanceof LineSymbolizer ls) {
            paintLine(gc, tx, geom, ls, feature);
        } else if (sym instanceof PointSymbolizer pts) {
            paintPoint(gc, tx, geom, pts, feature);
        }
    }

    private static Geometry extractGeometry(Feature feature) {
        if (feature instanceof SimpleFeature sf) {
            Object def = sf.getDefaultGeometry();
            return def instanceof Geometry geo ? geo : null;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Internals – geometry → GraphicsContext path
    // -----------------------------------------------------------------------

    private static void appendGeometry(GraphicsContext gc, Geometry geom, ScreenTransform tx) {
        if (geom instanceof Polygon polygon) {
            appendCoords(gc, polygon.getExteriorRing().getCoordinateSequence(), tx, true);
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                appendCoords(gc, polygon.getInteriorRingN(i).getCoordinateSequence(), tx, true);
            }
        } else if (geom instanceof LineString ls) {
            appendCoords(gc, ls.getCoordinateSequence(), tx, false);
        } else if (geom instanceof GeometryCollection col) {
            for (int i = 0; i < col.getNumGeometries(); i++) {
                appendGeometry(gc, col.getGeometryN(i), tx);
            }
        }
    }

    /**
     * Appends a JTS {@link CoordinateSequence} to the current path.
     *
     * @param close if {@code true}, emits {@code closePath()} and skips the
     *              closing duplicate coordinate that JTS rings carry.
     */
    private static void appendCoords(GraphicsContext gc, CoordinateSequence seq,
                                     ScreenTransform tx, boolean close) {
        int n = seq.size();
        int last = close ? n - 1 : n; // JTS rings duplicate the first coordinate at the end
        // A valid closed ring needs ≥ 3 unique coords (4 total with the closing duplicate);
        // a valid open line needs ≥ 2 coords.
        if (last < (close ? 3 : 2)) {
            return;
        }
        gc.moveTo(tx.toScreenX(seq.getX(0)), tx.toScreenY(seq.getY(0)));
        for (int i = 1; i < last; i++) {
            gc.lineTo(tx.toScreenX(seq.getX(i)), tx.toScreenY(seq.getY(i)));
        }
        if (close) {
            gc.closePath();
        }
    }

    // -----------------------------------------------------------------------
    // Internals – polygon
    // -----------------------------------------------------------------------

    private static void paintPolygon(GraphicsContext gc, ScreenTransform tx,
                                     Geometry geom, PolygonSymbolizer sym, Feature feature) {
        Fill fill = sym.getFill();
        org.geotools.api.style.Stroke stroke = sym.getStroke();
        if (fill == null && stroke == null) {
            return;
        }
        gc.beginPath();
        appendGeometry(gc, geom, tx);
        if (fill != null) {
            gc.setFill(evalColor(fill.getColor(), fill.getOpacity(), feature, DEFAULT_FILL));
            gc.fill();
        }
        if (stroke != null) {
            configureStroke(gc, stroke, feature);
            gc.stroke();
        }
    }

    // -----------------------------------------------------------------------
    // Internals – line
    // -----------------------------------------------------------------------

    private static void paintLine(GraphicsContext gc, ScreenTransform tx,
                                  Geometry geom, LineSymbolizer sym, Feature feature) {
        org.geotools.api.style.Stroke stroke = sym.getStroke();
        if (stroke == null) {
            return;
        }
        gc.beginPath();
        appendGeometry(gc, geom, tx);
        configureStroke(gc, stroke, feature);
        gc.stroke();
    }

    // -----------------------------------------------------------------------
    // Internals – point
    // -----------------------------------------------------------------------

    private static void paintPoint(GraphicsContext gc, ScreenTransform tx,
                                   Geometry geom, PointSymbolizer sym, Feature feature) {
        final double px, py;
        if (geom instanceof Point p) {
            px = tx.toScreenX(p.getX());
            py = tx.toScreenY(p.getY());
        } else {
            // Envelope centre is O(1); getCentroid() would be O(n) per feature on every frame.
            var env = geom.getEnvelopeInternal();
            px = tx.toScreenX((env.getMinX() + env.getMaxX()) / 2.0);
            py = tx.toScreenY((env.getMinY() + env.getMaxY()) / 2.0);
        }

        double size = 6.0;
        Graphic graphic = sym.getGraphic();
        if (graphic != null && graphic.getSize() != null) {
            size = evalDouble(graphic.getSize(), feature, 6.0);
        }
        double half = size / 2.0;

        Color fillColor   = DEFAULT_POINT_FILL;
        Color strokeColor = DEFAULT_POINT_STROKE;

        if (graphic != null) {
            for (GraphicalSymbol gs : graphic.graphicalSymbols()) {
                if (gs instanceof Mark mark) {
                    if (mark.getFill() != null) {
                        fillColor = evalColor(mark.getFill().getColor(),
                                mark.getFill().getOpacity(), feature, DEFAULT_POINT_FILL);
                    }
                    if (mark.getStroke() != null) {
                        strokeColor = evalColor(mark.getStroke().getColor(),
                                mark.getStroke().getOpacity(), feature, DEFAULT_POINT_STROKE);
                    }
                    break;
                }
            }
        }

        gc.setFill(fillColor);
        gc.fillOval(px - half, py - half, size, size);
        gc.setStroke(strokeColor);
        gc.setLineWidth(1.0);
        gc.strokeOval(px - half, py - half, size, size);
    }

    // -----------------------------------------------------------------------
    // Internals – stroke helper
    // -----------------------------------------------------------------------

    private static void configureStroke(GraphicsContext gc,
                                        org.geotools.api.style.Stroke stroke,
                                        Feature feature) {
        gc.setStroke(evalColor(stroke.getColor(), stroke.getOpacity(), feature, DEFAULT_STROKE));
        gc.setLineWidth(evalDouble(stroke.getWidth(), feature, 1.0));
        gc.setLineCap(toLineCap(evalString(stroke.getLineCap(), feature)));
        gc.setLineJoin(toLineJoin(evalString(stroke.getLineJoin(), feature)));
    }

    private static StrokeLineCap toLineCap(String sld) {
        if (sld == null) return StrokeLineCap.ROUND;
        return switch (sld.toLowerCase(Locale.ROOT)) {
            case "butt"   -> StrokeLineCap.BUTT;
            case "square" -> StrokeLineCap.SQUARE;
            default       -> StrokeLineCap.ROUND;
        };
    }

    private static StrokeLineJoin toLineJoin(String sld) {
        if (sld == null) return StrokeLineJoin.ROUND;
        return switch (sld.toLowerCase(Locale.ROOT)) {
            case "miter", "mitre" -> StrokeLineJoin.MITER;
            case "bevel"          -> StrokeLineJoin.BEVEL;
            default               -> StrokeLineJoin.ROUND;
        };
    }

    // -----------------------------------------------------------------------
    // Internals – style expression evaluation
    // -----------------------------------------------------------------------

    /**
     * Evaluates a color expression and an opacity expression, returning a
     * JavaFX {@link Color}.  SLD color expressions are CSS hex strings
     * (e.g. {@code #FF0000}) evaluated as {@code String} and parsed via
     * {@link Color#web(String)}.
     */
    private static Color evalColor(Expression colorExpr, Expression opacityExpr,
                                   Feature feature, Color fallback) {
        double opacity = evalDouble(opacityExpr, feature, 1.0);
        if (colorExpr == null) {
            return withOpacity(fallback, opacity);
        }
        String str = colorExpr.evaluate(feature, String.class);
        if (str == null) {
            return withOpacity(fallback, opacity);
        }
        try {
            return withOpacity(Color.web(str), opacity);
        } catch (IllegalArgumentException e) {
            return withOpacity(fallback, opacity);
        }
    }

    private static double evalDouble(Expression expr, Feature feature, double fallback) {
        if (expr == null) {
            return fallback;
        }
        Double result = expr.evaluate(feature, Double.class);
        return result != null ? result : fallback;
    }

    private static String evalString(Expression expr, Feature feature) {
        if (expr == null) {
            return null;
        }
        return expr.evaluate(feature, String.class);
    }

    private static Color withOpacity(Color color, double opacity) {
        if (opacity <= 0.0) return Color.TRANSPARENT;
        if (opacity >= 1.0) return color;
        return color.deriveColor(0, 1, 1, opacity);
    }
}
