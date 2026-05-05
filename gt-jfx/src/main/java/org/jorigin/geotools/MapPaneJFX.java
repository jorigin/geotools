package org.jorigin.geotools;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.jorigin.geotools.renderer.GTRendererJFX;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.MapContent;

import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;

/**
 * A JavaFX Pane that displays GeoTools {@link MapContent} with pan and zoom support,
 * equivalent to the Swing {@code JMapPane} component.
 *
 * <p>Rendering is performed by {@link StreamingRendererJFX}, which draws directly
 * onto a JavaFX {@link Canvas} on the JavaFX application thread.
 *
 * <p>Supported interactions:
 * <ul>
 *   <li>Pan – primary mouse button drag</li>
 *   <li>Zoom – mouse wheel (centred on cursor position)</li>
 *   <li>Full extent – call {@link #reset()}</li>
 * </ul>
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 */
public class MapPaneJFX extends Pane {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final double ZOOM_FACTOR = 1.5;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private MapContent mapContent;
    private final GTRendererJFX jfxRenderer;

    private ReferencedEnvelope displayArea;

    /**
     * Incremented on every {@link #scheduleRender} call.  Each submitted
     * {@code Platform.runLater} callback compares its captured generation
     * against the current value and skips rendering if a newer request has
     * arrived in the meantime.
     */
    private final AtomicLong scheduleGeneration = new AtomicLong();

    // Pan state (non-null only while a primary-button drag is in progress)
    private double panStartX;
    private double panStartY;
    private ReferencedEnvelope panStartArea;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Creates an empty pane that use by default a {@link StreamingRendererJFX}. Before any display, a {@link MapContent map content} has to be set.
     * @see #setMapContent(MapContent)
     */
    public MapPaneJFX() {
        this(null, null);
    }

    /**
     * Creates a map pane that relies on a default {@link StreamingRendererJFX} and immediately displays the given {@link MapContent map content}.
     * @param content the map content to display
     */
    public MapPaneJFX(MapContent content) {
        this(content, null);
    }

    /**
     * Creates a map pane that relies on the given {@link GTRendererJFX renderer} and immediately displays the given {@link MapContent map content}.
     * If the given {@code renderer} is {@code null}, a {@link StreamingRendererJFX} is used.
     * @param content the map content to display, or {@code null}
     * @param renderer the renderer to use, or {@code null}
     */
    public MapPaneJFX(MapContent content, GTRendererJFX renderer) {

        // Set the renderer
        this.jfxRenderer = Objects.requireNonNullElseGet(renderer, StreamingRendererJFX::new);
        getChildren().add(this.jfxRenderer.getCanvas());

        widthProperty().addListener((obs, oldVal, newVal) -> scheduleRender());
        heightProperty().addListener((obs, oldVal, newVal) -> scheduleRender());

        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);
        setOnScroll(this::handleScroll);

        if (content != null) {
            setMapContent(content);
        }
    }

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    @Override
    protected void layoutChildren() {
        Canvas canvas = jfxRenderer.getCanvas();
        double w = getWidth();
        double h = getHeight();
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
    }

    // -----------------------------------------------------------------------
    // Public API – map content
    // -----------------------------------------------------------------------

    /**
     * Sets the map content to display and triggers a render.
     * Initializes the display area to the full extent of all layers.
     *
     * @param content the map content, or {@code null} to clear the display
     */
    public void setMapContent(MapContent content) {
        this.mapContent = content;
        if (content != null) {
            ReferencedEnvelope bounds = content.getViewport().getBounds();
            if (bounds == null || bounds.isEmpty()) {
                bounds = content.getMaxBounds();
            }
            displayArea = bounds;
        } else {
            displayArea = null;
        }
        scheduleRender();
    }

    /**
     * Get the current {@link MapContent map content}, or {@code null} if none is set.
     * @return the current map content
     */
    public MapContent getMapContent() {
        return mapContent;
    }

    // -----------------------------------------------------------------------
    // Public API – display area
    // -----------------------------------------------------------------------

    /**
     * Get the portion of the map (in world / CRS coordinates) currently
     * visible, or {@code null} if no map content has been set.
     * @return the portion of the map (in world / CRS coordinates) currently visible
     */
    public ReferencedEnvelope getDisplayArea() {
        return displayArea;
    }

    /**
     * Sets the visible map area and triggers a render.
     *
     * @param envelope the world-coordinate bounding box to display
     */
    public void setDisplayArea(ReferencedEnvelope envelope) {
        this.displayArea = envelope;
        scheduleRender();
    }

    /**
     * Resets the view to the full extent of all layers.
     */
    public void reset() {
        if (mapContent != null) {
            displayArea = mapContent.getMaxBounds();
            scheduleRender();
        }
    }

    // -----------------------------------------------------------------------
    // Public API – renderer
    // -----------------------------------------------------------------------

    /**
     * Get the {@link GTRendererJFX renderer} used by this pane.
     * @return the renderer used by this pane
     */
    public GTRendererJFX getRenderer() {
        return jfxRenderer;
    }

    // -----------------------------------------------------------------------
    // Public API – coordinate transforms
    // -----------------------------------------------------------------------

    /**
     * Converts a screen pixel position to world (CRS) coordinates.
     *
     * @param screenX screen X in pixels
     * @param screenY screen Y in pixels
     * @return the corresponding world coordinate as a point-sized envelope,
     *         or {@code null} if the display area is not initialized
     */
    public ReferencedEnvelope screenToWorld(double screenX, double screenY) {
        if (displayArea == null || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }
        double scaleX = displayArea.getWidth()  / getWidth();
        double scaleY = displayArea.getHeight() / getHeight();
        double worldX = displayArea.getMinX() + screenX * scaleX;
        double worldY = displayArea.getMaxY() - screenY * scaleY;
        return new ReferencedEnvelope(worldX, worldX, worldY, worldY,
                displayArea.getCoordinateReferenceSystem());
    }

    /**
     * Converts a world (CRS) coordinate to a screen pixel position.
     *
     * @param worldX world X coordinate
     * @param worldY world Y coordinate
     * @return {@code double[]{screenX, screenY}}, or {@code null} if the
     *         display area is not initialized
     */
    public double[] worldToScreen(double worldX, double worldY) {
        if (displayArea == null || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }
        double scaleX = getWidth()  / displayArea.getWidth();
        double scaleY = getHeight() / displayArea.getHeight();
        return new double[]{
                (worldX - displayArea.getMinX()) * scaleX,
                (displayArea.getMaxY() - worldY) * scaleY
        };
    }

    // -----------------------------------------------------------------------
    // Public API – lifecycle
    // -----------------------------------------------------------------------

    /**
     * Releases resources held by this pane.
     * Call this when the pane is no longer needed.
     */
    public void dispose() {
        jfxRenderer.stopRendering();
        scheduleGeneration.incrementAndGet(); // invalidate any pending runLater
        MapContent content = mapContent;
        mapContent = null;
        if (content != null) {
            content.dispose();
        }
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    /**
     * Schedules a render on the JavaFX application thread.
     *
     * <p>Each call increments {@link #scheduleGeneration}.  The submitted
     * {@code Platform.runLater} callback only executes the paint if its
     * captured generation still matches, so rapid successive calls (e.g.
     * during resize or pan) result in at most one render executing.
     */
    private void scheduleRender() {
        if (mapContent == null || getWidth() <= 0 || getHeight() <= 0
                || displayArea == null || displayArea.isEmpty()) {
            return;
        }

        jfxRenderer.stopRendering();
        final long gen  = scheduleGeneration.incrementAndGet();
        final double w  = getWidth();
        final double h  = getHeight();
        final ReferencedEnvelope area = new ReferencedEnvelope(displayArea);

        Platform.runLater(() -> {
            if (scheduleGeneration.get() == gen) {
                jfxRenderer.paint(mapContent, w, h, area);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Mouse – pan
    // -----------------------------------------------------------------------

    private void handleMousePressed(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY) {
            panStartX    = e.getX();
            panStartY    = e.getY();
            panStartArea = displayArea;
            setCursor(Cursor.CLOSED_HAND);
        }
    }

    private void handleMouseDragged(MouseEvent e) {
        if (panStartArea == null) {
            return;
        }

        double scaleX = panStartArea.getWidth()  / getWidth();
        double scaleY = panStartArea.getHeight() / getHeight();

        // Dragging right moves the world left (subtract); dragging down moves it up (add).
        double worldDx = -(e.getX() - panStartX) * scaleX;
        double worldDy =  (e.getY() - panStartY) * scaleY;

        displayArea = new ReferencedEnvelope(
                panStartArea.getMinX() + worldDx,
                panStartArea.getMaxX() + worldDx,
                panStartArea.getMinY() + worldDy,
                panStartArea.getMaxY() + worldDy,
                panStartArea.getCoordinateReferenceSystem());

        scheduleRender();
    }

    private void handleMouseReleased(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY) {
            panStartArea = null;
            setCursor(Cursor.DEFAULT);
        }
    }

    // -----------------------------------------------------------------------
    // Mouse – zoom
    // -----------------------------------------------------------------------

    private void handleScroll(ScrollEvent e) {
        if (displayArea == null) {
            return;
        }

        // Scroll up → zoom in (shrink the envelope).
        double factor = e.getDeltaY() > 0 ? 1.0 / ZOOM_FACTOR : ZOOM_FACTOR;

        double scaleX = displayArea.getWidth()  / getWidth();
        double scaleY = displayArea.getHeight() / getHeight();

        double worldX = displayArea.getMinX() + e.getX() * scaleX;
        double worldY = displayArea.getMaxY() - e.getY() * scaleY;

        double newWidth  = displayArea.getWidth()  * factor;
        double newHeight = displayArea.getHeight() * factor;

        double newMinX = worldX - (e.getX() / getWidth())  * newWidth;
        double newMinY = worldY - (1.0 - e.getY() / getHeight()) * newHeight;

        displayArea = new ReferencedEnvelope(
                newMinX, newMinX + newWidth,
                newMinY, newMinY + newHeight,
                displayArea.getCoordinateReferenceSystem());

        scheduleRender();
    }
}
