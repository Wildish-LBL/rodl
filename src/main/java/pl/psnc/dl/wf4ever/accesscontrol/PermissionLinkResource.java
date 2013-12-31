package pl.psnc.dl.wf4ever.accesscontrol;

import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.log4j.Logger;

import pl.psnc.dl.wf4ever.accesscontrol.dicts.Role;
import pl.psnc.dl.wf4ever.accesscontrol.model.Permission;
import pl.psnc.dl.wf4ever.accesscontrol.model.PermissionLink;
import pl.psnc.dl.wf4ever.accesscontrol.model.dao.PermissionDAO;
import pl.psnc.dl.wf4ever.accesscontrol.model.dao.PermissionLinkDAO;
import pl.psnc.dl.wf4ever.auth.RequestAttribute;
import pl.psnc.dl.wf4ever.db.UserProfile;
import pl.psnc.dl.wf4ever.db.dao.UserProfileDAO;
import pl.psnc.dl.wf4ever.dl.ConflictException;
import pl.psnc.dl.wf4ever.exceptions.BadRequestException;
import pl.psnc.dl.wf4ever.model.Builder;

import com.hp.hpl.jena.shared.NotFoundException;

/**
 * API for granting permissions.
 * 
 * @author pejot
 * 
 */
@Path("accesscontrol/permissionlinks/")
public class PermissionLinkResource {

    /** logger. */
    private static final Logger LOGGER = Logger.getLogger(PermissionLinkResource.class);

    /** URI info. */
    @Context
    UriInfo uriInfo;

    /** Resource builder. */
    @RequestAttribute("Builder")
    private Builder builder;

    /** Permissions link dao. */
    private PermissionLinkDAO dao = new PermissionLinkDAO();

    /** Permission dao. */
    private PermissionDAO permissionDao = new PermissionDAO();

    /** User Profile dao. */
    private UserProfileDAO userProfileDAO = new UserProfileDAO();


    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addPermissions(PermissionLink permission)
            throws BadRequestException {
        if (dao.findByUserROAndPermission(permission.getUser(), permission.getRo(), permission.getRole()).size() > 0) {
            throw new ConflictException("The permission was already given");
        }
        //first check permissions
        if (!builder.getUser().getRole().equals(pl.psnc.dl.wf4ever.dl.UserMetadata.Role.ADMIN)) {
            UserProfile userProfile = userProfileDAO.findByLogin(builder.getUser().getLogin());
            if (userProfile == null) {
                throw new BadRequestException("There is no user like this");
            }
            List<Permission> permissions = permissionDao.findByUserROAndPermission(userProfile, permission.getRo(),
                Role.OWNER);
            if (permissions.size() == 0) {
                throw new BadRequestException("The given ro doesn't exists or doesn't belong to user");
            } else if (permissions.size() > 1) {
                LOGGER.error("Multiply RO ownership detected for" + permission.getRo());
                throw new WebApplicationException(500);
            }
        }
        dao.save(permission);
        permission.setUri(uriInfo.getRequestUri().resolve(""));
        return Response.created(uriInfo.getRequestUri().resolve("")).type(MediaType.APPLICATION_JSON)
                .entity(permission).build();
    }


    @Path("{permission_id}/")
    @Produces(MediaType.APPLICATION_JSON)
    @GET
    public PermissionLink getPermission(@PathParam("permission_id") String permission_id) {
        PermissionLink result = dao.findById(Integer.valueOf(permission_id));
        if (result != null) {
            result.setUri(uriInfo.getRequestUri().resolve(result.getId().toString()));
        }
        return result;
    }


    @Path("{permission_id}/")
    @Produces(MediaType.APPLICATION_JSON)
    @DELETE
    public Response deletePermission(@PathParam("permission_id") String permission_id) {
        PermissionLink permission = dao.findById(Integer.valueOf(permission_id));
        if (permission == null) {
            throw new NotFoundException("The permission " + permission_id + " doesn't exists");
        }
        dao.delete(permission);
        return Response.noContent().build();
    }


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public PermissionLink[] getPermissions(@QueryParam("ro") String ro) {
        List<PermissionLink> result = dao.findByResearchObject(ro);
        if (result == null || result.size() == 0) {
            return new PermissionLink[0];
        }
        PermissionLink[] permissionArray = new PermissionLink[result.size()];
        for (int i = 0; i < result.size(); i++) {
            permissionArray[i] = result.get(i);
            permissionArray[i].setUri(uriInfo.getRequestUri().resolve(permissionArray[i].getId().toString()));
        }
        return permissionArray;

    }
}
