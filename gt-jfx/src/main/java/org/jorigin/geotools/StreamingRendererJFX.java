package org.jorigin.geotools;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import org.geotools.api.style.FeatureTypeStyle;
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
import org.geotools.ows.wmts.map.WMTSMapLayer;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * A GeoTools map renderer that draws directly onto a JavaFX {@link Canvas}
 * using the JavaFX {@link GraphicsContext} API — no AWT dependency.
 *
 * <p>The rendering pipeline iterates over the {@link MapContent} layers and
 * handles each layer type:
 * <ul>
 *   <li>{@link FeatureLayer} — vector layers with SLD-based symbolization
 *       (polygon, line, point symbolizers); geometries are converted to
 *       JavaFX path commands via JTS {@link CoordinateSequence} traversal.</li>
 *   <li>{@link WMSLayer} — raster layers fetched from a WMS server via a
 *       {@code GetMap} request; the response image is loaded asynchronously
 *       and drawn onto the canvas when available.</li>
 *   <li>{@link WMTSMapLayer} — tiled raster layers fetched through the
 *       {@link GridCoverage2DReader} exposed by {@code WMTSMapLayer}; the
 *       read (which performs {@code GetTile} requests and composites the tiles)
 *       runs on a background thread and the resulting image is drawn onto the
 *       canvas when available.</li>
 * </ul>
 *
 * <p>Layer compositing: WMS layers are drawn asynchronously on top of whatever
 * is already on the canvas; feature layers are drawn synchronously on top of
 * WMS content. The canvas is only cleared on resize — during pan/zoom the old
 * image remains visible until the new WMS response arrives.
 *
 * <h2>Usage (on the JavaFX application thread):</h2>
 * <pre>{@code
 *   StreamingRendererJFX renderer = new StreamingRendererJFX();
 *   somePane.getChildren().add(renderer.getCanvas());
 *   renderer.paint(mapContent, 800, 600, envelope);
 * }</pre>
 *
 * <p>{@link #paint} must be called on the JavaFX application thread.
 * {@link #stopRendering()} may be called from any thread.
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 */
public class StreamingRendererJFX implements GTRendererJFX {

    private static final Logger LOG =
            Logger.getLogger(StreamingRendererJFX.class.getName());

    /** SRS code used when the map envelope carries no CRS information. */
    private static final String FALLBACK_SRS = "EPSG:4326";

    /** MIME type requested from WMS servers. */
    private static final String WMS_FORMAT = "image/png";

    private static final FilterFactory FF = CommonFactoryFinder.getFilterFactory();

    /** Cache: maps CRS instances to their resolved SRS string so the EPSG scan runs at most once. */
    private static final ConcurrentHashMap<CoordinateReferenceSystem, String> SRS_CODE_CACHE =
            new ConcurrentHashMap<>();

    private static final Color DEFAULT_FILL         = Color.GRAY;
    private static final Color DEFAULT_STROKE       = Color.BLACK;
    private static final Color DEFAULT_POINT_FILL   = Color.BLUE;
    private static final Color DEFAULT_POINT_STROKE = Color.DARKGRAY;

    // -----------------------------------------------------------------------
    // Canvas
    // -----------------------------------------------------------------------

    private final Canvas canvas = new Canvas();

    /**
     * Size tracked separately to avoid calling {@code canvas.setWidth/setHeight}
     * with identical values — JavaFX clears the canvas backing store on every
     * resize even when the value is unchanged.
     */
    private double lastCanvasWidth  = -1;
    private double lastCanvasHeight = -1;

    // -----------------------------------------------------------------------
    // Rendering state
    // -----------------------------------------------------------------------

    /** Interrupted by {@link #stopRendering()} to abort feature iteration. */
    private volatile boolean stop;

    /**
     * Incremented at the start of each {@link #paint} call (FX thread only —
     * no atomicity needed). WMS image callbacks compare their captured value
     * against this field to discard stale responses.
     */
    private volatile long renderGeneration;

    /**
     * Create a streaming renderer with default parameters.
     */
    public StreamingRendererJFX(){

    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link Canvas} node onto which the map is rendered.
     * Add it to the JavaFX scene graph to display the map.
     */
    public Canvas getCanvas() {
        return canvas;
    }

    /**
     * Renders the given map content onto the internal {@link Canvas}.
     *
     * <p><strong>Must be called on the JavaFX application thread.</strong>
     *
     * @param content the map content to render
     * @param w       target width in pixels
     * @param h       target height in pixels
     * @param area    the world-coordinate envelope to render
     */
    public void paint(MapContent content, double w, double h, ReferencedEnvelope area) {
        stop = false;
        final long generation = ++renderGeneration;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Resize the canvas backing store when dimensions change.
        if (w != lastCanvasWidth || h != lastCanvasHeight) {
            canvas.setWidth(w);
            canvas.setHeight(h);
            lastCanvasWidth  = w;
            lastCanvasHeight = h;
        }

        // Always clear before each render. Feature layers are synchronous: they
        // draw on top of whatever is already on the canvas in the same paint() call,
        // so stale content from the previous frame must be removed. WMS layers are
        // async, but their callbacks always paint the full tile region, so they too
        // are unaffected by the clear here.
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, w, h);

        if (content == null || area == null || area.isEmpty()) {
            return;
        }

        ScreenTransform tx = ScreenTransform.of(w, h, area);

        for (Layer layer : content.layers()) {
            if (stop || !layer.isVisible()) {
                continue;
            }
            if (layer instanceof WMSLayer wl) {
                paintWMSLayer(gc, w, h, area, wl, generation);
            } else if (layer instanceof WMTSMapLayer wtl) {
                paintWMTSLayer(w, h, area, wtl, generation);
            } else if (layer instanceof FeatureLayer fl) {
                paintFeatureLayer(gc, w, h, area, tx, fl);
            }
        }
    }

    /**
     * Signals the current render to stop after the current feature.
     * Safe to call from any thread.
     */
    public void stopRendering() {
        stop = true;
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
        return SRS_CODE_CACHE.computeIfAbsent(crs, StreamingRendererJFX::resolveSrsCode);
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
     * Issues a WMS {@code GetMap} request and draws the response image on the
     * canvas when it arrives.
     *
     * <p>The self-removing listener is the only reference keeping the
     * {@code Image} alive during loading; it removes itself on first
     * completion (success or error) to prevent retention.
     */
    private void paintWMSLayer(GraphicsContext gc, double w, double h,
                               ReferencedEnvelope mapArea, WMSLayer wmsLayer,
                               long generation) {
        try {
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

            URL url = request.getFinalURL();
            Image image = new Image(url.toString(), w, h, false, true, true);

            ChangeListener<Number> listener = new ChangeListener<>() {
                @Override
                public void changed(ObservableValue<? extends Number> obs,
                                    Number old, Number progress) {
                    if (progress.doubleValue() < 1.0) {
                        return;
                    }
                    image.progressProperty().removeListener(this);
                    if (image.isError() || renderGeneration != generation) {
                        return;
                    }
                    canvas.getGraphicsContext2D().drawImage(image, 0, 0, w, h);
                }
            };
            image.progressProperty().addListener(listener);

        } catch (Exception e) {
            LOG.log(Level.WARNING, "WMS GetMap request failed for layer: {0}", wmsLayer.getTitle());
            LOG.log(Level.FINE, "WMS GetMap exception", e);
        }
    }

    // -----------------------------------------------------------------------
    // Internals – WMTS layer
    // -----------------------------------------------------------------------

    /**
     * Reads the WMTS coverage for {@code mapArea} on a background thread (so the
     * underlying {@code GetTile} HTTP requests don't block the FX thread) and
     * draws the resulting image once it arrives.  Stale results — for requests
     * whose {@code generation} no longer matches the current render — are
     * discarded on the FX thread.
     */
    private void paintWMTSLayer(double w, double h, ReferencedEnvelope mapArea,
                                WMTSMapLayer wmtsLayer, long generation) {
        final GridCoverage2DReader reader = wmtsLayer.getReader();
        if (reader == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> readWmtsCoverage(reader, w, h, mapArea))
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
                        ScreenTransform tx = ScreenTransform.of(w, h, mapArea);
                        double dx = tx.toScreenX(cov.getMinX());
                        double dy = tx.toScreenY(cov.getMaxY());
                        double dw = cov.getWidth()  * tx.scaleX();
                        double dh = cov.getHeight() * tx.scaleY();
                        canvas.getGraphicsContext2D().drawImage(fxImage, dx, dy, dw, dh);
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
     * already a {@link BufferedImage} (the common case for {@code WMTSCoverageReader}
     * is a BufferedImage, so this is usually a cheap cast).
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

    private void paintFeatureLayer(GraphicsContext gc, double w, double h,
                                   ReferencedEnvelope mapArea, ScreenTransform tx,
                                   FeatureLayer layer) {
        var source   = layer.getFeatureSource();
        var schema   = source.getSchema();
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
            List<Rule> rules = new ArrayList<>();
            for (FeatureTypeStyle fts : style.featureTypeStyles()) {
                rules.addAll(fts.rules());
            }

            try (FeatureIterator<?> it = features.features()) {
                while (it.hasNext() && !stop) {
                    Feature feature = it.next();
                    for (Rule rule : rules) {
                        if (!ruleApplies(rule, feature)) {
                            continue;
                        }
                        for (Symbolizer sym : rule.symbolizers()) {
                            applySymbolizer(gc, tx, feature, sym);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load features for layer: {0}", layer.getTitle());
            LOG.log(Level.FINE, "Feature load exception", e);
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
                                        Feature feature, Symbolizer sym) {
        Geometry geom = extractGeometry(feature);
        if (geom == null) {
            return;
        }
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
            appendRing(gc, polygon.getExteriorRing(), tx);
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                appendRing(gc, polygon.getInteriorRingN(i), tx);
            }
        } else if (geom instanceof LineString ls) {
            appendLine(gc, ls, tx);
        } else if (geom instanceof GeometryCollection col) {
            for (int i = 0; i < col.getNumGeometries(); i++) {
                appendGeometry(gc, col.getGeometryN(i), tx);
            }
        }
    }

    private static void appendRing(GraphicsContext gc, LinearRing ring, ScreenTransform tx) {
        appendCoords(gc, ring.getCoordinateSequence(), tx, true);
    }

    private static void appendLine(GraphicsContext gc, LineString ls, ScreenTransform tx) {
        appendCoords(gc, ls.getCoordinateSequence(), tx, false);
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
        int last = close ? n - 1 : n; // JTS rings duplicate the first coord at the end
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
            Point centroid = geom.getCentroid();
            px = tx.toScreenX(centroid.getX());
            py = tx.toScreenY(centroid.getY());
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
