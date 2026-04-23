package art.arcane.iris.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class ServerConfiguratorDatapackFingerprintTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Method fingerprintMethod() throws Exception {
        try {
            return ServerConfigurator.class.getMethod("computePackFingerprint", File.class);
        } catch (NoSuchMethodException e) {
            fail("ServerConfigurator.computePackFingerprint(File) does not exist yet — implement it in Task 2");
            throw e;
        }
    }

    @Test
    public void computePackFingerprintReturnsSameHashForUnchangedFiles() throws Exception {
        Method method = fingerprintMethod();
        File packsDir = tmp.newFolder("packs");
        File dimFile = new File(packsDir, "testpack/dimensions/overworld.json");
        dimFile.getParentFile().mkdirs();
        dimFile.createNewFile();

        String fp1 = (String) method.invoke(null, packsDir);
        String fp2 = (String) method.invoke(null, packsDir);

        assertNotNull("Fingerprint must not be null", fp1);
        assertEquals("Same unchanged files must produce identical fingerprint", fp1, fp2);
    }

    @Test
    public void computePackFingerprintChangesWhenFileIsModified() throws Exception {
        Method method = fingerprintMethod();
        File packsDir = tmp.newFolder("packs");
        File dimFile = new File(packsDir, "testpack/dimensions/overworld.json");
        dimFile.getParentFile().mkdirs();
        dimFile.createNewFile();

        String fp1 = (String) method.invoke(null, packsDir);
        dimFile.setLastModified(dimFile.lastModified() + 2000L);
        String fp2 = (String) method.invoke(null, packsDir);

        assertNotEquals("A modified file must produce a different fingerprint", fp1, fp2);
    }

    @Test
    public void computePackFingerprintChangesWhenFileIsAdded() throws Exception {
        Method method = fingerprintMethod();
        File packsDir = tmp.newFolder("packs");
        File dimDir = new File(packsDir, "testpack/dimensions");
        dimDir.mkdirs();
        File dimFile = new File(dimDir, "overworld.json");
        dimFile.createNewFile();

        String fp1 = (String) method.invoke(null, packsDir);
        File extraFile = new File(dimDir, "nether.json");
        extraFile.createNewFile();
        String fp2 = (String) method.invoke(null, packsDir);

        assertNotEquals("Adding a file must produce a different fingerprint", fp1, fp2);
    }
}
