package pl.psnc.dl.wf4ever.model.RO;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.joda.time.DateTime;

import pl.psnc.dl.wf4ever.common.Builder;
import pl.psnc.dl.wf4ever.dl.AccessDeniedException;
import pl.psnc.dl.wf4ever.dl.ConflictException;
import pl.psnc.dl.wf4ever.dl.DigitalLibraryException;
import pl.psnc.dl.wf4ever.dl.NotFoundException;
import pl.psnc.dl.wf4ever.dl.UserMetadata;
import pl.psnc.dl.wf4ever.model.AO.Annotation;
import pl.psnc.dl.wf4ever.model.ORE.AggregatedResource;
import pl.psnc.dl.wf4ever.model.ORE.ResourceMap;
import pl.psnc.dl.wf4ever.model.RDF.Thing;
import pl.psnc.dl.wf4ever.vocabulary.AO;
import pl.psnc.dl.wf4ever.vocabulary.ORE;
import pl.psnc.dl.wf4ever.vocabulary.RO;

import com.hp.hpl.jena.ontology.Individual;
import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ReadWrite;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.vocabulary.DCTerms;

/**
 * ro:Manifest.
 * 
 */
public class Manifest extends ResourceMap {

    /**
     * Constructor.
     * 
     * @param user
     *            user creating the instance
     * @param uri
     *            manifest uri
     * @param researchObject
     *            research object being described
     */
    public Manifest(UserMetadata user, Dataset dataset, boolean useTransactions, URI uri, ResearchObject researchObject) {
        super(user, dataset, useTransactions, researchObject, uri);
        setNamedGraph(true);
    }


    /**
     * Constructor.
     * 
     * @param user
     *            user creating the instance
     * @param uri
     *            manifest uri
     * @param researchObject
     *            research object being described
     */
    public Manifest(UserMetadata user, URI uri, ResearchObject researchObject) {
        super(user, researchObject, uri);
        setNamedGraph(true);
    }


    @Override
    public void save()
            throws ConflictException, DigitalLibraryException, AccessDeniedException, NotFoundException {
        super.save();
        boolean transactionStarted = beginTransaction(ReadWrite.WRITE);
        try {
            Individual ro = model.createIndividual(aggregation.getUri().toString(), RO.ResearchObject);
            Individual manifest = model.createIndividual(uri.toString(), RO.Manifest);
            model.add(ro, ORE.isDescribedBy, manifest);
            model.add(manifest, ORE.describes, ro);

            saveAuthor((Thing) aggregation);
            saveAuthor(this);
            commitTransaction(transactionStarted);
        } finally {
            endTransaction(transactionStarted);
        }
    }


    public static Manifest get(Builder builder, URI uri, ResearchObject researchObject) {
        Manifest manifest = builder.buildManifest(uri, researchObject);
        return manifest;
    }


    public static Manifest create(Builder builder, URI uri, ResearchObject researchObject)
            throws ConflictException, DigitalLibraryException, AccessDeniedException, NotFoundException {
        Manifest manifest = builder.buildManifest(uri, researchObject, builder.getUser().getUri(), DateTime.now());
        manifest.save();
        return manifest;
    }


    public void saveRoResourceClass(Resource resource) {
        boolean transactionStarted = beginTransaction(ReadWrite.WRITE);
        try {
            model.createIndividual(resource.getUri().toString(), RO.Resource);
            commitTransaction(transactionStarted);
        } finally {
            endTransaction(transactionStarted);
        }
    }


    public void saveFolderClass(Folder folder) {
        boolean transactionStarted = beginTransaction(ReadWrite.WRITE);
        try {
            Individual folderInd = model.createIndividual(folder.getUri().toString(), RO.Folder);
            com.hp.hpl.jena.rdf.model.Resource folderMapR = model.createResource(folder.getResourceMap().getUri()
                    .toString());
            folderInd.addProperty(ORE.isDescribedBy, folderMapR);
            commitTransaction(transactionStarted);
        } finally {
            endTransaction(transactionStarted);
        }
    }


    public void saveRoStats(Resource resource) {
        if (resource.getStats() != null) {
            boolean transactionStarted = beginTransaction(ReadWrite.WRITE);
            try {
                Individual resourceR = model.getIndividual(resource.getUri().toString());
                if (resource.getStats().getName() != null) {
                    model.add(resourceR, RO.name, model.createTypedLiteral(resource.getStats().getName()));
                }
                model.add(resourceR, RO.filesize, model.createTypedLiteral(resource.getStats().getSizeInBytes()));
                if (resource.getStats().getChecksum() != null && resource.getStats().getDigestMethod() != null) {
                    model.add(resourceR, RO.checksum, model.createResource(String.format("urn:%s:%s", resource
                            .getStats().getDigestMethod(), resource.getStats().getChecksum())));
                }
                commitTransaction(transactionStarted);
            } finally {
                endTransaction(transactionStarted);
            }
        }
    }


    public Map<URI, AggregatedResource> extractAggregatedResources(Map<URI, Resource> resources2,
            Map<URI, Folder> folders2, Map<URI, Annotation> annotations2) {
        boolean transactionStarted = beginTransaction(ReadWrite.READ);
        try {
            Map<URI, AggregatedResource> aggregated = new HashMap<>();
            String queryString = String
                    .format(
                        "PREFIX ore: <%s> PREFIX dcterms: <%s> SELECT ?resource ?proxy ?created ?creator WHERE { <%s> ore:aggregates ?resource . ?resource a ore:AggregatedResource . OPTIONAL { ?proxy ore:proxyFor ?resource . } OPTIONAL { ?resource dcterms:creator ?creator . } OPTIONAL { ?resource dcterms:created ?created . } }",
                        ORE.NAMESPACE, DCTerms.NS, aggregation.getUri().toString());

            Query query = QueryFactory.create(queryString);
            QueryExecution qe = QueryExecutionFactory.create(query, model);
            try {
                ResultSet results = qe.execSelect();
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    AggregatedResource resource;
                    RDFNode r = solution.get("resource");
                    URI rUri = URI.create(r.asResource().getURI());
                    if (resources2.containsKey(rUri)) {
                        resource = resources2.get(rUri);
                    } else if (folders2.containsKey(rUri)) {
                        resource = folders2.get(rUri);
                    } else if (annotations2.containsKey(rUri)) {
                        resource = annotations2.get(rUri);
                    } else {
                        RDFNode p = solution.get("proxy");
                        URI pUri = p != null ? URI.create(p.asResource().getURI()) : null;
                        RDFNode creatorNode = solution.get("creator");
                        URI resCreator = creatorNode != null && creatorNode.isURIResource() ? URI.create(creatorNode
                                .asResource().getURI()) : null;
                        RDFNode createdNode = solution.get("created");
                        DateTime resCreated = createdNode != null && createdNode.isLiteral() ? DateTime
                                .parse(createdNode.asLiteral().getString()) : null;
                        resource = builder.buildAggregatedResource(rUri, getResearchObject(), resCreator, resCreated);
                        if (pUri != null) {
                            resource.setProxy(builder.buildProxy(pUri, resource, getResearchObject()));
                        }
                    }
                    aggregated.put(rUri, resource);
                }
            } finally {
                qe.close();
            }
            return aggregated;
        } finally {
            endTransaction(transactionStarted);
        }
    }


    /**
     * Identify ro:Resources that are not ro:Folders, aggregated by the RO.
     * 
     * @return a set of resources (not loaded)
     */
    public Map<URI, Resource> extractResources() {
        boolean transactionStarted = beginTransaction(ReadWrite.READ);
        try {
            Map<URI, Resource> resources2 = new HashMap<>();
            String queryString = String
                    .format(
                        "PREFIX ore: <%s> PREFIX dcterms: <%s> PREFIX ro: <%s> SELECT ?resource ?proxy ?created ?creator WHERE { <%s> ore:aggregates ?resource . ?resource a ro:Resource . ?proxy ore:proxyFor ?resource . OPTIONAL { ?resource dcterms:creator ?creator . } OPTIONAL { ?resource dcterms:created ?created . } }",
                        ORE.NAMESPACE, DCTerms.NS, RO.NAMESPACE, aggregation.getUri().toString());

            Query query = QueryFactory.create(queryString);
            QueryExecution qe = QueryExecutionFactory.create(query, model);
            try {
                ResultSet results = qe.execSelect();
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    RDFNode r = solution.get("resource");
                    if (r.as(Individual.class).hasRDFType(RO.Folder)) {
                        continue;
                    }
                    URI rUri = URI.create(r.asResource().getURI());
                    RDFNode p = solution.get("proxy");
                    URI pUri = URI.create(p.asResource().getURI());
                    RDFNode creatorNode = solution.get("creator");
                    URI resCreator = creatorNode != null && creatorNode.isURIResource() ? URI.create(creatorNode
                            .asResource().getURI()) : null;
                    RDFNode createdNode = solution.get("created");
                    DateTime resCreated = createdNode != null && createdNode.isLiteral() ? DateTime.parse(createdNode
                            .asLiteral().getString()) : null;
                    Resource resource = builder.buildResource(getResearchObject(), rUri, resCreator, resCreated);
                    if (pUri != null) {
                        resource.setProxy(builder.buildProxy(pUri, resource, getResearchObject()));
                    }
                    resources2.put(rUri, resource);
                }
            } finally {
                qe.close();
            }
            return resources2;
        } finally {
            endTransaction(transactionStarted);
        }
    }


    /**
     * Identify ro:Resources that are not ro:Folders, aggregated by the RO.
     * 
     * @return a set of folders (not loaded)
     */
    public Map<URI, Folder> extractFolders() {
        boolean transactionStarted = beginTransaction(ReadWrite.READ);
        try {
            Map<URI, Folder> folders2 = new HashMap<>();
            String queryString = String
                    .format(
                        "PREFIX ore: <%s> PREFIX dcterms: <%s> PREFIX ro: <%s> SELECT ?folder ?proxy ?resourcemap ?created ?creator WHERE { <%s> ore:aggregates ?folder . ?folder a ro:Folder ; ore:isDescribedBy ?resourcemap . ?proxy ore:proxyFor ?folder . OPTIONAL { ?folder dcterms:creator ?creator . } OPTIONAL { ?folder dcterms:created ?created . } }",
                        ORE.NAMESPACE, DCTerms.NS, RO.NAMESPACE, aggregation.getUri().toString());

            Query query = QueryFactory.create(queryString);
            QueryExecution qe = QueryExecutionFactory.create(query, model);
            try {
                ResultSet results = qe.execSelect();
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    RDFNode f = solution.get("folder");
                    URI fURI = URI.create(f.asResource().getURI());
                    RDFNode p = solution.get("proxy");
                    URI pUri = URI.create(p.asResource().getURI());
                    RDFNode rm = solution.get("resourcemap");
                    //this is not used because the URI of resource map is fixed
                    URI rmUri = URI.create(rm.asResource().getURI());
                    RDFNode creatorNode = solution.get("creator");
                    URI resCreator = creatorNode != null && creatorNode.isURIResource() ? URI.create(creatorNode
                            .asResource().getURI()) : null;
                    RDFNode createdNode = solution.get("created");
                    DateTime resCreated = createdNode != null && createdNode.isLiteral() ? DateTime.parse(createdNode
                            .asLiteral().getString()) : null;

                    String queryString2 = String.format("PREFIX ro: <%s> ASK { <%s> ro:rootFolder <%s> }",
                        RO.NAMESPACE, uri.toString(), fURI.toString());
                    Query query2 = QueryFactory.create(queryString2);
                    QueryExecution qe2 = QueryExecutionFactory.create(query2, model);
                    boolean isRootFolder = false;
                    try {
                        isRootFolder = qe2.execAsk();
                    } finally {
                        qe2.close();
                    }

                    Folder folder = builder.buildFolder(getResearchObject(), fURI, resCreator, resCreated);
                    folder.setRootFolder(isRootFolder);
                    if (pUri != null) {
                        folder.setProxy(builder.buildProxy(pUri, folder, getResearchObject()));
                    }
                    folders2.put(fURI, folder);
                }
            } finally {
                qe.close();
            }
            return folders2;
        } finally {
            endTransaction(transactionStarted);
        }
    }


    /**
     * Identify ro:AggregatedAnnotations that aggregated by the RO.
     * 
     * @return a multivalued map of annotations, with bodies not loaded
     */
    public Map<URI, Annotation> extractAnnotations() {
        boolean transactionStarted = beginTransaction(ReadWrite.READ);
        try {
            Map<URI, Annotation> annotationsByUri = new HashMap<>();
            String queryString = String
                    .format(
                        "PREFIX ore: <%s> PREFIX dcterms: <%s> PREFIX ao: <%s> PREFIX ro: <%s> SELECT ?annotation ?body ?target ?created ?creator WHERE { <%s> ore:aggregates ?annotation . ?annotation a ro:AggregatedAnnotation ; ao:body ?body ; ro:annotatesAggregatedResource ?target . ?proxy ore:proxyFor ?annotation . OPTIONAL { ?annotation dcterms:creator ?creator . } OPTIONAL { ?annotation dcterms:created ?created . } }",
                        ORE.NAMESPACE, DCTerms.NS, AO.NAMESPACE, RO.NAMESPACE, aggregation.getUri().toString());

            Query query = QueryFactory.create(queryString);
            QueryExecution qe = QueryExecutionFactory.create(query, model);
            try {
                ResultSet results = qe.execSelect();
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    RDFNode a = solution.get("annotation");
                    URI aURI = URI.create(a.asResource().getURI());
                    RDFNode p = solution.get("proxy");
                    URI pUri = p != null ? URI.create(p.asResource().getURI()) : null;
                    RDFNode t = solution.get("target");
                    URI tUri = URI.create(t.asResource().getURI());
                    Annotation annotation;
                    if (annotationsByUri.containsKey(aURI)) {
                        annotation = annotationsByUri.get(aURI);
                        annotation.getAnnotated().add(new Thing(user, tUri));
                    } else {
                        RDFNode b = solution.get("body");
                        URI bUri = URI.create(b.asResource().getURI());
                        RDFNode creatorNode = solution.get("creator");
                        URI resCreator = creatorNode != null && creatorNode.isURIResource() ? URI.create(creatorNode
                                .asResource().getURI()) : null;
                        RDFNode createdNode = solution.get("created");
                        DateTime resCreated = createdNode != null && createdNode.isLiteral() ? DateTime
                                .parse(createdNode.asLiteral().getString()) : null;
                        annotation = builder.buildAnnotation(getResearchObject(), aURI, builder.buildThing(bUri),
                            builder.buildThing(tUri), resCreator, resCreated);
                        if (pUri != null) {
                            annotation.setProxy(builder.buildProxy(pUri, annotation, getResearchObject()));
                        }
                    }
                }
            } finally {
                qe.close();
            }
            return annotationsByUri;
        } finally {
            endTransaction(transactionStarted);
        }
    }


    public ResearchObject getResearchObject() {
        return (ResearchObject) aggregation;
    }
}
