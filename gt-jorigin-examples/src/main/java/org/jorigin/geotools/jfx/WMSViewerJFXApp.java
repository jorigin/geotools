package org.jorigin.geotools.jfx;

import java.net.URI;
import java.net.URL;
import java.util.List;

import org.jorigin.geotools.CachedRendererJFX;
import org.jorigin.geotools.map.FXTiledWMSLayer;
import org.geotools.api.data.ServiceInfo;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.MapContent;
import org.geotools.ows.wms.Layer;
import org.geotools.ows.wms.WMSCapabilities;
import org.geotools.ows.wms.WebMapServer;
import org.geotools.ows.wms.map.WMSLayer;
import org.geotools.referencing.CRS;

import org.jorigin.geotools.MapPaneJFX;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * A JavaFX application that display a <a href="https://www.ogc.org/fr/standards/wms/">Web Map Service (WMS) stream</a>.
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public class WMSViewerJFXApp extends Application {


    /** The coordinate reference system that is used. */
    final String crsName = "EPSG:3857";

    /** WMS-R endpoint of the French Géoplateforme (IGN). */
    final String wmsServer = "https://data.geopf.fr/wms-r";

    /** Name of the WMS layer to display (Plan IGN v2). */
    final String wmsLayerName = "HR.ORTHOIMAGERY.ORTHOPHOTOS";

    /**
     * Create a new JavaFX application.
     */
    public WMSViewerJFXApp(){}

    @Override
    public void start(Stage primaryStage) throws Exception {

        // --- Connect to the WMS server and retrieve capabilities ---
        URL url = URI.create(wmsServer + "?SERVICE=WMS&REQUEST=GetCapabilities").toURL();
        WebMapServer wms = new WebMapServer(url);

        ServiceInfo info = wms.getInfo();

        System.out.println("WMS Server info");
        System.out.println("  Title      : "+info.getTitle());
        System.out.println("  Description: "+info.getDescription());
        System.out.println("  Title: "+info.getPublisher());

        WMSCapabilities capabilities = wms.getCapabilities();

        System.out.println();
        System.out.println("WMS Server capabilities");

        
        List<Layer> wmsLayers = capabilities.getLayerList();
        
        System.out.println();
        if ((wmsLayers == null) || (wmsLayers.isEmpty())){
            System.out.println("No layer available.");
            System.exit(1);
        }

        // Display all the available layers name
        // and select the required layer by its name
        System.out.println("  Layers: ");

        Layer wmsLayer = null;

        for(Layer l : wmsLayers){
            System.out.println("    "+l.getName()+" ["+l.getLatLonBoundingBox()+"]");

            if (wmsLayerName.equals(l.getName())) {
                wmsLayer = l;
            }
        }

        if (wmsLayer == null) {
            throw new IllegalStateException(
                    "Layer '" + wmsLayerName + "' not found in WMS capabilities from " + wmsServer);
        }

        System.out.println("Layer \""+wmsLayer.getName()+"\" bounding box: "+wmsLayer.getLatLonBoundingBox());

        // Create a Geotools Layer from WMS layer
        WMSLayer layer = null;

        // This step will try to decode CRS from its name
        // The dependency gt-epsg-hsql is needed at this point
        try {
            // FXTiledWMSLayer opts the layer into client-side PM-grid tiling:
            // CachedRendererJFX issues one GetMap per 256×256 PM tile, which
            // the Géoplateforme wms-r endpoint serves from its tile cache
            // (orders of magnitude faster than ad-hoc GetMaps) and whose URLs
            // are reused across pan/zoom via the renderer's image cache.
            layer = new FXTiledWMSLayer(wms, wmsLayer);
        } catch (Exception e) {
            System.out.println("Cannot instanciate layer");
            System.out.println("Ensure that dependency gt-epsg-hsql is added to the project");
            e.printStackTrace(System.out);
            System.exit(1);
        }


        // --- Build the MapContent with the WMS layer ---
        MapContent mapContent = new MapContent();
        mapContent.setTitle("Geoplateforme WMS – " + wmsLayerName);
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

    /**
     * The main method.
     * @param args the method arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}
