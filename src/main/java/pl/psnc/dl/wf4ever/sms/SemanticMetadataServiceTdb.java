package pl.psnc.dl.wf4ever.sms;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javax.naming.NamingException;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.openrdf.rio.RDFFormat;

import pl.psnc.dl.wf4ever.common.EvoType;
import pl.psnc.dl.wf4ever.common.ResearchObject;
import pl.psnc.dl.wf4ever.common.util.SafeURI;
import pl.psnc.dl.wf4ever.dl.ResourceMetadata;
import pl.psnc.dl.wf4ever.dl.UserMetadata;
import pl.psnc.dl.wf4ever.exceptions.ManifestTraversingException;
import pl.psnc.dl.wf4ever.model.AO.Annotation;
import pl.psnc.dl.wf4ever.model.ORE.AggregatedResource;
import pl.psnc.dl.wf4ever.model.RO.Folder;
import pl.psnc.dl.wf4ever.model.RO.FolderEntry;
import pl.psnc.dl.wf4ever.vocabulary.AO;
import pl.psnc.dl.wf4ever.vocabulary.FOAF;
import pl.psnc.dl.wf4ever.vocabulary.ORE;
import pl.psnc.dl.wf4ever.vocabulary.PROV;
import pl.psnc.dl.wf4ever.vocabulary.RO;
import pl.psnc.dl.wf4ever.vocabulary.ROEVO;
import pl.psnc.dl.wf4ever.vocabulary.W4E;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.hp.hpl.jena.datatypes.xsd.XSDDateTime;
import com.hp.hpl.jena.ontology.Individual;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.ontology.OntModelSpec;
import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ReadWrite;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.query.ResultSetFormatter;
import com.hp.hpl.jena.query.Syntax;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.shared.JenaException;
import com.hp.hpl.jena.tdb.TDB;
import com.hp.hpl.jena.tdb.TDBFactory;
import com.hp.hpl.jena.vocabulary.DCTerms;

import de.fuberlin.wiwiss.ng4j.NamedGraphSet;
import de.fuberlin.wiwiss.ng4j.impl.NamedGraphSetImpl;

public class SemanticMetadataServiceTdb implements SemanticMetadataService {

    private static final Logger log = Logger.getLogger(SemanticMetadataServiceTdb.class);

    private final Dataset dataset;

    private final String getResourceQueryTmpl = "DESCRIBE <%s> WHERE { }";

    private final String getUserQueryTmpl = "DESCRIBE <%s> WHERE { }";

    private final String findResearchObjectsQueryTmpl = "PREFIX ro: <" + RO.NAMESPACE + "> SELECT ?ro "
            + "WHERE { ?ro a ro:ResearchObject. FILTER regex(str(?ro), \"^%s\") . }";

    private final String findResearchObjectsByCreatorQueryTmpl = "PREFIX ro: <" + RO.NAMESPACE + "> PREFIX dcterms: <"
            + DCTerms.NS + "> SELECT ?ro WHERE { ?ro a ro:ResearchObject ; dcterms:creator <%s> . }";

    private final UserMetadata user;

    /** SPARQL query syntax. */
    private static final Syntax SPARQL_SYNTAX = Syntax.syntaxARQ;


    public SemanticMetadataServiceTdb(UserMetadata user, boolean useDb)
            throws IOException {
        this.user = user;

        if (useDb) {
            String dir = getStoreDirectory("connection.properties");
            if (dir == null) {
                throw new IOException("Store directory could not be read");
            }
            dataset = TDBFactory.createDataset(dir);
        } else {
            dataset = TDBFactory.createDataset("/tmp/volatilestore");
            dataset.begin(ReadWrite.WRITE);
            List<String> models = new ArrayList<>();
            Iterator<String> it = dataset.listNames();
            while (it.hasNext()) {
                models.add(it.next());
            }
            for (String model : models) {
                dataset.removeNamedModel(model);
            }
            dataset.commit();
        }
        W4E.DEFAULT_MODEL.setNsPrefixes(W4E.STANDARD_NAMESPACES);
        createUserProfile(user);
        TDB.getContext().set(TDB.symUnionDefaultGraph, true);
    }


    public SemanticMetadataServiceTdb(UserMetadata user, ResearchObject researchObject, InputStream manifest,
            RDFFormat rdfFormat)
            throws IOException {
        this(user, false);
        createResearchObject(researchObject);
        updateManifest(researchObject, manifest, rdfFormat);
    }


    private void createUserProfile(UserMetadata user) {
        if (!containsNamedGraph(user.getUri())) {
            dataset.begin(ReadWrite.WRITE);
            try {
                OntModel userModel = createOntModelForNamedGraph(user.getUri());
                userModel.removeAll();
                Individual agent = userModel.createIndividual(user.getUri().toString(), FOAF.Agent);
                userModel.add(agent, FOAF.name, user.getName());
                dataset.commit();
            } finally {
                dataset.end();
            }
        }
    }


    private String getStoreDirectory(String filename)
            throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(filename);
        Properties props = new Properties();
        props.load(is);
        String dir = props.getProperty("store.directory");
        is.close();
        return dir;
    }


    @Override
    public UserMetadata getUserProfile() {
        return user;
    }


    @Override
    public void createResearchObject(ResearchObject researchObject) {
        createLiveResearchObject(researchObject, null);
    }


    @Override
    public void createLiveResearchObject(ResearchObject researchObject, ResearchObject source) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual manifest = manifestModel.getIndividual(researchObject.getManifestUri().toString());

            if (manifest != null) {
                throw new IllegalArgumentException("URI already exists: " + researchObject.getManifestUri());
            }

            manifest = manifestModel.createIndividual(researchObject.getManifestUri().toString(), RO.Manifest);
            Individual ro = manifestModel.createIndividual(researchObject.getUri().toString(), RO.ResearchObject);

            manifestModel.add(ro, ORE.isDescribedBy, manifest);
            manifestModel.add(ro, DCTerms.created, manifestModel.createTypedLiteral(Calendar.getInstance()));
            manifestModel.add(ro, DCTerms.creator, manifestModel.createResource(user.getUri().toString()));
            manifestModel.add(manifest, ORE.describes, ro);
            manifestModel.add(manifest, DCTerms.created, manifestModel.createTypedLiteral(Calendar.getInstance()));

            if (source != null) {
                manifestModel.add(ro, PROV.hadOriginalSource, manifestModel.createResource(source.getUri().toString()));
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public void createSnapshotResearchObject(ResearchObject researchObject, ResearchObject liveResearchObject) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual manifest = manifestModel.getIndividual(researchObject.getManifestUri().toString());
            if (manifest != null) {
                throw new IllegalArgumentException("URI already exists: " + researchObject.getManifestUri());
            }

            OntModel liveManifestModel = createOntModelForNamedGraph(liveResearchObject.getManifestUri());

            Individual liveManifest = liveManifestModel.getIndividual(liveResearchObject.getManifestUri().toString());

            manifest = manifestModel.createIndividual(researchObject.getManifestUri().toString(), RO.Manifest);
            Individual ro = manifestModel.createIndividual(researchObject.getUri().toString(), RO.ResearchObject);

            RDFNode creator, created;
            Resource liveRO;
            if (liveManifest == null) {
                log.warn("Live RO is not an RO: " + liveResearchObject.getUri());
                liveRO = manifestModel.createResource(liveResearchObject.getUri().toString());
                creator = manifestModel.createResource(user.getUri().toString());
                created = manifestModel.createTypedLiteral(Calendar.getInstance());
            } else {
                liveRO = liveManifestModel.getIndividual(liveResearchObject.getUri().toString());
                if (liveRO == null) {
                    throw new IllegalArgumentException("Live RO does not describe the research object");
                }
                creator = liveRO.getPropertyResourceValue(DCTerms.creator);
                created = liveRO.as(Individual.class).getPropertyValue(DCTerms.created);

            }

            manifestModel.add(ro, ORE.isDescribedBy, manifest);
            manifestModel.add(ro, DCTerms.created, created);
            manifestModel.add(ro, DCTerms.creator, creator);

            manifestModel.add(manifest, ORE.describes, ro);
            manifestModel.add(manifest, DCTerms.created, manifestModel.createTypedLiteral(Calendar.getInstance()));

            //TODO add wasRevisionOf
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public void createArchivedResearchObject(ResearchObject researchObject, ResearchObject liveResearchObject) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual manifest = manifestModel.getIndividual(researchObject.getManifestUri().toString());
            if (manifest != null) {
                throw new IllegalArgumentException("URI already exists: " + researchObject.getManifestUri());
            }

            OntModel liveManifestModel = createOntModelForNamedGraph(liveResearchObject.getManifestUri());
            Individual liveManifest = liveManifestModel.getIndividual(liveResearchObject.getManifestUri().toString());

            manifest = manifestModel.createIndividual(researchObject.getManifestUri().toString(), RO.Manifest);
            Individual ro = manifestModel.createIndividual(researchObject.getUri().toString(), RO.ResearchObject);

            RDFNode creator, created;
            Resource liveRO;
            if (liveManifest == null) {
                log.warn("Live RO is not an RO: " + liveResearchObject.getManifestUri());
                liveRO = manifestModel.createResource(liveResearchObject.getManifestUri().toString());
                creator = manifestModel.createResource(user.getUri().toString());
                created = manifestModel.createTypedLiteral(Calendar.getInstance());
            } else {
                liveRO = liveManifestModel.getIndividual(liveResearchObject.getManifestUri().toString());
                if (liveRO == null) {
                    throw new IllegalArgumentException("Live RO does not describe the research object");
                }
                creator = liveRO.getPropertyResourceValue(DCTerms.creator);
                created = liveRO.as(Individual.class).getPropertyValue(DCTerms.created);
            }

            manifestModel.add(ro, ORE.isDescribedBy, manifest);
            manifestModel.add(ro, DCTerms.created, created);
            manifestModel.add(ro, DCTerms.creator, creator);

            manifestModel.add(manifest, ORE.describes, ro);
            manifestModel.add(manifest, DCTerms.created, manifestModel.createTypedLiteral(Calendar.getInstance()));

            //TODO add wasRevisionOf
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public void updateManifest(ResearchObject researchObject, InputStream is, RDFFormat rdfFormat) {
        addNamedGraph(researchObject.getManifestUri(), is, rdfFormat);
    }


    @Override
    public InputStream getManifest(ResearchObject researchObject, RDFFormat rdfFormat) {
        return getNamedGraph(researchObject.getManifestUri(), rdfFormat);
    }


    @Override
    public boolean addResource(ResearchObject researchObject, URI resourceURI, ResourceMetadata resourceInfo) {
        dataset.begin(ReadWrite.WRITE);
        try {
            resourceURI = resourceURI.normalize();
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
            if (ro == null) {
                throw new IllegalArgumentException("URI not found: " + researchObject.getUri());
            }
            boolean created = false;
            Individual resource = manifestModel.getIndividual(resourceURI.toString());
            if (resource == null) {
                created = true;
                resource = manifestModel.createIndividual(resourceURI.toString(), RO.Resource);
            }
            manifestModel.add(ro, ORE.aggregates, resource);
            if (resourceInfo != null) {
                if (resourceInfo.getName() != null) {
                    manifestModel.add(resource, RO.name, manifestModel.createTypedLiteral(resourceInfo.getName()));
                }
                manifestModel.add(resource, RO.filesize,
                    manifestModel.createTypedLiteral(resourceInfo.getSizeInBytes()));
                if (resourceInfo.getChecksum() != null && resourceInfo.getDigestMethod() != null) {
                    manifestModel.add(resource, RO.checksum, manifestModel.createResource(String.format("urn:%s:%s",
                        resourceInfo.getDigestMethod(), resourceInfo.getChecksum())));
                }
            }
            manifestModel.add(resource, DCTerms.created, manifestModel.createTypedLiteral(Calendar.getInstance()));
            manifestModel.add(resource, DCTerms.creator, manifestModel.createResource(user.getUri().toString()));
            dataset.commit();
            return created;
        } finally {
            dataset.end();
        }
    }


    @Override
    public void removeResource(ResearchObject researchObject, URI resourceURI) {
        dataset.begin(ReadWrite.WRITE);
        try {
            resourceURI = resourceURI.normalize();
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
            if (ro == null) {
                throw new IllegalArgumentException("URI not found: " + researchObject.getUri());
            }
            Resource resource = manifestModel.getResource(resourceURI.toString());
            manifestModel.remove(ro, ORE.aggregates, resource);
            manifestModel.removeAll(resource, null, null);

            ResIterator it2 = manifestModel.listSubjectsWithProperty(RO.annotatesAggregatedResource, resource);
            while (it2.hasNext()) {
                Resource ann = it2.next();
                manifestModel.remove(ann, RO.annotatesAggregatedResource, resource);
                if (!ann.hasProperty(RO.annotatesAggregatedResource)) {
                    Resource annBody = ann.getPropertyResourceValue(AO.body);
                    if (annBody != null && annBody.isURIResource()) {
                        URI annBodyURI = URI.create(annBody.getURI());
                        if (dataset.containsNamedModel(SafeURI.URItoString(annBodyURI))) {
                            removeNamedGraphNoTransaction(annBodyURI);
                        }
                    }
                    manifestModel.removeAll(ann, null, null);
                    manifestModel.removeAll(null, null, ann);
                }
            }

            URI proxy = getProxyForResourceNoTransaction(researchObject, resourceURI);
            if (proxy != null) {
                deleteProxyNoTransaction(researchObject, proxy);
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public InputStream getResource(ResearchObject researchObject, URI resourceURI, RDFFormat rdfFormat) {
        dataset.begin(ReadWrite.READ);
        try {
            resourceURI = resourceURI.normalize();
            Model manifestModel = dataset.getNamedModel(researchObject.getManifestUri().toString());
            Resource resource = manifestModel.getResource(resourceURI.toString());
            if (!manifestModel.containsResource(resource)) {
                return null;
            }

            String queryString = String.format(getResourceQueryTmpl, resourceURI.toString());
            Query query = QueryFactory.create(queryString);

            QueryResult result = processDescribeQuery(query, rdfFormat);
            if (!result.getFormat().equals(rdfFormat)) {
                log.warn(String.format("Possible RDF format mismatch: %s requested, %s returned", rdfFormat.getName(),
                    result.getFormat().getName()));
            }
            return result.getInputStream();
        } finally {
            dataset.end();
        }
    }


    @Override
    public InputStream getNamedGraph(URI namedGraphURI, RDFFormat rdfFormat) {
        dataset.begin(ReadWrite.READ);
        try {
            namedGraphURI = namedGraphURI.normalize();
            if (!dataset.containsNamedModel(namedGraphURI.toString())) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (rdfFormat.supportsContexts()) {
                Dataset tmpDataset = TDBFactory.createDataset();
                addNamedModelsRecursively(tmpDataset, namedGraphURI);

                NamedGraphSet ngs = new NamedGraphSetImpl();
                Iterator<String> it = tmpDataset.listNames();
                while (it.hasNext()) {
                    String uri = it.next();
                    Model ng4jModel = ModelFactory.createModelForGraph(ngs.createGraph(uri));
                    Model tdbModel = tmpDataset.getNamedModel(uri);
                    List<Statement> statements = tdbModel.listStatements().toList();
                    ng4jModel.add(statements);
                }
                ngs.write(out, rdfFormat.getName().toUpperCase(), null);
            } else {
                dataset.getNamedModel(namedGraphURI.toString()).write(out, rdfFormat.getName().toUpperCase());
            }
            return new ByteArrayInputStream(out.toByteArray());
        } finally {
            dataset.end();
        }
    }


    @Override
    public InputStream getNamedGraphWithRelativeURIs(URI namedGraphURI, ResearchObject researchObject,
            RDFFormat rdfFormat) {
        dataset.begin(ReadWrite.READ);
        try {
            ResearchObjectRelativeWriter writer;
            if (rdfFormat != RDFFormat.RDFXML && rdfFormat != RDFFormat.TURTLE) {
                throw new RuntimeException("Format " + rdfFormat + " is not supported");
            } else if (rdfFormat == RDFFormat.RDFXML) {
                writer = new RO_RDFXMLWriter();
            } else {
                writer = new RO_TurtleWriter();
            }
            namedGraphURI = namedGraphURI.normalize();
            if (!dataset.containsNamedModel(namedGraphURI.toString())) {
                return null;
            }
            writer.setResearchObjectURI(researchObject.getUri());
            writer.setBaseURI(namedGraphURI);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.write(dataset.getNamedModel(namedGraphURI.toString()), out, null);
            return new ByteArrayInputStream(out.toByteArray());
        } finally {
            dataset.end();
        }
    }


    private void addNamedModelsRecursively(Dataset tmpDataset, URI namedGraphURI) {
        tmpDataset.addNamedModel(namedGraphURI.toString(), dataset.getNamedModel(namedGraphURI.toString()));

        OntModel annotationModel = createOntModelForNamedGraph(namedGraphURI);
        List<RDFNode> it = annotationModel.listObjectsOfProperty(AO.body).toList();
        it.addAll(annotationModel.listObjectsOfProperty(ORE.isDescribedBy).toList());
        for (RDFNode annotationBodyRef : it) {
            URI childURI = URI.create(annotationBodyRef.asResource().getURI());
            if (dataset.containsNamedModel(childURI.toString()) && !tmpDataset.containsNamedModel(childURI.toString())) {
                addNamedModelsRecursively(tmpDataset, childURI);
            }
        }
    }


    @Override
    public Set<URI> findResearchObjectsByPrefix(URI partialURI) {
        dataset.begin(ReadWrite.READ);
        try {
            String queryString = String.format(findResearchObjectsQueryTmpl, partialURI != null ? partialURI
                    .normalize().toString() : "");
            Query query = QueryFactory.create(queryString);

            // Execute the query and obtain results
            QueryExecution qe = QueryExecutionFactory.create(query, dataset.getNamedModel("urn:x-arq:UnionGraph"));
            ResultSet results = qe.execSelect();
            Set<URI> uris = new HashSet<URI>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                Resource manifest = solution.getResource("ro");
                uris.add(URI.create(manifest.getURI()));
            }

            // Important - free up resources used running the query
            qe.close();
            return uris;
        } finally {
            dataset.end();
        }
    }


    @Override
    public Set<URI> findResearchObjectsByCreator(URI user) {
        dataset.begin(ReadWrite.READ);
        try {
            if (user == null) {
                throw new IllegalArgumentException("User cannot be null");
            }
            String queryString = String.format(findResearchObjectsByCreatorQueryTmpl, user.toString());
            Query query = QueryFactory.create(queryString);

            // Execute the query and obtain results
            QueryExecution qe = QueryExecutionFactory.create(query, dataset.getNamedModel("urn:x-arq:UnionGraph"));
            ResultSet results = qe.execSelect();
            Set<URI> uris = new HashSet<URI>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                Resource manifest = solution.getResource("ro");
                uris.add(URI.create(manifest.getURI()));
            }

            // Important - free up resources used running the query
            qe.close();
            return uris;
        } finally {
            dataset.end();
        }
    }


    @Override
    public Set<URI> findResearchObjects() {
        return findResearchObjectsByPrefix(null);
    }


    /**
     * @param namedGraphURI
     * @return
     */
    private OntModel createOntModelForNamedGraph(URI namedGraphURI) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM,
            dataset.getNamedModel(namedGraphURI.toString()));
        //        ontModel.addSubModel(W4E.defaultModel);
        return ontModel;
    }


    @Override
    public void close() {
    }


    @Override
    public boolean isRoFolder(ResearchObject researchObject, URI resourceURI) {
        dataset.begin(ReadWrite.READ);
        try {
            resourceURI = resourceURI.normalize();
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual resource = manifestModel.getIndividual(resourceURI.toString());
            if (resource == null) {
                return false;
            }
            return resource.hasRDFType(RO.Folder);
        } finally {
            dataset.end();
        }
    }


    @Override
    public boolean addNamedGraph(URI graphURI, InputStream inputStream, RDFFormat rdfFormat) {
        boolean created = !containsNamedGraph(graphURI);
        dataset.begin(ReadWrite.WRITE);
        try {
            Model namedGraphModel = dataset.getNamedModel(graphURI.toString());
            namedGraphModel.removeAll();
            namedGraphModel.read(inputStream, graphURI.resolve(".").toString(), rdfFormat.getName().toUpperCase());
            dataset.commit();
            return created;
        } finally {
            dataset.end();
        }
    }


    @Override
    public boolean isROMetadataNamedGraph(ResearchObject researchObject, URI graphURI) {
        dataset.begin(ReadWrite.READ);
        try {
            Model manifest = dataset.getNamedModel(researchObject.getManifestUri().toString());
            Resource annBody = manifest.createResource(graphURI.toString());
            boolean isManifest = researchObject.getManifestUri().equals(graphURI);
            boolean isAnnotationBody = manifest.contains(null, AO.body, annBody);
            //FIXME this actually covers the manifest as well
            boolean isDescription = manifest.contains(null, ORE.isDescribedBy, annBody);
            return isManifest || isAnnotationBody || isDescription;
        } finally {
            dataset.end();
        }
    }


    @Override
    public void removeNamedGraph(URI graphURI) {
        dataset.begin(ReadWrite.WRITE);
        try {
            removeNamedGraphNoTransaction(graphURI);
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    private void removeNamedGraphNoTransaction(URI graphURI) {
        //@TODO Remove evo_inf file
        graphURI = graphURI.normalize();
        if (!dataset.containsNamedModel(graphURI.toString())) {
            return;
        }

        List<URI> graphsToDelete = new ArrayList<>();
        graphsToDelete.add(graphURI);

        int i = 0;
        while (i < graphsToDelete.size()) {
            OntModel model = createOntModelForNamedGraph(graphsToDelete.get(i));
            List<RDFNode> annotationBodies = model.listObjectsOfProperty(AO.body).toList();
            for (RDFNode annotationBodyRef : annotationBodies) {
                if (annotationBodyRef.isURIResource()) {
                    URI graphURI2 = URI.create(annotationBodyRef.asResource().getURI());
                    // TODO make sure that this named graph is internal
                    if (dataset.containsNamedModel(graphURI2.toString()) && !graphsToDelete.contains(graphURI2)) {
                        graphsToDelete.add(graphURI2);
                    }
                }
            }
            List<RDFNode> evos = model.listObjectsOfProperty(ROEVO.wasChangedBy).toList();
            for (RDFNode evo : evos) {
                if (evo.isURIResource()) {
                    URI graphURI2 = URI.create(evo.asResource().getURI());
                    if (dataset.containsNamedModel(graphURI2.toString()) && !graphsToDelete.contains(graphURI2)) {
                        graphsToDelete.add(graphURI2);
                    }
                }
            }
            List<Individual> folders = model.listIndividuals(RO.Folder).toList();
            for (Individual folder : folders) {
                if (folder.isURIResource()) {
                    Folder f = new Folder();
                    f.setUri(URI.create(folder.asResource().getURI()));
                    if (dataset.containsNamedModel(f.getResourceMapUri().toString())
                            && !graphsToDelete.contains(f.getResourceMapUri())) {
                        graphsToDelete.add(f.getResourceMapUri());
                    }
                }
            }
            i++;
        }
        for (URI graphURI2 : graphsToDelete) {
            dataset.removeNamedModel(graphURI2.toString());
        }
    }


    private OntModel createOntModelForAllNamedGraphs(OntModelSpec spec) {
        OntModel ontModel = ModelFactory.createOntologyModel(spec, dataset.getNamedModel("urn:x-arq:UnionGraph"));
        //        ontModel.addSubModel(W4E.defaultModel);
        return ontModel;
    }


    @Override
    public void removeResearchObject(ResearchObject researchObject) {
        removeNamedGraph(researchObject.getManifestUri());
    }


    @Override
    public boolean containsNamedGraph(URI graphURI) {
        dataset.begin(ReadWrite.READ);
        try {
            return dataset.containsNamedModel(SafeURI.URItoString(graphURI));
        } finally {
            dataset.end();
        }
    }


    @Override
    public QueryResult executeSparql(String queryS, RDFFormat rdfFormat) {
        dataset.begin(ReadWrite.READ);
        try {
            if (queryS == null) {
                throw new NullPointerException("Query cannot be null");
            }
            Query query = null;
            try {
                query = QueryFactory.create(queryS, SPARQL_SYNTAX);
            } catch (Exception e) {
                throw new IllegalArgumentException("Wrong query syntax: " + e.getMessage());
            }

            switch (query.getQueryType()) {
                case Query.QueryTypeSelect:
                    return processSelectQuery(query, rdfFormat);
                case Query.QueryTypeConstruct:
                    return processConstructQuery(query, rdfFormat);
                case Query.QueryTypeDescribe:
                    return processDescribeQuery(query, rdfFormat);
                case Query.QueryTypeAsk:
                    return processAskQuery(query, rdfFormat);
                default:
                    return null;
            }
        } finally {
            dataset.end();
        }
    }


    private QueryResult processSelectQuery(Query query, RDFFormat rdfFormat) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFFormat outputFormat;
        QueryExecution qexec = QueryExecutionFactory.create(query, dataset);
        if (SemanticMetadataService.SPARQL_JSON.equals(rdfFormat)) {
            outputFormat = SemanticMetadataService.SPARQL_JSON;
            ResultSetFormatter.outputAsJSON(out, qexec.execSelect());
        } else {
            outputFormat = SemanticMetadataService.SPARQL_XML;
            ResultSetFormatter.outputAsXML(out, qexec.execSelect());
        }
        qexec.close();

        return new QueryResult(new ByteArrayInputStream(out.toByteArray()), outputFormat);
    }


    private QueryResult processAskQuery(Query query, RDFFormat rdfFormat) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFFormat outputFormat;
        QueryExecution qexec = QueryExecutionFactory.create(query, dataset);
        if ("application/sparql-results+json".equals(rdfFormat.getDefaultMIMEType())) {
            outputFormat = SemanticMetadataService.SPARQL_JSON;
            ResultSetFormatter.outputAsJSON(out, qexec.execAsk());
        } else {
            outputFormat = SemanticMetadataService.SPARQL_XML;
            ResultSetFormatter.outputAsXML(out, qexec.execAsk());
        }
        qexec.close();

        return new QueryResult(new ByteArrayInputStream(out.toByteArray()), outputFormat);
    }


    private QueryResult processConstructQuery(Query query, RDFFormat rdfFormat) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QueryExecution qexec = QueryExecutionFactory.create(query, dataset);
        Model resultModel = qexec.execConstruct();
        qexec.close();

        RDFFormat outputFormat;
        if (RDFFormat.values().contains(rdfFormat)) {
            outputFormat = rdfFormat;
        } else {
            outputFormat = RDFFormat.RDFXML;
        }

        resultModel.write(out, outputFormat.getName().toUpperCase());
        return new QueryResult(new ByteArrayInputStream(out.toByteArray()), outputFormat);
    }


    private QueryResult processDescribeQuery(Query query, RDFFormat rdfFormat) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QueryExecution qexec = QueryExecutionFactory.create(query, dataset);
        Model resultModel = qexec.execDescribe();
        qexec.close();

        RDFFormat outputFormat;
        if (RDFFormat.values().contains(rdfFormat)) {
            outputFormat = rdfFormat;
        } else {
            outputFormat = RDFFormat.RDFXML;
        }

        resultModel.removeNsPrefix("xml");

        resultModel.write(out, outputFormat.getName().toUpperCase());
        return new QueryResult(new ByteArrayInputStream(out.toByteArray()), outputFormat);
    }


    @Override
    public Multimap<URI, Object> getAllAttributes(URI subjectURI) {
        dataset.begin(ReadWrite.WRITE);
        try {
            Multimap<URI, Object> attributes = HashMultimap.<URI, Object> create();
            // This could be an inference model but it slows down the lookup process and
            // generates super-attributes
            OntModel model = createOntModelForAllNamedGraphs(OntModelSpec.OWL_MEM);
            Resource subject = model.getResource(subjectURI.toString());
            if (subject == null) {
                dataset.abort();
                return attributes;
            }
            StmtIterator it = model.listStatements(subject, null, (RDFNode) null);
            while (it.hasNext()) {
                Statement s = it.next();
                try {
                    URI propURI = new URI(s.getPredicate().getURI());
                    Object object;
                    if (s.getObject().isResource()) {
                        // Need to check both because the model has no inference
                        if (s.getObject().as(Individual.class).hasRDFType(FOAF.Agent)
                                || s.getObject().as(Individual.class).hasRDFType(FOAF.Person)) {
                            object = s.getObject().asResource().getProperty(FOAF.name).getLiteral().getValue();
                        } else {
                            if (s.getObject().isURIResource()) {
                                object = new URI(s.getObject().asResource().getURI());
                            } else {
                                continue;
                            }
                        }
                    } else {
                        object = s.getObject().asLiteral().getValue();
                    }
                    if (object instanceof XSDDateTime) {
                        object = ((XSDDateTime) object).asCalendar();
                    }
                    attributes.put(propURI, object);
                } catch (URISyntaxException e) {
                    log.error(e);
                }
            }
            return attributes;
        } finally {
            dataset.end();
        }
    }


    @Override
    public QueryResult getUser(URI userURI, RDFFormat rdfFormat) {
        dataset.begin(ReadWrite.READ);
        try {
            userURI = userURI.normalize();

            String queryString = String.format(getUserQueryTmpl, userURI.toString());
            Query query = QueryFactory.create(queryString);

            return processDescribeQuery(query, rdfFormat);
        } finally {
            dataset.end();
        }
    }


    @Override
    public void removeUser(URI userURI) {
        dataset.begin(ReadWrite.WRITE);
        try {
            if (dataset.containsNamedModel(userURI.toString())) {
                dataset.removeNamedModel(userURI.toString());
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public boolean isAggregatedResource(ResearchObject researchObject, URI resource) {
        dataset.begin(ReadWrite.READ);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Resource researchObjectR = manifestModel.createResource(researchObject.getUri().toString());
            Resource resourceR = manifestModel.createResource(resource.normalize().toString());
            return manifestModel.contains(researchObjectR, ORE.aggregates, resourceR);
        } finally {
            dataset.end();
        }
    }


    @Override
    public boolean isAnnotation(ResearchObject researchObject, URI resource) {
        dataset.begin(ReadWrite.READ);
        try {
            return isAnnotationNoTransaction(researchObject, resource);
        } finally {
            dataset.end();
        }
    }


    private boolean isAnnotationNoTransaction(ResearchObject researchObject, URI resource) {
        OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
        Individual resourceR = manifestModel.getIndividual(resource.normalize().toString());
        return resourceR != null && resourceR.hasRDFType(RO.AggregatedAnnotation);
    }


    @Override
    public URI addProxy(ResearchObject researchObject, URI resource) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Resource researchObjectR = manifestModel.createResource(researchObject.getUri().toString());
            Resource resourceR = manifestModel.createResource(resource.normalize().toString());
            URI proxyURI = generateProxyURI(researchObject);
            Individual proxy = manifestModel.createIndividual(proxyURI.toString(), ORE.Proxy);
            manifestModel.add(proxy, ORE.proxyIn, researchObjectR);
            manifestModel.add(proxy, ORE.proxyFor, resourceR);
            dataset.commit();
            return proxyURI;
        } finally {
            dataset.end();
        }
    }


    private static URI generateProxyURI(ResearchObject researchObject) {
        return researchObject.getUri().resolve(".ro/proxies/" + UUID.randomUUID());
    }


    @Override
    public boolean isProxy(ResearchObject researchObject, URI resource) {
        dataset.begin(ReadWrite.READ);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual resourceR = manifestModel.getIndividual(resource.normalize().toString());
            return resourceR != null && resourceR.hasRDFType(ORE.Proxy);
        } finally {
            dataset.end();
        }
    }


    @Override
    public boolean existsProxyForResource(ResearchObject researchObject, URI resource) {
        return getProxyForResource(researchObject, resource) != null;
    }


    @Override
    public URI getProxyForResource(ResearchObject researchObject, URI resource) {
        dataset.begin(ReadWrite.READ);
        try {
            return getProxyForResourceNoTransaction(researchObject, resource);
        } finally {
            dataset.end();
        }
    }


    private URI getProxyForResourceNoTransaction(ResearchObject researchObject, URI resource) {
        OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
        Resource resourceR = manifestModel.createResource(resource.normalize().toString());
        ResIterator it = manifestModel.listSubjectsWithProperty(ORE.proxyFor, resourceR);
        while (it.hasNext()) {
            Individual r = it.next().as(Individual.class);
            if (r != null && r.hasRDFType(ORE.Proxy)) {
                return URI.create(r.getURI());
            }
        }
        return null;
    }


    @Override
    public URI getProxyFor(ResearchObject researchObject, URI proxy) {
        dataset.begin(ReadWrite.READ);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual proxyR = manifestModel.getIndividual(proxy.normalize().toString());
            if (proxyR.hasProperty(ORE.proxyFor)) {
                return URI.create(proxyR.getPropertyResourceValue(ORE.proxyFor).getURI());
            } else {
                return null;
            }
        } finally {
            dataset.end();
        }
    }


    @Override
    public void deleteProxy(ResearchObject researchObject, URI proxy) {
        dataset.begin(ReadWrite.WRITE);
        try {
            deleteProxyNoTransaction(researchObject, proxy);
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    private void deleteProxyNoTransaction(ResearchObject researchObject, URI proxy) {
        OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
        Resource proxyR = manifestModel.getResource(proxy.normalize().toString());
        manifestModel.removeAll(proxyR, null, null);
    }


    @Override
    public URI addAnnotation(ResearchObject researchObject, List<URI> annotationTargets, URI annotationBody) {
        return addAnnotation(researchObject, annotationTargets, annotationBody, null);
    }


    @Override
    public URI addAnnotation(ResearchObject researchObject, List<URI> annotationTargets, URI annotationBody,
            String annotationUUID) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Resource researchObjectR = manifestModel.createResource(researchObject.getUri().toString());
            Resource body = manifestModel.createResource(annotationBody.normalize().toString());
            URI annotationURI = generateAnnotationURI(researchObject, annotationUUID);
            Individual annotation = manifestModel.createIndividual(annotationURI.toString(), RO.AggregatedAnnotation);
            manifestModel.add(researchObjectR, ORE.aggregates, annotation);
            manifestModel.add(annotation, AO.body, body);
            for (URI targetURI : annotationTargets) {
                Resource target = manifestModel.createResource(targetURI.normalize().toString());
                manifestModel.add(annotation, RO.annotatesAggregatedResource, target);
            }
            manifestModel.add(annotation, DCTerms.created, manifestModel.createTypedLiteral(Calendar.getInstance()));
            Resource agent = manifestModel.createResource(user.getUri().toString());
            manifestModel.add(annotation, DCTerms.creator, agent);
            dataset.commit();
            return annotationURI;
        } finally {
            dataset.end();
        }
    }


    /**
     * Get the annotation URI basing on Research Object URI and given id.
     * 
     * @param researchObject
     *            give research object
     * @param id
     *            the postfix of result URI
     * @return new annotation URI
     */
    private static URI generateAnnotationURI(ResearchObject researchObject, String id) {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        return researchObject.getUri().resolve(".ro/annotations/" + id);
    }


    @Override
    public void updateAnnotation(ResearchObject researchObject, URI annotationURI, List<URI> annotationTargets,
            URI annotationBody) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Resource body = manifestModel.createResource(annotationBody.normalize().toString());
            Individual annotation = manifestModel.getIndividual(annotationURI.toString());
            manifestModel.removeAll(annotation, AO.body, null);
            manifestModel.removeAll(annotation, RO.annotatesAggregatedResource, null);
            manifestModel.add(annotation, AO.body, body);
            for (URI targetURI : annotationTargets) {
                Resource target = manifestModel.createResource(targetURI.normalize().toString());
                manifestModel.add(annotation, RO.annotatesAggregatedResource, target);
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public URI getAnnotationBody(ResearchObject researchObject, URI annotation) {
        dataset.begin(ReadWrite.READ);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual annotationR = manifestModel.getIndividual(annotation.normalize().toString());
            if (annotationR.hasProperty(AO.body)) {
                return URI.create(annotationR.getPropertyResourceValue(AO.body).getURI());
            } else {
                return null;
            }
        } finally {
            dataset.end();
        }
    }


    @Override
    public void deleteAnnotation(ResearchObject researchObject, URI annotation) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Resource annotationR = manifestModel.getResource(annotation.normalize().toString());
            manifestModel.removeAll(annotationR, null, null);
            manifestModel.removeAll(null, null, annotationR);
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public int migrateRosr5To6(String datasource)
            throws NamingException, SQLException {
        return 0;
    }


    @Override
    public int changeURI(URI oldUri, URI uri) {
        dataset.begin(ReadWrite.WRITE);
        try {
            int cnt = 0;
            Iterator<String> it = dataset.listNames();
            while (it.hasNext()) {
                Model model = dataset.getNamedModel(it.next());
                Resource old = model.createResource(oldUri.toString());
                Resource newu = model.createResource(uri.toString());
                List<Statement> toDelete = new ArrayList<>();
                List<Statement> statements = model.listStatements(old, null, (RDFNode) null).toList();
                for (Statement s : statements) {
                    toDelete.add(s);
                    model.add(newu, s.getPredicate(), s.getObject());
                    cnt++;
                }
                statements = model.listStatements(null, null, (RDFNode) old).toList();
                for (Statement s : statements) {
                    toDelete.add(s);
                    model.add(s.getSubject(), s.getPredicate(), newu);
                    cnt++;
                }
                model.remove(toDelete);
            }
            dataset.commit();
            return cnt;
        } finally {
            dataset.end();
        }
    }


    @Override
    public boolean isSnapshot(ResearchObject ro) {
        dataset.begin(ReadWrite.READ);
        try {
            return getIndividualNoTransaction(ro).hasRDFType(ROEVO.SnapshotRO);
        } finally {
            dataset.end();
        }
    }


    @Override
    public boolean isArchive(ResearchObject ro) {
        dataset.begin(ReadWrite.READ);
        try {
            return getIndividualNoTransaction(ro).hasRDFType(ROEVO.ArchivedRO);
        } finally {
            dataset.end();
        }
    }


    @Override
    public URI getLiveURIFromSnapshotOrArchive(ResearchObject snaphotOrArchive)
            throws URISyntaxException {
        dataset.begin(ReadWrite.READ);
        try {
            Individual source = getIndividualNoTransaction(snaphotOrArchive);
            if (source.hasRDFType(ROEVO.SnapshotRO)) {
                RDFNode roNode = source.getProperty(ROEVO.isSnapshotOf).getObject();
                return new URI(roNode.toString());
            } else if (source.hasRDFType(ROEVO.ArchivedRO)) {
                RDFNode roNode = source.getProperty(ROEVO.isArchiveOf).getObject();
                return new URI(roNode.toString());
            }
            return null;
        } finally {
            dataset.end();
        }
    }


    @Override
    public URI getPreviousSnaphotOrArchive(ResearchObject liveRO, ResearchObject freshSnapshotOrArchive) {
        return getPreviousSnaphotOrArchive(liveRO, freshSnapshotOrArchive, null);
    }


    @Override
    public URI getPreviousSnaphotOrArchive(ResearchObject liveRO, ResearchObject freshSnapshotOrArchive, EvoType type) {
        dataset.begin(ReadWrite.READ);
        try {
            Individual liveSource = getIndividualNoTransaction(liveRO);
            StmtIterator snaphotsIterator;
            snaphotsIterator = liveSource.listProperties(ROEVO.hasSnapshot);
            StmtIterator archiveItertator;
            archiveItertator = liveSource.listProperties(ROEVO.hasArchive);
            Individual freshSource = getIndividualNoTransaction(freshSnapshotOrArchive);
            RDFNode dateNode;
            DateTime freshTime;

            if (type == null) {
                if (getIndividualNoTransaction(freshSnapshotOrArchive).hasRDFType(ROEVO.SnapshotRO)) {
                    dateNode = freshSource.getProperty(ROEVO.snapshottedAtTime).getObject();
                } else if (getIndividualNoTransaction(freshSnapshotOrArchive).hasRDFType(ROEVO.ArchivedRO)) {
                    dateNode = freshSource.getProperty(ROEVO.archivedAtTime).getObject();
                } else {
                    return null;
                }
                freshTime = new DateTime(dateNode.asLiteral().getValue().toString());
            } else {
                freshTime = DateTime.now();
            }
            DateTime predecessorTime = null;
            URI result = null;

            while (snaphotsIterator.hasNext()) {
                URI tmpURI = URI.create(snaphotsIterator.next().getObject().toString());
                if (tmpURI.equals(freshSnapshotOrArchive.getUri())) {
                    continue;
                }
                RDFNode node = getIndividualNoTransaction(ResearchObject.create(tmpURI)).getProperty(
                    ROEVO.snapshottedAtTime).getObject();
                DateTime tmpTime = new DateTime(node.asLiteral().getValue().toString());
                if ((tmpTime.compareTo(freshTime) == -1)
                        && ((predecessorTime == null) || (tmpTime.compareTo(predecessorTime) == 1))) {
                    predecessorTime = tmpTime;
                    result = tmpURI;
                }
            }
            while (archiveItertator.hasNext()) {
                URI tmpURI = URI.create(archiveItertator.next().getObject().toString());
                RDFNode node = getIndividualNoTransaction(ResearchObject.create(tmpURI)).getProperty(
                    ROEVO.archivedAtTime).getObject();
                DateTime tmpTime = new DateTime(node.asLiteral().getValue().toString());
                if ((tmpTime.compareTo(freshTime) == -1)
                        && ((predecessorTime == null) || (tmpTime.compareTo(predecessorTime) == 1))) {
                    predecessorTime = tmpTime;
                    result = tmpURI;
                }
            }
            return result;
        } finally {
            dataset.end();
        }
    }


    private enum Direction {
        NEW,
        DELETED
    }


    @Override
    public String storeAggregatedDifferences(ResearchObject freshRO, ResearchObject oldRO)
            throws URISyntaxException {
        dataset.begin(ReadWrite.WRITE);
        try {
            if (oldRO == null) {
                return "";
            }
            if (freshRO == null) {
                throw new IllegalArgumentException("Fresh object can not be null");
            }
            if (freshRO == oldRO) {
                throw new IllegalArgumentException("Fresh and old RO can not be this same");
            }
            if (compareTwoResearchObjectDateOfCreation(freshRO, oldRO) <= 0) {
                throw new IllegalArgumentException("Fresh RO can not be older them old RO");
            }

            List<RDFNode> freshAggreagted = getAggregatedWithNoEvoAndBody(freshRO);
            List<RDFNode> oldAggreagted = getAggregatedWithNoEvoAndBody(oldRO);

            OntModel evoInfoModel = createOntModelForNamedGraph(freshRO.getFixedEvolutionAnnotationBodyPath());

            Individual changeSpecificationIndividual = evoInfoModel.createIndividual(
                generateRandomUriRelatedToResource(freshRO, "change_specification"), ROEVO.ChangeSpecification);

            evoInfoModel.getIndividual(freshRO.getUriString()).addProperty(ROEVO.wasChangedBy,
                changeSpecificationIndividual);

            String result = lookForAggregatedDifferents(freshRO, oldRO, freshAggreagted, oldAggreagted, evoInfoModel,
                changeSpecificationIndividual, Direction.NEW);
            result += lookForAggregatedDifferents(freshRO, oldRO, oldAggreagted, freshAggreagted, evoInfoModel,
                changeSpecificationIndividual, Direction.DELETED);
            dataset.commit();
            return result;
        } finally {
            dataset.end();
        }
    }


    /**
     * Compare dates of creation of two given research objects.
     * 
     * @param first
     * @param second
     * @return positive value if first is younger, negative otherwise, 0 if they are equal.
     */
    private int compareTwoResearchObjectDateOfCreation(ResearchObject first, ResearchObject second) {
        DateTime dateFirst = null;
        DateTime dateSecond = null;
        Individual firstIndividual = getIndividualNoTransaction(first);
        Individual secondIndividual = getIndividualNoTransaction(second);

        if (!dataset.containsNamedModel(first.getFixedEvolutionAnnotationBodyPath().toString())) {
            dateFirst = DateTime.now();
        } else {

            if (getIndividualNoTransaction(first).hasRDFType(ROEVO.SnapshotRO)) {
                dateFirst = new DateTime(firstIndividual.getPropertyValue(ROEVO.snapshottedAtTime).asLiteral()
                        .getValue().toString());
            } else if (getIndividualNoTransaction(first).hasRDFType(ROEVO.ArchivedRO)) {
                dateFirst = new DateTime(firstIndividual.getPropertyValue(ROEVO.archivedAtTime).asLiteral().getValue()
                        .toString());
            } else {
                dateFirst = new DateTime(firstIndividual.getPropertyValue(DCTerms.created).asLiteral().getValue()
                        .toString());
            }
        }

        if (!dataset.containsNamedModel(second.getFixedEvolutionAnnotationBodyPath().toString())) {
            dateSecond = DateTime.now();
        } else {

            if (getIndividualNoTransaction(second).hasRDFType(ROEVO.SnapshotRO)) {
                dateSecond = new DateTime(secondIndividual.getPropertyValue(ROEVO.snapshottedAtTime).asLiteral()
                        .getValue().toString());
            } else if (getIndividualNoTransaction(second).hasRDFType(ROEVO.ArchivedRO)) {
                dateSecond = new DateTime(secondIndividual.getPropertyValue(ROEVO.archivedAtTime).asLiteral()
                        .getValue().toString());
            } else {
                dateSecond = new DateTime(secondIndividual.getPropertyValue(DCTerms.created).asLiteral().getValue()
                        .toString());
            }
        }
        return dateFirst.compareTo(dateSecond);
    }


    /**
     * Compare dates of creation of two given research objects.
     * 
     * @param first
     * @param second
     * @return positive value if first is younger, negative otherwise, 0 if they are equal.
     */
    private List<RDFNode> getAggregatedWithNoEvoAndBody(ResearchObject researchObject) {
        Individual roIndividual = getIndividualNoTransaction(researchObject);
        DateTime date = null;
        if (getIndividualNoTransaction(researchObject).hasRDFType(ROEVO.SnapshotRO)) {
            date = new DateTime(roIndividual.getPropertyValue(ROEVO.snapshottedAtTime).asLiteral().getValue()
                    .toString());
        } else if (getIndividualNoTransaction(researchObject).hasRDFType(ROEVO.ArchivedRO)) {
            date = new DateTime(roIndividual.getPropertyValue(ROEVO.archivedAtTime).asLiteral().getValue().toString());
        }

        List<RDFNode> aggreageted = roIndividual.listPropertyValues(ORE.aggregates).toList();
        List<RDFNode> aggreagetedWithNoEvo = new ArrayList<RDFNode>();

        for (RDFNode a : aggreageted) {
            try {
                if (a.isResource()) {
                    DateTime newDate = new DateTime(a.asResource().getProperty(DCTerms.created).getObject().asLiteral()
                            .getValue().toString());
                    if (date.compareTo(newDate) > 0) {
                        aggreagetedWithNoEvo.add(a);
                    }
                }
            } catch (NullPointerException e) {
                continue;
            }
        }
        for (RDFNode a : aggreageted) {
            try {
                if (a.as(Individual.class).hasRDFType(RO.AggregatedAnnotation)) {
                    RDFNode node = a.as(Individual.class).getPropertyValue(AO.body);
                    aggreagetedWithNoEvo.remove(node);
                }
            } catch (Exception e) {
                continue;
            }
        }
        return aggreagetedWithNoEvo;
    }


    /**
     * Get an URI with random id basing on research object URI and certain suffix called description.
     * 
     * @param ro
     *            given Research object
     * @param description
     *            suffix
     * @return an URI with random id basing on research object URI and certain suffix called description.
     */
    private String generateRandomUriRelatedToResource(ResearchObject ro, String description) {
        return ro.getUri().resolve(description) + "/" + UUID.randomUUID().toString();
    }


    //TODO
    //MAKE IT EASIER!
    /**
     * Find and save differences between snapshots or archives.
     * 
     * @param first
     *            current Research Object
     * @param second
     *            compared Research Object
     * @param pattern
     *            current node
     * @param compared
     *            processed node
     * @param freshROModel
     *            OntModel where changes are added
     * @param changeSpecificationIndividual
     *            ChangeSpecification class Individual
     * @param direction
     *            NEW in case fresh snapshot/archive is processed, OLD in case old snaphot/archive is processed
     * @return report in string format (When function is executed, information about evolution are stored in the triple
     *         store)
     */
    private String lookForAggregatedDifferents(ResearchObject first, ResearchObject second, List<RDFNode> pattern,
            List<RDFNode> compared, OntModel freshROModel, Individual changeSpecificationIndividual, Direction direction) {
        String result = "";
        Boolean tmp = null;
        for (RDFNode patternNode : pattern) {
            Boolean loopResult = null;
            for (RDFNode comparedNode : compared) {
                if (direction == Direction.NEW) {
                    tmp = compareProperties(patternNode, comparedNode, first.getUri(), second.getUri());
                } else {
                    tmp = compareProperties(patternNode, comparedNode, second.getUri(), first.getUri());
                }
                if (tmp != null) {
                    loopResult = tmp;
                }
            }
            result += serviceDetectedEVOmodification(loopResult, first, second, patternNode, freshROModel,
                changeSpecificationIndividual, direction);
        }
        return result;
    }


    //TODO
    //MAKE IT EASIER!
    /**
     * Save found differences.
     * 
     * @param loopResult
     *            true/false/null change/no changes/ depends on the direction (NEW - Addition, OLD - Removal )
     * @param first
     *            processed Research Object
     * @param second
     *            compared Research Object
     * @param node
     *            processed node
     * @param freshROModel
     *            OntModel where changes are added
     * @param changeSpecificationIndividual
     *            ChangeSpecification class Individual
     * @param direction
     *            NEW in case fresh snapshot/archive is processed, OLD in case old snaphot/archive is processed
     * @return String with the report about introduced changes
     */
    private String serviceDetectedEVOmodification(Boolean loopResult, ResearchObject first, ResearchObject second,
            RDFNode node, OntModel freshROModel, Individual changeSpecificationIndividual, Direction direction) {
        String result = "";
        //Null means they are not comparable. Resource is new or deleted depends on the direction. 
        if (loopResult == null) {
            if (direction == Direction.NEW) {
                Individual changeIndividual = freshROModel.createIndividual(
                    generateRandomUriRelatedToResource(first, "change"), ROEVO.Change);
                changeIndividual.addRDFType(ROEVO.Addition);
                changeIndividual.addProperty(ROEVO.relatedResource, node);
                changeSpecificationIndividual.addProperty(ROEVO.hasChange, changeIndividual);
                result += first + " " + node.toString() + " " + direction + "\n";
            } else {
                Individual changeIndividual = freshROModel.createIndividual(
                    generateRandomUriRelatedToResource(first, "change"), ROEVO.Change);
                changeIndividual.addRDFType(ROEVO.Removal);
                changeIndividual.addProperty(ROEVO.relatedResource, node);
                changeSpecificationIndividual.addProperty(ROEVO.hasChange, changeIndividual);
                result += first + " " + node.toString() + " " + direction + "\n";
            }
        }
        //False means there are some changes (Changes exists in two directions so they will be stored onlu once)
        else if (!loopResult && direction == Direction.NEW) {
            Individual changeIndividual = freshROModel.createIndividual(
                generateRandomUriRelatedToResource(first, "change"), ROEVO.Change);
            changeIndividual.addRDFType(ROEVO.Modification);
            changeIndividual.addProperty(ROEVO.relatedResource, node);
            changeSpecificationIndividual.addProperty(ROEVO.hasChange, changeIndividual);
            result += first + " " + node.toString() + " MODIFICATION" + "\n";
        }
        //True means there are some unchanges objects
        else if (!loopResult && direction == Direction.DELETED) {
            result += first + " " + node.toString() + " UNCHANGED" + "\n";
        }
        return result;
    }


    /**
     * Compare two RDF properties taking into account URI in relativized form.
     * 
     * @param pattern
     *            pattern node
     * @param compared
     *            compared node
     * @param patternROURI
     *            the URI of pattern Research Object
     * @param comparedROURI
     *            the pattern of Research Object
     * @return true if they are equal,false otherwise
     */
    private Boolean compareProperties(RDFNode pattern, RDFNode compared, URI patternROURI, URI comparedROURI) {
        if (pattern.isResource() && compared.isResource()) {
            return compareTwoResources(pattern.asResource(), compared.asResource(), patternROURI, comparedROURI);
        } else if (pattern.isLiteral() && compared.isLiteral()) {
            return compareTwoLiterals(pattern.asLiteral(), compared.asLiteral());
        }
        return null;
    }


    /**
     * Compare two RDF literals taking into account URI in relativized form.
     * 
     * @param pattern
     *            first literal
     * @param compared
     *            second literal
     * @return true if they are equal, otherwise false.
     */
    private Boolean compareTwoLiterals(Literal pattern, Literal compared) {
        //@TODO compare checksums
        Boolean result = null;
        if (pattern.equals(compared)) {
            //@TODO compare checksums
            return true;
        }
        return result;
    }


    /**
     * Compare two URIs relativized them before.
     * 
     * @param patternURI
     *            first URI
     * @param comparedURI
     *            second URI
     * @param baseForPatternURI
     *            base of first URI
     * @param baseForComparedURI
     *            base of second URI
     * @return true if they are equal, false otherwise
     */
    private Boolean compareRelativesURI(URI patternURI, URI comparedURI, URI baseForPatternURI, URI baseForComparedURI) {
        return baseForPatternURI.relativize(patternURI).toString()
                .equals(baseForComparedURI.relativize(comparedURI).toString());
    }


    private Boolean compareTwoResources(Resource pattern, Resource compared, URI patternROURI, URI comparedROURI) {
        Individual patternSource = pattern.as(Individual.class);
        Individual comparedSource = compared.as(Individual.class);
        if (patternSource.hasRDFType(RO.AggregatedAnnotation) && comparedSource.hasRDFType(RO.AggregatedAnnotation)) {
            try {
                if (compareRelativesURI(new URI(pattern.getURI()), new URI(compared.getURI()), patternROURI,
                    comparedROURI)) {
                    return compareTwoAggreagatedResources(patternSource, comparedSource, patternROURI, comparedROURI)
                            && compareTwoAggregatedAnnotationBody(patternSource, comparedSource, patternROURI,
                                comparedROURI);
                } else {
                    return null;
                }
            } catch (URISyntaxException e) {
                log.debug(e);
                return null;
            }
        } else {
            try {
                if (compareRelativesURI(new URI(pattern.getURI()), new URI(compared.getURI()), patternROURI,
                    comparedROURI)) {
                    //@TODO compare checksums
                    return true;
                } else {
                    return null;
                }
            } catch (URISyntaxException e) {
                log.info(e);
                return null;
            }
        }
    }


    private Boolean compareTwoAggreagatedResources(Individual pattern, Individual compared, URI patternROURI,
            URI comparedROURI) {
        List<RDFNode> patternList = pattern.listPropertyValues(RO.annotatesAggregatedResource).toList();
        List<RDFNode> comparedList = compared.listPropertyValues(RO.annotatesAggregatedResource).toList();
        if (patternList.size() != comparedList.size())
            return false;
        for (RDFNode patternNode : patternList) {
            Boolean result = null;
            for (RDFNode comparedNode : comparedList) {
                if (comparedNode.isResource() && patternNode.isResource()) {
                    try {
                        if (compareRelativesURI(new URI(patternNode.asResource().getURI()), new URI(comparedNode
                                .asResource().getURI()), patternROURI, comparedROURI)) {
                            result = true;
                        }
                    } catch (URISyntaxException e) {
                        log.debug(e);
                    }
                } else if (comparedNode.isLiteral() && patternNode.isLiteral()) {
                    if (compareTwoLiterals(comparedNode.asLiteral(), patternNode.asLiteral())) {
                        result = true;
                    }
                }
            }
            if (result == null) {
                return false;
            }
        }
        for (RDFNode comparedNode : comparedList) {
            Boolean result = null;
            for (RDFNode patternNode : patternList) {
                if (comparedNode.isResource() && patternNode.isResource()) {
                    try {
                        if (compareRelativesURI(new URI(patternNode.asResource().getURI()), new URI(comparedNode
                                .asResource().getURI()), patternROURI, comparedROURI)) {
                            result = true;
                        }
                    } catch (URISyntaxException e) {
                        log.debug(e);
                        return null;
                    }
                } else if (comparedNode.isLiteral() && patternNode.isLiteral()) {
                    if (compareTwoLiterals(comparedNode.asLiteral(), patternNode.asLiteral())) {
                        result = true;
                    }
                }
            }
            if (result == false || result == null) {
                return false;
            }
        }
        return true;
    }


    private Boolean compareTwoAggregatedAnnotationBody(Individual patternSource, Individual comparedSource,
            URI patternROURI, URI comparedROURI) {
        Resource patternBody = patternSource.getProperty(AO.body).getResource();
        Resource comparedBody = comparedSource.getProperty(AO.body).getResource();
        OntModel patternModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_LITE_MEM);
        RDFFormat patternFormat = RDFFormat.forFileName(patternBody.getURI(), RDFFormat.RDFXML);
        try {
            patternModel.read(patternBody.getURI(), patternBody.getURI(), patternFormat.getName().toUpperCase());
        } catch (JenaException e) {
            log.warn("Could not read annotation body " + patternBody.getURI() + ", assuming unchanged. ", e);
            return true;
        }
        OntModel comparedModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_LITE_MEM);
        RDFFormat comparedFormat = RDFFormat.forFileName(comparedBody.getURI(), RDFFormat.RDFXML);
        try {
            comparedModel.read(comparedBody.getURI(), comparedBody.getURI(), comparedFormat.getName().toUpperCase());
        } catch (JenaException e) {
            log.warn("Could not read annotation body " + comparedBody.getURI() + ", assuming unchanged. ", e);
            return true;
        }

        List<Statement> patternList = patternModel.listStatements().toList();
        List<Statement> comparedList = comparedModel.listStatements().toList();

        for (Statement s : patternList) {
            try {
                if (!isStatementInList(s, comparedList, patternROURI, comparedROURI)) {
                    return false;
                }
            } catch (URISyntaxException e) {
                log.debug(e);
                return true;
            }
        }
        for (Statement s : comparedList) {
            try {
                if (!isStatementInList(s, patternList, comparedROURI, patternROURI)) {
                    return false;
                }
            } catch (URISyntaxException e) {
                log.debug(e);
                return true;
            }
        }
        return true;
    }


    /**
     * Check if the given statement is this same like one of the statements in the give list. URIs are relativized by
     * patternURI and comaparedUTI parameters.
     * 
     * @param statement
     *            given statement
     * @param list
     *            given list
     * @param patternURI
     *            the URI of research object where statements comes from
     * @param comparedURI
     *            the URI of research object where list comes from
     * @return true is statement is in the list, false otherwise.
     * @throws URISyntaxException .
     */
    private Boolean isStatementInList(Statement statement, List<Statement> list, URI patternURI, URI comparedURI)
            throws URISyntaxException {
        for (Statement listStatement : list) {
            try {
                if (compareRelativesURI(new URI(statement.getSubject().asResource().getURI()), new URI(listStatement
                        .getSubject().asResource().getURI()), patternURI, comparedURI)) {
                    if (compareRelativesURI(new URI(statement.getPredicate().asResource().getURI()), new URI(
                            listStatement.getPredicate().asResource().getURI()), patternURI, comparedURI)) {
                        if (statement.getObject().isResource() && listStatement.getObject().isResource()) {
                            if (compareRelativesURI(new URI(statement.getObject().asResource().getURI()), new URI(
                                    listStatement.getObject().asResource().getURI()), patternURI, comparedURI)) {
                                return true;
                            }
                        }
                        if (listStatement.getObject().isLiteral() && statement.getObject().isLiteral()) {
                            if (compareTwoLiterals(listStatement.getObject().asLiteral(), statement.getObject()
                                    .asLiteral())) {
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn(e.getMessage());
                continue;
            }
        }
        return false;
    }


    private Individual getIndividualNoTransaction(ResearchObject ro) {
        Model roManifestModel = dataset.getNamedModel(ro.getManifestUri().toString());
        Model roEvolutionModel = dataset.getNamedModel(ro.getFixedEvolutionAnnotationBodyPath().toString());
        return ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, roManifestModel.union(roEvolutionModel))
                .getIndividual(ro.getUriString());
    }


    @Override
    public URI resolveURI(URI base, String second) {
        if ("file".equals(base.getScheme())) {
            URI incorrectURI = base.resolve(second);
            return URI.create(incorrectURI.toString().replaceFirst("file:/", "file:///"));
        } else {
            return base.resolve(second);
        }
    }


    @Override
    public int changeURIInManifestAndAnnotationBodies(ResearchObject researchObject, URI oldURI, URI newURI,
            Boolean withBodies) {
        dataset.begin(ReadWrite.WRITE);
        try {
            int cnt = changeURIInNamedGraph(researchObject.getManifestUri(), oldURI, newURI);
            if (withBodies) {
                OntModel model = createOntModelForNamedGraph(researchObject.getManifestUri());
                List<RDFNode> bodies = model.listObjectsOfProperty(AO.body).toList();
                for (RDFNode body : bodies) {
                    URI bodyURI = URI.create(body.asResource().getURI());
                    cnt += changeURIInNamedGraph(bodyURI, oldURI, newURI);
                }
            }
            dataset.commit();
            return cnt;
        } finally {
            dataset.end();
        }
    }


    private int changeURIInNamedGraph(URI graph, URI oldURI, URI newURI) {
        OntModel model = createOntModelForNamedGraph(graph);
        Resource oldResource = model.createResource(SafeURI.URItoString(oldURI));
        Resource newResource = model.createResource(SafeURI.URItoString(newURI));

        List<Statement> s1 = model.listStatements(oldResource, null, (RDFNode) null).toList();
        for (Statement s : s1) {
            model.remove(s.getSubject(), s.getPredicate(), s.getObject());
            model.add(newResource, s.getPredicate(), s.getObject());
        }
        List<Statement> s2 = model.listStatements(null, null, oldResource).toList();
        for (Statement s : s2) {
            model.remove(s.getSubject(), s.getPredicate(), s.getObject());
            model.add(s.getSubject(), s.getPredicate(), newResource);
        }
        return s1.size() + s2.size();
    }


    @Override
    public InputStream getEvoInfo(ResearchObject researchObject) {
        return getNamedGraph((researchObject.getFixedEvolutionAnnotationBodyPath()), RDFFormat.TURTLE);
    }


    @Override
    public List<AggregatedResource> getAggregatedResources(ResearchObject researchObject)
            throws ManifestTraversingException {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel model = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual source = model.getIndividual(researchObject.getUri().toString());
            if (source == null) {
                throw new ManifestTraversingException();
            }
            List<RDFNode> aggregatesList = source.listPropertyValues(ORE.aggregates).toList();
            List<AggregatedResource> aggregated = new ArrayList<AggregatedResource>();
            for (RDFNode node : aggregatesList) {
                try {
                    if (node.isURIResource()) {
                        if (!isAnnotationNoTransaction(researchObject, new URI(node.asResource().getURI()))) {
                            aggregated.add(new AggregatedResource(new URI(node.asResource().getURI())));
                        }
                    } else if (node.isResource()) {
                        URI nodeUri = changeBlankNodeToUriResources(researchObject, model, node);
                        if (!isAnnotationNoTransaction(researchObject, nodeUri)) {
                            aggregated.add(new AggregatedResource(nodeUri));
                        }
                    }
                } catch (URISyntaxException e) {
                    continue;
                }
            }
            return aggregated;
        } finally {
            dataset.end();
        }
    }


    private URI changeBlankNodeToUriResources(ResearchObject researchObject, OntModel model, RDFNode node)
            throws URISyntaxException {
        Resource r = model.createResource(researchObject.getUri().resolve(UUID.randomUUID().toString()).toString());
        List<Statement> statements = new ArrayList<Statement>();
        for (Statement s : model.listStatements(node.asResource(), null, (RDFNode) null).toList()) {
            Statement s2 = model.createStatement(r, s.getPredicate(), s.getObject());
            statements.add(s);
            model.add(s2);
        }

        for (Statement s : model.listStatements(null, null, node).toList()) {
            Statement s2 = model.createStatement(s.getSubject(), s.getPredicate(), r);
            model.add(s2);
            statements.add(s);
        }
        model.remove(statements);
        return new URI(r.getURI());
    }


    @Override
    public List<Annotation> getAnnotations(ResearchObject researchObject)
            throws ManifestTraversingException {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel model = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual source = model.getIndividual(researchObject.getUri().toString());
            if (source == null) {
                throw new ManifestTraversingException();
            }
            List<RDFNode> aggregatesList = source.listPropertyValues(ORE.aggregates).toList();
            List<Annotation> annotations = new ArrayList<Annotation>();
            for (RDFNode node : aggregatesList) {
                try {
                    if (node.isURIResource()) {
                        if (isAnnotationNoTransaction(researchObject, new URI(node.asResource().getURI()))) {
                            annotations.add(new Annotation(new URI(node.asResource().getURI()), model));
                        }
                    } else if (node.isResource()) {
                        URI nodeUri = changeBlankNodeToUriResources(researchObject, model, node);
                        if (isAnnotationNoTransaction(researchObject, nodeUri)) {
                            annotations.add(new Annotation(nodeUri, model));
                        }
                    }
                } catch (URISyntaxException e) {
                    continue;
                }
            }
            return annotations;
        } finally {
            dataset.end();
        }
    }


    @Override
    public Folder addFolder(ResearchObject researchObject, Folder folder) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
            if (ro == null) {
                throw new IllegalArgumentException("URI not found: " + researchObject.getUri());
            }
            folder.setAggregationUri(researchObject.getUri());
            Individual resource = manifestModel.createIndividual(folder.getUri().toString(), RO.Folder);
            resource.addRDFType(RO.Resource);
            manifestModel.add(ro, ORE.aggregates, resource);
            if (!ro.hasProperty(RO.rootFolder)) {
                manifestModel.add(ro, RO.rootFolder, resource);
            }
            URI proxyURI = generateProxyURI(researchObject);
            Individual proxy = manifestModel.createIndividual(proxyURI.toString(), ORE.Proxy);
            manifestModel.add(proxy, ORE.proxyIn, ro);
            manifestModel.add(proxy, ORE.proxyFor, resource);
            folder.setProxyUri(proxyURI);

            OntModel folderModel = createOntModelForNamedGraph(folder.getResourceMapUri());
            Resource manifestRes = folderModel.createResource(researchObject.getManifestUri().toString());
            Individual roInd = folderModel.createIndividual(researchObject.getUri().toString(), RO.ResearchObject);
            folderModel.add(roInd, ORE.isDescribedBy, manifestRes);

            Resource folderRMRes = folderModel.createResource(folder.getResourceMapUri().toString());
            Individual folderInd = folderModel.createIndividual(folder.getUri().toString(), RO.Folder);
            folderInd.addRDFType(ORE.Aggregation);
            folderModel.add(folderInd, ORE.isAggregatedBy, roInd);
            folderModel.add(folderInd, ORE.isDescribedBy, folderRMRes);

            for (FolderEntry entry : folder.getFolderEntries()) {
                addFolderEntry(folder, folderInd, entry);
            }
            dataset.commit();
            return folder;
        } finally {
            dataset.end();
        }
    }


    @Override
    public void generateEvoInformation(ResearchObject researchObject, ResearchObject liveRO, EvoType type) {
        dataset.begin(ReadWrite.WRITE);
        try {
            switch (type) {
                case LIVE:
                    generateLiveRoEvoInf(researchObject);
                    break;
                case SNAPSHOT:
                    generateSnaphotEvoInf(researchObject, liveRO);
                    break;
                case ARCHIVED:
                    generateArchiveEvoInf(researchObject, liveRO);
                    break;
                default:
                    generateLiveRoEvoInf(researchObject);
            }
        } finally {
            dataset.end();
        }
    }


    /**
     * Generate of evolution information annotation for given live Research Object.
     * 
     * @param researchObject
     *            given Research Object
     */
    private void generateLiveRoEvoInf(ResearchObject researchObject) {
        OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
        Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
        OntModel evoModel = createOntModelForNamedGraph(researchObject.getFixedEvolutionAnnotationBodyPath());
        Individual evoInfo = evoModel.getIndividual(researchObject.getFixedEvolutionAnnotationBodyPath().toString());
        if (evoInfo != null) {
            throw new IllegalArgumentException("URI already exists: "
                    + researchObject.getFixedEvolutionAnnotationBodyPath());
        }
        evoInfo = manifestModel.createIndividual(researchObject.getFixedEvolutionAnnotationBodyPath().toString(),
            ORE.AggregatedResource);

        evoModel.createIndividual(ro.getURI(), ROEVO.LiveRO);
        manifestModel.add(evoInfo, ORE.describes, ro);
        manifestModel.add(evoInfo, DCTerms.created, evoModel.createTypedLiteral(Calendar.getInstance()));

        addAnnotation(researchObject, Arrays.asList(researchObject.getUri()),
            researchObject.getFixedEvolutionAnnotationBodyPath()).toString();
        ro.addProperty(ORE.aggregates,
            manifestModel.getResource(researchObject.getFixedEvolutionAnnotationBodyPath().toString()));
    }


    /**
     * Generate of evolution information annotation for given snapshot of Research Object.
     * 
     * @param researchObject
     *            given snapshot of Research Object
     * @param liveRO
     *            the origin of processed snapshot of Research Object.
     */
    private void generateSnaphotEvoInf(ResearchObject researchObject, ResearchObject liveRO) {

        OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
        Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
        OntModel evoModel = createOntModelForNamedGraph(researchObject.getFixedEvolutionAnnotationBodyPath());
        Individual evoInfo = evoModel.getIndividual(researchObject.getFixedEvolutionAnnotationBodyPath().toString());
        if (evoInfo != null) {
            throw new IllegalArgumentException("URI already exists: "
                    + researchObject.getFixedEvolutionAnnotationBodyPath());
        }
        evoInfo = manifestModel.createIndividual(researchObject.getFixedEvolutionAnnotationBodyPath().toString(),
            ORE.AggregatedResource);

        manifestModel.add(evoInfo, ORE.describes, ro);
        manifestModel.add(evoInfo, DCTerms.created, evoModel.createTypedLiteral(Calendar.getInstance()));

        evoModel.createIndividual(ro.getURI(), ROEVO.SnapshotRO);
        evoModel.add(ro, ROEVO.isSnapshotOf, liveRO.getUri().toString());
        evoModel.add(ro, ROEVO.snapshottedAtTime, evoModel.createTypedLiteral(Calendar.getInstance()));
        evoModel.add(ro, ROEVO.snapshottedBy, evoModel.createResource(user.getUri().toString()));

        addAnnotation(researchObject, Arrays.asList(researchObject.getUri()),
            researchObject.getFixedEvolutionAnnotationBodyPath()).toString();
        ro.addProperty(ORE.aggregates, researchObject.getFixedEvolutionAnnotationBodyPath().toString());
        OntModel liveEvoModel = createOntModelForNamedGraph(liveRO.getFixedEvolutionAnnotationBodyPath());
        liveEvoModel.add(createOntModelForNamedGraph(researchObject.getManifestUri())
                .getResource(liveRO.getUriString()), ROEVO.hasSnapshot, ro);

        try {
            if (getPreviousSnaphotOrArchive(liveRO, researchObject, EvoType.SNAPSHOT) != null) {
                storeAggregatedDifferences(researchObject,
                    ResearchObject.create(getPreviousSnaphotOrArchive(liveRO, researchObject, EvoType.SNAPSHOT)));
            }
        } catch (URISyntaxException e) {
            //any way to informa about the problem?
            e.printStackTrace();
        }
    }


    /**
     * Generate of evolution information annotation for given snapshot of Research Object.
     * 
     * @param researchObject
     *            given snapshot of Research Object
     * @param liveRO
     *            the origin of processed snapshot of Research Object.
     */
    private void generateArchiveEvoInf(ResearchObject researchObject, ResearchObject liveRO) {
        OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
        Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
        OntModel evoModel = createOntModelForNamedGraph(researchObject.getFixedEvolutionAnnotationBodyPath());
        Individual evoInfo = evoModel.getIndividual(researchObject.getFixedEvolutionAnnotationBodyPath().toString());
        if (evoInfo != null) {
            throw new IllegalArgumentException("URI already exists: "
                    + researchObject.getFixedEvolutionAnnotationBodyPath());
        }
        evoInfo = manifestModel.createIndividual(researchObject.getFixedEvolutionAnnotationBodyPath().toString(),
            ORE.AggregatedResource);

        manifestModel.add(evoInfo, ORE.describes, ro);
        manifestModel.add(evoInfo, DCTerms.created, evoModel.createTypedLiteral(Calendar.getInstance()));

        evoModel.createIndividual(ro.getURI(), ROEVO.ArchivedRO);
        evoModel.add(ro, ROEVO.isArchiveOf, liveRO.getUri().toString());
        evoModel.add(ro, ROEVO.archivedAtTime, evoModel.createTypedLiteral(Calendar.getInstance()));
        evoModel.add(ro, ROEVO.archivedBy, evoModel.createResource(user.getUri().toString()));

        addAnnotation(researchObject, Arrays.asList(researchObject.getUri()),
            researchObject.getFixedEvolutionAnnotationBodyPath()).toString();
        ro.addProperty(ORE.aggregates, researchObject.getFixedEvolutionAnnotationBodyPath().toString());
        OntModel liveEvoModel = createOntModelForNamedGraph(liveRO.getFixedEvolutionAnnotationBodyPath());
        liveEvoModel.add(createOntModelForNamedGraph(researchObject.getManifestUri())
                .getResource(liveRO.getUriString()), ROEVO.hasArchive, ro);
        try {
            if (getPreviousSnaphotOrArchive(liveRO, researchObject, EvoType.ARCHIVED) != null) {
                storeAggregatedDifferences(researchObject,
                    ResearchObject.create(getPreviousSnaphotOrArchive(liveRO, researchObject, EvoType.ARCHIVED)));
            }
        } catch (URISyntaxException e) {
            //any way to informa about the problem?
            e.printStackTrace();
        }
    }


    @Override
    public Annotation findAnnotationForBody(ResearchObject researchObject, URI body) {
        dataset.begin(ReadWrite.READ);
        try {
            Model manifestModel = dataset.getNamedModel(researchObject.getManifestUri().toString());
            Resource bodyR = manifestModel.createResource(SafeURI.URItoString(body));
            StmtIterator it = manifestModel.listStatements(null, AO.body, bodyR);
            if (!it.hasNext()) {
                return null;
            }
            return new Annotation(URI.create(it.next().getSubject().getURI()));
        } finally {
            dataset.end();
        }
    }


    @Override
    public boolean addAnnotationBody(ResearchObject researchObject, URI graphURI, InputStream inputStream,
            RDFFormat rdfFormat) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
            if (ro == null) {
                throw new IllegalArgumentException("URI not found: " + researchObject.getUri());
            }
            Resource resource = manifestModel.createResource(graphURI.toString());
            manifestModel.add(ro, ORE.aggregates, resource);
            dataset.commit();
            return addNamedGraph(graphURI, inputStream, rdfFormat);
        } finally {
            dataset.end();
        }
    }


    @Override
    public void removeAnnotationBody(ResearchObject researchObject, URI graphURI) {
        dataset.begin(ReadWrite.WRITE);
        try {
            graphURI = graphURI.normalize();
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
            if (ro == null) {
                throw new IllegalArgumentException("URI not found: " + researchObject.getUri());
            }
            Resource resource = manifestModel.getResource(graphURI.toString());
            if (resource == null) {
                throw new IllegalArgumentException("URI not found: " + graphURI);
            }
            manifestModel.remove(ro, ORE.aggregates, resource);

            URI proxy = getProxyForResource(researchObject, graphURI);
            if (proxy != null) {
                deleteProxy(researchObject, proxy);
            }
            if (dataset.containsNamedModel(graphURI.toString())) {
                dataset.removeNamedModel(graphURI.toString());
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public Folder getRootFolder(ResearchObject researchObject) {
        dataset.begin(ReadWrite.READ);
        try {
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            Individual ro = manifestModel.getIndividual(researchObject.getUri().toString());
            Resource r = ro.getPropertyResourceValue(RO.rootFolder);
            if (r == null) {
                return null;
            } else {
                return getFolderNoTransaction(URI.create(r.getURI()));
            }
        } finally {
            dataset.end();
        }
    }


    @Override
    public Individual getIndividual(ResearchObject ro) {
        dataset.begin(ReadWrite.READ);
        try {
            return getIndividualNoTransaction(ro);
        } finally {
            dataset.end();
        }
    }


    @Override
    public Folder getFolder(URI folderURI) {
        dataset.begin(ReadWrite.READ);
        try {
            return getFolderNoTransaction(folderURI);
        } finally {
            dataset.end();
        }
    }


    private Folder getFolderNoTransaction(URI folderURI) {
        Folder folder = new Folder();
        folder.setUri(folderURI);
        if (!dataset.containsNamedModel(folder.getResourceMapUri().toString())) {
            return null;
        }
        OntModel model = createOntModelForNamedGraph(folder.getResourceMapUri());
        Individual folderI = model.getIndividual(folder.getUri().toString());
        List<RDFNode> aggregations = folderI.listPropertyValues(ORE.isAggregatedBy).toList();
        Individual ro = null;
        for (RDFNode node : aggregations) {
            if (node.isURIResource() && node.as(Individual.class).hasRDFType(RO.ResearchObject)) {
                ro = node.as(Individual.class);
                break;
            }
        }
        // this would work if we turned inference on
        //        ObjectProperty aggregatedByRO = model.createObjectProperty(RO.NAMESPACE.concat("aggregatedByRO"), true);
        //        aggregatedByRO.addSuperProperty(ORE.isAggregatedBy);
        //        aggregatedByRO.addRange(RO.ResearchObject);
        //        Resource ro = folderI.getPropertyResourceValue(aggregatedByRO);
        URI roURI = URI.create(ro.getURI());
        folder.setAggregationUri(roURI);
        URI proxyURI = getProxyForResourceNoTransaction(ResearchObject.create(roURI), folder.getUri());
        folder.setProxyUri(proxyURI);

        List<Individual> entries = model.listIndividuals(RO.FolderEntry).toList();
        for (Individual entryI : entries) {
            String proxyIn = entryI.getPropertyResourceValue(ORE.proxyIn).getURI();
            if (!proxyIn.equals(folder.getUri().toString())) {
                continue;
            }
            FolderEntry entry = new FolderEntry();
            entry.setUri(URI.create(entryI.getURI()));
            entry.setProxyIn(URI.create(proxyIn));
            String proxyFor = entryI.getPropertyResourceValue(ORE.proxyFor).getURI();
            entry.setProxyFor(URI.create(proxyFor));
            String name = entryI.getPropertyValue(RO.entryName).asLiteral().getString();
            entry.setEntryName(name);
            folder.getFolderEntries().add(entry);
        }
        return folder;
    }


    @Override
    public void updateFolder(Folder folder) {
        dataset.begin(ReadWrite.WRITE);
        try {
            OntModel folderModel = createOntModelForNamedGraph(folder.getResourceMapUri());
            Individual folderInd = folderModel.createIndividual(folder.getUri().toString(), RO.Folder);
            List<Individual> entries = folderModel.listIndividuals(RO.FolderEntry).toList();
            for (Individual entry : entries) {
                deleteFolderEntry(entry);
            }

            for (FolderEntry entry : folder.getFolderEntries()) {
                addFolderEntry(folder, folderInd, entry);
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    /**
     * Save a folder entry in the triplestore.
     * 
     * @param folder
     *            folder that contains the entry
     * @param folderInd
     *            folder individual
     * @param entry
     *            entry
     */
    private void addFolderEntry(Folder folder, Individual folderInd, FolderEntry entry) {
        OntModel folderModel = folderInd.getOntModel();
        if (entry.getUri() == null) {
            entry.setUri(folder.getUri().resolve("entries/" + UUID.randomUUID()));
        }
        entry.setProxyIn(folder.getUri());
        Individual entryInd = folderModel.createIndividual(entry.getUri().toString(), RO.FolderEntry);
        entryInd.addRDFType(ORE.Proxy);
        //FIXME we should check if the resource is really aggregated and what classes it has
        Individual resInd = folderModel.createIndividual(entry.getProxyFor().toString(), RO.Resource);
        folderModel.add(folderInd, ORE.aggregates, resInd);
        folderModel.add(resInd, ORE.isAggregatedBy, folderInd);
        folderModel.add(entryInd, ORE.proxyFor, resInd);
        folderModel.add(entryInd, ORE.proxyIn, folderInd);
        Literal name = folderModel.createLiteral(entry.getEntryName());
        folderModel.add(entryInd, RO.entryName, name);

        OntModel entryModel = createOntModelForNamedGraph(entry.getUri());
        entryInd = entryModel.createIndividual(entry.getUri().toString(), RO.FolderEntry);
        entryInd.addProperty(ORE.isAggregatedBy, folderInd);
    }


    /**
     * Delete a folder entry.
     * 
     * @param entry
     *            entry
     */
    private void deleteFolderEntry(Individual entry) {
        Individual proxyFor = entry.getPropertyResourceValue(ORE.proxyFor).as(Individual.class);
        entry.remove();
        proxyFor.remove();
        removeNamedGraphNoTransaction(URI.create(entry.getURI()));
    }


    @Override
    public void deleteFolder(Folder folder) {
        dataset.begin(ReadWrite.WRITE);
        try {
            ResearchObject researchObject = ResearchObject.create(folder.getAggregationUri());
            OntModel manifestModel = createOntModelForNamedGraph(researchObject.getManifestUri());
            manifestModel.getIndividual(folder.getUri().toString()).remove();

            OntModel folderModel = createOntModelForNamedGraph(folder.getResourceMapUri());
            List<Individual> entries = folderModel.listIndividuals(RO.FolderEntry).toList();
            for (Individual entry : entries) {
                deleteFolderEntry(entry);
            }

            removeNamedGraphNoTransaction(folder.getResourceMapUri());
            dataset.commit();
        } finally {
            dataset.end();
        }
    }


    @Override
    public FolderEntry getFolderEntry(URI entryUri) {
        dataset.begin(ReadWrite.WRITE);
        try {
            if (!dataset.containsNamedModel(entryUri.toString())) {
                return null;
            }
            OntModel entryModel = createOntModelForNamedGraph(entryUri);
            Individual entryInd = entryModel.getIndividual(entryUri.toString());
            if (entryInd == null || !entryInd.hasRDFType(RO.FolderEntry)) {
                return null;
            }
            Resource folderR = entryInd.getPropertyResourceValue(ORE.isAggregatedBy);
            Folder folder = getFolderNoTransaction(URI.create(folderR.getURI()));
            for (FolderEntry entry : folder.getFolderEntries()) {
                if (entry.getUri().equals(entryUri)) {
                    return entry;
                }
            }
            return null;
        } finally {
            dataset.end();
        }
    }
}
