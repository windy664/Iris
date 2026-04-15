package art.arcane.iris.core.nms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class NmsBindingProbeSupport {
    private NmsBindingProbeSupport() {
    }

    static List<String> getBindingProbeCodes(String code, boolean disableNms, Collection<String> fallbackCodes) {
        List<String> probeCodes = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return probeCodes;
        }

        if (!"BUKKIT".equals(code)) {
            probeCodes.add(code);
            return probeCodes;
        }

        if (disableNms || fallbackCodes == null) {
            return probeCodes;
        }

        probeCodes.addAll(fallbackCodes);
        return probeCodes;
    }
}
