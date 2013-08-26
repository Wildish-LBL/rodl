package pl.psnc.dl.wf4ever.accesscontrol;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.log4j.Logger;

import pl.psnc.dl.wf4ever.auth.RequestAttribute;
import pl.psnc.dl.wf4ever.model.Builder;

/**
 * API for setting Research Object access mode.
 * 
 * @author pejot
 * 
 */
@Path("accesscontrol/modes/")
public class ModeResource {

    /** logger. */
    private static final Logger LOGGER = Logger.getLogger(ModeResource.class);

    /** URI info. */
    @Context
    UriInfo uriInfo;

    /** Resource builder. */
    @RequestAttribute("Builder")
    private Builder builder;


    @POST
    @Consumes("application/json")
    public Response setMode() {
        return null;
    }


    @Path("{mode_id}/")
    @Produces("application/json")
    @GET
    public Response getMoge(@PathParam("mode_id") String mode_id) {
        return null;
    }


    @Produces("application/json")
    @GET
    public Response getMoges(@QueryParam("ro") String ro) {
        return null;
    }

}
