/*
 * Copyright (c) 2011 Poznan Supercomputing and Networking Center
 * 10 Noskowskiego Street, Poznan, Wielkopolska 61-704, Poland
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Poznan Supercomputing and Networking Center ("Confidential Information").
 * You shall not disclose such Confidential Information and shall use it only
 * in accordance with the terms of the license agreement you entered into
 * with PSNC.
 */
package pl.psnc.dl.wf4ever.connection;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import pl.psnc.dl.wf4ever.ApplicationProperties;

/**
 * Read in the dLibra connection parameters on startup.
 * 
 * @author nowakm
 * 
 */
public class DlibraConnectionConfigurationListener implements ServletContextListener {

    /** connection properties file name. */
    private static final String CONNECTION_PROPERTIES_FILENAME = "connection.properties.filename";

    /** profile properties file name. */
    private static final String PROFILE_PROPERTIES_FILENAME = "profiles.properties.filename";


    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext servletContext = sce.getServletContext();

        String fileName = servletContext.getInitParameter(CONNECTION_PROPERTIES_FILENAME);
        DigitalLibraryFactory.loadDigitalLibraryConfiguration(fileName);
        String profileName = servletContext.getInitParameter(PROFILE_PROPERTIES_FILENAME);
        DigitalLibraryFactory.loadProfilesConfiguration(profileName);

        ApplicationProperties.load();
    }


    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // nothing to do
    }

}
