package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.Snippet;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Snippet("vacuum-settings")
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Defines how a VACUUM place mode bends terrain to meet an object's base.")
@Data
public class IrisVacuumSettings {
    @MinNumber(0)
    @MaxNumber(128)
    @Desc("The horizontal radius (in blocks) the terrain deformation extends beyond the object footprint before it blends back to the natural surface. 0 = derive automatically from the place mode (VACUUM, VACUUM_HIGH larger, VACUUM_FAST smaller).")
    private int radius = 0;
    @MinNumber(0.25)
    @MaxNumber(8)
    @Desc("The falloff exponent controlling how the deformation eases out across the radius. 1 = linear (cone), 2 = parabolic (default, gentle bowl), higher = flatter near the object with a sharper drop at the edge.")
    private double falloff = 2.0;
    @MinNumber(0)
    @MaxNumber(64)
    @Desc("For VACUUM_ORGANIC: the maximum number of blocks the effective radius is randomly perturbed per column, giving the meeting edge an irregular organic outline instead of a clean circle.")
    private int organicJitter = 4;
}
