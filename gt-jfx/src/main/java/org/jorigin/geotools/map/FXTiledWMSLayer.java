package org.jorigin.geotools.map;

import org.geotools.ows.wms.Layer;
import org.geotools.ows.wms.WebMapServer;
import org.geotools.ows.wms.map.WMSLayer;

/**
 * Marker {@link WMSLayer} that asks FX renderers to tile the {@code GetMap}
 * requests client-side on the Pseudo-Mercator ("PM") quadtree grid
 * ({@link PMTileGrid}) — instead of issuing one big
 * {@code GetMap} per view.
 *
 * <h2>Motivation</h2>
 * <p>Many WMS endpoints are actually WMS-R (tile-cached WMS): the server keeps
 * a cache of pre-rendered tiles aligned on the PM grid and serves cached bytes
 * when the {@code GetMap} bbox / size match a grid tile exactly. Typical
 * ad-hoc {@code GetMap}s produced from a freely-panned viewport miss that
 * cache and force a fresh render, which is orders of magnitude slower.
 *
 * <p>Tiling the request client-side on the PM grid has three benefits:
 * <ol>
 *   <li>every tile URL is stable across pan/zoom, so it reuses the renderer's
 *       URL-keyed image cache;</li>
 *   <li>the server hits its own tile cache — usually ~10× faster than rendering;</li>
 *   <li>tiles are loaded in parallel via JavaFX async image I/O, so the view
 *       fills progressively as responses come in.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   WebMapServer wms       = new WebMapServer(url);
 *   Layer layer            = wms.getCapabilities().getLayerList().get(i);
 *   FXTiledWMSLayer mapLyr = new FXTiledWMSLayer(wms, layer);
 *   mapContent.addLayer(mapLyr);
 * }</pre>
 *
 * <p>Renderers that do not recognize this subclass treat it as a regular
 * {@link WMSLayer} and fall back to the single-request pipeline.
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 */
public class FXTiledWMSLayer extends WMSLayer {

    /**
     * Builds a new FX tiled WMS map layer.
     *
     * @param wms   the WMS server
     * @param layer the WMS layer descriptor (from capabilities)
     */
    public FXTiledWMSLayer(WebMapServer wms, Layer layer) {
        super(wms, layer);
    }
}
