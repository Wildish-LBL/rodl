package pl.psnc.dl.wf4ever.oauth;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.naming.NamingException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.codec.binary.Base64;

import pl.psnc.dl.wf4ever.Constants;
import pl.psnc.dl.wf4ever.auth.OAuthManager;
import pl.psnc.dl.wf4ever.connection.DigitalLibraryFactory;
import pl.psnc.dl.wf4ever.connection.SemanticMetadataServiceFactory;
import pl.psnc.dl.wf4ever.dlibra.ConflictException;
import pl.psnc.dl.wf4ever.dlibra.DigitalLibrary;
import pl.psnc.dl.wf4ever.dlibra.DigitalLibraryException;
import pl.psnc.dl.wf4ever.dlibra.NotFoundException;
import pl.psnc.dl.wf4ever.dlibra.UserProfile;
import pl.psnc.dl.wf4ever.sms.SemanticMetadataService;

/**
 * 
 * @author Piotr Hołubowicz
 *
 */
@Path(("users/{U_ID}"))
public class UserResource
{

	@Context
	HttpServletRequest request;

	@Context
	UriInfo uriInfo;


	@GET
	public Response getUser(@PathParam("U_ID")
	String urlSafeUserId)
		throws RemoteException, MalformedURLException, UnknownHostException,
		DigitalLibraryException, NotFoundException
	{
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
		DigitalLibrary dl = DigitalLibraryFactory.getDigitalLibrary(
			user.getLogin(), user.getPassword());

		String userId = new String(Base64.decodeBase64(urlSafeUserId));

		if (dl.userExists(userId)) {
			return Response.ok(userId).build();
		}
		else {
			return Response.status(Status.NOT_FOUND).type("text/plain")
					.entity("User " + userId + " does not exist").build();
		}
	}


	/**
	 * Creates new user with given USER_ID. input: USER_ID (the password is
	 * generated internally).
	 * 
	 * @param user
	 *            id, base64 url-safe encoded
	 * @return 201 (Created) when the user was successfully created, 400 (Bad
	 *         Request) if the content is malformed 409 (Conflict) if the
	 *         USER_ID is already used
	 * @throws RemoteException
	 * @throws DigitalLibraryException
	 * @throws UnknownHostException
	 * @throws MalformedURLException
	 * @throws ConflictException
	 * @throws NotFoundException
	 */
	@PUT
	@Consumes("text/plain")
	public Response createUser(@PathParam("U_ID")
	String urlSafeUserId, String username)
		throws RemoteException, DigitalLibraryException, MalformedURLException,
		UnknownHostException, NotFoundException, ConflictException
	{
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
		OAuthManager oauth = new OAuthManager();
		DigitalLibrary dl = DigitalLibraryFactory.getDigitalLibrary(
			user.getLogin(), user.getPassword());

		String userId = new String(Base64.decodeBase64(urlSafeUserId));

		String password = UUID.randomUUID().toString().replaceAll("-", "")
				.substring(0, 20);
		dl.createUser(userId, password,
			username != null && !username.isEmpty() ? username : userId);
		oauth.createUserCredentials(userId, password);

		return Response.ok().build();
	}


	/**
	 * Deletes the workspace.
	 * @param userId identifier of a workspace in the RO SRS
	 * @throws DigitalLibraryException 
	 * @throws NotFoundException 
	 * @throws SQLException 
	 * @throws NamingException 
	 * @throws IOException 
	 * @throws ClassNotFoundException 
	 */
	@DELETE
	public void deleteUser(@PathParam("U_ID")
	String urlSafeUserId)
		throws DigitalLibraryException, NotFoundException,
		ClassNotFoundException, IOException, NamingException, SQLException
	{
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
		OAuthManager oauth = new OAuthManager();
		DigitalLibrary dl = DigitalLibraryFactory.getDigitalLibrary(
			user.getLogin(), user.getPassword());
		SemanticMetadataService sms = SemanticMetadataServiceFactory
				.getService(user);

		String userId = new String(Base64.decodeBase64(urlSafeUserId));

		List<String> list = dl.getWorkspaceIds();
		try {
			for (String workspaceId : list) {
				dl.deleteWorkspace(workspaceId);
				Set<URI> versions = sms.findResearchObjects(uriInfo
						.getBaseUriBuilder().path("workspaces")
						.path(workspaceId).build());
				for (URI uri : versions) {
					sms.removeResearchObject(uri);
				}
			}
		}
		finally {
			sms.close();
		}

		dl.deleteUser(userId);
		oauth.deleteUserCredentials(userId);
	}
}