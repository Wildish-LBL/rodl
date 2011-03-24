package pl.psnc.dl.wf4ever;

import java.rmi.RemoteException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

import pl.psnc.dl.wf4ever.connection.DLibraDataSource;
import pl.psnc.dlibra.metadata.GroupPublicationInfo;
import pl.psnc.dlibra.service.DLibraException;

/**
 * 
 * @author nowakm
 *
 */
@Path(Constants.WORKSPACES_URL_PART + "/{W_ID}/"
		+ Constants.RESEARCH_OBJECTS_URL_PART)
public class ResearchObjectListResource
{

	@Context
	HttpServletRequest request;

	@Context
	UriInfo uriInfo;


	@GET
	@Produces("text/plain")
	public String getResearchObjectList(@PathParam("W_ID") String workspaceId)
		throws RemoteException, DLibraException
	{

		DLibraDataSource dLibraDataSource = (DLibraDataSource) request
				.getAttribute(Constants.DLIBRA_DATA_SOURCE);
		List<GroupPublicationInfo> list = dLibraDataSource
				.listUserGroupPublications();

		StringBuilder sb = new StringBuilder("");

		for (GroupPublicationInfo gp : list) {
			sb.append(uriInfo.getAbsolutePath());
			sb.append("/");
			sb.append(gp.getLabel());
			sb.append("\n");
		}

		return sb.toString();
	}
}
