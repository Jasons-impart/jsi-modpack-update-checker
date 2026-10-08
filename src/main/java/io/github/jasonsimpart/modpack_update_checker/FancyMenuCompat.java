package io.github.jasonsimpart.modpack_update_checker;

import de.keksuccino.fancymenu.util.rendering.ui.widget.UniqueWidget;
import net.minecraft.client.gui.components.Button;

/** Loaded only when FancyMenu is present. Its public identifier hook is verified with 3.9.12. */
final class FancyMenuCompat {
    private FancyMenuCompat() {}

    static void identify(Button button) {
        if (button instanceof UniqueWidget widget) {
            widget.setWidgetIdentifierFancyMenu("modpack_update_checker_status");
        }
    }
}
