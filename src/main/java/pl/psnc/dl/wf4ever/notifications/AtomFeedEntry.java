package pl.psnc.dl.wf4ever.notifications;

import java.io.Serializable;
import java.net.URI;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.joda.time.DateTime;

import pl.psnc.dl.wf4ever.model.RDF.Thing;
import pl.psnc.dl.wf4ever.model.RO.ResearchObject;

/**
 * Represents a simple entry of the feed, contains a simple report of the event like update, damage and so on.
 * 
 * @author pejot
 */
@Entity
@Table(name = "atom_feed_entries")
public class AtomFeedEntry implements Serializable {

    /** Serialization. */
    private static final long serialVersionUID = 1L;
    /** Id. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Timestamp. */
    @Column(nullable = false)
    private Date created;

    /** Title. */
    private String title;

    /** URI of the service that created this notification. */
    private String source;

    /** Entry human-friendly content. */
    private String summary;

    /** Related object (for example Research Object uri). */
    @Column(nullable = false)
    private String subject;


    /**
     * Create an entry based on the builder. Used builder only.
     * 
     * @param builder
     *            the builder that has the fields to set
     */
    private AtomFeedEntry(Builder builder) {
        this.created = builder.created;
        this.title = builder.title;
        this.source = builder.source;
        this.subject = builder.subject;
        this.summary = builder.summary;
        this.title = builder.title;
    }


    /**
     * Default constructor.
     */
    public AtomFeedEntry() {
    }


    public Integer getId() {
        return id;
    }


    public void setId(Integer id) {
        this.id = id;
    }


    public String getSource() {
        return source;
    }


    public void setSource(String source) {
        this.source = source;
    }


    public String getTitle() {
        return title;
    }


    public void setTitle(String title) {
        this.title = title;
    }


    public String getSummary() {
        return summary;
    }


    public void setSummary(String summary) {
        this.summary = summary;
    }


    public Date getCreated() {
        return created;
    }


    public void setCreated(Date created) {
        this.created = created;
    }


    public String getSubject() {
        return subject;
    }


    public void setSubject(String subject) {
        this.subject = subject;
    }


    /**
     * A builder class that allows to create a {@link AtomFeedEntry} using the builder design pattern.
     * 
     * @author piotrekhol
     * 
     */
    public static class Builder {

        /** Timestamp. */
        private Date created = DateTime.now().toDate();

        /** Title. */
        private String title;

        /** URI of the service that created this notification. */
        //FIXME use RODL URI
        private String source = ".";

        /** Entry human-friendly content. */
        private String summary;

        /** Related object (for example Research Object URI). */
        private String subject;


        /**
         * Constructor, all required fields are parameters.
         * 
         * @param subject
         *            Related object URI
         */
        public Builder(URI subject) {
            this.subject = subject.toString();
        }


        /**
         * Constructor, all required fields are parameters.
         * 
         * @param subject
         *            Related object (for example Research Object)
         */
        public Builder(Thing subject) {
            this(subject.getUri());
        }


        /**
         * Finish the build process and return an {@link AtomFeedEntry}.
         * 
         * @return an atom feed entry with fields provided using this builder
         */
        public AtomFeedEntry build() {
            return new AtomFeedEntry(this);
        }


        /**
         * Creation timestamp.
         * 
         * @param created
         *            timestamp
         * @return this builder
         */
        public Builder created(Date created) {
            this.created = created;
            return this;
        }


        /**
         * Creation timestamp.
         * 
         * @param created
         *            timestamp
         * @return this builder
         */
        public Builder created(DateTime created) {
            this.created = created.toDate();
            return this;
        }


        /**
         * Entry title.
         * 
         * @param title
         *            entry title
         * @return this builder
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }


        /**
         * Entry title.
         * 
         * @param title
         *            entry title
         * @return this builder
         */
        public Builder title(Title title) {
            this.title = title.getValue();
            return this;
        }


        /**
         * URI of the service that created this notification.
         * 
         * @param source
         *            URI of the service that created this notification
         * @return this builder
         */
        public Builder source(URI source) {
            this.source = source.toString();
            return this;
        }


        /**
         * Entry human-friendly content.
         * 
         * @param summary
         *            Entry human-friendly content
         * @return this builder
         */
        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

    }


    /**
     * Commonly used entry titles.
     * 
     * @author piotrekhol
     * 
     */
    public enum Title {

        /** Research Object has been created. */
        RESEARCH_OBJECT_CREATED("Research Object has been created"),
        /** Research Object has been deleted. */
        RESEARCH_OBJECT_DELETED("Research Object has been deleted");

        /** The title itself. */
        private final String value;


        /**
         * Internal constructor.
         * 
         * @param value
         *            The title itself
         */
        private Title(String value) {
            this.value = value;
        }


        public String getValue() {
            return value;
        }
    }


    /**
     * Commonly used entry content.
     * 
     * @author piotrekhol
     * 
     */
    public static class Summary {

        /**
         * An RO has been created.
         * 
         * @param researchObject
         *            the new RO
         * @return a message in HTML
         */
        public static String created(ResearchObject researchObject) {
            return String
                    .format(
                        "<p>A new Research Object has been created.</p><p>The Research Object URI is <a href=\"%s\">%<s</a>.</p>",
                        researchObject.toString());
        }


        /**
         * An RO has been deleted.
         * 
         * @param researchObject
         *            the deleted RO
         * @return a message in HTML
         */
        public static String deleted(ResearchObject researchObject) {
            return String.format(
                "<p>A Research Object has been deleted.</p><p>The Research Object URI was <em>%s</em>.</p>",
                researchObject.toString());
        }
    }
}
