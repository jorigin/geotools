package org.jorigin.geotools.jfx;

/**
 * A launcher for the {@link ShapefileViewerJFXApp JavaFX application} that display a <a href="https://www.esri.com/content/dam/esrisites/sitecore-archive/Files/Pdfs/library/whitepapers/pdfs/shapefile.pdf">Shapefile</a>.
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public final class ShapefileViewerJFXExample {

    /**
     * This is a static class.
     */
    private ShapefileViewerJFXExample(){}

    /**
     * The main method.
     * @param args the main method arguments
     */
    public static void main(String[] args){
        ShapefileViewerJFXApp.launch(ShapefileViewerJFXApp.class, args);
    }
}
