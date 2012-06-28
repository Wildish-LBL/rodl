package pl.psnc.dl.wf4ever.rosrs;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.apache.log4j.Logger;

import pl.psnc.dl.wf4ever.BadRequestException;
import pl.psnc.dl.wf4ever.Constants;
import pl.psnc.dl.wf4ever.auth.SecurityFilter;
import pl.psnc.dl.wf4ever.dlibra.DigitalLibraryException;
import pl.psnc.dl.wf4ever.dlibra.NotFoundException;
import pl.psnc.dlibra.service.AccessDeniedException;
import pl.psnc.dlibra.service.IdNotFoundException;

import com.sun.jersey.api.ConflictException;

/**
 * 
 * @author Piotr Hołubowicz
 * 
 */
@Path("ROs/{ro_id}/")
public class ResearchObjectResource {

    private static final Logger logger = Logger.getLogger(ResearchObjectResource.class);

    private static final URI portalRoPage = URI.create("http://sandbox.wf4ever-project.org/portal/ro");

    @Context
    HttpServletRequest request;

    @Context
    UriInfo uriInfo;


    /**
     * Returns zip archive with contents of RO version.
     * 
     * @param researchObjectId
     *            RO identifier - defined by the user
     * @return
     * @throws UnknownHostException
     * @throws MalformedURLException
     * @throws RemoteException
     * @throws IOException
     * @throws DigitalLibraryException
     * @throws IdNotFoundException
     * @throws OperationNotSupportedException
     * @throws NotFoundException
     */
    @GET
    @Produces({ "application/zip", "multipart/related", "*/*" })
    public Response getZippedRO(@PathParam("ro_id") String researchObjectId)
            throws RemoteException, MalformedURLException, UnknownHostException, DigitalLibraryException,
            NotFoundException {
        return Response.seeOther(getZippedROURI(uriInfo.getBaseUriBuilder(), researchObjectId)).build();
    }


    @GET
    @Produces({ "application/rdf+xml", "application/x-turtle", "text/turtle", "application/x-trig", "application/trix",
            "text/rdf+n3" })
    public Response getROMetadata(@PathParam("ro_id") String researchObjectId)
            throws RemoteException, MalformedURLException, UnknownHostException, DigitalLibraryException,
            NotFoundException {
        return Response.seeOther(getROMetadataURI(uriInfo.getBaseUriBuilder(), researchObjectId)).build();
    }


    @GET
    @Produces({ MediaType.TEXT_HTML })
    public Response getROHtml(@PathParam("ro_id") String researchObjectId)
            throws URISyntaxException, RemoteException, MalformedURLException, UnknownHostException,
            DigitalLibraryException, NotFoundException {
        URI uri = getROHtmlURI(uriInfo.getBaseUriBuilder(), researchObjectId);
        return Response.seeOther(uri).build();
    }


    @DELETE
    public void deleteVersion(@PathParam("ro_id") String researchObjectId)
            throws DigitalLibraryException, ClassNotFoundException, IOException, NamingException, SQLException {
        try {
            SecurityFilter.DL.get().deleteVersion(Constants.workspaceId, researchObjectId, Constants.versionId);
            if (SecurityFilter.DL.get().getVersionIds(Constants.workspaceId, researchObjectId).isEmpty()) {
                SecurityFilter.DL.get().deleteResearchObject(Constants.workspaceId, researchObjectId);
                if (SecurityFilter.DL.get().getResearchObjectIds(Constants.workspaceId).isEmpty()) {
                    SecurityFilter.DL.get().deleteWorkspace(Constants.workspaceId);
                }
            }
        } catch (NotFoundException e) {
            logger.warn("URI not found in dLibra: " + uriInfo.getAbsolutePath());
        } finally {
            try {
                SecurityFilter.SMS.get().removeResearchObject(uriInfo.getAbsolutePath());
            } catch (IllegalArgumentException e) {
                logger.warn("URI not found in SMS: " + uriInfo.getAbsolutePath());
            }
        }
    }


    /**
     * Add a resource with no specific MIME type.
     * 
     * @param researchObjectId
     * @param content
     * @return
     * @throws BadRequestException
     *             annotation target is an incorrect URI
     * @throws NotFoundException
     * @throws DigitalLibraryException
     * @throws AccessDeniedException
     */
    @POST
    public Response addResource(@PathParam("ro_id") String researchObjectId, String content)
            throws BadRequestException, AccessDeniedException, DigitalLibraryException, NotFoundException {
        URI researchObject = uriInfo.getAbsolutePath();
        URI resource;
        if (request.getHeader(Constants.SLUG_HEADER) != null) {
            resource = uriInfo.getAbsolutePathBuilder().path(request.getHeader(Constants.SLUG_HEADER)).build();
        } else {
            resource = uriInfo.getAbsolutePathBuilder().path(UUID.randomUUID().toString()).build();
        }
        if (SecurityFilter.SMS.get().isAggregatedResource(researchObject, resource)) {
            throw new ConflictException("This resource has already been aggregated. Use PUT to update it.");
        }
        if (request.getHeader(Constants.AO_ANNOTATES_HEADER) != null) {
            List<URI> annotationTargets = new ArrayList<>();
            for (@SuppressWarnings("unchecked")
            Enumeration<String> headers = request.getHeaders(Constants.AO_ANNOTATES_HEADER); headers.hasMoreElements();) {
                String header = headers.nextElement();
                try {
                    annotationTargets.add(new URI(header));
                } catch (URISyntaxException e) {
                    throw new BadRequestException("Annotation target " + header + " is incorrect", e);
                }
            }
            ROSRService.aggregateInternalResource(researchObject, resource, researchObjectId, content,
                request.getContentType(), null);
            ROSRService.convertAggregatedResourceToAnnotationBody(researchObject, resource, researchObjectId);
            return ROSRService.addAnnotation(researchObject, new Annotation(resource, annotationTargets));
        } else {
            Response response = ROSRService.aggregateInternalResource(researchObject, resource, researchObjectId,
                content, request.getContentType(), null);
            if (SecurityFilter.SMS.get().isROMetadataNamedGraph(researchObject, resource)) {
                ROSRService.convertAggregatedResourceToAnnotationBody(researchObject, resource, researchObjectId);
            }
            return response;
        }
    }


    /**
     * 
     * @param researchObjectId
     * @param content
     * @return
     * @throws BadRequestException
     * @throws NotFoundException
     *             could not find the resource in DL
     * @throws DigitalLibraryException
     *             could not connect to the DL
     * @throws AccessDeniedException
     *             access denied when updating data in DL
     */
    @POST
    @Consumes(Constants.PROXY_MIME_TYPE)
    public Response addProxy(@PathParam("ro_id") String researchObjectId, String content)
            throws BadRequestException, AccessDeniedException, DigitalLibraryException, NotFoundException {
        URI researchObject = uriInfo.getAbsolutePath();
        URI proxyFor;
        if (request.getHeader(Constants.SLUG_HEADER) != null) {
            // internal resource
            proxyFor = uriInfo.getAbsolutePathBuilder().path(request.getHeader(Constants.SLUG_HEADER)).build();
        } else {
            // external resource
            try {
                proxyFor = new URI(content);
            } catch (URISyntaxException e) {
                throw new BadRequestException("Wrong resource URI", e);
            }
        }
        return ROSRService.aggregateExternalResource(researchObject, proxyFor, researchObjectId);
    }


    /**
     * 
     * @param researchObjectId
     * @param annotation
     * @return
     * @throws NotFoundException
     * @throws DigitalLibraryException
     * @throws AccessDeniedException
     */
    @POST
    @Consumes(Constants.ANNOTATION_MIME_TYPE)
    public Response addAnnotation(@PathParam("ro_id") String researchObjectId, Annotation annotation)
            throws AccessDeniedException, DigitalLibraryException, NotFoundException {
        URI researchObject = uriInfo.getAbsolutePath();
        if (SecurityFilter.SMS.get().isAggregatedResource(researchObject, annotation.getAnnotationBody())) {
            ROSRService.convertAggregatedResourceToAnnotationBody(researchObject, annotation.getAnnotationBody(),
                researchObjectId);
        }

        return ROSRService.addAnnotation(researchObject, annotation);
    }


    private static URI getZippedROURI(UriBuilder baseUriBuilder, String researchObjectId) {
        return baseUriBuilder.path("zippedROs").path(researchObjectId).path("/").build();
    }


    private static URI getROHtmlURI(UriBuilder baseUriBuilder, String researchObjectId)
            throws URISyntaxException {
        return new URI(portalRoPage.getScheme(), portalRoPage.getAuthority(), portalRoPage.getPath(), "ro="
                + baseUriBuilder.path("ROs").path(researchObjectId).path("/").build().toString(), null);
    }


    private static URI getROMetadataURI(UriBuilder baseUriBuilder, String researchObjectId) {
        return baseUriBuilder.path("ROs").path(researchObjectId).path("/.ro/manifest.rdf").build();
    }


    public static ResponseBuilder addLinkHeaders(ResponseBuilder responseBuilder, UriInfo linkUriInfo,
            String researchObjectId) {
        try {
            return responseBuilder
                    .header(
                        Constants.LINK_HEADER,
                        String.format(Constants.LINK_HEADER_TEMPLATE,
                            getROHtmlURI(linkUriInfo.getBaseUriBuilder(), researchObjectId), "bookmark"))
                    .header(
                        Constants.LINK_HEADER,
                        String.format(Constants.LINK_HEADER_TEMPLATE,
                            getZippedROURI(linkUriInfo.getBaseUriBuilder(), researchObjectId), "bookmark"))
                    .header(
                        Constants.LINK_HEADER,
                        String.format(Constants.LINK_HEADER_TEMPLATE,
                            getROMetadataURI(linkUriInfo.getBaseUriBuilder(), researchObjectId), "bookmark"));
        } catch (URISyntaxException e) {
            logger.error("Could not create RO Portal URI", e);
            return responseBuilder.header(
                Constants.LINK_HEADER,
                String.format(Constants.LINK_HEADER_TEMPLATE,
                    getZippedROURI(linkUriInfo.getBaseUriBuilder(), researchObjectId), "bookmark")).header(
                Constants.LINK_HEADER,
                String.format(Constants.LINK_HEADER_TEMPLATE,
                    getROMetadataURI(linkUriInfo.getBaseUriBuilder(), researchObjectId), "bookmark"));
        }
    }
}
