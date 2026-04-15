package art.arcane.iris.core.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisEngineSVCTest {
    @Test
    public void maintenanceSkipsReductionWhenPregenDoesNotTargetWorld() {
        assertTrue(IrisEngineSVC.shouldSkipMantleReductionForMaintenance(true, false));
    }

    @Test
    public void maintenanceDoesNotSkipReductionForActivePregenWorld() {
        assertFalse(IrisEngineSVC.shouldSkipMantleReductionForMaintenance(true, true));
    }

    @Test
    public void noMaintenanceNeverSkipsReduction() {
        assertFalse(IrisEngineSVC.shouldSkipMantleReductionForMaintenance(false, false));
        assertFalse(IrisEngineSVC.shouldSkipMantleReductionForMaintenance(false, true));
    }
}
