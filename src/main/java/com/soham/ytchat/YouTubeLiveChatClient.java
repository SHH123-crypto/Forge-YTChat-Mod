package com.soham.ytchat;

import com.google.gson.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YouTubeLiveChatClient {

    // Often present on YouTube pages
    private static final Pattern YTCFG_RE =
            Pattern.compile("ytcfg\\.set\\((\\{.*?\\})\\);", Pattern.DOTALL);

    // Sometimes the page uses different keys / formatting
    private static final Pattern API_KEY_FLEX =
            Pattern.compile("INNERTUBE_API_KEY\"?\\s*[:=]\\s*\"([^\"]+)\"");
    private static final Pattern CLIENT_VER_FLEX =
            Pattern.compile("INNERTUBE_(?:CLIENT_VERSION|CONTEXT_CLIENT_VERSION)\"?\\s*[:=]\\s*\"([^\"]+)\"");

    // Continuation is usually inside liveChatContinuation somewhere
    private static final Pattern LIVECHAT_CONTINUATION_NEARBY =
            Pattern.compile("\"liveChatContinuation\".*?\"continuation\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL);

    // ytInitialData sometimes exists and is JSON
    private static final Pattern YT_INITIAL_DATA_RE =
            Pattern.compile("ytInitialData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson = new Gson();

    private String apiKey;
    private String clientVersion;
    private String continuation;

    // simple de-dupe
    private String lastFingerprint;

    public void reset() {
        apiKey = null;
        clientVersion = null;
        continuation = null;
        lastFingerprint = null;
    }

    /** Call once when URL changes. */
    public void initFromStreamUrl(String streamUrl) throws Exception {
        reset();

        String videoId = extractVideoId(streamUrl)
                .orElseThrow(() -> new IllegalArgumentException("Could not extract video ID from URL"));

        // 1) Try popout first
        String popout = "https://www.youtube.com/live_chat?is_popout=1&v=" +
                URLEncoder.encode(videoId, StandardCharsets.UTF_8);

        HttpResponse<String> popResp = getHtml(popout, videoId);
        if (!tryExtractTokens(popResp.body())) {
            // 2) Fallback: watch page is often more reliable for ytcfg / ytInitialData
            String watch = "https://www.youtube.com/watch?v=" +
                    URLEncoder.encode(videoId, StandardCharsets.UTF_8);

            HttpResponse<String> watchResp = getHtml(watch, videoId);
            if (!tryExtractTokens(watchResp.body())) {
                throw new IllegalStateException(
                        "Could not extract live chat tokens. " +
                        "popoutStatus=" + popResp.statusCode() + " popoutUri=" + popResp.uri() +
                        " watchStatus=" + watchResp.statusCode() + " watchUri=" + watchResp.uri() +
                        " snippet=" + safeSnippet(watchResp.body())
                );
            }
        }

        if (apiKey == null || continuation == null) {
            throw new IllegalStateException("Token extraction incomplete (apiKey or continuation is null).");
        }
        if (clientVersion == null || clientVersion.isBlank()) {
            clientVersion = "2.20250101.00.00";
        }
    }

    /** Poll once and push any new messages into the provided queue. */
    public void pollOnce(java.util.concurrent.ConcurrentLinkedQueue<Chat> out) throws Exception {
        if (apiKey == null || continuation == null) return;

        JsonObject body = new JsonObject();
        JsonObject context = new JsonObject();
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB");
        client.addProperty("clientVersion", clientVersion);
        context.add("client", client);
        body.add("context", context);
        body.addProperty("continuation", continuation);

        String url = "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=" +
                URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        String json = postJson(url, gson.toJson(body));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        extractMessages(root, out);

        String next = extractNextContinuation(root);
        if (next != null && !next.isBlank()) {
            continuation = next;
        }
    }

    // --------------------------
    // Token extraction
    // --------------------------

    private boolean tryExtractTokens(String html) {
        if (html == null || html.isBlank()) return false;

        // Prefer ytcfg.set({...})
        JsonObject ytcfg = find1(YTCFG_RE, html)
                .map(s -> {
                    try { return JsonParser.parseString(s).getAsJsonObject(); }
                    catch (Exception e) { return null; }
                })
                .orElse(null);

        if (ytcfg != null) {
            if (apiKey == null && ytcfg.has("INNERTUBE_API_KEY")) {
                apiKey = ytcfg.get("INNERTUBE_API_KEY").getAsString();
            }
            if (clientVersion == null) {
                if (ytcfg.has("INNERTUBE_CONTEXT_CLIENT_VERSION")) {
                    clientVersion = ytcfg.get("INNERTUBE_CONTEXT_CLIENT_VERSION").getAsString();
                } else if (ytcfg.has("INNERTUBE_CLIENT_VERSION")) {
                    clientVersion = ytcfg.get("INNERTUBE_CLIENT_VERSION").getAsString();
                }
            }
        }

        // Flexible regex fallbacks
        if (apiKey == null) apiKey = find1(API_KEY_FLEX, html).orElse(null);
        if (clientVersion == null) clientVersion = find1(CLIENT_VER_FLEX, html).orElse(null);

        // Continuation: try "liveChatContinuation ... continuation"
        if (continuation == null) {
            continuation = find1(LIVECHAT_CONTINUATION_NEARBY, html).orElse(null);
        }

        // If still missing continuation, try parsing ytInitialData JSON and searching inside
        if (continuation == null) {
            JsonObject initial = find1(YT_INITIAL_DATA_RE, html)
                    .map(s -> {
                        try { return JsonParser.parseString(s).getAsJsonObject(); }
                        catch (Exception e) { return null; }
                    })
                    .orElse(null);

            if (initial != null) {
                continuation = deepFindFirstContinuation(initial);
            }
        }

        return apiKey != null && continuation != null;
    }

    private static String deepFindFirstContinuation(JsonElement root) {
        // DFS search for a property named "continuation" with a primitive string
        if (root == null) return null;

        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("continuation") && obj.get("continuation").isJsonPrimitive()) {
                return obj.get("continuation").getAsString();
            }
            for (String k : obj.keySet()) {
                String found = deepFindFirstContinuation(obj.get(k));
                if (found != null && !found.isBlank()) return found;
            }
        } else if (root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                String found = deepFindFirstContinuation(el);
                if (found != null && !found.isBlank()) return found;
            }
        }
        return null;
    }

    // --------------------------
    // Network helpers
    // --------------------------

    private HttpResponse<String> getHtml(String url, String videoId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=" + videoId)
                .GET()
                .build();

        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String postJson(String url, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static Optional<String> find1(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return Optional.empty();
        return Optional.ofNullable(m.group(1));
    }

    private static String safeSnippet(String html) {
        if (html == null) return "";
        String oneLine = html.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() > 220) return oneLine.substring(0, 220) + "...";
        return oneLine;
    }

    // --------------------------
    // Parsing live chat JSON responses
    // --------------------------

    private void extractMessages(JsonObject root, java.util.concurrent.ConcurrentLinkedQueue<Chat> out) {
        JsonArray actions = deepGetArray(root,
                "continuationContents", "liveChatContinuation", "actions");

        if (actions == null) return;

        for (JsonElement el : actions) {
            JsonObject action = el.getAsJsonObject();

            JsonObject addChat = deepGetObj(action, "addChatItemAction");
            if (addChat == null) continue;

            JsonObject item = deepGetObj(addChat, "item");
            if (item == null) continue;

            JsonObject msg = deepGetObj(item, "liveChatTextMessageRenderer");
            if (msg == null) continue;

            String author = deepGetString(msg, "authorName", "simpleText");
            if (author == null) author = "unknown";

            String text = concatRunsText(deepGetArray(msg, "message", "runs"));
            if (text == null || text.isBlank()) continue;

            String fp = author + "|" + text;
            if (fp.equals(lastFingerprint)) continue;
            lastFingerprint = fp;

            out.add(new Chat(author, text));
        }
    }

    private String extractNextContinuation(JsonObject root) {
        JsonArray conts = deepGetArray(root,
                "continuationContents", "liveChatContinuation", "continuations");
        if (conts == null) return null;

        for (JsonElement el : conts) {
            JsonObject c = el.getAsJsonObject();

            String timed = deepGetString(c, "timedContinuationData", "continuation");
            if (timed != null) return timed;

            String inval = deepGetString(c, "invalidationContinuationData", "continuation");
            if (inval != null) return inval;

            String reload = deepGetString(c, "reloadContinuationData", "continuation");
            if (reload != null) return reload;
        }
        return null;
    }

    private static String concatRunsText(JsonArray runs) {
        if (runs == null) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : runs) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("text")) sb.append(o.get("text").getAsString());
        }
        return sb.toString();
    }

    private static JsonObject deepGetObj(JsonObject root, String... path) {
        JsonElement cur = root;
        for (String key : path) {
            if (cur == null || !cur.isJsonObject()) return null;
            JsonObject obj = cur.getAsJsonObject();
            if (!obj.has(key)) return null;
            cur = obj.get(key);
        }
        return (cur != null && cur.isJsonObject()) ? cur.getAsJsonObject() : null;
    }

    private static JsonArray deepGetArray(JsonObject root, String... path) {
        JsonElement cur = root;
        for (String key : path) {
            if (cur == null || !cur.isJsonObject()) return null;
            JsonObject obj = cur.getAsJsonObject();
            if (!obj.has(key)) return null;
            cur = obj.get(key);
        }
        return (cur != null && cur.isJsonArray()) ? cur.getAsJsonArray() : null;
    }

    private static String deepGetString(JsonObject root, String... path) {
        JsonElement cur = root;
        for (String key : path) {
            if (cur == null || !cur.isJsonObject()) return null;
            JsonObject obj = cur.getAsJsonObject();
            if (!obj.has(key)) return null;
            cur = obj.get(key);
        }
        return (cur != null && cur.isJsonPrimitive()) ? cur.getAsString() : null;
    }

    // --------------------------
    // Video ID extraction
    // --------------------------

    public static Optional<String> extractVideoId(String url) {
        try {
            URI u = URI.create(url.trim());

            if (u.getHost() != null && u.getHost().contains("youtu.be")) {
                String path = u.getPath();
                if (path != null && path.length() > 1) {
                    return Optional.of(path.substring(1));
                }
            }

            String q = u.getQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    int idx = part.indexOf('=');
                    if (idx > 0) {
                        String k = part.substring(0, idx);
                        String v = part.substring(idx + 1);
                        if (k.equals("v") && !v.isBlank()) return Optional.of(v);
                    }
                }
            }

            String path = u.getPath();
            if (path != null) {
                String[] seg = path.split("/");
                for (int i = 0; i < seg.length - 1; i++) {
                    if (seg[i].equals("live") && !seg[i + 1].isBlank()) {
                        return Optional.of(seg[i + 1]);
                    }
                }
            }
        } catch (Exception ignored) {}

        return Optional.empty();
    }
}
