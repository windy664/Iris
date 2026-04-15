package art.arcane.iris.core.runtime;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DatapackReadinessResultTest {
    @Test
    public void verificationUsesDimensionTypeKeyPath() throws Exception {
        Path root = Files.createTempDirectory("iris-datapack-readiness");
        Path datapackRoot = root.resolve("iris");
        Files.createDirectories(datapackRoot.resolve("data/iris/dimension_type"));
        Files.writeString(datapackRoot.resolve("pack.mcmeta"), "{}");
        Files.writeString(datapackRoot.resolve("data/iris/dimension_type/runtime-key.json"), "{}");

        ArrayList<String> verifiedPaths = new ArrayList<>();
        ArrayList<String> missingPaths = new ArrayList<>();
        DatapackReadinessResult.collectVerificationPaths(root.toFile(), "runtime-key", verifiedPaths, missingPaths);

        assertTrue(missingPaths.isEmpty());
        assertEquals(2, verifiedPaths.size());
    }

    @Test
    public void verificationMarksMissingDimensionTypePath() throws Exception {
        Path root = Files.createTempDirectory("iris-datapack-readiness-missing");
        Path datapackRoot = root.resolve("iris");
        Files.createDirectories(datapackRoot);
        Files.writeString(datapackRoot.resolve("pack.mcmeta"), "{}");

        ArrayList<String> verifiedPaths = new ArrayList<>();
        ArrayList<String> missingPaths = new ArrayList<>();
        DatapackReadinessResult.collectVerificationPaths(root.toFile(), "runtime-key", verifiedPaths, missingPaths);

        assertEquals(1, verifiedPaths.size());
        assertEquals(1, missingPaths.size());
        assertTrue(missingPaths.get(0).endsWith(File.separator + "iris" + File.separator + "data" + File.separator + "iris" + File.separator + "dimension_type" + File.separator + "runtime-key.json"));
    }
}
