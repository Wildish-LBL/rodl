package pl.psnc.dl.wf4ever.db.dao;

import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import pl.psnc.dl.wf4ever.AbstractUnitTest;
import pl.psnc.dl.wf4ever.db.ResourceInfo;

public class ResourceInfoDAOTest extends AbstractUnitTest {

    String path = "path";
    String name = "name";
    String checksum = "checksum";
    long sizeInBytes = 100;
    String digestMethod = "method";
    DateTime lastModified = DateTime.now();
    String mimeType = "text/plain";
    ResourceInfoDAO dao;


    @Override
    @Before
    public void setUp()
            throws Exception {
        super.setUp();
        dao = new ResourceInfoDAO();
    }


    @Test
    public void testConstructor() {
        ResourceInfoDAO daoT = new ResourceInfoDAO();
        Assert.assertNotNull(daoT);
    }


    @Test
    public void testCreate() {
        ResourceInfo info = dao.create(path, name, checksum, sizeInBytes, digestMethod, lastModified, mimeType);
        dao.save(info);
        Assert.assertEquals(info, dao.findByPath(path));
        dao.delete(info);
    }
}
