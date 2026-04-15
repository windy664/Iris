package art.arcane.iris.core.nms.datapack.v1217;

import art.arcane.iris.core.nms.datapack.IDataFixer.Dimension;
import art.arcane.volmlib.util.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DataFixerV1217DimensionTypeTest {
    private final DataFixerV1217 fixer = new DataFixerV1217();

    @Test
    public void createsOverworldDimensionWithDragonFightDisabled() {
        JSONObject json = fixer.createDimension(Dimension.OVERWORLD, -256, 768, 512, null);

        assertTrue(json.has("has_ender_dragon_fight"));
        assertEquals(false, json.getBoolean("has_ender_dragon_fight"));
    }

    @Test
    public void createsEndDimensionWithDragonFightEnabled() {
        JSONObject json = fixer.createDimension(Dimension.END, 0, 256, 256, null);

        assertTrue(json.has("has_ender_dragon_fight"));
        assertEquals(true, json.getBoolean("has_ender_dragon_fight"));
    }
}
