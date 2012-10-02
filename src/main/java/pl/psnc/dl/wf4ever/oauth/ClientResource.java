package pl.psnc.dl.wf4ever.oauth;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

import pl.psnc.dl.wf4ever.Constants;
import pl.psnc.dl.wf4ever.auth.ForbiddenException;
import pl.psnc.dl.wf4ever.auth.OAuthClient;
import pl.psnc.dl.wf4ever.auth.OAuthManager;
import pl.psnc.dl.wf4ever.dlibra.UserProfile;

/**
 * Client application REST API resource.
 * 
 * @author Piotr Hołubowicz
 * 
 */
@Path(("clients" + "/{C_ID}"))
public class ClientResource {

    /** HTTP request. */
    @Context
    HttpServletRequest request;

    /** URI info. */
    @Context
    UriInfo uriInfo;


    /**
     * Get the OAuth 2.0 client.
     * 
     * @param clientId
     *            client id
     * @return an XML serialization of the client DAO
     */
    @GET
    public OAuthClient getClient(@PathParam("C_ID") String clientId) {
        UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
        OAuthManager oauth = new OAuthManager();

        if (user.getRole() != UserProfile.Role.ADMIN) {
            throw new ForbiddenException("Only admin users can manage clients.");
        }

        return oauth.getClient(clientId);
    }


    /**
     * Deletes the OAuth 2.0 client.
     * 
     * @param clientId
     *            client id
     */
    @DELETE
    public void deleteClient(@PathParam("C_ID") String clientId) {
        UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
        OAuthManager oauth = new OAuthManager();

        if (user.getRole() != UserProfile.Role.ADMIN) {
            throw new ForbiddenException("Only admin users can manage clients.");
        }

        oauth.deleteClient(clientId);
    }
}
