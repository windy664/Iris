package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Desc("Defines an external datapack source. When replace is true, minecraft namespace entries override the vanilla datapack.")
public class IrisExternalDatapack {
    @Desc("Stable id for this external datapack entry")
    private String id = "";

    @Desc("Datapack source URL. Modrinth version page URLs are supported.")
    private String url = "";

    @Desc("Enable or disable this external datapack entry")
    private boolean enabled = true;

    @Desc("If true, Iris hard-fails startup when this external datapack cannot be synced/imported/installed")
    private boolean required = false;

    @Desc("If true, this datapack replaces vanilla worldgen entries. The datapack itself determines what it overrides.")
    private boolean replace = false;
}
