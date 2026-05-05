/**
 * GeoTools {@link org.geotools.api.referencing.crs.CRSAuthorityFactory CRSAuthorityFactory}
 * extension that resolves Coordinate Reference Systems through the
 * <a href="https://spatialreference.org/">spatialreference.org</a> service.
 *
 * <p>Registers {@code SpatialReferenceIGNFCRSAuthorityFactory} as a service
 * provider so that GeoTools' {@code ReferencingFactoryFinder} discovers it
 * via the standard {@link java.util.ServiceLoader} mechanism, both on the
 * module path (through the {@code provides} declaration below) and on the
 * classpath (through {@code META-INF/services}).</p>
 *
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
module org.jorigin.geotools.referencing.spatialreference {

    requires java.logging;

    requires transitive org.geotools.api;
    requires transitive org.geotools.metadata;
    requires org.geotools.referencing;

    exports org.jorigin.geotools.referencing.spatialreference;

    provides org.geotools.api.referencing.crs.CRSAuthorityFactory
        with org.jorigin.geotools.referencing.spatialreference.SpatialReferenceIGNFCRSAuthorityFactory;
}
