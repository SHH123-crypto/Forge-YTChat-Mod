package com.soham.ytchat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class ChatHudLayer {

    // How many *chat entries* to keep (each entry can wrap into multiple lines)
    private static final int MAX_ENTRIES = 30;

    // Twitch-like layout
    private static final int MARGIN = 6;
    private static final int PADDING = 4;

    // Target on-screen size (in pixels)
    private static final int BOX_W_PX = 185;   // narrow column like the screenshot
    private static final int MAX_BOX_H_PX = 120; // keeps it from getting huge

    // Makes text + UI smaller (this is the “text smaller” fix)
    private static final float SCALE = 0.75f;

    // Colors
    private static final int HEADER_BG = 0xCC1E3A8A; // bluish header
    private static final int BODY_BG   = 0x99000000; // translucent black
    private static final int BORDER    = 0x66000000;

    private static final int USER_DEFAULT = 0xFF66CCFF; // light cyan for username
    private static final int MSG_COLOR    = 0xFFFFFFFF; // white message
    private static final int TITLE_COLOR  = 0xFFFFFFFF;

    private static final Deque<ChatEntry> ENTRIES = new ArrayDeque<>();

    private ChatHudLayer() {}

    private record ChatEntry(String author, String msg, int authorColor) {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Drain queue
        if (ExampleMod.SCRAPER != null) {
            for (int i = 0; i < 25; i++) {
                Chat c = ExampleMod.SCRAPER.incoming.poll();
                if (c == null) break;

                String author = (c.author() == null) ? "" : c.author().trim();
                String msg = (c.chat() == null) ? "" : c.chat().trim();
                if (author.isEmpty() || msg.isEmpty()) continue;

                int color = colorFor(author);
                ENTRIES.addLast(new ChatEntry(author, msg, color));
                while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
            }
        }

        int sw = mc.getWindow().getGuiScaledWidth();

        // === "Smaller" look without scaling the matrix ===
        // Instead: reduce widths/heights + line spacing to feel smaller.
        // (Font size itself can’t be changed without scaling.)
        final int margin = 6;
        final int padding = 3;
        final int boxW = 170;         // narrower
        final int maxBoxH = 105;      // shorter
        final int headerH = mc.font.lineHeight + 3;
        final int lineH = mc.font.lineHeight; // tighter than +1/+2

        // Top-right anchor
        int x0 = sw - boxW - margin;
        int y0 = margin;

        int wrapW = boxW - (padding * 2);

        record Line(FormattedCharSequence seq, int color) {}
        ArrayDeque<Line> lines = new ArrayDeque<>();

        for (ChatEntry e : ENTRIES) {
            // Username (colored)
            String head = e.author + ": ";
            List<FormattedCharSequence> headParts =
                    mc.font.split(net.minecraft.network.chat.Component.literal(head), wrapW);
            // Message (white)
            List<FormattedCharSequence> msgParts =
                    mc.font.split(net.minecraft.network.chat.Component.literal(e.msg), wrapW);

            // Put username on its own line, then message lines under it
            for (FormattedCharSequence hp : headParts) lines.addLast(new Line(hp, e.authorColor));
            for (FormattedCharSequence mp : msgParts) lines.addLast(new Line(mp, MSG_COLOR));
        }

        int bodyH = (lines.size() * lineH) + (padding * 2);
        int boxH = Math.min(headerH + bodyH, maxBoxH);

        // Background + header
        g.fill(x0, y0, x0 + boxW, y0 + boxH, BODY_BG);
        g.fill(x0, y0, x0 + boxW, y0 + headerH, HEADER_BG);

        // Border
        g.fill(x0, y0, x0 + boxW, y0 + 1, BORDER);
        g.fill(x0, y0 + boxH - 1, x0 + boxW, y0 + boxH, BORDER);
        g.fill(x0, y0, x0 + 1, y0 + boxH, BORDER);
        g.fill(x0 + boxW - 1, y0, x0 + boxW, y0 + boxH, BORDER);

        // Header label
        g.drawString(mc.font, "Chat", x0 + padding, y0 + 1, TITLE_COLOR, false);

        // Draw bottom-up
        int bodyTop = y0 + headerH + padding;
        int bodyBottom = y0 + boxH - padding;

        int y = bodyBottom - lineH;

        Line[] arr = lines.toArray(new Line[0]);
        for (int i = arr.length - 1; i >= 0; i--) {
            if (y < bodyTop) break;
            Line ln = arr[i];
            g.drawString(mc.font, ln.seq, x0 + padding, y, ln.color, false);
            y -= lineH;
        }
    }


    // Simple stable username colors (Twitch-ish vibe)
    private static int colorFor(String name) {
        int h = name.hashCode();
        // generate a bright-ish color
        int r = 120 + (Math.abs(h) % 100);
        int g = 120 + (Math.abs(h / 7) % 100);
        int b = 120 + (Math.abs(h / 13) % 100);
        r = Math.min(255, r);
        g = Math.min(255, g);
        b = Math.min(255, b);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}
