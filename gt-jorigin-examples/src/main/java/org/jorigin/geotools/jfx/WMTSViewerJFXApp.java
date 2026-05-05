package org.jorigin.geotools.jfx;

import org.jorigin.geotools.CachedRendererJFX;
import org.jorigin.geotools.MapPaneJFX;
import org.jorigin.geotools.map.FXWMTSMapLayer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.MapContent;
import org.geotools.ows.wms.Layer;
import org.geotools.ows.wms.StyleImpl;
import org.geotools.ows.wmts.WebMapTileServer;
import org.geotools.ows.wmts.map.WMTSMapLayer;
import org.geotools.ows.wmts.model.WMTSCapabilities;
import org.geotools.ows.wmts.model.WMTSLayer;
import org.geotools.referencing.CRS;

import java.net.URI;
import java.net.URL;
import java.util.List;

/**
 * A JavaFX application that display a <a href="https://www.ogc.org/fr/standards/wmts/">Web Map Tile Service (WMTS) stream</a>.
 * <br><br>
 * Detailed Geotools documentation for WMTS is available at <a href="https://docs.geotools.org/stable/userguide/extension/wmts/index.html">https://docs.geotools.org/stable/userguide/extension/wmts/index.html</a>
 * <br><br>
 * This class use the IGN WMTS server as data souce (see <a href="https://cartes.gouv.fr/aide/fr/guides-utilisateur/utiliser-les-services-de-la-geoplateforme/diffusion/wmts/">https://cartes.gouv.fr/aide/fr/guides-utilisateur/utiliser-les-services-de-la-geoplateforme/diffusion/wmts/</a> for details)
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public class WMTSViewerJFXApp extends Application {

    /** The coordinate reference system that is used. */
    final String crsName = "EPSG:3857";

    /** WMTS endpoint of the French Géoplateforme (IGN). */
    final String wmtsServer = "https://data.geopf.fr/wmts";

    /** Name of the WMTS layer to display (LIDAR HD). */
    final String wmtsLayerName = "IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.SHADOW";

    /**
     * Create a new JavaFX application.
     */
    public WMTSViewerJFXApp(){}

    @Override
    public void start(Stage primaryStage) throws Exception {

        // Connect to the WMTS server.
        // Use IgnfPatchingHttpClient to replace IGNF CRS codes with EPSG equivalents
        // in the GetCapabilities response before GeoTools parses it.
        URL url = URI.create(wmtsServer + "?SERVICE=WMTS&VERSION=1.0.0").toURL();

        WebMapTileServer server;
        try {
            //wmts = new WebMapTileServer(url, new IgnfPatchingHttpClient());
            server = new WebMapTileServer(url);
        } catch (Exception e) {
            System.err.println("Cannot instantiate WMTS server: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
            return;
        }

        // Extract server capabilities
        WMTSCapabilities capabilities = server.getCapabilities();
        String serverName  = capabilities.getService().getName();
        String serverTitle = capabilities.getService().getTitle();
        System.out.println("Capabilities retrieved from server: " + serverName + " (" + serverTitle + ")");

        // Get the layers and their formats
        List<WMTSLayer> layers = capabilities.getLayerList();

        System.out.println("Available layers:");
        for (WMTSLayer layer : layers) {
            System.out.println("  Layer \"" + layer.getName() + "\": "+layer.getTitle());

            List<String> formats = layer.getFormats();
            System.out.println("    Formats:");
            for (String format : formats) {
                System.out.println("      " + format);
            }

            System.out.println("    Styles:");
            for (StyleImpl style : layer.getStyles()) {
                System.out.println("      Name:  " + style.getName());
                System.out.println("      Title: " + style.getTitle());
            }
        }

        // Load specific layer
        Layer wmtsLayer = capabilities.getLayer("IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.SHADOW");

        System.out.println("Selected layer: "+wmtsLayer.getName());

        // Create a Geotools Layer from WMS layer.
        // FXWMTSMapLayer retains references to the server and layer so the
        // CachedRendererJFX can bypass the (sequential) WMTSCoverageReader
        // pipeline and fetch tiles in parallel via JavaFX async image loading.
        WMTSMapLayer layer = null;

        // This step will try to decode CRS from its name
        // The dependency gt-epsg-hsql is needed at this point
        try {
            layer = new FXWMTSMapLayer(server, wmtsLayer);
        } catch (Exception e) {
            System.out.println("Cannot instanciate layer: "+e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        }

        // --- Build the MapContent with the WMS layer ---
        MapContent mapContent = new MapContent();
        mapContent.setTitle("Geoplateforme WMTS – " + wmtsLayerName);
        mapContent.addLayer(layer);

        // --- Build the JavaFX scene ---
        MapPaneJFX mapPane = new MapPaneJFX(mapContent, new CachedRendererJFX());

        // Zoom to the desired area
        mapPane.setDisplayArea(new ReferencedEnvelope(624299.4285, 722138.8247, 5321781.2655, 5370662.7451, CRS.decode(this.crsName)));

        System.out.println("Displayed area Enveloppe: "+mapPane.getDisplayArea());
        System.out.println("Displayed area CRS      : "+mapPane.getDisplayArea().getCoordinateReferenceSystem());

        BorderPane root = new BorderPane(mapPane);
        Scene scene = new Scene(root, 900, 500);

        primaryStage.setTitle("MapPaneJFX – WMS Géoplateforme");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> mapPane.dispose());
        primaryStage.show();
    }
}
