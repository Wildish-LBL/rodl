package pl.psnc.dl.wf4ever.resources;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import pl.psnc.dl.wf4ever.W4ETest;
import pl.psnc.dl.wf4ever.model.RDF.Thing;

import com.sun.jersey.api.client.ClientResponse;

public class AdminTest extends W4ETest {

    protected String urlConnectionString;
    protected static final String CONNECTION_PROPERTIES_FILENAME = "connection.properties";
    protected String getAllQuery = "uri:uri";
    protected String cleaningString = "update?commit=true -d  '<delete><query>*:*</query></delete>'";


    @Override
    public void setUp()
            throws Exception {
        super.setUp();
        accessToken = createAccessToken(userId);
        try (InputStream is = Thing.class.getClassLoader().getResourceAsStream(CONNECTION_PROPERTIES_FILENAME)) {
            Properties props = new Properties();
            props.load(is);
            urlConnectionString = props.getProperty("solrServer");

        } catch (Exception e) {
            //do nth, it's just a test
        }
    }


    @Override
    public void tearDown()
            throws Exception {
        deleteAccessToken(accessToken);
        super.tearDown();
    }


    @Test
    public void testUpdateIndexAttributes()
            throws IOException {
        System.out.println("********************");
        System.out.println("**********1*********");
        System.out.println("********************");
        URI ro1uri = createRO(accessToken);
        URI ro2uri = createRO(accessToken);
        System.out.println("********************");
        System.out.println("**********2*********");
        System.out.println("********************");
        ClientResponse response = webResource.uri(URI.create(urlConnectionString)).path("select")
                .queryParam("q", getAllQuery).get(ClientResponse.class);
        System.out.println("********************");
        System.out.println("**********3*********");
        System.out.println("********************");
        InputStream is = response.getEntityInputStream();
        String result = IOUtils.toString(is);
        System.out.println(result);
        System.out.println("********************");
        System.out.println("**********4*********");
        System.out.println("********************");
    }


    public void testDeleteIndexAttributes() {

    }

}
