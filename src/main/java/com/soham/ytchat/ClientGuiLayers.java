package com.soham.ytchat;

import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;

public final class ClientGuiLayers {
    private ClientGuiLayers() {}

    public static void addLayers(AddGuiOverlayLayersEvent event) {
        Identifier layerId = Identifier.tryParse(ExampleMod.MODID + ":ytchat_overlay");
        if (layerId == null) return;

        event.getLayeredDraw().add(
                ForgeLayeredDraw.POST_SLEEP_STACK,
                layerId,
                (guiGraphics, deltaTracker) -> ChatHudLayer.render(guiGraphics)
        );

        System.out.println("YTCHAT: overlay layer added: " + layerId);
    }
}
