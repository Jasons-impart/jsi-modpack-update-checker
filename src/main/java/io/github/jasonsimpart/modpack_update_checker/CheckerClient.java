package io.github.jasonsimpart.modpack_update_checker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.net.URI;
import java.util.Locale;

@EventBusSubscriber(modid = Modpack_update_checker.MODID, value = Dist.CLIENT)
public final class CheckerClient {
    private static final PackChecker CHECKER = new PackChecker(FMLPaths.GAMEDIR.get());
    private static Button menuButton;
    private static Component label(PackChecker.Status status) {
        return Component.translatable("modpack_update_checker.status." + status.name().toLowerCase(Locale.ROOT));
    }
    @SubscribeEvent
    public static void menu(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        CHECKER.check(false);
        menuButton = Button.builder(label(CHECKER.view().status()), button ->
                Minecraft.getInstance().setScreen(new Details(event.getScreen())))
                .bounds(8, 8, 150, 20).build();
        if (ModList.get().isLoaded("fancymenu")) FancyMenuCompat.identify(menuButton);
        event.addListener(menuButton);
    }
    @SubscribeEvent
    public static void render(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof TitleScreen && menuButton != null) menuButton.setMessage(label(CHECKER.view().status()));
    }

    private static final class Details extends Screen {
        private final Screen parent;
        private Button retry;
        private Button link;
        Details(Screen parent) { super(Component.translatable("modpack_update_checker.title")); this.parent = parent; }
        @Override protected void init() {
            retry = addRenderableWidget(Button.builder(Component.translatable("modpack_update_checker.retry"),
                    button -> CHECKER.check(true)).bounds(width / 2 - 155, height - 56, 150, 20).build());
            link = addRenderableWidget(Button.builder(Component.translatable("modpack_update_checker.open"), button -> {
                var view = CHECKER.view();
                String suffix = view.target() == null ? "/tags" : "/tree/" + view.target().name();
                Util.getPlatform().openUri(URI.create("https://github.com/" + view.repository() + suffix));
            }).bounds(width / 2 + 5, height - 56, 150, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                    .bounds(width / 2 - 75, height - 30, 150, 20).build());
        }
        @Override public void tick() {
            var view = CHECKER.view();
            retry.active = view.status() != PackChecker.Status.CHECKING;
            link.active = !view.repository().isBlank();
        }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            var view = CHECKER.view();
            graphics.drawCenteredString(font, title, width / 2, 20, 0xffffff);
            graphics.drawCenteredString(font, label(view.status()), width / 2, 48, 0xffff55);
            String content = view.pack() + "\n" + Component.translatable("modpack_update_checker.installed", view.installed()).getString();
            if (view.target() != null) content += "\n" + Component.translatable("modpack_update_checker.target", view.target().name()).getString();
            if (view.status() == PackChecker.Status.UNCONFIGURED) content += "\n" + Component.translatable("modpack_update_checker.configure").getString();
            if (!view.error().isBlank()) content += "\n" + view.error();
            graphics.enableScissor(12, 72, width - 12, height - 64);
            graphics.drawWordWrap(font, Component.literal(content), 16, 76, width - 32, 0xffffff);
            graphics.disableScissor();
        }
        @Override public void onClose() { minecraft.setScreen(parent); }
    }
}
