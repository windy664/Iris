package art.arcane.iris.core.lifecycle;

import org.bukkit.World;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WorldLifecycleSelectionTest {
    @Test
    public void studioSelectsPaperLikeBackendOnPaper() {
        WorldLifecycleService service = new WorldLifecycleService(CapabilitySnapshot.forTesting(ServerFamily.PAPER, false, false, true));
        WorldLifecycleRequest request = new WorldLifecycleRequest("studio", World.Environment.NORMAL, null, null, null, true, false, 1337L, true, false, WorldLifecycleCaller.STUDIO);

        assertEquals("paper_like_runtime", service.selectCreateBackend(request).backendName());
    }

    @Test
    public void studioSelectsPaperLikeBackendOnPurpur() {
        WorldLifecycleService service = new WorldLifecycleService(CapabilitySnapshot.forTesting(ServerFamily.PURPUR, false, false, true));
        WorldLifecycleRequest request = new WorldLifecycleRequest("studio", World.Environment.NORMAL, null, null, null, true, false, 1337L, true, false, WorldLifecycleCaller.STUDIO);

        assertEquals("paper_like_runtime", service.selectCreateBackend(request).backendName());
    }

    @Test
    public void studioSelectsPaperLikeBackendOnCanvas() {
        WorldLifecycleService service = new WorldLifecycleService(CapabilitySnapshot.forTesting(ServerFamily.CANVAS, true, false, true));
        WorldLifecycleRequest request = new WorldLifecycleRequest("studio", World.Environment.NORMAL, null, null, null, true, false, 1337L, true, false, WorldLifecycleCaller.STUDIO);

        assertEquals("paper_like_runtime", service.selectCreateBackend(request).backendName());
    }

    @Test
    public void studioSelectsPaperLikeBackendOnFolia() {
        WorldLifecycleService service = new WorldLifecycleService(CapabilitySnapshot.forTesting(ServerFamily.FOLIA, true, false, true));
        WorldLifecycleRequest request = new WorldLifecycleRequest("studio", World.Environment.NORMAL, null, null, null, true, false, 1337L, true, false, WorldLifecycleCaller.STUDIO);

        assertEquals("paper_like_runtime", service.selectCreateBackend(request).backendName());
    }

    @Test
    public void studioSelectsBukkitBackendOnSpigot() {
        WorldLifecycleService service = new WorldLifecycleService(CapabilitySnapshot.forTesting(ServerFamily.SPIGOT, false, false, false));
        WorldLifecycleRequest request = new WorldLifecycleRequest("studio", World.Environment.NORMAL, null, null, null, true, false, 1337L, true, false, WorldLifecycleCaller.STUDIO);

        assertEquals("bukkit_public", service.selectCreateBackend(request).backendName());
    }

    @Test
    public void persistentCreatePrefersBukkitBackendOnPaperLikeServers() {
        WorldLifecycleService service = new WorldLifecycleService(CapabilitySnapshot.forTesting(ServerFamily.PURPUR, false, false, true));
        WorldLifecycleRequest request = new WorldLifecycleRequest("persistent", World.Environment.NORMAL, null, null, null, true, false, 1337L, false, false, WorldLifecycleCaller.CREATE);

        assertEquals("bukkit_public", service.selectCreateBackend(request).backendName());
    }

    @Test
    public void unloadUsesRememberedBackendFamily() {
        WorldLifecycleService service = new WorldLifecycleService(CapabilitySnapshot.forTesting(ServerFamily.PURPUR, false, false, true));

        service.rememberBackend("studio", "paper_like_runtime");
        assertEquals("paper_like_runtime", service.selectUnloadBackend("studio").backendName());
    }
}
