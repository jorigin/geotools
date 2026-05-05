package org.jorigin.geotools.referencing.spatialreference;

import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.NoSuchAuthorityCodeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * This class enable to connect to <a href="https://spatialreference.org/">spatialreference.org</a> and download Coordinates Reference Systems data from it.
 * @author Julien SEINTURIER - <a href="http://www.univ-tln.fr">Universit&eacute; de Toulon</a>
 *         / <a href="http://www.lis-lab.fr">CNRS LIS umr 7020</a>
 *         - <a href="https://github.com/jseinturier/">github.com/jseinturier</a>
 *         (<a href="mailto:julien.seinturier@univ-tln.fr">julien.seinturier@univ-tln.fr</a>)
 */
public class SpatialReferenceConnector {

    /**
     * The OGC WKT format (for remote CRS encoding)
     * @see #FORMAT_ESRI_WKT
     */
    public static final String FORMAT_OGCWKT = "ogcwkt";

    /**
     * The ESRI WKT format (for remote CRS encoding)
     * @see #FORMAT_OGCWKT
     */
    public static final String FORMAT_ESRI_WKT = "esriwkt.txt";

    /**
     * The reference to the web server.
     */
    private String serverURL = "https://spatialreference.org/ref";

    /**
     * The format that is provided by the remote website.
     */
    private String format = FORMAT_OGCWKT;

    /**
     * Construct a default connector to spatialreference.org.
     */
    public SpatialReferenceConnector(){

    }

    /**
     * Get the format of the server response.
     * @return the format of the server response
     * @see #setFormat(String)
     */
    public String getFormat(){
        return this.format;
    }

    /**
     * Set the format of the server response.
     * @param format the format of the server response
     * @see #getFormat()
     */
    public void setFormat(String format){
        this.format = format;
    }

    /**
     * Get the server URL.
     * @return the server URL
     * @see #setServerURL(String)
     */
    public String getServerURL() {
        return serverURL;
    }

    /**
     * Set the server URL.
     * @param url the server URL
     */
    public void setServerURL(String url) {
        this.serverURL = url;
    }

    /**
     * Get the URL String that correspond to the given coordinates reference system identified by its {@code authority} and {@code number}.
     * @param authority the authority of the coordinates reference system
     * @param number the number coordinates reference system
     * @return the URL String that correspond to the given coordinates reference system
     * @see #getURL(String, String) 
     */
    protected String getURLString(String authority, String number) {
        return serverURL+"/"+authority+"/"+number+"/"+format;
    }

    /**
     * Get the {@link URL} that correspond to the given coordinates reference system identified by its {@code authority} and {@code number}.
     * @param authority the authority of the coordinates reference system
     * @param number the number coordinates reference system
     * @return the URL that correspond to the given coordinates reference system
     * @throws MalformedURLException if an error occurs
     * @see #loadData(String, String) 
     */
    protected URL getURL(String authority, String number) throws MalformedURLException {
        // Create an URL for the authority / number
        String str = getURLString(authority, number);
        URL url;
        try {
            url = URI.create(str).toURL();
            return url;
        } catch (MalformedURLException e) {
            throw new MalformedURLException("Malformed URL \""+str+"\"");
        }
    }

    /**
     * Load the data provided by a request to the web server for the given coordinates reference system identified by its {@code authority} and {@code number}.
     * @param authority the authority of the coordinates reference system
     * @param number the number coordinates reference system
     * @return the data provided by a request to the web server for the given coordinates reference system
     * @throws FactoryException if an error occurs
     * @see #getURL(String, String)
     */
    protected String loadData(String authority, String number) throws FactoryException {
        // Request the web server
        String data;

        // Get URL
        URL url;
        try {
            url = getURL(authority, number);
        } catch (MalformedURLException e) {
            throw new FactoryException("Cannot set up URL for authority \""+authority+"\" and code \""+number+"\"", e);
        }

        // Establish a connection
        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Accept", "text/html, */*");
        } catch (IOException e) {
            throw new FactoryException("Cannot connect to \""+url+"\"", e);
        }

        // Check the response code
        int responseCode;
        try {
            responseCode = connection.getResponseCode();
        } catch (IOException e) {
            throw new FactoryException("Cannot extract response code from \""+url+"\"", e);
        }

        if (responseCode != 200){
            throw new NoSuchAuthorityCodeException("Cannot read response from \""+url+"\", got code "+responseCode, authority, number);
        }

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            StringBuilder response = new StringBuilder();
            String inputLine;

            while ((inputLine = in.readLine()) != null)
                response.append(inputLine);

            in.close();

            data = response.toString();
        } catch (Exception e) {
            throw new FactoryException("Cannot read response stream from \""+url+"\"", e);
        }

        return data;
    }
}
