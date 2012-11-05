package pl.psnc.dl.wf4ever;

import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import com.sun.jersey.api.view.Viewable;

/**
 * The base URI of RODL.
 * 
 * @author piotrekhol
 * 
 */
@Path("/")
public class RootResource {

    /**
     * Return the main HTML page.
     * 
     * @return an HTML page
     */
    @GET
    @Produces("text/html")
    public Response index() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("version", ApplicationProperties.getVersion());
        return Response.ok(new Viewable("/index", map)).build();
    }
}
