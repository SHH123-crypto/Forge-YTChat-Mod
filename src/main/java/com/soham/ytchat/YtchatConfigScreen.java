package com.soham.ytchat;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class YtchatConfigScreen extends Screen {
    private final Screen parent;
    private EditBox urlBox;

    public YtchatConfigScreen(Screen parent) {
        super(Component.literal("YouTube Chat Overlay Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = this.width;
        int y = 60;

        urlBox = new EditBox(this.font, w / 2 - 160, y, 320, 20, Component.literal("Chat URL"));
        urlBox.setMaxLength(2048);
        urlBox.setValue(Config.getChatUrl());
        this.addRenderableWidget(urlBox);

        this.addRenderableWidget(Button.builder(Component.literal("Save"), btn -> {
            String v = urlBox.getValue().trim();

            if (!Config.isValidUrl(v)) {
                urlBox.setValue(Config.getChatUrl());
                return;
            }

            Config.CHAT_URL.set(v);

            if (Config.CLIENT_CONFIG != null) {
                Config.CLIENT_CONFIG.save();
            }

            if (ExampleMod.SCRAPER != null) {
                ExampleMod.SCRAPER.restart(v);
            }

            this.minecraft.setScreen(parent);
        }).bounds(w / 2 - 160, y + 40, 150, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
            this.minecraft.setScreen(parent);
        }).bounds(w / 2 + 10, y + 40, 150, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
