/**
 * JavaFX bindings for GeoTools.
 *
 * <p>Provides a JavaFX {@link javafx.scene.layout.Pane Pane}
 * ({@code MapPaneJFX}) along with renderers ({@code StreamingRendererJFX},
 * {@code CachedRendererJFX}) that draw a GeoTools
 * {@link org.geotools.map.MapContent MapContent} onto a JavaFX
 * {@link javafx.scene.canvas.Canvas Canvas}. Also exposes JavaFX-friendly
 * tiled WMS and WMTS map layers in the {@code map} package.</p>
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
module org.jorigin.geotools.jfx {

    requires java.desktop;
    requires java.logging;

    requires transitive javafx.base;
    requires transitive javafx.graphics;
    requires transitive javafx.controls;
    requires javafx.swing;

    requires transitive org.geotools.api;
    requires transitive org.geotools.main;
    requires transitive org.geotools.render;
    requires transitive org.geotools.referencing;
    requires transitive org.geotools.coverage;
    requires transitive org.geotools.wms;
    requires transitive org.geotools.wmts;
    requires org.geotools.tile_client;

    requires transitive org.locationtech.jts;

    exports org.jorigin.geotools;
    exports org.jorigin.geotools.map;
    exports org.jorigin.geotools.renderer;
}
