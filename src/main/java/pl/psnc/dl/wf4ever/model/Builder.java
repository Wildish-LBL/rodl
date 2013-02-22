package pl.psnc.dl.wf4ever.model;

import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;

import pl.psnc.dl.wf4ever.common.db.EvoType;
import pl.psnc.dl.wf4ever.dl.UserMetadata;
import pl.psnc.dl.wf4ever.model.AO.Annotation;
import pl.psnc.dl.wf4ever.model.ORE.AggregatedResource;
import pl.psnc.dl.wf4ever.model.ORE.Aggregation;
import pl.psnc.dl.wf4ever.model.ORE.Proxy;
import pl.psnc.dl.wf4ever.model.RDF.Thing;
import pl.psnc.dl.wf4ever.model.RO.Folder;
import pl.psnc.dl.wf4ever.model.RO.FolderEntry;
import pl.psnc.dl.wf4ever.model.RO.FolderResourceMap;
import pl.psnc.dl.wf4ever.model.RO.Manifest;
import pl.psnc.dl.wf4ever.model.RO.ResearchObject;
import pl.psnc.dl.wf4ever.model.RO.Resource;
import pl.psnc.dl.wf4ever.model.ROEVO.ImmutableEvoInfo;
import pl.psnc.dl.wf4ever.model.ROEVO.ImmutableResearchObject;
import pl.psnc.dl.wf4ever.model.ROEVO.LiveEvoInfo;
import pl.psnc.dl.wf4ever.vocabulary.W4E;

import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.ReadWrite;
import com.hp.hpl.jena.tdb.TDB;
import com.hp.hpl.jena.tdb.TDBFactory;

/**
 * Builder in the builder design pattern. Used to build ROSR and EVO model instances.
 * 
 * @author piotrekhol
 * 
 */
public class Builder {

    /** Triple store location. */
    protected static final String TRIPLE_STORE_DIR = getStoreDirectory("connection.properties");

    /** Logger. */
    private static final Logger LOGGER = Logger.getLogger(Builder.class);
    /** Jena dataset. */
    private final Dataset dataset;

    /** Use transactions on the Jena dataset. */
    private final boolean useTransactions;

    /** Authenticated user. */
    private final UserMetadata user;

    static {
        init();
    }


    /**
     * Constructor.
     * 
     * @param user
     *            Authenticated user
     * @param dataset
     *            Jena dataset
     * @param useTransactions
     *            Use transactions on the Jena dataset
     */
    public Builder(UserMetadata user, Dataset dataset, boolean useTransactions) {
        this.dataset = dataset;
        this.user = user;
        this.useTransactions = useTransactions;
    }


    /**
     * Constructor of a builder that uses the default dataset with transactions.
     * 
     * @param user
     *            Authenticated user
     */
    public Builder(UserMetadata user) {
        this(user, TDBFactory.createDataset(TRIPLE_STORE_DIR), true);
    }


    /**
     * Load the triple store location from the properties file. In case of any exceptions, log them and return null.
     * 
     * @param filename
     *            properties file name
     * @return the path to the triple store directory
     */
    private static String getStoreDirectory(String filename) {
        try (InputStream is = Thing.class.getClassLoader().getResourceAsStream(filename)) {
            Properties props = new Properties();
            props.load(is);
            return props.getProperty("store.directory");

        } catch (Exception e) {
            LOGGER.error("Trple store location can not be loaded from the properties file", e);
        }
        return null;
    }


    /**
     * Init .
     * 
     */
    public static void init() {
        TDB.getContext().set(TDB.symUnionDefaultGraph, true);
        W4E.DEFAULT_MODEL.setNsPrefixes(W4E.STANDARD_NAMESPACES);
    }


    public Dataset getDataset() {
        return dataset;
    }


    public boolean isUseTransactions() {
        return useTransactions;
    }


    public UserMetadata getUser() {
        return user;
    }


    /**
     * Start a TDB transaction provided that the flag useTransactions is set, the dataset supports transactions and
     * there is no open transaction. According to TDB, many read or one write transactions are allowed.
     * 
     * @param mode
     *            read or write
     * @return true if a new transaction has been started, false otherwise
     */
    public boolean beginTransaction(ReadWrite mode) {
        boolean started = false;
        if (useTransactions && dataset.supportsTransactions() && !dataset.isInTransaction()) {
            dataset.begin(mode);
            started = true;
        }
        return started;
    }


    /**
     * Commit the transaction provided that the flag useTransactions is set, the dataset supports transactions and the
     * parameter is true.
     * 
     * @param wasStarted
     *            a convenience parameter to specify if the transaction should be committed
     */
    public void commitTransaction(boolean wasStarted) {
        if (useTransactions && dataset.supportsTransactions() && wasStarted) {
            dataset.commit();
        }
    }


    /**
     * End the transaction provided that the flag useTransactions is set, the dataset supports transactions and the
     * parameter is true.
     * 
     * @param wasStarted
     *            a convenience parameter to specify if the transaction should be ended
     */
    public void endTransaction(boolean wasStarted) {
        if (useTransactions && dataset.supportsTransactions() && wasStarted) {
            TDB.sync(dataset);
            dataset.end();
        }
    }


    /**
     * Abort the transaction provided that the flag useTransactions is set, the dataset supports transactions and the
     * parameter is true.
     * 
     * @param wasStarted
     *            a convenience parameter to specify if the transaction should be aborted
     */
    public void abortTransaction(boolean wasStarted) {
        if (useTransactions && dataset.supportsTransactions() && wasStarted) {
            dataset.abort();
        }
    }


    /**
     * Build a new Thing.
     * 
     * @param uri
     *            the URI
     * @return a new Thing instance
     */
    public Thing buildThing(URI uri) {
        Thing thing = new Thing(user, dataset, useTransactions, uri);
        thing.setBuilder(this);
        return thing;
    }


    /**
     * Build a new research object.
     * 
     * @param uri
     *            the URI
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new Research Object instance
     */
    public ResearchObject buildResearchObject(URI uri, UserMetadata creator, DateTime created) {
        ResearchObject researchObject = new ResearchObject(user, dataset, useTransactions, uri);
        researchObject.setCreator(creator);
        researchObject.setCreated(created);
        researchObject.setBuilder(this);
        return researchObject;
    }


    /**
     * Build a new research object.
     * 
     * @param uri
     *            the URI
     * @return a new Research Object instance
     */
    public ResearchObject buildResearchObject(URI uri) {
        ResearchObject researchObject = new ResearchObject(user, dataset, useTransactions, uri);
        researchObject.setBuilder(this);
        return researchObject;
    }


    /**
     * Build a new manifest.
     * 
     * @param uri
     *            the URI
     * @param researchObject
     *            the research object that is described
     * @return a new manifest instance
     */
    public Manifest buildManifest(URI uri, ResearchObject researchObject) {
        Manifest manifest = new Manifest(user, dataset, useTransactions, uri, researchObject);
        manifest.setBuilder(this);
        return manifest;
    }


    /**
     * Build a new manifest.
     * 
     * @param uri
     *            the URI
     * @param researchObject
     *            the research object that is described
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new manifest instance
     */
    public Manifest buildManifest(URI uri, ResearchObject researchObject, UserMetadata creator, DateTime created) {
        Manifest manifest = new Manifest(user, dataset, useTransactions, uri, researchObject);
        manifest.setCreator(creator);
        manifest.setCreated(created);
        manifest.setBuilder(this);
        return manifest;
    }


    /**
     * Build a new aggregated resource.
     * 
     * @param uri
     *            the URI
     * @param researchObject
     *            the research object that aggregates the resource
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new aggregated resource instance
     */
    public AggregatedResource buildAggregatedResource(URI uri, ResearchObject researchObject, UserMetadata creator,
            DateTime created) {
        AggregatedResource resource = new AggregatedResource(user, dataset, useTransactions, researchObject, uri);
        resource.setCreator(creator);
        resource.setCreated(created);
        resource.setBuilder(this);
        return resource;
    }


    /**
     * Build a new proxy.
     * 
     * @param uri
     *            the URI
     * @param proxyFor
     *            the resource for which the proxy stands
     * @param proxyIn
     *            the aggregation aggregating the proxyFor resource
     * @return a new proxy
     */
    public Proxy buildProxy(URI uri, AggregatedResource proxyFor, Aggregation proxyIn) {
        Proxy proxy = new Proxy(user, dataset, useTransactions, uri);
        proxy.setProxyFor(proxyFor);
        proxy.setProxyIn(proxyIn);
        proxy.setBuilder(this);
        return proxy;
    }


    /**
     * Build a new annotation.
     * 
     * @param researchObject
     *            research object aggregating the annotation
     * @param uri
     *            annotation URI
     * @param body
     *            annotation body
     * @param targets
     *            annotated resources
     * @return a new annotation
     */
    public Annotation buildAnnotation(ResearchObject researchObject, URI uri, Thing body, Set<Thing> targets) {
        Annotation annotation = new Annotation(user, dataset, useTransactions, researchObject, uri);
        annotation.setBody(body);
        annotation.setAnnotated(targets);
        annotation.setBuilder(this);
        return annotation;
    }


    /**
     * Build a new annotation.
     * 
     * @param researchObject
     *            research object aggregating the annotation
     * @param uri
     *            annotation URI
     * @param body
     *            annotation body
     * @param targets
     *            annotated resources
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new annotation
     */
    public Annotation buildAnnotation(ResearchObject researchObject, URI uri, Thing body, Set<Thing> targets,
            UserMetadata creator, DateTime created) {
        Annotation annotation = buildAnnotation(researchObject, uri, body, targets);
        annotation.setCreator(creator);
        annotation.setCreated(created);
        annotation.setBuilder(this);
        return annotation;
    }


    /**
     * Build a new annotation with one annotated resource.
     * 
     * @param researchObject
     *            research object aggregating the annotation
     * @param uri
     *            annotation URI
     * @param body
     *            annotation body
     * @param target
     *            the annotated resource
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new annotation
     */
    public Annotation buildAnnotation(ResearchObject researchObject, URI uri, Thing body, Thing target,
            UserMetadata creator, DateTime created) {
        Set<Thing> targets = new HashSet<>();
        targets.add(target);
        return buildAnnotation(researchObject, uri, body, targets, creator, created);
    }


    /**
     * Build a new ro:Resource.
     * 
     * @param researchObject
     *            research object aggregating the resource
     * @param uri
     *            resource URI
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new resource
     */
    public Resource buildResource(ResearchObject researchObject, URI uri, UserMetadata creator, DateTime created) {
        Resource resource = new Resource(user, dataset, useTransactions, researchObject, uri);
        resource.setCreator(creator);
        resource.setCreated(created);
        resource.setBuilder(this);
        return resource;
    }


    /**
     * Build a new folder.
     * 
     * @param researchObject
     *            research object aggregating the folder
     * @param uri
     *            folder URI
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new folder
     */
    public Folder buildFolder(ResearchObject researchObject, URI uri, UserMetadata creator, DateTime created) {
        Folder folder = new Folder(user, dataset, useTransactions, researchObject, uri);
        folder.setCreator(creator);
        folder.setCreated(created);
        folder.setBuilder(this);
        return folder;
    }


    /**
     * Build a new folder resource map.
     * 
     * @param uri
     *            resource map URI
     * @param folder
     *            folder it describes
     * @return a new folder resource map
     */
    public FolderResourceMap buildFolderResourceMap(URI uri, Folder folder) {
        FolderResourceMap map = new FolderResourceMap(user, dataset, useTransactions, folder, uri);
        map.setBuilder(this);
        return map;
    }


    /**
     * Build a new folder entry.
     * 
     * @param uri
     *            folder entry URI
     * @param aggregatedResource
     *            resource it stands for
     * @param proxyIn
     *            folder for which it is created
     * @param name
     *            resource name in the folder
     * @return a new folder entry
     */
    public FolderEntry buildFolderEntry(URI uri, AggregatedResource aggregatedResource, Folder proxyIn, String name) {
        FolderEntry entry = new FolderEntry(user, dataset, useTransactions, uri);
        entry.setProxyFor(aggregatedResource);
        entry.setProxyIn(proxyIn);
        entry.setEntryName(name);
        entry.setBuilder(this);
        return entry;
    }


    /**
     * Build a new immutable research object.
     * 
     * @param uri
     *            the URI
     * @return a new immutable Research Object instance
     */
    public ImmutableResearchObject buildImmutableResearchObject(URI uri) {
        ImmutableResearchObject researchObject = new ImmutableResearchObject(user, dataset, useTransactions, uri);
        researchObject.setBuilder(this);
        return researchObject;
    }


    /**
     * Build a new immutable research object.
     * 
     * @param uri
     *            the URI
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new immutable Research Object instance
     */
    public ImmutableResearchObject buildImmutableResearchObject(URI uri, UserMetadata creator, DateTime created) {
        ImmutableResearchObject researchObject = new ImmutableResearchObject(user, dataset, useTransactions, uri);
        researchObject.setCreator(creator);
        researchObject.setCreated(created);
        researchObject.setBuilder(this);
        return researchObject;
    }


    /**
     * Build a new evolution information resource.
     * 
     * @param uri
     *            the URI
     * @param researchObject
     *            the research object that is described
     * @param creator
     *            author
     * @param created
     *            creation date
     * @return a new evo info instance
     */
    public LiveEvoInfo buildLiveEvoInfo(URI uri, ResearchObject researchObject, UserMetadata creator, DateTime created) {
        LiveEvoInfo evoInfo = new LiveEvoInfo(user, dataset, useTransactions, researchObject, uri);
        evoInfo.setCreator(creator);
        evoInfo.setCreated(created);
        evoInfo.setBuilder(this);
        return evoInfo;
    }


    /**
     * Build a new evolution information resource.
     * 
     * @param uri
     *            the URI
     * @param immutableResearchObject
     *            the research object that is described
     * @param creator
     *            author
     * @param created
     *            creation date
     * @param evoType
     * @return a new evo info instance
     */
    public ImmutableEvoInfo buildImmutableEvoInfo(URI uri, ImmutableResearchObject immutableResearchObject,
            UserMetadata creator, DateTime created, EvoType evoType) {
        ImmutableEvoInfo evoInfo = new ImmutableEvoInfo(user, dataset, useTransactions, immutableResearchObject, uri);
        evoInfo.setCreator(creator);
        evoInfo.setCreated(created);
        evoInfo.setEvoType(evoType);
        evoInfo.setBuilder(this);
        return evoInfo;
    }


    /**
     * Build a new evolution information resource.
     * 
     * @param uri
     *            the URI
     * @param immutableResearchObject
     *            the research object that is described
     * @return a new evo info instance
     */
    public ImmutableEvoInfo buildImmutableEvoInfo(URI uri, ImmutableResearchObject immutableResearchObject) {
        ImmutableEvoInfo evoInfo = new ImmutableEvoInfo(user, dataset, useTransactions, immutableResearchObject, uri);
        evoInfo.setBuilder(this);
        return evoInfo;
    }

}
