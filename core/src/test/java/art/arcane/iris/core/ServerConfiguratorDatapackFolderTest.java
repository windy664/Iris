package art.arcane.iris.core;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServerConfiguratorDatapackFolderTest {
    @Test
    public void resolvesDimensionWorldFolderBackToRootDatapacks() {
        File folder = new File("/tmp/server/world/dimensions/minecraft/overworld");
        File datapacks = ServerConfigurator.resolveDatapacksFolder(folder);
        assertEquals(new File("/tmp/server/world/datapacks").getAbsolutePath(), datapacks.getAbsolutePath());
    }

    @Test
    public void keepsStandaloneWorldFolderDatapacksUnchanged() {
        File folder = new File("/tmp/server/custom_world");
        File datapacks = ServerConfigurator.resolveDatapacksFolder(folder);
        assertEquals(new File("/tmp/server/custom_world/datapacks").getAbsolutePath(), datapacks.getAbsolutePath());
    }

    @Test
    public void installFoldersIncludeExtraStudioWorldDatapackTargets() {
        File baseFolder = new File("/tmp/server/world/datapacks");
        File extraFolder = new File("/tmp/server/iris-studio/datapacks");
        KList<File> baseFolders = new KList<>();
        baseFolders.add(baseFolder);
        KList<File> extraFolders = new KList<>();
        extraFolders.add(extraFolder);
        KMap<String, KList<File>> extrasByPack = new KMap<>();
        extrasByPack.put("overworld", extraFolders);

        KList<File> folders = ServerConfigurator.collectInstallDatapackFolders(baseFolders, extrasByPack);

        assertEquals(2, folders.size());
        assertTrue(folders.contains(baseFolder));
        assertTrue(folders.contains(extraFolder));
    }
}
