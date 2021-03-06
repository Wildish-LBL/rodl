/**
 * 
 */
package pl.psnc.dl.wf4ever.db;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

import javax.persistence.Basic;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * OAuth access token DAO.
 * 
 * @author Piotr Hołubowicz
 * 
 */
@Entity
@Table(name = "tokens")
@XmlRootElement(name = "access-token")
public class AccessToken implements Serializable {

    /** id. */
    private static final long serialVersionUID = 8724845005623981779L;

    /** token. */
    private String token;

    /** client application. */
    private OAuthClient client;

    /** token owner. */
    private UserProfile user;

    /** token creation date. */
    private Date created = new Date();

    /** token last usage date. */
    private Date lastUsed;


    /**
     * Constructor.
     */
    public AccessToken() {

    }


    /**
     * Constructor.
     * 
     * @param token
     *            token
     * @param client
     *            client application
     * @param user
     *            token owner
     */
    public AccessToken(String token, OAuthClient client, UserProfile user) {
        super();
        this.token = token;
        this.client = client;
        this.user = user;
    }


    /**
     * Constructor. The token value will be a random UUID.
     * 
     * @param client
     *            client application
     * @param user
     *            token owner
     */
    public AccessToken(OAuthClient client, UserProfile user) {
        super();
        this.token = UUID.randomUUID().toString();
        this.client = client;
        this.user = user;
    }


    @Id
    @Column(length = 64)
    @XmlElement
    public String getToken() {
        return token;
    }


    public void setToken(String token) {
        this.token = token;
    }


    @ManyToOne
    @JoinColumn(nullable = false)
    @XmlElement
    public OAuthClient getClient() {
        return client;
    }


    public void setClient(OAuthClient client) {
        this.client = client;
    }


    @ManyToOne
    @JoinColumn(name = "userId", referencedColumnName = "login", nullable = false)
    @XmlElement
    public UserProfile getUser() {
        return user;
    }


    public void setUser(UserProfile user) {
        this.user = user;
    }


    @Basic
    @XmlElement
    public Date getCreated() {
        return created;
    }


    public void setCreated(Date created) {
        this.created = created;
    }


    @Basic
    @XmlElement
    public Date getLastUsed() {
        return lastUsed;
    }


    public void setLastUsed(Date lastUsed) {
        this.lastUsed = lastUsed;
    }

}
