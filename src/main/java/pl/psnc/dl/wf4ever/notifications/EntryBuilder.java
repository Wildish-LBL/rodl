package pl.psnc.dl.wf4ever.notifications;

import java.net.URI;

import pl.psnc.dl.wf4ever.db.AtomFeedEntry;
import pl.psnc.dl.wf4ever.db.dao.AtomFeedEntryDAO;
import pl.psnc.dl.wf4ever.model.RO.ResearchObject;

/**
 * Builder for AtomFeedEntry.
 * 
 * @author pejot
 * 
 */
public class EntryBuilder {

    /**
     * Hidden constructor.
     */
    protected EntryBuilder() {
        //nope
    }


    /**
     * Create and save a new AtomFeedEntry.
     * 
     * @param researchObject
     *            Research Object.
     * @param action
     *            the resean of the entry creation.
     * @return created entry.
     */
    public static AtomFeedEntry create(ResearchObject researchObject, ActionType action) {
        AtomFeedEntryDAO dao = new AtomFeedEntryDAO();
        AtomFeedEntry entry = new AtomFeedEntry();
        entry.setSource(URI.create("."));
        entry.setSubject(researchObject.getUri());
        switch (action) {
            case NEW_RO:
                entry.setTitle("Research Object has been created");
                entry.setSummary("<p>A new Research Object has been created.</p><p>The Research Object URI is <a href=\""
                        + researchObject.getUri() + "\">" + researchObject.getUri() + "</a>.</p>");
                break;
            case DELETED_RO:
                entry.setTitle("Research Object has been deleted");
                entry.setSummary("<p>A Research Object has been deleted.</p><p>The Research Object URI was <em>"
                        + researchObject.getUri() + "</em>.</p>");
                break;
            default:
                return null;
        }
        dao.save(entry);
        return entry;
    }
}
