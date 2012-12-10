package pl.psnc.dl.wf4ever.rosrs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilderException;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.openrdf.rio.RDFFormat;

import pl.psnc.dl.wf4ever.BadRequestException;
import pl.psnc.dl.wf4ever.Constants;
import pl.psnc.dl.wf4ever.common.ResearchObject;
import pl.psnc.dl.wf4ever.dl.AccessDeniedException;
import pl.psnc.dl.wf4ever.dl.ConflictException;
import pl.psnc.dl.wf4ever.dl.DigitalLibraryException;
import pl.psnc.dl.wf4ever.dl.NotFoundException;
import pl.psnc.dl.wf4ever.dl.UserMetadata;
import pl.psnc.dl.wf4ever.dl.UserMetadata.Role;
import pl.psnc.dl.wf4ever.utils.zip.MemoryZipFile;

import com.sun.jersey.core.header.ContentDisposition;

/**
 * A list of research objects REST APIs.
 * 
 * @author piotrhol
 * 
 */
@Path("ROs/")
public class ResearchObjectListResource {

    /** logger. */
    private static final Logger LOGGER = Logger.getLogger(ResearchObjectListResource.class);

    /** HTTP request. */
    @Context
    HttpServletRequest request;

    /** URI info. */
    @Context
    UriInfo uriInfo;


    /**
     * Returns list of relative links to research objects.
     * 
     * @return 200 OK
     */
    @GET
    @Produces("text/plain")
    public Response getResearchObjectList() {
        UserMetadata user = (UserMetadata) request.getAttribute(Constants.USER);

        Set<URI> list;
        if (user.getRole() == Role.PUBLIC) {
            list = ROSRService.SMS.get().findResearchObjects();
        } else {
            list = ROSRService.SMS.get().findResearchObjectsByCreator(user.getUri());
        }
        StringBuilder sb = new StringBuilder();
        for (URI id : list) {
            sb.append(id.toString());
            sb.append("\r\n");
        }

        ContentDisposition cd = ContentDisposition.type("text/plain").fileName("ROs.txt").build();

        return Response.ok().entity(sb.toString()).header("Content-disposition", cd).build();
    }


    /**
     * Creates new RO with given RO_ID.
     * 
     * @return 201 (Created) when the RO was successfully created, 409 (Conflict) if the RO_ID is already used in the
     *         WORKSPACE_ID workspace
     * @throws BadRequestException
     *             the RO id is incorrect
     * @throws NotFoundException
     *             problem with creating the RO in dLibra
     * @throws DigitalLibraryException
     *             problem with creating the RO in dLibra
     * @throws ConflictException
     *             problem with creating the RO in dLibra
     * @throws UriBuilderException
     *             problem with creating the RO in dLibra
     * @throws IllegalArgumentException
     *             problem with creating the RO in dLibra
     * @throws AccessDeniedException
     *             no permissions
     */
    @POST
    public Response createResearchObject()
            throws BadRequestException, IllegalArgumentException, UriBuilderException, ConflictException,
            DigitalLibraryException, NotFoundException, AccessDeniedException {
        LOGGER.debug(String.format("%s\t\tInit create RO", new DateTime().toString()));
        String researchObjectId = request.getHeader(Constants.SLUG_HEADER);
        if (researchObjectId == null || researchObjectId.isEmpty()) {
            throw new BadRequestException("Research object ID is null or empty");
        }
        if (researchObjectId.contains("/")) {
            throw new BadRequestException("Research object ID cannot contain slashes, see WFE-703");
        }
        URI uri = uriInfo.getAbsolutePathBuilder().path(researchObjectId).path("/").build();
        ResearchObject researchObject = ResearchObject.create(uri);
        URI researchObjectURI = ROSRService.createResearchObject(researchObject);
        LOGGER.debug(String.format("%s\t\tRO created", new DateTime().toString()));

        RDFFormat format = RDFFormat.forMIMEType(request.getHeader(Constants.ACCEPT_HEADER), RDFFormat.RDFXML);
        InputStream manifest = ROSRService.SMS.get().getNamedGraph(researchObject.getManifestUri(), format);
        ContentDisposition cd = ContentDisposition.type(format.getDefaultMIMEType())
                .fileName(ResearchObject.MANIFEST_PATH).build();

        LOGGER.debug(String.format("%s\t\tReturning", new DateTime().toString()));
        return Response.created(researchObjectURI).entity(manifest).header("Content-disposition", cd).build();
    }


    /**
     * Create a new RO based on a ZIP sent in the request.
     * 
     * @param zipStream
     *            ZIP input stream
     * @return 201 Created
     * @throws BadRequestException
     *             the ZIP content is not a valid RO
     * @throws IOException
     *             error when unzipping
     * @throws AccessDeniedException
     *             no permissions
     * @throws ConflictException
     *             RO already exists
     */
    @POST
    @Consumes("application/zip")
    public Response createResearchObjectFromZip(InputStream zipStream)
            throws BadRequestException, IOException, AccessDeniedException, ConflictException {
        String researchObjectId = request.getHeader(Constants.SLUG_HEADER);
        if (researchObjectId == null || researchObjectId.isEmpty()) {
            throw new BadRequestException("Research object ID is null or empty");
        }
        ResearchObject ro = ResearchObject.create(uriInfo.getAbsolutePathBuilder().path(researchObjectId).path("/")
                .build());
        UUID uuid = UUID.randomUUID();
        if (ROSRService.SMS.get().containsNamedGraph(ro.getManifestUri())) {
            throw new ConflictException("RO already exists");
        }
        File tmpFile = File.createTempFile("tmp_ro", uuid.toString());
        BufferedInputStream inputStream = new BufferedInputStream(request.getInputStream());
        FileOutputStream fileOutputStream = new FileOutputStream(tmpFile);
        IOUtils.copy(inputStream, fileOutputStream);
        Response response = ROSRService
                .createNewResearchObjectFromZip(ro, new MemoryZipFile(tmpFile, researchObjectId));
        tmpFile.delete();
        return response;
    }
}
