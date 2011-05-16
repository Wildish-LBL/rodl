package pl.psnc.dl.wf4ever;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.xml.transform.TransformerException;

import org.apache.log4j.Logger;

import pl.psnc.dl.wf4ever.connection.DLibraDataSource;
import pl.psnc.dlibra.service.DLibraException;
import pl.psnc.dlibra.service.IdNotFoundException;

import com.sun.jersey.core.header.ContentDisposition;

/**
 * 
 * @author nowakm
 * 
 */
@Path(Constants.WORKSPACES_URL_PART
		+ "/{W_ID}/"
		+ Constants.RESEARCH_OBJECTS_URL_PART
		+ "/{RO_ID}/{RO_VERSION_ID}/{FILE_PATH : [\\w\\d:#%/;$()~_?\\-=\\\\.&]+}")
public class FileResource {

	private final static Logger logger = Logger.getLogger(FileResource.class);

	@Context
	private HttpServletRequest request;

	@Context
	private UriInfo uriInfo;

	/**
	 * Returns requested file metadata. If requested URI leads to a directory,
	 * returns rdf file with list of files in this directory. Content - if this
	 * optional parameter is added to the query, instead of the metadata of the
	 * file, the file content will be returned. If requested URI leads to a
	 * directory, returns zip archive with contents of directory.
	 * 
	 * @param workspaceId
	 * @param researchObjectId
	 * @param versionId
	 * @param filePath
	 * @param isContentRequested
	 * @return
	 * @throws IOException
	 * @throws DLibraException
	 * @throws TransformerException
	 */
	@GET
	public Response getFile(@PathParam("W_ID") String workspaceId,
			@PathParam("RO_ID") String researchObjectId,
			@PathParam("RO_VERSION_ID") String versionId,
			@PathParam("FILE_PATH") String filePath,
			@QueryParam("content") String isContentRequested)
			throws IOException, DLibraException, TransformerException {

		DLibraDataSource dLibraDataSource = (DLibraDataSource) request
				.getAttribute(Constants.DLIBRA_DATA_SOURCE);

		if (isContentRequested != null) { // file or directory content
			try { // file
				InputStream body = dLibraDataSource.getFileContents(
						researchObjectId, versionId, filePath);
				String mimeType = dLibraDataSource.getFileMimeType(
						researchObjectId, versionId, filePath);

				String fileName = uriInfo.getPath().substring(
						1 + uriInfo.getPath().lastIndexOf("/"));
				ContentDisposition cd = ContentDisposition.type(mimeType)
						.fileName(fileName).build();
				return Response.ok(body)
						.header(Constants.CONTENT_DISPOSITION_HEADER_NAME, cd)
						.header(Constants.CONTENT_TYPE_HEADER_NAME, mimeType)
						.build();
			} catch (IdNotFoundException ex) { // directory
				logger.debug("Detected query for a directory: " + filePath);
				InputStream body = dLibraDataSource.getZippedDirectory(
						researchObjectId, versionId, filePath);
				ContentDisposition cd = ContentDisposition
						.type("application/zip").fileName(versionId + ".zip")
						.build();
				return Response.ok(body)
						.header(Constants.CONTENT_DISPOSITION_HEADER_NAME, cd)
						.build();
			}
		} else { // metadata
			try { // file
				String metadata = dLibraDataSource.getFileMetadata(
						researchObjectId, versionId, filePath, uriInfo
								.getAbsolutePath().toString());
				ContentDisposition cd = ContentDisposition.type(
						Constants.RDF_XML_MIME_TYPE).build();
				return Response
						.ok(metadata)
						.header(Constants.CONTENT_DISPOSITION_HEADER_NAME, cd)
						.header(Constants.CONTENT_TYPE_HEADER_NAME,
								Constants.RDF_XML_MIME_TYPE).build();
			} catch (IdNotFoundException ex) { // directory
				if (!filePath.endsWith("/"))
					filePath = filePath.concat("/");
				List<String> files = dLibraDataSource.listFilesInDirectory(
						researchObjectId, versionId, filePath);

				List<String> links = new ArrayList<String>(files.size());

				for (String path : files) {
					String fileUri = uriInfo.getAbsolutePath().toString();
					if (!fileUri.endsWith("/"))
						fileUri = fileUri.concat("/");
					fileUri = fileUri.concat(path.substring(path.indexOf(filePath)
						+ filePath.length()));
					links.add(fileUri);
				}
				logger.debug("For directory " + filePath + " found "
						+ links.size() + " files, " + "for example "
						+ (links.isEmpty() ? "-" : links.get(0)));

				String responseBody = RdfBuilder.serializeResource(RdfBuilder
						.createCollection(uriInfo.getAbsolutePath().toString(),
								links));

				ContentDisposition cd = ContentDisposition
						.type("application/rdf+xml")
						.fileName(researchObjectId + ".rdf").build();

				return Response.ok().entity(responseBody)
						.header(Constants.CONTENT_DISPOSITION_HEADER_NAME, cd)
						.build();
			}
		}

	}

	@POST
	public Response createOrUpdateFile(@PathParam("W_ID") String workspaceId,
			@PathParam("RO_ID") String researchObjectId,
			@PathParam("RO_VERSION_ID") String versionId,
			@PathParam("FILE_PATH") String filePath,
			@HeaderParam(Constants.CONTENT_TYPE_HEADER_NAME) String type,
			InputStream inputStream) throws IOException, DLibraException,
			TransformerException {

		DLibraDataSource dLibraDataSource = (DLibraDataSource) request
				.getAttribute(Constants.DLIBRA_DATA_SOURCE);

		String versionUri = uriInfo
				.getAbsolutePath()
				.toString()
				.substring(
						0,
						uriInfo.getAbsolutePath().toString()
								.lastIndexOf(filePath) - 1);

		dLibraDataSource.createOrUpdateFile(versionUri, researchObjectId,
				versionId, filePath, inputStream, type);

		return Response.ok().build();
	}

	@DELETE
	public void deleteFile(@PathParam("W_ID") String workspaceId,
			@PathParam("RO_ID") String researchObjectId,
			@PathParam("RO_VERSION_ID") String versionId,
			@PathParam("FILE_PATH") String filePath) throws DLibraException,
			IOException, TransformerException {
		DLibraDataSource dLibraDataSource = (DLibraDataSource) request
				.getAttribute(Constants.DLIBRA_DATA_SOURCE);

		String versionUri = uriInfo
				.getAbsolutePath()
				.toString()
				.substring(
						0,
						uriInfo.getAbsolutePath().toString()
								.lastIndexOf(filePath) - 1);

		dLibraDataSource.deleteFile(versionUri, researchObjectId, versionId,
				filePath);
	}
}
