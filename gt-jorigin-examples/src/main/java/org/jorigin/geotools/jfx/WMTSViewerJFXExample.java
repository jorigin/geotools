package org.jorigin.geotools.jfx;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * A launcher for the {@link WMSViewerJFXApp JavaFX application} that display a <a href="https://www.ogc.org/fr/standards/wmts/">Web Map Tile Service (WMTS) stream</a>.
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public final class WMTSViewerJFXExample {

    /**
     * This is a static class.
     */
    private WMTSViewerJFXExample(){}

    /**
     * The main method
     * @param args the main method arguments
     */
    public static void main(String[] args){

        // Set up logging
        Logger log = Logger.getLogger("");
        log.setLevel(Level.INFO);

        ConsoleHandler handler = new ConsoleHandler();

        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.INFO);

        log.addHandler(handler);

        // Run the §JFX App
        WMTSViewerJFXApp.launch(WMTSViewerJFXApp.class, args);
    }
}
