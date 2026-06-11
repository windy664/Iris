/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.platform.bukkit;

import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.data.BlockData;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class BukkitSpiConformanceTest {
    @BeforeClass
    public static void setup() {
        Server server = Bukkit.getServer();
        if (server == null) {
            server = mock(Server.class);
            doReturn(Logger.getLogger("IrisTest")).when(server).getLogger();
            doReturn("IrisTestServer").when(server).getName();
            doReturn("1.0").when(server).getVersion();
            doReturn("1.0").when(server).getBukkitVersion();
            doReturn(mock(BlockData.class)).when(server).createBlockData(any(Material.class));
            Bukkit.setServer(server);
        }
        doAnswer((InvocationOnMock invocation) -> blockData(invocation.getArgument(0))).when(server).createBlockData(anyString());
    }

    private static BlockData blockData(String asString) {
        BlockData data = mock(BlockData.class);
        doReturn(asString).when(data).getAsString();
        return data;
    }

    @Test
    public void blockStateInterningReturnsSameInstanceForSameKey() {
        BlockData first = blockData("iristest:intern_block[axis=y]");
        BlockData second = blockData("iristest:intern_block[axis=y]");
        assertSame(BukkitBlockState.of(first), BukkitBlockState.of(second));
    }

    @Test
    public void blockStateKeyMatchesCanonicalString() {
        BlockData data = blockData("iristest:key_block[facing=north,lit=true]");
        assertEquals("iristest:key_block[facing=north,lit=true]", BukkitBlockState.of(data).key());
    }

    @Test
    public void blockStateNamespaceParsesWithAndWithoutProperties() {
        BlockData withProps = blockData("iristest:ns_block[axis=y]");
        BlockData withoutProps = blockData("iristest:ns_plain");
        assertEquals("iristest", BukkitBlockState.of(withProps).namespace());
        assertEquals("iristest", BukkitBlockState.of(withoutProps).namespace());
    }

    @Test
    public void withPropertyReplacesExistingProperty() {
        BlockData data = blockData("iristest:merge_block[axis=y,waterlogged=false]");
        PlatformBlockState merged = BukkitBlockState.of(data).withProperty("axis", "x");
        assertEquals("iristest:merge_block[axis=x,waterlogged=false]", merged.key());
    }

    @Test
    public void withPropertyAppendsToExistingProperties() {
        BlockData data = blockData("iristest:append_block[axis=y]");
        PlatformBlockState merged = BukkitBlockState.of(data).withProperty("lit", "true");
        assertEquals("iristest:append_block[axis=y,lit=true]", merged.key());
    }

    @Test
    public void withPropertyAddsBracketSectionWhenAbsent() {
        BlockData data = blockData("iristest:bare_block");
        PlatformBlockState merged = BukkitBlockState.of(data).withProperty("lit", "true");
        assertEquals("iristest:bare_block[lit=true]", merged.key());
    }

    @Test
    public void platformBindingLifecycle() {
        assertFalse(IrisPlatforms.isBound());
        try {
            IrisPlatforms.get();
            fail("get() must throw while unbound");
        } catch (IllegalStateException expected) {
        }
        IrisPlatform first = mock(IrisPlatform.class);
        IrisPlatforms.bind(first);
        assertTrue(IrisPlatforms.isBound());
        assertSame(first, IrisPlatforms.get());
        IrisPlatforms.bind(first);
        assertSame(first, IrisPlatforms.get());
        IrisPlatform second = mock(IrisPlatform.class);
        try {
            IrisPlatforms.bind(second);
            fail("bind() must reject a different instance");
        } catch (IllegalStateException expected) {
        }
        assertSame(first, IrisPlatforms.get());
    }
}
