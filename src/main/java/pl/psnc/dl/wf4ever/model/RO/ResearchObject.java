/**
 * 
 */
package pl.psnc.dl.wf4ever.model.RO;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.activation.MimetypesFileTypeMap;
import javax.ws.rs.core.UriBuilder;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.openrdf.rio.RDFFormat;

import pl.psnc.dl.wf4ever.common.Builder;
import pl.psnc.dl.wf4ever.common.EvoType;
import pl.psnc.dl.wf4ever.common.util.MemoryZipFile;
import pl.psnc.dl.wf4ever.dl.AccessDeniedException;
import pl.psnc.dl.wf4ever.dl.ConflictException;
import pl.psnc.dl.wf4ever.dl.DigitalLibraryException;
import pl.psnc.dl.wf4ever.dl.NotFoundException;
import pl.psnc.dl.wf4ever.dl.UserMetadata;
import pl.psnc.dl.wf4ever.exceptions.BadRequestException;
import pl.psnc.dl.wf4ever.exceptions.IncorrectModelException;
import pl.psnc.dl.wf4ever.model.AO.Annotation;
import pl.psnc.dl.wf4ever.model.ORE.AggregatedResource;
import pl.psnc.dl.wf4ever.model.ORE.Aggregation;
import pl.psnc.dl.wf4ever.model.ORE.Proxy;
import pl.psnc.dl.wf4ever.model.ORE.ResourceMap;
import pl.psnc.dl.wf4ever.model.RDF.Thing;
import pl.psnc.dl.wf4ever.rosrs.ROSRService;
import pl.psnc.dl.wf4ever.sms.SemanticMetadataService;
import pl.psnc.dl.wf4ever.sms.SemanticMetadataServiceTdb;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.hp.hpl.jena.query.Dataset;

/**
 * A research object, live by default.
 * 
 * @author piotrekhol
 * 
 */
public class ResearchObject extends Thing implements Aggregation {

    /** logger. */
    private static final Logger LOGGER = Logger.getLogger(ResearchObject.class);

    /** Manifest path. */
    public static final String MANIFEST_PATH = ".ro/manifest.rdf";

    /** Fixed roevo annotation file path. */
    private static final String ROEVO_PATH = ".ro/evo_info.ttl";

    /** aggregated resources, including annotations, resources and folders. */
    protected Map<URI, AggregatedResource> aggregatedResources;

    /** proxies declared in this RO. */
    private Map<URI, Proxy> proxies;

    /** aggregated ro:Resources, excluding ro:Folders. */
    private Map<URI, Resource> resources;

    /** aggregated ro:Folders. */
    private Map<URI, Folder> folders;

    /** aggregated annotations, grouped based on ao:annotatesResource. */
    private Multimap<URI, Annotation> annotationsByTargetUri;

    /** aggregated annotations. */
    private Map<URI, Annotation> annotations;

    /** folder resource maps. */
    private Map<URI, ResourceMap> folderResourceMaps;

    /** Manifest. */
    private Manifest manifest;


    //TODO add properties stored in evo_info.ttl

    /**
     * Constructor.
     * 
     * @param user
     *            user creating the instance
     * @param dataset
     *            RO URI
     */
    //FIXME should this be private?
    public ResearchObject(UserMetadata user, Dataset dataset, boolean useTransactions, URI uri) {
        super(user, dataset, useTransactions, uri);
    }


    public ResearchObject(UserMetadata user, URI uri) {
        super(user, uri);
    }


    /**
     * Create new Research Object.
     * 
     * @param user
     *            user creating the instance
     * @param uri
     *            RO URI
     * @return an instance
     * @throws AccessDeniedException
     * @throws NotFoundException
     * @throws DigitalLibraryException
     * @throws ConflictException
     */
    public static ResearchObject create(Builder builder, URI uri)
            throws ConflictException, DigitalLibraryException, AccessDeniedException, NotFoundException {
        if (get(builder, uri) != null) {
            throw new ConflictException("Research Object already exists: " + uri);
        }
        ResearchObject researchObject = builder.buildResearchObject(uri, builder.getUser().getUri(), DateTime.now());
        researchObject.manifest = Manifest.create(builder, researchObject.getUri().resolve(MANIFEST_PATH),
            researchObject);
        researchObject.save();
        return researchObject;
    }


    public void generateEvoInfo()
            throws DigitalLibraryException, NotFoundException, AccessDeniedException {
        ROSRService.SMS.get().generateEvoInformation(this, null, EvoType.LIVE);
        this.getEvoInfoBody().serialize();
        this.getManifest().serialize();
    }


    /**
     * Get a resource with a given URI or null if doesn't exist.
     * 
     * @param resourceUri
     *            resource URI
     * @return resource instance or null
     */
    public Resource getResource(URI resourceUri) {
        return resources.get(resourceUri);
    }


    public AggregatedResource getEvoInfoBody() {
        //HACK this should be added automatically
        this.getAggregatedResources().put(getFixedEvolutionAnnotationBodyUri(),
            new AggregatedResource(user, this, getFixedEvolutionAnnotationBodyUri()));
        return aggregatedResources.get(getFixedEvolutionAnnotationBodyUri());
    }


    public Manifest getManifest() {
        if (manifest == null) {
            this.manifest = builder.buildManifest(getManifestUri(), this);
        }
        return manifest;
    }


    /**
     * Get a Research Object.
     * 
     * @param user
     *            user creating the instance
     * @param uri
     *            uri
     * @return an existing Research Object or null
     */
    public static ResearchObject get(Builder builder, URI uri) {
        if (ROSRService.SMS.get() == null
                || ROSRService.SMS.get().containsNamedGraph(uri.resolve(ResearchObject.MANIFEST_PATH))) {
            return builder.buildResearchObject(uri);
        } else {
            return null;
        }
    }


    /**
     * Delete the Research Object including its resources and annotations.
     */
    public void delete() {
        try {
            ROSRService.DL.get().deleteResearchObject(uri);
        } finally {
            try {
                ROSRService.SMS.get().removeResearchObject(this);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("URI not found in SMS: " + uri);
            }
        }
    }


    public void save() {
        super.save();
        getManifest().save();

        //TODO check if to create an RO or only serialize the manifest
        ROSRService.DL.get().createResearchObject(uri, getManifest().getGraphAsInputStream(RDFFormat.RDFXML),
            ResearchObject.MANIFEST_PATH, RDFFormat.RDFXML.getDefaultMIMEType());
        generateEvoInfo();
    }


    /**
     * Add an internal resource to the research object.
     * 
     * @param path
     *            resource path, relative to the RO URI, not encoded
     * @param content
     *            resource content
     * @param contentType
     *            resource Content Type
     * @return the resource instance
     */
    public Resource aggregate(String path, InputStream content, String contentType) {
        URI resourceUri = UriBuilder.fromUri(uri).path(path).build();
        Resource resource = Resource.create(builder, this, resourceUri, content, contentType);
        getManifest().serialize();
        this.getResources().put(resource.getUri(), resource);
        this.getAggregatedResources().put(resource.getUri(), resource);
        this.getProxies().put(resource.getProxy().getUri(), resource.getProxy());
        return resource;
    }


    /**
     * Add an external resource (a reference to a resource) to the research object.
     * 
     * @param uri
     *            resource URI
     * @return the resource instance
     */
    public Resource aggregate(URI uri) {
        Resource resource = ROSRService.SMS.get().addResource(this, uri, null);
        resource.setProxy(ROSRService.SMS.get().addProxy(this, resource));
        // update the manifest that describes the resource in dLibra
        this.getManifest().serialize();
        this.getResources().put(resource.getUri(), resource);
        this.getAggregatedResources().put(resource.getUri(), resource);
        return resource;
    }


    public Folder aggregateFolder(URI folderUri, InputStream content)
            throws BadRequestException {
        Folder folder = Folder.create(builder, this, folderUri, content);
        getManifest().serialize();
        this.getFolders().put(folder.getUri(), folder);
        this.getAggregatedResources().put(folder.getUri(), folder);
        this.getProxies().put(folder.getProxy().getUri(), folder.getProxy());
        return folder;
    }


    public Annotation annotate(Thing body, Set<Thing> targets) {
        return annotate(body, targets, null);
    }


    /**
     * Add and aggregate a new annotation to the research object.
     * 
     * @param body
     *            annotation body URI
     * @param targets
     *            list of annotated resources URIs
     * @param annotationId
     *            the id of the annotation, may be null
     * @return new annotation
     */
    public Annotation annotate(Thing body, Set<Thing> targets, String annotationId) {
        Annotation annotation = ROSRService.SMS.get().addAnnotation(this, targets, body, annotationId);
        annotation.setProxy(ROSRService.SMS.get().addProxy(this, annotation));
        getManifest().serialize();
        this.getAnnotations().put(annotation.getUri(), annotation);
        for (Thing target : annotation.getAnnotated()) {
            this.getAnnotationsByTarget().put(target.getUri(), annotation);
        }
        this.getAggregatedResources().put(annotation.getUri(), annotation);
        return annotation;
    }


    /**
     * Create a new research object submitted in ZIP format.
     * 
     * @param researchObjectUri
     *            the new research object
     * @param zip
     *            the ZIP file
     * @return HTTP response (created in case of success, 404 in case of error)
     * @throws IOException
     *             error creating the temporary file
     * @throws BadRequestException
     */
    public static ResearchObject create(Builder builder, URI researchObjectUri, MemoryZipFile zip)
            throws IOException, BadRequestException {
        ResearchObject researchObject = create(builder, researchObjectUri);
        SemanticMetadataService tmpSms = new SemanticMetadataServiceTdb(ROSRService.SMS.get().getUserProfile(),
                researchObject, zip.getManifestAsInputStream(), RDFFormat.RDFXML);

        List<AggregatedResource> aggregatedList;
        List<Annotation> annotationsList;

        try {
            aggregatedList = tmpSms.getAggregatedResources(researchObject);
            annotationsList = tmpSms.getAnnotations(researchObject);
            aggregatedList = tmpSms.removeSpecialFilesFromAggergated(aggregatedList);
            annotationsList = tmpSms.removeSpecialFilesFromAnnotatios(annotationsList);
        } catch (IncorrectModelException e) {
            throw new BadRequestException(e.getMessage(), e);
        }

        InputStream mimeTypesIs = ROSRService.class.getClassLoader().getResourceAsStream("mime.types");
        MimetypesFileTypeMap mfm = new MimetypesFileTypeMap(mimeTypesIs);
        mimeTypesIs.close();
        for (AggregatedResource aggregated : aggregatedList) {
            String originalResourceName = researchObject.getUri().relativize(aggregated.getUri()).getPath();
            URI resourceURI = UriBuilder.fromUri(researchObject.getUri()).path(originalResourceName).build();
            UUID uuid = UUID.randomUUID();
            File tmpFile = File.createTempFile("tmp_resource", uuid.toString());
            try {
                if (zip.containsEntry(originalResourceName)) {
                    try (InputStream is = zip.getEntryAsStream(originalResourceName)) {
                        FileOutputStream fileOutputStream = new FileOutputStream(tmpFile);
                        IOUtils.copy(is, fileOutputStream);
                        String mimeType = mfm.getContentType(resourceURI.getPath());
                        researchObject.aggregate(originalResourceName, new FileInputStream(tmpFile), mimeType);
                    }
                } else {
                    researchObject.aggregate(aggregated.getUri());
                }
            } catch (AccessDeniedException | DigitalLibraryException | NotFoundException | IncorrectModelException e) {
                LOGGER.error("Error when aggregating resources", e);
            } finally {
                tmpFile.delete();
            }
        }
        for (Annotation annotation : annotationsList) {
            try {
                if (researchObject.getAggregatedResources().containsKey(annotation.getBody())) {
                    ROSRService.convertRoResourceToAnnotationBody(researchObject, researchObject
                            .getAggregatedResources().get(annotation.getBody()));
                }
                researchObject.annotate(annotation.getBody(), annotation.getAnnotated());
            } catch (DigitalLibraryException | NotFoundException e) {
                LOGGER.error("Error when adding annotations", e);
            }
        }

        tmpSms.close();
        return researchObject;
    }


    /**
     * Get the manifest URI.
     * 
     * @return manifest URI
     */
    public URI getManifestUri() {
        return uri != null ? uri.resolve(MANIFEST_PATH) : null;
    }


    /**
     * Get the roevo annotation body URI.
     * 
     * @return roevo annotation body URI
     */
    public URI getFixedEvolutionAnnotationBodyUri() {
        return getUri().resolve(ROEVO_PATH);
    }


    public Map<URI, Resource> getResources() {
        if (resources == null) {
            this.resources = getManifest().extractResources();
        }
        return resources;
    }


    public Map<URI, Folder> getFolders() {
        if (folders == null) {
            this.folders = getManifest().extractFolders();
        }
        return folders;
    }


    public Map<URI, Proxy> getProxies() {
        if (proxies == null) {
            this.proxies = new HashMap<>();
            for (AggregatedResource aggregatedResource : this.getAggregatedResources().values()) {
                Proxy proxy = aggregatedResource.getProxy();
                if (proxy != null) {
                    this.proxies.put(proxy.getUri(), proxy);
                }
            }
        }
        return proxies;
    }


    public Map<URI, Annotation> getAnnotations() {
        if (annotations == null) {
            this.annotations = getManifest().extractAnnotations();
        }
        return annotations;
    }


    public Multimap<URI, Annotation> getAnnotationsByTarget() {
        if (annotationsByTargetUri == null) {
            this.annotationsByTargetUri = HashMultimap.<URI, Annotation> create();
            for (Annotation ann : getAnnotations().values()) {
                for (Thing target : ann.getAnnotated()) {
                    this.annotationsByTargetUri.put(target.getUri(), ann);
                }
            }
        }
        return annotationsByTargetUri;
    }


    /**
     * Get the aggregated resource. Load the metadata first, if necessary.
     * 
     * @return a map of aggregated resource by their URI
     */
    public Map<URI, AggregatedResource> getAggregatedResources() {
        if (aggregatedResources == null) {
            this.aggregatedResources = getManifest().extractAggregatedResources(getResources(), getFolders(),
                getAnnotations());
        }
        return aggregatedResources;
    }


    @Override
    public ResourceMap getResourceMap() {
        return getManifest();
    }


    public Map<URI, ResourceMap> getFolderResourceMaps() {
        if (folderResourceMaps == null) {
            this.folderResourceMaps = new HashMap<>();
            for (Folder folder : getFolders().values()) {
                folderResourceMaps.put(folder.getResourceMap().getUri(), folder.getResourceMap());
            }
        }
        return folderResourceMaps;
    }


    @Override
    public DateTime getCreated() {
        if (created == null) {
            this.created = getManifest().extractCreated(this);
        }
        return super.getCreated();
    }


    @Override
    public URI getCreator() {
        if (creator == null) {
            this.creator = getManifest().extractCreator(this);
        }
        return super.getCreator();
    }

}
