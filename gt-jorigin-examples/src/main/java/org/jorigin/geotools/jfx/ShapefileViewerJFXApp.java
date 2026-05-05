package org.jorigin.geotools.jfx;

import java.io.File;
import java.net.URL;

import org.jorigin.geotools.CachedRendererJFX;
import org.geotools.api.data.FileDataStore;
import org.geotools.api.data.FileDataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.style.Style;
import org.geotools.map.FeatureLayer;
import org.geotools.map.MapContent;
import org.geotools.styling.SLD;

import org.jorigin.geotools.MapPaneJFX;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * A JavaFX application that display a <a href="https://www.esri.com/content/dam/esrisites/sitecore-archive/Files/Pdfs/library/whitepapers/pdfs/shapefile.pdf">Shapefile</a>.
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public class ShapefileViewerJFXApp extends Application {

    /**
     * Create a new JavaFX application.
     */
    public ShapefileViewerJFXApp(){

    }

    @Override
    public void start(Stage primaryStage) throws Exception {

        // --- Load the shapefile from classpath resources ---
        URL shapefileUrl = getClass().getClassLoader().getResource("shapefile/world-land/ne_10m_land.shp");

        if (shapefileUrl == null) {
            throw new IllegalStateException(
                    "Shapefile not found in resources: shapefile/world-land/ne_10m_land.shp");
        }

        FileDataStore dataStore = FileDataStoreFinder.getDataStore(new File(shapefileUrl.toURI()));
        SimpleFeatureSource featureSource = dataStore.getFeatureSource();

        // Default style: filled polygon with a contrasting outline
        Style style = SLD.createPolygonStyle(
                java.awt.Color.DARK_GRAY,   // stroke colour
                java.awt.Color.decode("#A8D5A2"), // fill colour (muted green)
                0.8f);                      // opacity

        // --- Build the MapContent ---
        MapContent mapContent = new MapContent();
        mapContent.setTitle("Natural Earth – Land (1:10m)");
        mapContent.addLayer(new FeatureLayer(featureSource, style));

        // --- Build the scene ---
        MapPaneJFX mapPane = new MapPaneJFX(mapContent, new CachedRendererJFX());

        BorderPane root = new BorderPane(mapPane);
        Scene scene = new Scene(root, 900, 500);

        primaryStage.setTitle("MapPaneJFX – ne_10m_land");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> mapPane.dispose());
        primaryStage.show();
    }

    /**
     * The main method.
     * @param args the main method arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}
