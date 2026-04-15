package art.arcane.iris.core.nms;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class INMSBindingProbeCodesTest {
    @Test
    public void skipsSyntheticBukkitBindingWhenNmsIsEnabled() {
        List<String> probeCodes = NmsBindingProbeSupport.getBindingProbeCodes("BUKKIT", false, List.of("v1_21_R7"));

        assertEquals(List.of("v1_21_R7"), probeCodes);
    }

    @Test
    public void leavesBukkitFallbackEmptyWhenNmsIsDisabled() {
        List<String> probeCodes = NmsBindingProbeSupport.getBindingProbeCodes("BUKKIT", true, List.of("v1_21_R7"));

        assertTrue(probeCodes.isEmpty());
    }

    @Test
    public void keepsConcreteBindingCodesAsPrimaryProbe() {
        List<String> probeCodes = NmsBindingProbeSupport.getBindingProbeCodes("v1_21_R7", false, List.of("v1_21_R7"));

        assertEquals(List.of("v1_21_R7"), probeCodes);
    }
}
