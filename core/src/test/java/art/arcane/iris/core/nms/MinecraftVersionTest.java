package art.arcane.iris.core.nms;

import org.bukkit.Server;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class MinecraftVersionTest {
    private interface PaperLikeServer extends Server {
        String getMinecraftVersion();
    }

    @Test
    public void detectsMinecraftVersionFromPurpurDecoratedVersion() {
        Server server = mock(Server.class);
        doReturn("git-Purpur-2570 (MC: 1.21.11)").when(server).getVersion();
        doReturn("26.1.2.build.2570-experimental").when(server).getBukkitVersion();

        MinecraftVersion version = MinecraftVersion.detect(server);
        assertEquals("1.21.11", version.value());
        assertEquals(21, version.major());
        assertEquals(11, version.minor());
    }

    @Test
    public void prefersRuntimeMinecraftVersionMethodWhenPresent() {
        PaperLikeServer server = mock(PaperLikeServer.class);
        doReturn("1.21.11").when(server).getMinecraftVersion();
        doReturn("26.1.2-2570-e64b1b2 (MC: 26.1.2)").when(server).getVersion();
        doReturn("26.1.2.build.2570-experimental").when(server).getBukkitVersion();

        MinecraftVersion version = MinecraftVersion.detect(server);
        assertEquals("1.21.11", version.value());
        assertEquals(21, version.major());
        assertEquals(11, version.minor());
    }

    @Test
    public void rejectsPurpurApiBuildNumbersAsMinecraftVersion() {
        MinecraftVersion version = MinecraftVersion.fromBukkitVersion("26.1.2.build.2570-experimental");
        assertNull(version);
    }

    @Test
    public void parsesStandardBukkitSnapshotVersion() {
        MinecraftVersion version = MinecraftVersion.fromBukkitVersion("1.21.11-R0.1-SNAPSHOT");
        assertEquals("1.21.11", version.value());
        assertEquals(21, version.major());
        assertEquals(11, version.minor());
    }

    @Test
    public void comparesMajorBeforeMinor() {
        MinecraftVersion version = MinecraftVersion.fromBukkitVersion("1.20.12-R0.1-SNAPSHOT");
        assertFalse(version.isAtLeast(21, 11));
        assertTrue(version.isNewerThan(20, 11));
    }
}
