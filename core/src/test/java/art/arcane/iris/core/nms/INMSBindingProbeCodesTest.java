package art.arcane.iris.core.nms;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class INMSBindingProbeCodesTest {
    @Test
    public void skipsSyntheticBukkitBindingWhenNmsIsEnabled() {
        List<String> probeCodes = NmsBindingProbeSupport.getBindingProbeCodes("BUKKIT", false, List.of("v26_1_R1"));

        assertEquals(List.of("v26_1_R1"), probeCodes);
    }

    @Test
    public void leavesBukkitFallbackEmptyWhenNmsIsDisabled() {
        List<String> probeCodes = NmsBindingProbeSupport.getBindingProbeCodes("BUKKIT", true, List.of("v26_1_R1"));

        assertTrue(probeCodes.isEmpty());
    }

    @Test
    public void keepsConcreteBindingCodesAsPrimaryProbe() {
        List<String> probeCodes = NmsBindingProbeSupport.getBindingProbeCodes("v26_1_R1", false, List.of("v26_1_R1"));

        assertEquals(List.of("v26_1_R1"), probeCodes);
    }
}
