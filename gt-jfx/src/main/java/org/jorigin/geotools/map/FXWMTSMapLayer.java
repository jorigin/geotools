package org.jorigin.geotools.map;

import org.geotools.ows.wms.Layer;
import org.geotools.ows.wmts.WebMapTileServer;
import org.geotools.ows.wmts.map.WMTSMapLayer;
import org.geotools.ows.wmts.model.WMTSLayer;

/**
 * A {@link WMTSMapLayer} that retains references to the {@link WebMapTileServer}
 * and the {@link WMTSLayer} used to build it, enabling JavaFX renderers to
 * bypass {@link org.geotools.ows.wmts.map.WMTSCoverageReader} and fetch tiles
 * individually in parallel via JavaFX's native async image loading.
 *
 * <h2>Motivation</h2>
 * <p>Stock {@code WMTSMapLayer} stores the server and the layer metadata only
 * inside its internal {@code WMTSCoverageReader} (package-private fields), so
 * a custom renderer can only go through that reader — whose {@code read(...)}
 * method fetches tiles <em>sequentially</em> via blocking HTTP. For a view with
 * 8–12 tiles at 100–400 ms each this adds up to seconds of latency before
 * <em>anything</em> is drawn, and a visible white flash during zoom/pan.
 *
 * <p>By exposing {@link #getServer()} and {@link #getLayerInfo()}, a renderer
 * can construct a {@link org.geotools.ows.wmts.request.GetTileRequest} directly,
 * enumerate the {@link org.geotools.tile.Tile} instances for the visible area,
 * and load each tile URL via a JavaFX {@code Image} with background loading.
 * This method enable to parallelize HTTP on JavaFX's internal image-I/O thread pool and
 * supports progressive rendering (each tile appears as it arrives).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   WebMapTileServer server = new WebMapTileServer(url);
 *   WMTSCapabilities caps   = server.getCapabilities();
 *   Layer wmtsLayer         = caps.getLayer("MY_LAYER");
 *   FXWMTSMapLayer mapLayer = new FXWMTSMapLayer(server, wmtsLayer);
 *   mapContent.addLayer(mapLayer);
 * }</pre>
 *
 * <p>Renderers that do not recognize this subclass will simply treat it as a
 * regular {@link WMTSMapLayer} and fall back to the coverage-reader pipeline.
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 */
public class FXWMTSMapLayer extends WMTSMapLayer {

    private final WebMapTileServer server;
    private final WMTSLayer layerInfo;

    /**
     * Builds a new FX-aware WMTS map layer.
     *
     * @param server the WMTS server (retained for direct tile requests)
     * @param layer  the WMTS layer descriptor from {@code capabilities.getLayer(...)};
     *               must be a {@link WMTSLayer} (the dynamic type returned by
     *               {@code WMTSCapabilities.getLayer})
     * @throws ClassCastException if {@code layer} is not a {@link WMTSLayer}
     */
    public FXWMTSMapLayer(WebMapTileServer server, Layer layer) {
        super(server, layer);
        this.server = server;
        this.layerInfo = (WMTSLayer) layer;
    }

    /**
     * Get the WMTS server this layer was built from.
     * @return the WMTS server this layer was built from
     */
    public WebMapTileServer getServer() {
        return server;
    }

    /**
     * Get the WMTS layer descriptor (from the server's capabilities).
     * @return the WMTS layer descriptor (from the server's capabilities)
     */
    public WMTSLayer getLayerInfo() {
        return layerInfo;
    }
}
