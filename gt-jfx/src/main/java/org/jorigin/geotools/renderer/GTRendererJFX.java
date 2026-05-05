package org.jorigin.geotools.renderer;

import javafx.scene.canvas.Canvas;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.MapContent;

/**
 * A geotools renderer that work with underlying JavaFX components.
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 */
public interface GTRendererJFX {

    /**
     * Get the display {@link Canvas}.  Add it to the JavaFX scene graph
     * to show the rendered map.
     * @return the display canvas
     */
    Canvas getCanvas();

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
    void paint(MapContent content, double w, double h, ReferencedEnvelope area);

    /**
     * Signals the current render to stop after the current feature.
     * Safe to call from any thread.
     */
    void stopRendering();
}
