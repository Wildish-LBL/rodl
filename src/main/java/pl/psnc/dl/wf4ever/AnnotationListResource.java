/**
 * 
 */
package pl.psnc.dl.wf4ever;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.apache.log4j.Logger;

import pl.psnc.dl.wf4ever.connection.SemanticMetadataServiceFactory;
import pl.psnc.dl.wf4ever.dlibra.UserProfile;
import pl.psnc.dl.wf4ever.sms.SemanticMetadataService;

import com.sun.jersey.core.header.ContentDisposition;

/**
 * @author Piotr Hołubowicz
 * 
 */
@Path(URIs.ANNOTATIONS)
public class AnnotationListResource {

	@SuppressWarnings("unused")
	private static final Logger log = Logger.getLogger(AnnotationListResource.class);

	@Context
	HttpServletRequest request;

	@Context
	private UriInfo uriInfo;

	@GET
	@Produces({ "text/xml", "application/rdf+xml", "application/x+trig" })
	public Response getAnnotations() {
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
		SemanticMetadataService sms = SemanticMetadataServiceFactory.getService(user);

		SemanticMetadataService.Notation notation = URIs.recognizeNotation(request.getContentType());
		InputStream manifest = sms.getAnnotations(uriInfo.getAbsolutePath(), notation);
		ContentDisposition cd = URIs.generateContentDisposition(notation, "annotations");
		return Response.ok(manifest).header("Content-disposition", cd).build();
	}

	@POST
	@Consumes("text/plain")
	public Response createAnnotation(String data) throws URISyntaxException {
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
		SemanticMetadataService sms = SemanticMetadataServiceFactory.getService(user);

		String lines[] = data.split("[\\r\\n]+");
		if (lines.length < 2) {
			return Response.status(Status.BAD_REQUEST).entity("Content is shorter than 2 lines")
					.header("Content-type", "text/plain").build();
		}
		URI annotatedResourceURI = new URI(lines[0]);
		Map<String, String> attributes = new HashMap<String, String>();
		for (int i = 1; i < lines.length; i++) {
			String[] values = lines[i].split("[\\s]+", 2);
			if (values.length == 2) {
				attributes.put(values[0], values[1]);
			}
		}
		String annotationId = generateAnnotationId(annotatedResourceURI);
		URI annotationURI = uriInfo.getAbsolutePathBuilder().fragment(annotationId).build();
		URI annotationBodyURI = uriInfo.getAbsolutePathBuilder().path(annotationId).build();

		sms.addAnnotation(annotationURI, annotationBodyURI, annotatedResourceURI, attributes);

		return Response.created(annotationBodyURI).build();
	}

	@DELETE
	public void deleteAnnotations() {
		UserProfile user = (UserProfile) request.getAttribute(Constants.USER);
		SemanticMetadataService sms = SemanticMetadataServiceFactory.getService(user);

		sms.deleteAnnotationsWithBodies(uriInfo.getAbsolutePath());
	}

	private String generateAnnotationId(URI annotatedResourceURI) {
		String a = annotatedResourceURI.getRawPath();
		while (a.endsWith("/"))
			a = a.substring(0, a.length() - 1);
		a = a.substring(a.lastIndexOf('/') + 1);
		return a + "-" + new Date().getTime();
	}
}
