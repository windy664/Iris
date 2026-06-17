/*
 * Iris is a World Generator for Minecraft Servers
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

package art.arcane.iris.core.gui;

import art.arcane.iris.engine.framework.Engine;

import java.awt.GraphicsEnvironment;

public final class GuiHost {
    private static volatile Provider provider = new Provider() {
    };

    private GuiHost() {
    }

    public interface Provider {
        default Engine findActiveEngine() {
            return null;
        }

        default void registerHotloadHook(Runnable onHotload) {
        }

        default void unregisterHotloadHook(Runnable onHotload) {
        }

        default GuiOverlay overlayFor(Engine engine) {
            return null;
        }
    }

    public static void set(Provider boundProvider) {
        if (boundProvider != null) {
            provider = boundProvider;
        }
    }

    public static Provider get() {
        return provider;
    }

    public static boolean isAvailable() {
        return !GraphicsEnvironment.isHeadless();
    }
}
