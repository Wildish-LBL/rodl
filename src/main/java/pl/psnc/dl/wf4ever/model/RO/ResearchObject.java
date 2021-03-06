/**
 * 
 */
package pl.psnc.dl.wf4ever.model.RO;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.openrdf.rio.RDFFormat;

import pl.psnc.dl.wf4ever.db.ResearchObjectId;
import pl.psnc.dl.wf4ever.db.dao.ResearchObjectIdDAO;
import pl.psnc.dl.wf4ever.dl.ConflictException;
import pl.psnc.dl.wf4ever.dl.NotFoundException;
import pl.psnc.dl.wf4ever.dl.UserMetadata;
import pl.psnc.dl.wf4ever.dl.UserMetadata.Role;
import pl.psnc.dl.wf4ever.eventbus.events.ROAfterCreateEvent;
import pl.psnc.dl.wf4ever.eventbus.events.ROAfterDeleteEvent;
import pl.psnc.dl.wf4ever.eventbus.events.ROBeforeCreateEvent;
import pl.psnc.dl.wf4ever.eventbus.events.ROBeforeDeleteEvent;
import pl.psnc.dl.wf4ever.evo.EvoType;
import pl.psnc.dl.wf4ever.exceptions.BadRequestException;
import pl.psnc.dl.wf4ever.model.Builder;
import pl.psnc.dl.wf4ever.model.EvoBuilder;
import pl.psnc.dl.wf4ever.model.AO.Annotation;
import pl.psnc.dl.wf4ever.model.ORE.AggregatedResource;
import pl.psnc.dl.wf4ever.model.ORE.Aggregation;
import pl.psnc.dl.wf4ever.model.ORE.Proxy;
import pl.psnc.dl.wf4ever.model.ORE.ResourceMap;
import pl.psnc.dl.wf4ever.model.RDF.Thing;
import pl.psnc.dl.wf4ever.model.ROEVO.EvoInfo;
import pl.psnc.dl.wf4ever.model.ROEVO.ImmutableResearchObject;
import pl.psnc.dl.wf4ever.model.ROEVO.LiveEvoInfo;
import pl.psnc.dl.wf4ever.preservation.model.ResearchObjectComponentSerializable;
import pl.psnc.dl.wf4ever.preservation.model.ResearchObjectSerializable;
import pl.psnc.dl.wf4ever.searchserver.SearchServer;
import pl.psnc.dl.wf4ever.searchserver.solr.SolrSearchServer;
import pl.psnc.dl.wf4ever.util.MemoryZipFile;
import pl.psnc.dl.wf4ever.util.MimeTypeUtil;
import pl.psnc.dl.wf4ever.vocabulary.ORE;
import pl.psnc.dl.wf4ever.vocabulary.RO;
import pl.psnc.dl.wf4ever.zip.ROFromZipJobStatus;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.DatasetFactory;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ReadWrite;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.vocabulary.DCTerms;

/**
 * A research object, live by default.
 * 
 * @author piotrekhol
 * 
 */
public class ResearchObject extends Thing implements Aggregation, ResearchObjectSerializable {

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

    /** aggregated annotations, grouped based on ao:annotatesResource. */
    private Multimap<URI, Annotation> annotationsByBodyUri;

    /** aggregated annotations. */
    private Map<URI, Annotation> annotations;

    /** folder resource maps and the manifest. */
    private Map<URI, ResourceMap> resourceMaps;

    /** folder entries. */
    private Map<URI, FolderEntry> folderEntries;

    /** folder entries, grouped based on ore:proxyFor. */
    private Multimap<URI, FolderEntry> folderEntriesByResourceUri;

    /** Manifest. */
    private Manifest manifest;

    /** Evolution information annotation body. */
    private LiveEvoInfo evoInfo;

    /** The annotation for the evolution information. */
    protected Annotation evoInfoAnnotation;

    /** Optional URI of this research object bundled as an RO bundle. */
    protected URI bundleUri;

    /** URI of the RO that aggregates this RO, if it is nested. */
    protected Collection<URI> aggregatingROUris;


    /**
     * Constructor.
     * 
     * @param user
     *            user creating the instance
     * @param dataset
     *            custom dataset
     * @param useTransactions
     *            should transactions be used. Note that not using transactions on a dataset which already uses
     *            transactions may make it unreadable.
     * @param uri
     *            the RO URI
     */
    public ResearchObject(UserMetadata user, Dataset dataset, boolean useTransactions, URI uri) {
        super(user, dataset, useTransactions, uri);
    }


    /**
     * Create new Research Object.
     * 
     * @param builder
     *            model instance builder
     * @param uri
     *            RO URI
     * @return an instance
     */
    public static ResearchObject create(Builder builder, URI uri) {
        ResearchObjectIdDAO idDAO = new ResearchObjectIdDAO();
        //replace uri on the first free
        uri = idDAO.assignId(new ResearchObjectId(uri)).getId();
        //because of the line above should never be true;
        if (get(builder, uri) != null) {
            throw new ConflictException("Research Object already exists: " + uri);
        }
        ResearchObject researchObject = builder.buildResearchObject(uri, builder.getUser(), DateTime.now());
        researchObject.manifest = Manifest.create(builder, researchObject.getUri().resolve(MANIFEST_PATH),
            researchObject);
        researchObject.postEvent(new ROBeforeCreateEvent(researchObject));
        researchObject.save(EvoType.LIVE);
        researchObject.postEvent(new ROAfterCreateEvent(researchObject));
        return researchObject;
    }


    /**
     * Create a research object with the given resources, annotations and folders. The resources, annotations and
     * folders are not used directly but only their parameters are used as base.
     * 
     * @param builder
     *            model builder
     * @param uri
     *            RO URI
     * @param resources2
     *            resources to use as base
     * @param annotations2
     *            annotations to use as base
     * @param folders2
     *            folders to use as base
     * @return the new research object
     * @throws BadRequestException
     *             when provided parameters are incorrect
     */
    public static ResearchObject create(Builder builder, URI uri, Collection<? extends Resource> resources2,
            Collection<? extends Annotation> annotations2, Collection<? extends Folder> folders2)
            throws BadRequestException {
        ResearchObject researchObject = create(builder, uri);
        for (Resource resource : resources2) {
            if (resource.isInternal()) {
                Resource resource2 = researchObject.aggregate(resource.getPath(), resource.getSerialization(), resource
                        .getStats().getMimeType());
                LOGGER.debug("Aggregated an internal resource " + resource2);
            } else {
                Resource resource2 = researchObject.aggregate(resource.getUri());
                LOGGER.debug("Aggregated an external resource " + resource2);
            }
        }
        for (Annotation annotation : annotations2) {
            try {
                Set<Thing> targets = new HashSet<>();
                for (Thing target : annotation.getAnnotated()) {
                    targets.add(Annotation.validateTarget(researchObject,
                        researchObject.getUri().resolve(target.getUri())));
                }
                if (annotation.getBody() instanceof AggregatedResource
                        && ((AggregatedResource) annotation.getBody()).isInternal()) {
                    AggregatedResource body = (AggregatedResource) annotation.getBody();
                    try {
                        Resource body2 = researchObject.aggregate(body.getPath(), body.getSerialization(), body
                                .getStats().getMimeType());
                        LOGGER.debug("Aggregated an internal annotation body " + body2);
                    } catch (ConflictException e) {
                        LOGGER.debug("The internal annotation body has already been aggregated " + body.getPath());
                    }
                } else {
                    // external annotation bodies are not aggregated
                    LOGGER.debug("Identified an external annotation body " + annotation.getBody());
                }
                Annotation annotation2 = researchObject.annotate(
                    researchObject.getUri().resolve(annotation.getBody().getUri()), targets);
                LOGGER.debug("Aggregated an annotation with body " + annotation2.getBody().getUri());
            } catch (BadRequestException e) {
                LOGGER.warn("Annotation " + annotation.getUri() + " will be ignored, reason: " + e.getMessage());
            }
        }
        for (Folder folder : folders2) {
            researchObject.aggregateFolder(researchObject.getUri().resolve(folder.getPath()));
            LOGGER.debug("Aggregated folder " + researchObject.getUri().resolve(folder.getPath()));
        }
        for (Entry<URI, Folder> entryFolder : researchObject.getFolders().entrySet()){
        	for (Folder folder : folders2) {
        		if (folder.getPath().equals(entryFolder.getValue().getPath())){
        			for (FolderEntry entry : folder.getFolderEntries().values()) {
                        URI resourceUri = researchObject.getUri().resolve(entry.getProxyFor().getUri());
                        AggregatedResource resource = researchObject.getResources().get(resourceUri); 
                        if (resource == null) {
                        	resource = researchObject.getFolders().get(resourceUri);
                        	if (resource == null) {
                        		LOGGER.warn("Resource for entry not found: " + resourceUri);
                        		continue;
                        	}
                        }
                        entryFolder.getValue().createFolderEntry(resource);
                        LOGGER.debug("Created an entry for " + resource.getUri() + " in " + entryFolder.getValue().getUri());
                    }
        		}
        	}
        }
        return researchObject;
    }


    /**
     * Generate new evolution information, including the evolution annotation.
     * 
     * @param type
     *            evolution type
     */
    protected void createEvoInfo(EvoType type) {
        try {
            evoInfo = LiveEvoInfo.create(builder, getFixedEvolutionAnnotationBodyUri(), this);
            this.evoInfoAnnotation = annotate(evoInfo.getUri(), this);
            this.getManifest().serialize();
        } catch (BadRequestException e) {
            LOGGER.error("Failed to create the evo info annotation", e);
        }
    }


    /**
     * Get the resource with the evolution metadata.
     * 
     * @return an evolution resource
     */
    public LiveEvoInfo getLiveEvoInfo() {
        if (evoInfo == null) {
            evoInfo = LiveEvoInfo.get(builder, getFixedEvolutionAnnotationBodyUri(), this);
            if (evoInfo != null) {
                evoInfo.load();
            }
        }
        return evoInfo;
    }


    public Annotation getEvoInfoAnnotation() {
        return evoInfoAnnotation;
    }


    public SortedSet<ImmutableResearchObject> getImmutableResearchObjects() {
        return getLiveEvoInfo().getImmutableResearchObjects();
    }


    /**
     * Get the manifest, loaded lazily.
     * 
     * @return the manifest
     */
    public Manifest getManifest() {
        if (manifest == null) {
            this.manifest = builder.buildManifest(getManifestUri(), this);
        }
        return manifest;
    }


    /**
     * Get a Research Object if it exists.
     * 
     * @param builder
     *            model instance builder
     * @param uri
     *            uri
     * @return an existing Research Object or null
     */
    public static ResearchObject get(Builder builder, URI uri) {
        ResearchObject researchObject = builder.buildResearchObject(uri);
        if (researchObject.getManifest().isNamedGraph()) {
            return researchObject;
        } else {
            return null;
        }
    }


    /**
     * Save.
     * 
     * @param evoType
     *            evolution type
     */
    protected void save(EvoType evoType) {
        super.save();
        getManifest().save();
        //TODO check if to create an RO or only serialize the manifest
        builder.getDigitalLibrary().createResearchObject(uri,
            getManifest().getGraphAsInputStreamWithRelativeURIs(uri, RDFFormat.RDFXML), ResearchObject.MANIFEST_PATH,
            RDFFormat.RDFXML.getDefaultMIMEType());
        createEvoInfo(evoType);
    }


    /**
     * Delete the Research Object including its resources and annotations.
     */
    @Override
    public void delete() {
        this.postEvent(new ROBeforeDeleteEvent(this));
        //create another collection to avoid concurrent modification
        Set<AggregatedResource> resourcesToDelete = new HashSet<>(getAggregatedResources().values());
        for (AggregatedResource resource : resourcesToDelete) {
            try {
                resource.delete();
            } catch (Exception e) {
                LOGGER.error("Can't delete resource " + resource + ", will continue deleting the RO.", e);
            }
        }
        if (getBundleUri() != null) {
            try {
                // The bundle may be stored inside the parent RO. The path may then start with ../[parentRO]/.
                Path bundlePath = Paths.get(uri.getPath()).relativize(Paths.get(getBundleUri().getPath()));
                // delete the bundled file
                builder.getDigitalLibrary().deleteFile(uri, bundlePath.toString());
                // delete the references in the manifest
                for (URI parentUri : getAggregatingROUris()) {
                    ResearchObject parent = ResearchObject.get(builder, parentUri);
                    // if the parent RO is being deleted, it may have already deleted the references to this RO
                    if (parent.getAggregatedResources().containsKey(uri)) {
                        ((RoBundle) parent.getAggregatedResources().get(uri)).delete(false);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to delete the bundled version of the RO " + this, e);
            }
        }
        getManifest().delete();
        try {
            builder.getDigitalLibrary().deleteResearchObject(uri);
        } catch (NotFoundException e) {
            // good, nothing was left so the folder was deleted
            LOGGER.debug("As expected. RO folder was empty and was deleted: " + e.getMessage());
        }
        this.postEvent(new ROAfterDeleteEvent(this));
        super.delete();
    }


    /**
     * Create and save a new proxy.
     * 
     * @param resource
     *            resource for which the proxy is
     * @return a proxy instance
     */
    public Proxy addProxy(AggregatedResource resource) {
        URI proxyUri = uri.resolve(".ro/proxies/" + UUID.randomUUID());
        Proxy proxy = builder.buildProxy(proxyUri, resource, this);
        proxy.save();
        getProxies().put(proxy.getUri(), proxy);
        return proxy;
    }


    /**
     * Create an internal resource and add it to the research object.
     * 
     * @param path
     *            resource path, relative to the RO URI, not encoded
     * @param content
     *            resource content
     * @param contentType
     *            resource Content Type
     * @return the resource instance
     * @throws BadRequestException
     *             if it should be an annotation body according to an existing annotation and it's the wrong format
     */
    public Resource aggregate(String path, InputStream content, String contentType)
            throws BadRequestException {
        URI resourceUri = UriBuilder.fromUri(uri).path(path).build();
        Resource resource = Resource.create(builder, this, resourceUri, content, contentType);
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
        Resource resource = Resource.create(builder, this, uri);
        return resource;
    }


    /**
     * Aggregate a copy of the resource. The creation date and authors will be taken from the original. The URI of the
     * new resource will be different from the original.
     * 
     * @param resource
     *            the resource to copy
     * @param evoBuilder
     *            builder of evolution properties
     * @return the new resource
     * @throws BadRequestException
     *             if it should be an annotation body according to an existing annotation and it's the wrong format
     */
    public Resource copy(Resource resource, EvoBuilder evoBuilder)
            throws BadRequestException {
        Resource resource2 = resource.copy(builder, evoBuilder, this);
        return resource2;
    }


    /**
     * Aggregate a copy of the resource. The creation date and authors will be taken from the original. The URI of the
     * new resource will be different from the original.
     * 
     * @param resource
     *            the resource to copy
     * @param evoBuilder
     *            builder of evolution properties
     * @return the new resource
     * @throws BadRequestException
     *             if it should be an annotation body according to an existing annotation and it's the wrong format
     */
    public AggregatedResource copy(AggregatedResource resource, EvoBuilder evoBuilder)
            throws BadRequestException {
        AggregatedResource resource2 = resource.copy(builder, evoBuilder, this);
        return resource2;
    }


    /**
     * Add a named graph describing the folder and aggregate it by the RO. The folder must have its URI set. The folder
     * entries must have their proxyFor and RO name set. The folder entry URIs will be generated automatically if not
     * set.
     * 
     * If there is no root folder in the RO, the new folder will be the root folder of the RO.
     * 
     * @param folderUri
     *            folder URI
     * @param content
     *            folder description
     * @return a folder instance
     * @throws BadRequestException
     *             the folder description is not valid
     */
    public Folder aggregateFolder(URI folderUri, InputStream content)
            throws BadRequestException {
        Folder folder = Folder.create(builder, this, folderUri, content);
        return folder;
    }


    /**
     * Create and aggregate an empty folder instance.
     * 
     * @param folderUri
     *            a folderUri
     * @return an empty folder
     */
    public Folder aggregateFolder(URI folderUri) {
        Folder folder = Folder.create(builder, this, folderUri);
        return folder;
    }


    /**
     * Aggregate a copy of a folder. The aggregated resources will be relativized against the original RO URI and
     * resolved against this RO URI.
     * 
     * @param folder
     *            folder to copy
     * @param evoBuilder
     *            builder of evolution properties
     * @return the new folder
     */
    public Folder copy(Folder folder, EvoBuilder evoBuilder) {
        Folder folder2 = folder.copy(builder, evoBuilder, this);
        return folder2;
    }


    /**
     * Add and aggregate a new annotation to the research object.
     * 
     * @param body
     *            annotation body URI
     * @param target
     *            annotated resource's URI
     * @return new annotation
     * @throws BadRequestException
     *             if there is no data in storage or the file format is not RDF
     */
    public Annotation annotate(URI body, Thing target)
            throws BadRequestException {
        return annotate(body, new HashSet<>(new ArrayList<Thing>(Arrays.asList(target))), null);
    }


    /**
     * Add and aggregate a new annotation to the research object.
     * 
     * @param body
     *            annotation body URI
     * @param targets
     *            list of annotated resources URIs
     * @return new annotation
     * @throws BadRequestException
     *             if there is no data in storage or the file format is not RDF
     */
    public Annotation annotate(URI body, Set<Thing> targets)
            throws BadRequestException {
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
     * @throws BadRequestException
     *             if there is no data in storage or the file format is not RDF
     */
    public Annotation annotate(URI body, Set<Thing> targets, String annotationId)
            throws BadRequestException {
        URI annotationUri = getAnnotationUri(annotationId);
        Annotation annotation = Annotation.create(builder, this, annotationUri, body, targets);
        return annotation;
    }


    /**
     * Add and aggregate a new annotation to the research object.
     * 
     * @param data
     *            annotation description
     * @return new annotation
     * @throws BadRequestException
     *             if there is no data in storage or the file format is not RDF
     */
    public Annotation annotate(InputStream data)
            throws BadRequestException {
        URI annotationUri = getAnnotationUri(null);
        Annotation annotation = Annotation.create(builder, this, annotationUri, data);
        return annotation;
    }


    /**
     * Create a copy of an annotation and aggregated it. The annotation URI will be different, the other fields will be
     * the same. If the body is aggregated in the original annotation's RO, and it's not aggregated in this RO, then it
     * is also copied.
     * 
     * @param annotation
     *            the annotation to copy
     * @param evoBuilder
     *            builder of evolution properties
     * @return the new annotation
     * @throws BadRequestException
     *             if there is no data in storage or the file format is not RDF
     */
    public Annotation copy(Annotation annotation, EvoBuilder evoBuilder)
            throws BadRequestException {
        Annotation annotation2 = annotation.copy(builder, evoBuilder, this);
        return annotation2;
    }


    /**
     * Get an annotation URI based on the id.
     * 
     * @param annotationId
     *            annotation id, random UUID will be used if null
     * @return the annotation URI
     */
    private URI getAnnotationUri(String annotationId) {
        if (annotationId == null) {
            annotationId = UUID.randomUUID().toString();
        }
        URI annotationUri = uri.resolve(".ro/annotations/" + annotationId);
        return annotationUri;
    }


    /**
     * Delete the RO index.
     * 
     */
    public void deleteIndexAttributes() {
        try {
            SearchServer searchServer = SolrSearchServer.get();
            searchServer.deleteROAttributes(getUri());
        } catch (Exception e) {
            LOGGER.error("Cannot delete index from the search server: " + e.getMessage());
        }
    }


    /**
     * Update the RO index.
     */
    public void updateIndexAttributes() {
        try {
            Multimap<URI, Object> roDescription = this.getManifest().getDescriptionFor(this.getUri());
            for (Annotation annotation : this.getAnnotations().values()) {
                roDescription.putAll(annotation.getBody().getDescriptionFor(this.getUri()));
            }
            SearchServer searchServer = SolrSearchServer.get();
            searchServer.saveRO(this, roDescription);
        } catch (Exception e) {
            LOGGER.error("Cannot store index in the search server: " + e.getMessage());
        }
    }


    /**
     * Create a new research object submitted in ZIP format.
     * 
     * @param builder
     *            model instance builder
     * @param researchObjectUri
     *            the new research object
     * @param zip
     *            the ZIP file
     * @param status
     *            the status of proceeded operation
     * @return HTTP response (created in case of success, 404 in case of error)
     * @throws IOException
     *             error creating the temporary filez
     * @throws BadRequestException
     *             the ZIP contains an invalid RO
     */
    public static ResearchObject create(Builder builder, URI researchObjectUri, MemoryZipFile zip,
            ROFromZipJobStatus status)
            throws IOException, BadRequestException {
        status.setProcessedResources(0);
        Dataset dataset = DatasetFactory.createMem();
        Builder inMemoryBuilder = new Builder(builder.getUser(), dataset, false);
        Model model = ModelFactory.createDefaultModel();
        try (InputStream manifest = zip.getManifestAsInputStream()) {
            if (manifest == null) {
                throw new BadRequestException("Manifest not found");
            }
            model.read(manifest, researchObjectUri.resolve(ResearchObject.MANIFEST_PATH).toString(), "RDF/XML");
            dataset.addNamedModel(researchObjectUri.resolve(ResearchObject.MANIFEST_PATH).toString(), model);
            //System.out.println("Manifest in Turtle");
            //model.write(System.out, "TURTLE");
        }
        ResearchObject inMemoryResearchObject = inMemoryBuilder.buildResearchObject(researchObjectUri);
        ResearchObject researchObject = create(builder, researchObjectUri);
        
        int submittedresources = 0;
        for (Resource resource : inMemoryResearchObject.getResources().values()) {
            if (resource.isSpecialResource()) {
                continue;
            } else {
                submittedresources++;
            }
        }
        for (Annotation annotation : inMemoryResearchObject.getAnnotations().values()) {
            try {
                if (inMemoryResearchObject.getAggregatedResources().containsKey(annotation.getBody().getUri())) {
                    AggregatedResource body = inMemoryResearchObject.getAggregatedResources().get(
                        annotation.getBody().getUri());
                    if (body.isSpecialResource()) {
                        continue;
                    }
                    submittedresources++;
                }
            } catch (Exception e) {
                LOGGER.error("Error when aggregating annotations", e);
            }
        }
        status.setSubmittedResources(submittedresources);
        for (Resource resource : inMemoryResearchObject.getResources().values()) {
        	
            if (resource.isSpecialResource()) {
                continue;
            }
            try {
                if (zip.containsEntry(resource.getPath())) {
                	if (resource.getPath().contains("bundle.zip")){
                		unpackAndAggregate(researchObject, zip, resource.getPath(),
                				RoBundle.MIME_TYPE);
                	}
                	else{
                		unpackAndAggregate(researchObject, zip, resource.getPath(),
                				MimeTypeUtil.getContentType(resource.getPath()));
                	}
                    LOGGER.debug("Aggregated an internal resource " + resource.getUri());
                } else {
                    researchObject.aggregate(resource.getUri());
                    LOGGER.debug("Aggregated an external resource " + resource.getUri());
                }
                if (status.getProcessedResources() < status.getSubmittedResources()) {
                    status.setProcessedResources(status.getProcessedResources() + 1);
                }
            } catch (Exception e) {
                LOGGER.error("Error when aggregating resources", e);
            }
        }
        for (Annotation annotation : inMemoryResearchObject.getAnnotations().values()) {
            try {
                if (inMemoryResearchObject.getAggregatedResources().containsKey(annotation.getBody().getUri())) {
                    AggregatedResource body = inMemoryResearchObject.getAggregatedResources().get(
                        annotation.getBody().getUri());
                    if (body.isSpecialResource()) {
                        continue;
                    }
                    
                    if (!researchObject.getAggregatedResources().containsKey(researchObject.getUri().resolve(body.getPath()))){
                    	unpackAndAggregate(researchObject, zip, body.getPath(),
                    			RDFFormat.forFileName(body.getPath(), RDFFormat.RDFXML).getDefaultMIMEType());
                    	LOGGER.debug("Aggregated an internal annotation body " + body.getUri());
                    }
                }
                
                Annotation annotation2 = researchObject.annotate(annotation.getBody().getUri(), annotation.getAnnotated());
                LOGGER.debug("Aggregated an annotation with body " + annotation2.getBody().getUri());
                if (status.getProcessedResources() < status.getSubmittedResources()) {
                    status.setProcessedResources(status.getProcessedResources() + 1);
                }
            } catch (Exception e) {
                LOGGER.error("Error when aggregating annotations", e);
            }
        }
        
        for (Folder folder : inMemoryResearchObject.getFolders().values()) {
        	researchObject.aggregateFolder(folder.getUri());
        	//researchObject.aggregateFolder(researchObject.getUri().resolve(folder.getPath().replace(" ", "%20"))); //APPARENTLY NOT NECESSARY - FOLDER.GETURI IS ALREADY RESOLVED
        	LOGGER.debug("Aggregated folder " + folder.getUri());
        }
        
        for (Entry<URI, Folder> entryFolder : researchObject.getFolders().entrySet()){
        	String queryString = String
        	                .format(
        	                	"PREFIX ore: <%s> PREFIX ro: <%s> SELECT ?resource WHERE { <%s> ore:aggregates ?resource . ?resource a ro:Resource . }",
        	                    ORE.NAMESPACE, RO.NAMESPACE, entryFolder.getKey().toString());	
        	Query query = QueryFactory.create(queryString);
        	QueryExecution qe = QueryExecutionFactory.create(query, model);
        	
        	try {
        		ResultSet results = qe.execSelect();
        	    while (results.hasNext()) {
        	    	QuerySolution solution = results.next();
        			RDFNode f = solution.get("resource");
        			URI resourceUri = researchObject.getUri().resolve(f.asResource().getURI());
        			AggregatedResource resource = researchObject.getResources().get(resourceUri); 
                    if (resource == null) {
                    	resource = researchObject.getFolders().get(resourceUri);
                    	if (resource == null) {
                    		LOGGER.warn("Resource for entry not found: " + resourceUri);
                            continue;
                        }
                    }
                    entryFolder.getValue().createFolderEntry(resource);
                    LOGGER.debug("Created an entry for " + resource.getUri() + " in " + entryFolder.getValue().getUri());
                }
        	} finally {
        		qe.close();
        	}
        }
        
        /* THIS SHOULD BE THE CORRECT WAY OF ADDING FOLDER ENTRIES (INSTEAD OF LAST FOR LOOP), 
         * BUT RESOURCEMAPS IN MYEXPERIMENT
         * ARE NOT HAVING CORRECT ORE:AGGREGATES, I.E., ALL FOLDERS (EXCEPT FROM LEAF LEVEL) 
         * ONLY INCLUDE ORE:AGGREGATE OF SUBFOLDERS NOT RESOURCES 
         * 
        for (Entry<URI, Folder> entryFolder : researchObject.getFolders().entrySet()){
        	Folder folder = inMemoryResearchObject.getFolders().get(entryFolder.getKey());
        	InputStream is = zip.getFolderResourceMap(folder.getResourceMap().getPath());
        	Model model2 = ModelFactory.createDefaultModel();
        	model2.read(is, researchObjectUri.resolve(folder.getResourceMap().getPath()).toString(), "RDF/XML");
        	//System.out.println("resource map in Turtle");
            //model2.write(System.out, "TURTLE");
        	
        	String queryString = String
	                .format(
	                	"PREFIX ore: <%s> PREFIX ro: <%s> SELECT ?resource WHERE { <%s> ore:aggregates ?resource . }",
	                    ORE.NAMESPACE, RO.NAMESPACE, entryFolder.getKey().toString());	
        	Query query = QueryFactory.create(queryString);
        	QueryExecution qe = QueryExecutionFactory.create(query, model2);
        	try {
        		ResultSet results = qe.execSelect();
        	    while (results.hasNext()) {
        	    	QuerySolution solution = results.next();
        			RDFNode f = solution.get("resource");
        			URI resourceUri = researchObject.getUri().resolve(f.asResource().getURI());
        			AggregatedResource resource = researchObject.getResources().get(resourceUri); 
                    if (resource == null) {
                    	resource = researchObject.getFolders().get(resourceUri);
                    	if (resource == null) {
                    		LOGGER.warn("Resource for entry not found: " + resourceUri);
                            continue;
                        }
                    }
                    entryFolder.getValue().createFolderEntry(resource);
                    LOGGER.debug("Created an entry for " + resource.getUri() + " in " + entryFolder.getValue().getUri());
        	    }
        	}finally {
        		qe.close();
        	}
        	
        }
        */
        dataset.close();
        return researchObject;
    }


    /**
     * Unpack a resource from the ZIP archive and aggregate to the research object.
     * 
     * @param researchObject
     *            research object to which to aggregate
     * @param zip
     *            ZIP archive
     * @param path
     *            resource path
     * @param mimeType
     *            resource MIME type
     * @throws IOException
     *             when there is an error handling the temporary file or the zip archive
     * @throws FileNotFoundException
     *             when the temporary file cannot be read
     * @throws BadRequestException
     *             when the resource should be an annotation body but is not an RDF file
     */
    private static void unpackAndAggregate(ResearchObject researchObject, MemoryZipFile zip, String path,
            String mimeType)
            throws IOException, FileNotFoundException, BadRequestException {
        UUID uuid = UUID.randomUUID();
        File tmpFile = File.createTempFile("tmp_resource", uuid.toString());
        try (InputStream is = zip.getEntryAsStream(path)) {
            FileOutputStream fileOutputStream = new FileOutputStream(tmpFile);
            IOUtils.copy(is, fileOutputStream);
            researchObject.aggregate(path, new FileInputStream(tmpFile), mimeType);
        } finally {
            tmpFile.delete();
        }
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


    /**
     * Get aggregated ro:Resources, excluding ro:Folders, loaded lazily.
     * 
     * @return aggregated resources mapped by their URI
     */
    public Map<URI, Resource> getResources() {
        if (resources == null) {
            this.resources = getManifest().extractResources();
        }
        return resources;
    }


    /**
     * Get aggregated ro:Folders, loaded lazily.
     * 
     * @return aggregated folders mapped by their URI
     */
    public Map<URI, Folder> getFolders() {
        if (folders == null) {
            this.folders = getManifest().extractFolders();
        }
        return folders;
    }


    /**
     * Get folder entries of all folders.
     * 
     * @return folder entries mapped by the URIs.
     */
    public Map<URI, FolderEntry> getFolderEntries() {
        if (folderEntries == null) {
            folderEntries = new HashMap<>();
            for (Folder folder : getFolders().values()) {
                folderEntries.putAll(folder.getFolderEntries());
            }
        }
        return folderEntries;
    }


    /**
     * Get folder entries grouped by the URI of the resource they proxy. Loaded lazily.
     * 
     * @return multimap of folder entries
     */
    public Multimap<URI, FolderEntry> getFolderEntriesByResourceUri() {
        if (folderEntriesByResourceUri == null) {
            folderEntriesByResourceUri = HashMultimap.<URI, FolderEntry> create();
            for (FolderEntry entry : getFolderEntries().values()) {
                if (entry.getProxyFor() != null) {
                    folderEntriesByResourceUri.put(entry.getProxyFor().getUri(), entry);
                } else {
                    LOGGER.warn("Folder entry " + entry + " has no proxy for");
                }
            }
        }
        return folderEntriesByResourceUri;
    }


    /**
     * Get proxies for aggregated resources, loaded lazily.
     * 
     * @return proxies mapped by their URI
     */
    @Override
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


    /**
     * Get aggregated annotations, loaded lazily.
     * 
     * @return aggregated annotations mapped by their URI
     */
    public Map<URI, Annotation> getAnnotations() {
        if (annotations == null) {
            this.annotations = getManifest().extractAnnotations();
        }
        return annotations;
    }


    /**
     * Get aggregated annotations, mapped by the annotated resources, loaded lazily.
     * 
     * @return aggregated annotations mapped by annotated resources URIs
     */
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
     * Get aggregated annotations, mapped by the bodies, loaded lazily.
     * 
     * @return aggregated annotations mapped by body URIs
     */
    public Multimap<URI, Annotation> getAnnotationsByBodyUri() {
        if (annotationsByBodyUri == null) {
            this.annotationsByBodyUri = HashMultimap.<URI, Annotation> create();
            for (Annotation ann : getAnnotations().values()) {
                this.annotationsByBodyUri.put(ann.getBody().getUri(), ann);
            }
        }
        return annotationsByBodyUri;
    }


    /**
     * Get the aggregated resource. Load the metadata first, if necessary.
     * 
     * @return a map of aggregated resource by their URI
     */
    @Override
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


    /**
     * Get manifest and folder resource maps, loading the lazily.
     * 
     * @return folder resource maps mapped by their URIs
     */
    public Map<URI, ResourceMap> getResourceMaps() {
        if (resourceMaps == null) {
            this.resourceMaps = new HashMap<>();
            this.resourceMaps.put(getManifest().getUri(), getManifest());
            for (Folder folder : getFolders().values()) {
                resourceMaps.put(folder.getResourceMap().getUri(), folder.getResourceMap());
            }
        }
        return resourceMaps;
    }


    @Override
    public DateTime getCreated() {
        if (created == null) {
            this.created = getManifest().extractCreated(this);
        }
        return super.getCreated();
    }


    @Override
    public UserMetadata getCreator() {
        if (creator == null) {
            this.creator = getManifest().extractCreator(this);
        }
        return super.getCreator();
    }


    /**
     * Is there already a resource in this RO with that URI.
     * 
     * @param uri
     *            the URI
     * @return true if there is an aggregated resource / proxy / folder resource map / manifest / folder entry with that
     *         URI
     */
    public boolean isUriUsed(URI uri) {
        return getAggregatedResources().containsKey(uri) || getProxies().containsKey(uri)
                || getFolderEntries().containsKey(uri) || getResourceMaps().containsKey(uri);
    }


    public InputStream getAsZipArchive() {
        return builder.getDigitalLibrary().getZippedResearchObject(uri);
    }


    /**
     * Get all research objects. If the user is set whose role is not public, only the user's research objects are
     * looked for.
     * 
     * @param builder
     *            builder that defines the dataset and the user
     * @param userMetadata
     *            the user to filter the results
     * @return a set of research objects
     */
    public static Set<ResearchObject> getAll(Builder builder, UserMetadata userMetadata) {
        boolean wasStarted = builder.beginTransaction(ReadWrite.READ);
        try {
            Set<ResearchObject> ros = new HashSet<>();
            String queryString;
            if (userMetadata == null || userMetadata.getRole() == Role.PUBLIC) {
                queryString = String.format(
                    "PREFIX ro: <%s> SELECT ?ro WHERE { GRAPH ?g { ?ro a ro:ResearchObject . } }", RO.NAMESPACE);
            } else {
                queryString = String
                        .format(
                            "PREFIX ro: <%s> PREFIX dcterms: <%s> SELECT ?ro WHERE { GRAPH ?g { ?ro a ro:ResearchObject ; dcterms:creator <%s> . } }",
                            RO.NAMESPACE, DCTerms.NS, userMetadata.getUri());
            }
            Query query = QueryFactory.create(queryString);
            QueryExecution qe = QueryExecutionFactory.create(query, builder.getDataset());
            try {
                ResultSet results = qe.execSelect();
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    RDFNode r = solution.get("ro");
                    URI rUri = URI.create(r.asResource().getURI());
                    ros.add(builder.buildResearchObject(rUri));
                }
            } finally {
                qe.close();
            }
            return ros;
        } finally {
            builder.endTransaction(wasStarted);
        }
    }


    public EvoInfo getEvoInfo() {
        return getLiveEvoInfo();
    }


    @Override
    public Map<URI, ResearchObjectComponentSerializable> getSerializables() {
        HashMap<URI, ResearchObjectComponentSerializable> result = new HashMap<>();

        for (URI uri : getAggregatedResources().keySet()) {
            if (getAggregatedResources().get(uri).isInternal()) {
                result.put(uri, getAggregatedResources().get(uri));
            }
        }
        for (URI uri : getResourceMaps().keySet()) {
            if (getResourceMaps().get(uri) != null && getResourceMaps().get(uri).isInternal()) {
                result.put(uri, getResourceMaps().get(uri));
            }
        }
        return result;
    }


    /**
     * Return the URI of the RO bundle if exists.
     * 
     * @return the URI of this RO's bundle or null if doesn't exist
     */
    public URI getBundleUri() {
        if (bundleUri == null) {
            bundleUri = getManifest().extractAlternativeFormat(RoBundle.MIME_TYPE);
        }
        return bundleUri;
    }


    /**
     * Save the URI of the RO bundle representing this RO.
     * 
     * @param bundleUri
     *            the URI of this RO's bundle
     */
    public void setBundleUri(URI bundleUri) {
        this.bundleUri = bundleUri;
        getManifest().saveAlternativeFormat(bundleUri, RoBundle.MIME_TYPE);
    }


    /**
     * Return the serialization as RO Bundle if available.
     * 
     * @return the serialized RO bundle or null if not available
     */
    public InputStream getBundle() {
        URI bundleUri2 = getBundleUri();
        if (bundleUri2 == null) {
            return null;
        }
        // The bundle may be stored inside the parent RO. The path may then start with ../[parentRO]/.
        Path bundlePath = Paths.get(uri.getPath()).relativize(Paths.get(bundleUri2.getPath()));
        return builder.getDigitalLibrary().getFileContents(uri, bundlePath.toString());
    }


    /**
     * Return the URI of the RO bundle if exists.
     * 
     * @return the URI of this RO's bundle or null if doesn't exist
     */
    public Collection<URI> getAggregatingROUris() {
        if (aggregatingROUris == null) {
            aggregatingROUris = getManifest().extractAggregatingROUris();
        }
        return aggregatingROUris;
    }

}
