package com.soham.ytchat;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // Stored in ytchat-client.toml
    public static final ForgeConfigSpec.ConfigValue<String> CHAT_URL = BUILDER
            .comment("YouTube stream URL or watch URL. Must start with http:// or https://")
            .define("chatUrl", "https://example.com");

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    // Keep a reference so GUI can save()
    public static ModConfig CLIENT_CONFIG;

    private Config() {}

    @SubscribeEvent
    public static void onConfigLoad(final ModConfigEvent event) {
        if (event.getConfig().getType() == ModConfig.Type.CLIENT) {
            CLIENT_CONFIG = event.getConfig();
        }
    }

    public static String getChatUrl() {
        String s = CHAT_URL.get();
        return (s == null) ? "" : s.trim();
    }

    public static boolean isValidUrl(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.isEmpty()) return false;
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return false;
        try {
            new java.net.URI(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
