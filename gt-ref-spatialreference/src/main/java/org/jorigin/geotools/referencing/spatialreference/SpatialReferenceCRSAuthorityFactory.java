package org.jorigin.geotools.referencing.spatialreference;

import org.geotools.api.metadata.citation.Citation;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.IdentifiedObject;
import org.geotools.api.referencing.NoSuchAuthorityCodeException;
import org.geotools.api.referencing.ObjectFactory;
import org.geotools.api.referencing.crs.*;
import org.geotools.api.util.InternationalString;
import org.geotools.metadata.iso.citation.Citations;
import org.geotools.referencing.ReferencingFactoryFinder;
import org.geotools.util.Version;
import org.geotools.util.factory.AbstractFactory;
import org.geotools.util.factory.Hints;

import java.net.*;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link CRSAuthorityFactory} that enable to deal with Coordinate Reference System from the website <a href="https://https://spatialreference.org">spatialreference.org</a>.
 * This factory is abstract and has to be extended for each authority that is hosted by the website.
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public abstract class SpatialReferenceCRSAuthorityFactory extends AbstractFactory implements CRSAuthorityFactory {

    /**
     * The logger to use.
     */
    protected static final Logger LOGGER = Logger.getLogger(SpatialReferenceCRSAuthorityFactory.class.getSimpleName());

    /**
     * The instantiated object factory.
     */
    protected CRSFactory crsFactory;

    /** The connector to spatialreference.org website. */
    private SpatialReferenceConnector connector;

    /** The authority */
    private String authorityName = "UNKNOWN";

    /**
     * The CRS that have already been decoded
     */
    protected Properties cache;

    /**
     * Create a new factory.
     */
    public SpatialReferenceCRSAuthorityFactory() {
        this(ReferencingFactoryFinder.getCRSFactory(null));
    }

    /**
     * Create a new factory with the given definition.
     * @param factory the underlying factory
     * @param definition the authority definition URL
     */
    protected SpatialReferenceCRSAuthorityFactory(final CRSFactory factory, URL definition) {
        this(factory);
    }

    /**
     * Create a new factory.
     * @param factory the underlying factory
     */
    protected SpatialReferenceCRSAuthorityFactory(final CRSFactory factory) {
        super(MINIMUM_PRIORITY); // Priority to other factories.
        this.crsFactory = factory;

        // Add hints to avoid this being chosen when those are asked for.
        this.hints.put(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.FALSE);
        this.hints.put(Hints.VERSION, new Version(""));

        this.cache = new Properties();

        this.connector = new SpatialReferenceConnector();
    }

    @Override
    public CoordinateReferenceSystem createCoordinateReferenceSystem(String code) throws NoSuchAuthorityCodeException, FactoryException {

        LOGGER.log(Level.FINE, "Creating CRS for \""+code+"\"");

        if (code == null) {
            return null;
        }

        int separatorIndex = code.indexOf(':');

        if ((code.length() < 3) || (separatorIndex <= 0) || (separatorIndex >= code.length() - 1)){
            throw new FactoryException("Invalid CRS code \""+code+"\", expected form is \"AUTHORITY:NUMBER\"");
        }

        String authority = code.substring(0, separatorIndex).trim();
        String number = code.substring(separatorIndex+1).trim();

        // Check if the requested CRS has already been decoded to avoid requesting.
        if (this.cache.containsKey(authority+":"+number)) {
            Object value = this.cache.get(authority+":"+number);

            if (value instanceof CoordinateReferenceSystem system){
                LOGGER.log(Level.INFO, "Loaded cached CRS "+system.getName()+" from "+this.connector.getURLString(authority, number));
                return system;
            }
        }

        // Load data from server
        String data = this.connector.loadData(authority.toLowerCase(), number);

        if (!data.contains(number)) {
            data = data.trim();
            data = data.substring(0, data.length() - 1);
            data += ",AUTHORITY[\""+authority+"\",\"" + number + "\"]]";
            LOGGER.log(Level.WARNING,
                    authority+":"+number + " lacks a proper identifying authority in its Well-Known Text. It is being added programmatically.");
        }

        CoordinateReferenceSystem crs = this.crsFactory.createFromWKT(data);
        this.cache.put(authority+":"+number, crs);

        LOGGER.log(Level.INFO, "Loaded CRS "+crs.getName()+" from "+this.connector.getURLString(authority, number));

        return crs;
    }

    @Override
    public IdentifiedObject createObject(String code) throws NoSuchAuthorityCodeException, FactoryException {
        return createCoordinateReferenceSystem(code);
    }

    @Override
    public ProjectedCRS createProjectedCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        return (ProjectedCRS) createCoordinateReferenceSystem(code);
    }

    @Override
    public GeographicCRS createGeographicCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        return (GeographicCRS) createCoordinateReferenceSystem(code);
    }

    @Override
    public CompoundCRS createCompoundCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented");
    }

    @Override
    public DerivedCRS createDerivedCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented");
    }

    @Override
    public EngineeringCRS createEngineeringCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented");
    }

    @Override
    public GeocentricCRS createGeocentricCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented");
    }

    @Override
    public ImageCRS createImageCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented");
    }

    @Override
    public TemporalCRS createTemporalCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented");
    }

    @Override
    public VerticalCRS createVerticalCRS(String code) throws NoSuchAuthorityCodeException, FactoryException {
        throw new FactoryException("Not implemented");
    }

    @Override
    public InternationalString getDescriptionText(String code) throws NoSuchAuthorityCodeException, FactoryException {
        if (code == null) {
            return null;
        }

        CoordinateReferenceSystem crs = createCoordinateReferenceSystem(code);

        String wkt = crs.toWKT().trim();
        int start = wkt.indexOf('"');
        int end = wkt.indexOf('"', start + 1);
        return new org.geotools.util.SimpleInternationalString(wkt.substring(start + 1, end));
    }

    @Override
    public Citation getVendor() {
        return Citations.GEOTOOLS;
    }

    /**
     * Get the name of the authority provided by spatialreference.org website.
     * @return the name of the authority provided by spatialreference.org website.
     * @see #setAuthorityName(String)
     */
    protected String getAuthorityName() {
        return this.authorityName;
    }

    /**
     * Set the name of the authority provided by spatialreference.org website.
     * @param authority the name of the authority provided by spatialreference.org website.
     * @see #getAuthorityName()
     */
    protected void setAuthorityName(String authority) {
        this.authorityName = authority;
    }

    /**
     * Get a reference to this factory instance as an {@link ObjectFactory object factory}.
     * @return the object factory
     */
    public ObjectFactory getObjectFactory() {
        return crsFactory;
    }
}
