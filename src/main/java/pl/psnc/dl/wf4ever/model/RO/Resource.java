package pl.psnc.dl.wf4ever.model.RO;

import java.net.URI;

import org.joda.time.DateTime;

import pl.psnc.dl.wf4ever.dl.ResourceMetadata;
import pl.psnc.dl.wf4ever.model.ORE.AggregatedResource;

/**
 * ro:Resource.
 * 
 * @author piotrekhol
 * @author pejot
 */
public class Resource extends AggregatedResource {

    /** physical representation metadata. */
    private ResourceMetadata stats;


    /**
     * Constructor.
     * 
     * @param researchObject
     *            The RO it is aggregated by
     * @param uri
     *            resource URI
     * @param proxyUri
     *            URI of the proxy
     * @param creator
     *            author of the resource
     * @param created
     *            creation date
     */
    public Resource(ResearchObject researchObject, URI uri, URI proxyUri, URI creator, DateTime created) {
        super(researchObject, uri, proxyUri, creator, created);
    }


    /**
     * Constructor.
     * 
     * @param researchObject
     *            The RO it is aggregated by
     * @param uri
     *            resource URI
     * @param proxyURI
     *            URI of the proxy
     * @param creator
     *            author of the resource
     * @param created
     *            creation date
     * @param stats
     *            physical statistics (size, checksum, etc)
     */
    public Resource(ResearchObject researchObject, URI uri, URI proxyURI, URI creator, DateTime created,
            ResourceMetadata stats) {
        this(researchObject, uri, proxyURI, creator, created);
        this.stats = stats;
    }


    public ResourceMetadata getStats() {
        return stats;
    }


    public void setStats(ResourceMetadata stats) {
        this.stats = stats;
    }

}
