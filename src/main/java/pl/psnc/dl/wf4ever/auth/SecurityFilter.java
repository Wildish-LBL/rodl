package pl.psnc.dl.wf4ever.auth;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.sql.SQLException;

import javax.naming.NamingException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

import org.apache.log4j.Logger;

import pl.psnc.dl.wf4ever.Constants;
import pl.psnc.dl.wf4ever.connection.DigitalLibraryFactory;
import pl.psnc.dl.wf4ever.connection.SemanticMetadataServiceFactory;
import pl.psnc.dl.wf4ever.dlibra.DigitalLibraryException;
import pl.psnc.dl.wf4ever.dlibra.NotFoundException;
import pl.psnc.dl.wf4ever.dlibra.UserProfile;
import pl.psnc.dl.wf4ever.rosrs.ROSRService;
import pl.psnc.dlibra.service.AccessDeniedException;
import pl.psnc.dlibra.service.DLibraException;

import com.sun.jersey.api.container.MappableContainerException;
import com.sun.jersey.core.util.Base64;
import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

public class SecurityFilter implements ContainerRequestFilter {

    private final static Logger logger = Logger.getLogger(SecurityFilter.class);

    public static final String REALM = "ROSRS";

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpServletRequest httpRequest;


    @Override
    public ContainerRequest filter(ContainerRequest request) {
        try {
            UserCredentials creds = authenticate(request);
            UserProfile user = DigitalLibraryFactory.getDigitalLibrary(creds).getUserProfile();
            //TODO in here should go access rights control, based on dLibra for example
            if (!request.getMethod().equals("GET") && user.getRole() == UserProfile.Role.PUBLIC) {
                throw new AuthenticationException("Only authenticated users can do that.", SecurityFilter.REALM);
            }

            httpRequest.setAttribute(Constants.USER, user);
            ROSRService.DL.set(DigitalLibraryFactory.getDigitalLibrary(user.getLogin(), user.getPassword()));
            ROSRService.SMS.set(SemanticMetadataServiceFactory.getService(user));
        } catch (AccessDeniedException | DigitalLibraryException e) {
            throw new MappableContainerException(new AuthenticationException("Incorrect login/password\r\n", REALM));
        } catch (NotFoundException | DLibraException | SQLException | NamingException | IOException
                | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        return request;
    }


    private UserCredentials authenticate(ContainerRequest request)
            throws MalformedURLException, RemoteException, AccessDeniedException, UnknownHostException, DLibraException {
        //TODO allow only secure https connections
        //		logger.info("Connection secure? " + isSecure());
        logger.info("Request to: " + uriInfo.getAbsolutePath() + " | method:  " + request.getMethod());

        // Extract authentication credentials
        String authentication = request.getHeaderValue(ContainerRequest.AUTHORIZATION);
        if (authentication == null) {
            return UserCredentials.PUBLIC_USER;
        }
        try {
            if (authentication.startsWith("Basic ")) {
                return getBasicCredentials(authentication.substring("Basic ".length()));
            }
            // this is the recommended OAuth 2.0 method
            else if (authentication.startsWith("Bearer ")) {
                return getBearerCredentials(authentication.substring("Bearer ".length()));
            } else {
                throw new MappableContainerException(new AuthenticationException(
                        "Only HTTP Basic and OAuth 2.0 Bearer authentications are supported\r\n", REALM));
            }
        } catch (IllegalArgumentException e) {
            throw new MappableContainerException(new AuthenticationException(e.getMessage(), REALM));
        }
    }


    public UserCredentials getBearerCredentials(String accessToken) {
        OAuthManager manager = new OAuthManager();
        AccessToken token = manager.getAccessToken(accessToken);
        if (token == null) {
            return getBasicCredentials(accessToken);
        }
        return token.getUser();
    }


    /**
     * @param authentication
     * @return
     */
    public UserCredentials getBasicCredentials(String authentication) {
        String[] values = new String(Base64.base64Decode(authentication)).split(":");
        if (values.length != 2)
            throw new MappableContainerException(new AuthenticationException("Incorrect login/password\r\n", REALM));
        return new UserCredentials(values[0], values[1]);
    }


    public boolean isSecure() {
        return "https".equals(uriInfo.getRequestUri().getScheme());
    }

}
