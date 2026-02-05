package com.soham.ytchat;

import java.util.concurrent.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ChatScraperService {

    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ytchat-scraper");
                t.setDaemon(true);
                return t;
            });

    private final YouTubeLiveChatClient yt = new YouTubeLiveChatClient();
    public final ConcurrentLinkedQueue<Chat> incoming = new ConcurrentLinkedQueue<>();
    private volatile long lastErrorAtMs = 0;
    private volatile String lastErrorKey = null;
    
    private ScheduledFuture<?> task;

    private volatile String url;
    private volatile boolean initialized;

    // Prevent HUD spam
    private volatile long lastErrorMs = 0;

    public void start(String url) {
        restart(url);
    }

    public void restart(String url) {
        stopTask();
        incoming.clear();

        this.url = (url == null) ? "" : url.trim();
        this.initialized = false;
        this.lastErrorMs = 0;

        yt.reset();

        incoming.add(new Chat("YTCHAT", "Restarting live chat fetch..."));

        task = exec.scheduleAtFixedRate(() -> {
            String u = this.url;
            if (u == null || u.isBlank()) return;

            try {
                if (!initialized) {
                    yt.initFromStreamUrl(u);
                    initialized = true;
                    incoming.add(new Chat("YTCHAT", "Connected. Polling chat..."));
                }

                yt.pollOnce(incoming);

            } catch (Exception e) {
                // Show the real message, but throttle to avoid spam
                String msg = e.getMessage();
                if (msg == null) msg = "";
                String key = e.getClass().getSimpleName() + "|" + msg;

                long now = System.currentTimeMillis();
                if (!key.equals(lastErrorKey) || now - lastErrorAtMs > 10_000) {
                    lastErrorKey = key;
                    lastErrorAtMs = now;

                    incoming.add(new Chat("YTCHAT", "Error: " + e.getClass().getSimpleName()));
                    if (!msg.isBlank()) incoming.add(new Chat("YTCHAT", msg));
                }

                // Also print stack trace to the console/log for debugging
                e.printStackTrace();
            }

        }, 0, 30, TimeUnit.SECONDS);
    }

    private void stopTask() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    public void shutdown() {
        stopTask();
        exec.shutdownNow();
    }
}
