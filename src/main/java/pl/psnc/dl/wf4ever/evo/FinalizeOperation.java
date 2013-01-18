package pl.psnc.dl.wf4ever.evo;

import java.net.URI;

import pl.psnc.dl.wf4ever.common.db.EvoType;
import pl.psnc.dl.wf4ever.dl.AccessDeniedException;
import pl.psnc.dl.wf4ever.dl.DigitalLibraryException;
import pl.psnc.dl.wf4ever.dl.NotFoundException;
import pl.psnc.dl.wf4ever.hibernate.HibernateUtil;
import pl.psnc.dl.wf4ever.model.Builder;
import pl.psnc.dl.wf4ever.model.AO.Annotation;
import pl.psnc.dl.wf4ever.model.RO.ResearchObject;
import pl.psnc.dl.wf4ever.model.ROEVO.ArchiveResearchObject;
import pl.psnc.dl.wf4ever.model.ROEVO.SnapshotResearchObject;

/**
 * Finalize research object status transformation.
 * 
 * @author piotrekhol
 * 
 */
public class FinalizeOperation implements Operation {

    /** resource builder. */
    private Builder builder;


    /**
     * Constructor.
     * 
     * @param builder
     *            user calling this operation
     */
    public FinalizeOperation(Builder builder) {
        this.builder = builder;
    }


    @Override
    public void execute(JobStatus status)
            throws OperationFailedException {
        HibernateUtil.getSessionFactory().getCurrentSession().beginTransaction();
        try {
            if (status.getTarget() == null) {
                throw new OperationFailedException("Target research object must be set");
            }
            if (status.getType() == null || status.getType() == EvoType.LIVE) {
                throw new OperationFailedException("New type must be a snaphot or archive");
            }
            URI target = status.getCopyfrom().resolve("../" + status.getTarget() + "/");
            ResearchObject researchObject = null;
            ResearchObject liveRO = ResearchObject.get(builder, status.getCopyfrom());
            if (liveRO == null) {
                throw new NotFoundException("Research Object not found " + status.getCopyfrom().toString());
            }
            switch (status.getType()) {
                case SNAPSHOT:
                    researchObject = SnapshotResearchObject.get(builder, target, liveRO);
                    break;
                case ARCHIVE:
                    researchObject = ArchiveResearchObject.get(builder, target, liveRO);
                    break;
                default:
                    throw new OperationFailedException("New type must be a snaphot or archive");
            }
            if (researchObject == null) {
                throw new NotFoundException("Research Object not found " + status.getTarget());
            }
            Annotation annotation = researchObject.getAnnotationsByBodyUri()
                    .get(researchObject.getFixedEvolutionAnnotationBodyUri()).iterator().next();
            //FIXME this should always be included
            if (researchObject.getAggregatedResources().containsKey(annotation.getBody().getUri())) {
                researchObject.getAggregatedResources().get(annotation.getBody().getUri()).delete();
            }
            annotation.delete();
            researchObject.generateEvoInfo();
        } catch (DigitalLibraryException | NotFoundException | AccessDeniedException e) {
            throw new OperationFailedException("Could not generate evo info", e);
        } finally {
            HibernateUtil.getSessionFactory().getCurrentSession().getTransaction().commit();
        }
    }
}
