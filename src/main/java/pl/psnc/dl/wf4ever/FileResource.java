package pl.psnc.dl.wf4ever;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.rmi.RemoteException;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.xml.transform.TransformerException;

import org.apache.log4j.Logger;

import pl.psnc.dl.wf4ever.auth.ForbiddenException;
import pl.psnc.dl.wf4ever.connection.DigitalLibraryFactory;
import pl.psnc.dl.wf4ever.connection.SemanticMetadataServiceFactory;
import pl.psnc.dl.wf4ever.dlibra.DigitalLibrary;
import pl.psnc.dl.wf4ever.dlibra.DigitalLibraryException;
import pl.psnc.dl.wf4ever.dlibra.ResourceInfo;
import pl.psnc.dl.wf4ever.dlibra.UserProfile;
import pl.psnc.dl.wf4ever.sms.SemanticMetadataService;
import pl.psnc.dl.wf4ever.sms.SemanticMetadataService.Notation;
import pl.psnc.dlibra.service.IdNotFoundException;

import com.sun.jersey.core.header.ContentDisposition;

/**
 * 
 * @author Piotr Hołubowicz
 * 
 */
@Path(Constants.WORKSPACES_URL_PART
		+ "/{W_ID}/"
		+ Constants.RESEARCH_OBJECTS_URL_PART
		+ "/{RO_ID}/{RO_VERSION_ID}/{FILE_PATH : [\\w\\d:#%/;$()~_?\\-=\\\\.&]+}")
public class FileResource
{

	private final static Logger logger = Logger.getLogger(FileResource.class);

	@Context
	private HttpServletRequest request;

	@Context
	private UriInfo uriInfo;


	/**
	 * Returns requested file metadata. If requested URI leads to a folder,
	 * returns rdf file with list of files in this folder. Content - if this
	 * optional parameter is added to the query, instead of the metadata of the
	 * file, the file content will be returned. If requested URI leads to a
	 * folder, returns zip archive with contents of folder.
	 * 
	 * @param workspaceId
	 * @param researchObjectId
	 * @param versionId
	 * @param filePath
	 * @param isContentRequested
	 * @return
	 * @throws IOException
	 * @throws TransformerException
	 * @throws DigitalLibraryException 
	 * @throws IdNotFoundException 
	 */
	@GET
	public Response getFile(@PathParam("W_ID")
	String workspaceId, @PathParam("RO_ID")
	String researchObjectId, @PathParam("RO_VERSION_ID")
	String versionId, @PathParam("FILE_PATH")
	String filePath, @QueryParam("content")
	String isContentRequested, @QueryParam("edition_id")
	@DefaultValue(Constants.EDITION_QUERY_PARAM_DEFAULT_STRING)
	long editionId)
		throws IOException, TransformerException, DigitalLibraryException,
		IdNotFoundException
	{
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);

		if (isContentRequested != null) { // file or folder content
			DigitalLibrary dl = DigitalLibraryFactory.getDigitalLibrary(
				user.getLogin(), user.getPassword());
			try { // file
				return getFileContent(workspaceId, researchObjectId, versionId,
					filePath, dl, editionId);
			}
			catch (IdNotFoundException ex) { // folder
				return getFolderContent(workspaceId, researchObjectId,
					versionId, filePath, dl, editionId);
			}
		}
		else { // metadata
			SemanticMetadataService sms = SemanticMetadataServiceFactory
					.getService(user);
			String contentType = request.getContentType();
			SemanticMetadataService.Notation notation;
			if ("application/x+trig".equals(contentType)) {
				notation = Notation.TRIG;
			}
			else {
				contentType = "application/rdf+xml";
				notation = Notation.RDF_XML;
			}
			InputStream body = sms.getResource(uriInfo.getAbsolutePath(),
				notation);
			ContentDisposition cd = ContentDisposition.type(contentType)
					.fileName(Constants.MANIFEST_FILENAME).build();
			return Response.ok(body)
					.header(Constants.CONTENT_DISPOSITION_HEADER_NAME, cd)
					.build();
		}

	}


	private Response getFolderContent(String workspaceId,
			String researchObjectId, String versionId, String filePath,
			DigitalLibrary dLibraDataSource, Long editionId)
		throws RemoteException, DigitalLibraryException, IdNotFoundException
	{
		logger.debug("Detected query for a folder: " + filePath);
		InputStream body;
		if (editionId == Constants.EDITION_QUERY_PARAM_DEFAULT) {
			body = dLibraDataSource.getZippedFolder(workspaceId,
				researchObjectId, versionId, filePath);
		}
		else {
			body = dLibraDataSource.getZippedFolder(workspaceId,
				researchObjectId, versionId, filePath, editionId);
		}
		ContentDisposition cd = ContentDisposition.type("application/zip")
				.fileName(versionId + ".zip").build();
		return Response.ok(body)
				.header(Constants.CONTENT_DISPOSITION_HEADER_NAME, cd).build();
	}


	private Response getFileContent(String workspaceId,
			String researchObjectId, String versionId, String filePath,
			DigitalLibrary dLibraDataSource, Long editionId)
		throws IOException, RemoteException, DigitalLibraryException,
		IdNotFoundException
	{
		InputStream body;
		String mimeType;
		if (editionId == Constants.EDITION_QUERY_PARAM_DEFAULT) {
			body = dLibraDataSource.getFileContents(workspaceId,
				researchObjectId, versionId, filePath);
			mimeType = dLibraDataSource.getFileMimeType(workspaceId,
				researchObjectId, versionId, filePath);
		}
		else {
			body = dLibraDataSource.getFileContents(workspaceId,
				researchObjectId, versionId, filePath, editionId);
			mimeType = dLibraDataSource.getFileMimeType(workspaceId,
				researchObjectId, versionId, filePath, editionId);
		}

		String fileName = uriInfo.getPath().substring(
			1 + uriInfo.getPath().lastIndexOf("/"));
		ContentDisposition cd = ContentDisposition.type(mimeType)
				.fileName(fileName).build();
		return Response.ok(body)
				.header(Constants.CONTENT_DISPOSITION_HEADER_NAME, cd)
				.header(Constants.CONTENT_TYPE_HEADER_NAME, mimeType).build();
	}


	@PUT
	public Response createOrUpdateFile(@PathParam("W_ID")
	String workspaceId, @PathParam("RO_ID")
	String researchObjectId, @PathParam("RO_VERSION_ID")
	String versionId, @PathParam("FILE_PATH")
	String filePath, @HeaderParam(Constants.CONTENT_TYPE_HEADER_NAME)
	String type, InputStream inputStream)
		throws IOException, TransformerException, DigitalLibraryException,
		IdNotFoundException
	{
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
		DigitalLibrary dl = DigitalLibraryFactory.getDigitalLibrary(
			user.getLogin(), user.getPassword());
		SemanticMetadataService sms = SemanticMetadataServiceFactory
				.getService(user);

		URI versionUri = Utils.createVersionURI(uriInfo, workspaceId,
			researchObjectId, versionId);

		ResourceInfo resourceInfo = dl.createOrUpdateFile(versionUri,
			workspaceId, researchObjectId, versionId, filePath, inputStream,
			type);
		sms.addResource(uriInfo.getAbsolutePath(), resourceInfo);

		return Response.ok().build();
	}


	@DELETE
	public void deleteFile(@PathParam("W_ID")
	String workspaceId, @PathParam("RO_ID")
	String researchObjectId, @PathParam("RO_VERSION_ID")
	String versionId, @PathParam("FILE_PATH")
	String filePath)
		throws IOException, TransformerException, DigitalLibraryException,
		IdNotFoundException
	{
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
		DigitalLibrary dl = DigitalLibraryFactory.getDigitalLibrary(
			user.getLogin(), user.getPassword());
		SemanticMetadataService sms = SemanticMetadataServiceFactory
				.getService(user);

		URI versionUri = Utils.createVersionURI(uriInfo, workspaceId,
			researchObjectId, versionId);

		if (filePath.equals("manifest.rdf"))
			throw new ForbiddenException(
					"Blocked attempt to delete manifest.rdf");

		dl.deleteFile(versionUri, workspaceId, researchObjectId, versionId,
			filePath);
		sms.removeResource(uriInfo.getAbsolutePath());
	}
}
