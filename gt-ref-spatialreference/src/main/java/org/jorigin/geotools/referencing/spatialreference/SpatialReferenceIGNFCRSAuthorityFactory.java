package org.jorigin.geotools.referencing.spatialreference;

import org.geotools.api.metadata.citation.Citation;
import org.geotools.api.metadata.citation.PresentationForm;
import org.geotools.api.metadata.citation.Role;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CRSAuthorityFactory;
import org.geotools.api.util.InternationalString;
import org.geotools.metadata.iso.citation.*;
import org.geotools.util.SimpleInternationalString;

import java.io.IOException;
import java.util.Set;
import java.util.logging.Level;

/**
 * A {@link CRSAuthorityFactory} that request Coordinates References System from the <a href="https://spatialreference.org/">spatialreference.org</a> for the authority <a href="http://www.ign.fr">Institut national de l'information Géographique et Forestiere (IGN)</a>.
 * This factory can process coordinates reference system with code "IGNF:XXXX".
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public class SpatialReferenceIGNFCRSAuthorityFactory extends SpatialReferenceCRSAuthorityFactory{

    /** The IGNF authority */
    public static final String AUTHORITY_NAME = "IGNF";

    /** The IGNF authority */
    public static final String AUTHORITY_URL = "http://www.ign.fr/";

    /** The Authority citation */
    private final CitationImpl authorityCitation;

    /** The default coordinate system authority factory.
     * Will be constructed only when first requested.
     */
    protected static SpatialReferenceIGNFCRSAuthorityFactory DEFAULT;

    /**
     * Returns a default coordinate system factory backed by the spatialreference.org website.
     * @return The default factory.
     * @throws IOException if the connection to the spatialreference.org website cannot be established.
     */
    public static synchronized CRSAuthorityFactory getDefault() throws IOException {
        if (DEFAULT == null) {
            DEFAULT = new SpatialReferenceIGNFCRSAuthorityFactory();
        }
        return DEFAULT;
    }

    /**
     * Create a new spatialreference.org CRS factory for IGNF authority.
     */
    public SpatialReferenceIGNFCRSAuthorityFactory(){
        super();

        setAuthorityName(AUTHORITY_NAME);

        // Create Citation
        final InternationalString organisationName = new SimpleInternationalString(AUTHORITY_NAME);

        final OnLineResourceImpl or = new OnLineResourceImpl();
        or.setName(AUTHORITY_URL);

        final ContactImpl c = new ContactImpl(or);
        c.freeze();

        final ResponsiblePartyImpl r = new ResponsiblePartyImpl(Role.RESOURCE_PROVIDER);
        r.setOrganisationName(organisationName);
        r.setContactInfo(c);
        r.freeze();

        // Citation Title and Identifier  has to contains the AUTHORITHY_NAME to be activated
        // when using a CRS.decode()
        authorityCitation = new CitationImpl(r);
        authorityCitation.setTitle(new SimpleInternationalString(AUTHORITY_NAME));
        authorityCitation.getAlternateTitles().add(organisationName);
        authorityCitation.getIdentifiers().add(new org.geotools.metadata.iso.IdentifierImpl(AUTHORITY_NAME));
        authorityCitation.getPresentationForm().add(PresentationForm.DOCUMENT_DIGITAL);
        authorityCitation.freeze();
    }

    @Override
    public Citation getAuthority() {
        return authorityCitation;
    }

    /**
     * Returns the set of authority codes of the given type. The type argument specify the base class. For example if
     * this factory is an instance of CRSAuthorityFactory, then:
     *
     * <ul>
     *   <li>CoordinateReferenceSystem.class asks for all authority codes accepted by createGeographicCRS,
     *       createProjectedCRS, createVerticalCRS, createTemporalCRS and their friends.
     *   <li>ProjectedCRS.class asks only for authority codes accepted by createProjectedCRS.
     * </ul>
     *
     * The following implementaiton filters the set of codes based on the "PROJCS" and "GEOGCS" at the start of the WKT
     * strings. It is assumed that we only have GeographicCRS and ProjectedCRS's here.
     *
     * @param clazz The spatial reference objects type (may be Object.class).
     * @return The set of authority codes for spatial reference objects of the given type. If this factory doesn't
     *     contains any object of the given type, then this method returns an empty set.
     * @throws FactoryException if access to the underlying database failed.
     */
    @Override
    public Set<String> getAuthorityCodes(Class clazz) throws FactoryException {
        LOGGER.log(Level.INFO, "Not yet Implemented");
        return null;
    }
}
