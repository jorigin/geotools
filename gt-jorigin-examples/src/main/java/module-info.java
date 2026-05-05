/**
 * JavaFX example applications demonstrating the JOrigin GeoTools extensions.
 *
 * <p>Includes Shapefile, WMS and WMTS viewers built on top of the
 * {@code MapPaneJFX} component provided by the {@code gt-jfx} module.</p>
 *
 * <p>This module {@code requires} the
 * {@code org.jorigin.geotools.referencing.spatialreference} module so that
 * its {@code CRSAuthorityFactory} provider (IGNF) is reachable in the
 * resolved module graph at runtime, even though no class from that module
 * is imported here.</p>
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
module org.jorigin.geotools.examples {

    requires java.desktop;
    requires java.logging;

    requires transitive org.jorigin.geotools.jfx;
    requires org.jorigin.geotools.referencing.spatialreference;

    requires org.geotools.api;
    requires org.geotools.main;
    requires org.geotools.render;
    requires org.geotools.referencing;
    requires org.geotools.wms;
    requires org.geotools.wmts;

    requires javafx.controls;
    requires javafx.graphics;

    // JavaFX launcher reflectively instantiates Application subclasses.
    opens org.jorigin.geotools.jfx to javafx.graphics;
}
