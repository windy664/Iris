package art.arcane.iris.util.common.plugin;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VolmitSenderMiniMessageEscapeTest {
    @Test
    public void escapesApostrophesForQuotedHoverText() {
        String escaped = VolmitSender.escapeMiniMessageQuotedText("This world's dimension config");

        assertEquals("This world\\'s dimension config", escaped);
        MiniMessage.miniMessage().deserialize("<hover:show_text:'" + escaped + "'>ok</hover>");
    }

    @Test
    public void escapesBackslashesBeforeQuotedHoverText() {
        String escaped = VolmitSender.escapeMiniMessageQuotedText("Path \\\\ data");

        assertEquals("Path \\\\\\\\ data", escaped);
        MiniMessage.miniMessage().deserialize("<hover:show_text:'" + escaped + "'>ok</hover>");
    }
}
