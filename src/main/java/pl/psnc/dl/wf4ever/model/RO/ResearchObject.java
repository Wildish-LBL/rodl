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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import javax.activation.MimetypesFileTypeMap;
import javax.ws.rs.core.UriBuilder;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.openrdf.rio.RDFFormat;

import pl.psnc.dl.wf4ever.common.util.MemoryZipFile;
import pl.psnc.dl.wf4ever.connection.DigitalLibraryFactory;
import pl.psnc.dl.wf4ever.dl.ConflictException;
import pl.psnc.dl.wf4ever.dl.NotFoundException;
import pl.psnc.dl.wf4ever.dl.UserMetadata;
import pl.psnc.dl.wf4ever.dl.UserMetadata.Role;
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
import pl.psnc.dl.wf4ever.vocabulary.RO;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.hp.hpl.jena.query.Dataset;
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
import com.hp.hpl.jena.tdb.TDBFactory;
import com.hp.hpl.jena.vocabulary.DCTerms;

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
     * Constructor.
     * 
     * @param user
     *            user creating the instance
     * @param uri
     *            the RO URI
     */
    public ResearchObject(UserMetadata user, URI uri) {
        super(user, uri);
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
        if (get(builder, uri) != null) {
            throw new ConflictException("Research Object already exists: " + uri);
        }
        ResearchObject researchObject = builder.buildResearchObject(uri, builder.getUser(), DateTime.now());
        researchObject.manifest = Manifest.create(builder, researchObject.getUri().resolve(MANIFEST_PATH),
            researchObject);
        researchObject.save();
        researchObject.createEvoInfo();
        return researchObject;
    }


    /**
     * Generate new evolution information, including the evolution annotation.
     */
    public void createEvoInfo() {
        try {
            evoInfo = LiveEvoInfo.create(builder, getFixedEvolutionAnnotationBodyUri(), this);
            getAggregatedResources().put(evoInfo.getUri(), evoInfo);
            evoInfo.save();

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
            evoInfo = builder.buildLiveEvoInfo(getFixedEvolutionAnnotationBodyUri(), this, null, null);
            getAggregatedResources().put(evoInfo.getUri(), evoInfo);
            evoInfo.load();
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


    public void save() {
        super.save();
        getManifest().save();

        //TODO check if to create an RO or only serialize the manifest
        DigitalLibraryFactory.getDigitalLibrary().createResearchObject(uri,
            getManifest().getGraphAsInputStream(RDFFormat.RDFXML), ResearchObject.MANIFEST_PATH,
            RDFFormat.RDFXML.getDefaultMIMEType());
    }


    /**
     * Delete the Research Object including its resources and annotations.
     */
    @Override
    public void delete() {
        //create another collection to avoid concurrent modification
        Set<AggregatedResource> resourcesToDelete = new HashSet<>(getAggregatedResources().values());
        for (AggregatedResource resource : resourcesToDelete) {
            resource.delete();
        }
        getManifest().delete();
        try {
            DigitalLibraryFactory.getDigitalLibrary().deleteResearchObject(uri);
        } catch (NotFoundException e) {
            // good, nothing was left so the folder was deleted
            LOGGER.debug("As expected. RO folder was empty and was deleted: " + e.getMessage());
        }
        super.delete();
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
        if (getAnnotationsByBodyUri().containsKey(resource.getUri())) {
            resource.saveGraphAndSerialize();
        }
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
        Resource resource = Resource.create(builder, this, uri);
        this.getManifest().serialize();
        this.getResources().put(resource.getUri(), resource);
        this.getAggregatedResources().put(resource.getUri(), resource);
        this.getProxies().put(resource.getProxy().getUri(), resource.getProxy());
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
        if (getAnnotationsByBodyUri().containsKey(resource2.getUri())) {
            resource2.saveGraphAndSerialize();
        }
        getManifest().serialize();
        this.getResources().put(resource2.getUri(), resource2);
        this.getAggregatedResources().put(resource2.getUri(), resource2);
        this.getProxies().put(resource2.getProxy().getUri(), resource2.getProxy());
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
        if (getAnnotationsByBodyUri().containsKey(resource2.getUri())) {
            resource2.saveGraphAndSerialize();
        }
        getManifest().serialize();
        this.getAggregatedResources().put(resource2.getUri(), resource2);
        this.getProxies().put(resource2.getProxy().getUri(), resource2.getProxy());
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
        getManifest().serialize();
        this.getFolders().put(folder.getUri(), folder);
        this.getAggregatedResources().put(folder.getUri(), folder);
        this.getProxies().put(folder.getProxy().getUri(), folder.getProxy());
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
        getManifest().serialize();
        this.getFolders().put(folder2.getUri(), folder2);
        this.getAggregatedResources().put(folder2.getUri(), folder2);
        this.getProxies().put(folder2.getProxy().getUri(), folder2.getProxy());
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
        return postAnnotate(annotation);
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
        return postAnnotate(annotation);
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
        return postAnnotate(annotation2);
    }


    /**
     * Make all changes necessary after creating the annotation.
     * 
     * @param annotation
     *            new annotation
     * @return the annotation
     * @throws BadRequestException
     *             if there is no data in storage or the file format is not RDF
     */
    private Annotation postAnnotate(Annotation annotation)
            throws BadRequestException {
        AggregatedResource resource = getAggregatedResources().get(annotation.getBody().getUri());
        if (resource != null && resource.isInternal()) {
            resource.saveGraphAndSerialize();
            int c = resource.updateReferences(resource.getResearchObject());
            LOGGER.debug(String.format("Updated %d triples in %s", c, resource.getUri()));
            getManifest().removeRoResourceClass(resource);
        }
        getManifest().serialize();
        this.getAnnotations().put(annotation.getUri(), annotation);
        for (Thing target : annotation.getAnnotated()) {
            this.getAnnotationsByTarget().put(target.getUri(), annotation);
        }
        this.getAnnotationsByBodyUri().put(annotation.getBody().getUri(), annotation);
        this.getAggregatedResources().put(annotation.getUri(), annotation);
        return annotation;
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
     * Update the RO index.
     */
    public void updateIndexAttributes() {
        //FIXME this makes no sense without dLibra
        //        Multimap<URI, Object> roAttributes = ROSRService.SMS.get().getAllAttributes(uri);
        //        roAttributes.put(URI.create("Identifier"), this);
        //        try {
        //            DigitalLibraryFactory.getDigitalLibrary().storeAttributes(uri, roAttributes);
        //        } catch (Exception e) {
        //            LOGGER.error("Caught an exception when updating RO attributes, will continue", e);
        //        }
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
     * @return HTTP response (created in case of success, 404 in case of error)
     * @throws IOException
     *             error creating the temporary file
     * @throws BadRequestException
     *             the ZIP contains an invalid RO
     */
    public static ResearchObject create(Builder builder, URI researchObjectUri, MemoryZipFile zip)
            throws IOException, BadRequestException {
        Dataset dataset = TDBFactory.createDataset();
        Builder inMemoryBuilder = new Builder(builder.getUser(), dataset, false);
        try (InputStream manifest = zip.getManifestAsInputStream()) {
            if (manifest == null) {
                throw new BadRequestException("Manifest not found");
            }
            Model model = ModelFactory.createDefaultModel();
            model.read(manifest, researchObjectUri.resolve(ResearchObject.MANIFEST_PATH).toString(), "RDF/XML");
            dataset.addNamedModel(researchObjectUri.resolve(ResearchObject.MANIFEST_PATH).toString(), model);
        }
        ResearchObject inMemoryResearchObject = inMemoryBuilder.buildResearchObject(researchObjectUri);
        ResearchObject researchObject = create(builder, researchObjectUri);

        MimetypesFileTypeMap mfm = new MimetypesFileTypeMap();
        try (InputStream mimeTypesIs = ResearchObject.class.getClassLoader().getResourceAsStream("mime.types")) {
            mfm = new MimetypesFileTypeMap(mimeTypesIs);
        }
        for (Resource resource : inMemoryResearchObject.getResources().values()) {
            if (resource.isSpecialResource()) {
                continue;
            }
            try {
                if (zip.containsEntry(resource.getPath())) {
                    unpackAndAggregate(researchObject, zip, resource.getPath(), mfm.getContentType(resource.getPath()));
                } else {
                    researchObject.aggregate(resource.getUri());
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
                    unpackAndAggregate(researchObject, zip, body.getPath(), mfm.getContentType(body.getPath()));
                }
                researchObject.annotate(annotation.getBody().getUri(), annotation.getAnnotated());
            } catch (Exception e) {
                LOGGER.error("Error when aggregating annotations", e);
            }
        }
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
                folderEntriesByResourceUri.put(entry.getProxyFor().getUri(), entry);
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
        return DigitalLibraryFactory.getDigitalLibrary().getZippedResearchObject(uri);
    }


    /**
     * Get all research objects. If the builder has a user set whose role is not public, only the user's research
     * objects are looked for.
     * 
     * @param builder
     *            builder that defines the dataset and the user
     * @return a set of research objects
     */
    public static Set<ResearchObject> getAll(Builder builder) {
        boolean wasStarted = builder.beginTransaction(ReadWrite.READ);
        try {
            Set<ResearchObject> ros = new HashSet<>();
            String queryString;
            if (builder.getUser() == null || builder.getUser().getRole() == Role.PUBLIC) {
                queryString = String.format("PREFIX ro: <%s> SELECT ?ro WHERE { ?ro a ro:ResearchObject . }",
                    RO.NAMESPACE);
            } else {
                queryString = String
                        .format(
                            "PREFIX ro: <%s> PREFIX dcterms: <%s> SELECT ?ro WHERE { ?ro a ro:ResearchObject ; dcterms:creator <%s> . }",
                            RO.NAMESPACE, DCTerms.NS, builder.getUser().getUri());
            }
            Query query = QueryFactory.create(queryString);
            QueryExecution qe = QueryExecutionFactory.create(query,
                builder.getDataset().getNamedModel("urn:x-arq:UnionGraph"));
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

}
