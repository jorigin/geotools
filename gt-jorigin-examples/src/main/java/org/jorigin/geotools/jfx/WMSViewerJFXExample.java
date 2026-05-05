package org.jorigin.geotools.jfx;

/**
 * A launcher for the {@link WMSViewerJFXApp JavaFX application} that display a <a href="https://www.ogc.org/fr/standards/wms/">Web Map Service (WMS) stream</a>.
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public final class WMSViewerJFXExample {

    /**
     * This is a static class.
     */
    private WMSViewerJFXExample(){}

    /**
     * The main method.
     * @param args the main method arguments
     */
    public static void main(String[] args){
        WMSViewerJFXApp.launch(WMSViewerJFXApp.class, args);
    }
}
