package com.soham.ytchat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ExampleMod.MODID)
public final class ExampleMod {
    public static final String MODID = "ytchat";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final FMLJavaModLoadingContext context;
    public static ChatScraperService SCRAPER;

    public ExampleMod(FMLJavaModLoadingContext context) {
        this.context = context;

        // IMPORTANT: this event is on the DEFAULT bus, not MOD bus.
        AddGuiOverlayLayersEvent.BUS.addListener(ClientGuiLayers::addLayers);

        // Register client config
        context.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        var modBusGroup = context.getModBusGroup();
        FMLClientSetupEvent.getBus(modBusGroup).addListener(this::onClientSetup);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        // Config button -> opens your screen
        context.registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new YtchatConfigScreen(parent))
        );

        // Start scraper service
        SCRAPER = new ChatScraperService();
        SCRAPER.start(Config.getChatUrl());

        LOGGER.info("YTCHAT client setup complete");
    }
}
