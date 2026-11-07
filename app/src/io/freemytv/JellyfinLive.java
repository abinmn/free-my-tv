package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live channels from the owner's Jellyfin. All network and file reads run off the UI thread. */
final class JellyfinLive {
    static final int PAGE_SIZE = 4;
    static boolean enabled() { return !Config.JELLYFIN_SERVER.isEmpty(); }
    private static JellyfinLive instance;
    static synchronized JellyfinLive get(Context c) {
        if (instance == null) instance = new JellyfinLive(c.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService playback = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<String, Bitmap> logos = new LinkedHashMap<>();
    private final Map<String, Integer> logoColors = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>(), failedLogos = new HashSet<>();
    private volatile List<Recs.Pick> channels = Collections.emptyList();
    private volatile JSONObject config;
    private volatile String status = Ui.tr("Connecting…");
    private volatile boolean fetching;
    private volatile long fetchedAt, attemptedAt;
    private volatile int playGeneration;

    private JellyfinLive(Context c) { context = c; }
    void listen(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    void unlisten(Runnable r) { listeners.remove(r); }
    private void changed() { main.post(() -> { for (Runnable r : new ArrayList<>(listeners)) r.run(); }); }
    int count() { return channels.size(); }
    String status() { return status; }
    List<Recs.Pick> page(int start) {
        List<Recs.Pick> all = channels;
        if (all.isEmpty()) return Collections.emptyList();
        start = Math.max(0, Math.min(start, all.size() - 1));
        List<Recs.Pick> page = new ArrayList<>();
        for (int i = start; i < Math.min(all.size(), start + PAGE_SIZE); i++) {
            // Keep bitmaps on the visible page, not on every channel ever visited (low-memory TVs).
            Recs.Pick source = all.get(i), p = new Recs.Pick();
            p.liveId = source.liveId; p.liveNumber = source.liveNumber;
            p.id = source.id; p.kind = source.kind; p.title = source.title; p.meta = source.meta;
            p.pkg = source.pkg; p.app = source.app; p.link = source.link;
            page.add(p);
        }
        return page;
    }
    void refresh() { refresh(false); }
    void resume() { refresh(true); }
    private void refresh(boolean force) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (fetching || !force && ((fetchedAt > 0 && now - fetchedAt < 300_000) || now - attemptedAt < 30_000)) return;
        fetching = true;
        attemptedAt = now;
        io.execute(() -> {
            try {
                String secret = Secrets.get(context, Secrets.JELLYFIN_CONNECTION);
                if (secret.isEmpty()) {
                    config = null;
                    channels = Collections.emptyList();
                    status = Ui.tr("Unavailable");
                    return;
                }
                JSONObject next = new JSONObject(secret);
                next.put("server", Config.JELLYFIN_SERVER.replaceAll("/+$", ""));
                URL server = new URL(next.getString("server"));
                if (server.getUserInfo() != null || server.getQuery() != null || server.getRef() != null)
                    throw new Exception("Invalid server address");
                if (!next.getString("token").matches("[a-zA-Z0-9._-]{8,1024}")
                        || !next.getString("userId").matches("[a-fA-F0-9-]{32,36}")
                        || next.getString("deviceId").isEmpty()) throw new Exception("Invalid connection");
                String owner = next.getString("server") + "|" + next.getString("userId");
                if (config != null && !owner.equals(config.optString("owner"))) {
                    channels = Collections.emptyList();
                    synchronized (logos) { logos.clear(); logoColors.clear(); failedLogos.clear(); }
                }
                next.put("owner", owner);
                config = next;
                if (channels.isEmpty()) {
                    try {
                        JSONObject saved = new JSONObject(readFile(new File(context.getFilesDir(), "jellyfin-channels.json")));
                        if (owner.equals(saved.optString("_freeMyTvOwner"))) { parse(saved); changed(); }
                    }
                    catch (Exception ignored) { }
                }
                JSONObject data = request("/LiveTv/Channels?UserId=" + config.getString("userId")
                        + "&AddCurrentProgram=true&SortBy=SortName&Limit=300", "GET");
                parse(data);
                data.put("_freeMyTvOwner", owner);
                File part = new File(context.getFilesDir(), "jellyfin-channels.part");
                try (FileOutputStream out = new FileOutputStream(part)) { out.write(data.toString().getBytes("UTF-8")); }
                part.renameTo(new File(context.getFilesDir(), "jellyfin-channels.json"));
                fetchedAt = System.currentTimeMillis();
                status = channels.isEmpty() ? Ui.tr("Unavailable") : "";
            } catch (Exception e) {
                AppLog.w("Jellyfin live unavailable: " + e.getClass().getSimpleName());
                status = channels.isEmpty() ? Ui.tr("No connection") : Ui.tr("No connection");
            } finally {
                fetching = false;
                changed();
            }
        });
    }
    private void parse(JSONObject data) throws Exception {
        List<Recs.Pick> out = new ArrayList<>();
        JSONArray a = data.getJSONArray("Items");
        for (int i = 0; i < Math.min(a.length(), 300); i++) {
            JSONObject c = a.getJSONObject(i);
            String id = c.getString("Id");
            if (!id.matches("[a-fA-F0-9-]{32,36}")) continue;
            Recs.Pick p = new Recs.Pick();
            p.liveId = id;
            p.liveNumber = c.optString("ChannelNumber", "");
            p.id = id.hashCode();
            p.kind = "live";
            p.title = c.optString("Name", "TV");
            p.pkg = "org.jellyfin.androidtv";
            p.app = "jellyfin";
            p.link = id;
            JSONObject program = c.optJSONObject("CurrentProgram");
            p.meta = program == null ? Ui.tr("Live TV") : program.optString("Name", Ui.tr("Live TV"));
            out.add(p);
        }
        Collections.sort(out, (a1, b1) -> {
            try { int n = Double.compare(Double.parseDouble(a1.liveNumber), Double.parseDouble(b1.liveNumber)); if (n != 0) return n; }
            catch (NumberFormatException ignored) { }
            return a1.title.compareToIgnoreCase(b1.title);
        });
        channels = out;
    }
    Bitmap logo(Recs.Pick p) {
        synchronized (logos) {
            Bitmap b = logos.get(p.liveId);
            if (b != null) {
                Integer color = logoColors.get(p.liveId);
                p.liveColor = color == null ? 0xFFF7F7FA : color;
                return b;
            }
            if (config == null || failedLogos.contains(p.liveId) || !pending.add(p.liveId)) return null;
        }
        io.execute(() -> {
            try {
                File dir = new File(context.getCacheDir(), "jellyfin-logos-" + config.getString("owner").hashCode()); dir.mkdirs();
                File file = new File(dir, p.liveId + ".png");
                if (!file.exists()) {
                    byte[] data = bytes("/Items/" + p.liveId + "/Images/Primary?MaxWidth=480&MaxHeight=270", "GET", 2_000_000);
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 480 || bounds.outHeight / options.inSampleSize > 270)
                    options.inSampleSize *= 2;
                Bitmap b = BitmapFactory.decodeFile(file.getPath(), options);
                synchronized (logos) {
                    if (b != null) {
                        logos.put(p.liveId, b);
                        logoColors.put(p.liveId, background(b));
                        while (logos.size() > 12) {
                            String oldest = logos.keySet().iterator().next();
                            logos.remove(oldest); logoColors.remove(oldest);
                        }
                    } else failedLogos.add(p.liveId);
                }
            } catch (Exception | OutOfMemoryError e) {
                synchronized (logos) { failedLogos.add(p.liveId); }
            } finally {
                synchronized (logos) { pending.remove(p.liveId); }
                changed();
            }
        });
        return null;
    }
    void play(Activity activity, Recs.Pick p, Runnable failed) {
        final int generation = ++playGeneration;
        final long requestedAt = System.currentTimeMillis();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("org.jellyfin.androidtv",
                    "org.jellyfin.androidtv.ui.startup.StartupActivity"));
            activity.startActivity(intent);
        } catch (RuntimeException e) { failed.run(); return; }
        playback.execute(() -> {
            try {
                // Pin commands to this TV's device ID, even if another family TV is connected.
                for (int attempt = 0; attempt < 12; attempt++) {
                    if (generation != playGeneration) return;
                    JSONArray sessions = request("/Sessions", "GET").getJSONArray("Items");
                    for (int i = 0; i < sessions.length(); i++) {
                        JSONObject s = sessions.getJSONObject(i);
                        if (!config.getString("deviceId").equals(s.optString("DeviceId"))
                                || !config.getString("userId").replace("-", "").equals(s.optString("UserId").replace("-", ""))
                                || !s.optBoolean("SupportsRemoteControl")) continue;
                        String active = s.optString("LastActivityDate");
                        // The TV must have reconnected after startup, rather than a stale server-side session.
                        try {
                            java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                            date.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                            if (date.parse(active).getTime() < requestedAt - 3000) continue;
                        } catch (Exception ignored) { continue; }
                        request("/Sessions/" + s.getString("Id") + "/Playing?PlayCommand=PlayNow&ItemIds=" + p.liveId, "POST");
                        return;
                    }
                    Thread.sleep(1000);
                }
                throw new Exception("TV session unavailable");
            } catch (Exception e) { if (generation == playGeneration) main.post(failed); }
        });
    }
    private static int background(Bitmap b) {
        int bright = 0, visible = 0;
        for (int y = 0; y < b.getHeight(); y += Math.max(1, b.getHeight() / 40)) {
            for (int x = 0; x < b.getWidth(); x += Math.max(1, b.getWidth() / 64)) {
                int pixel = b.getPixel(x, y);
                if ((pixel >>> 24) < 128) continue;
                visible++;
                if (((pixel >> 16) & 255) > 210 && ((pixel >> 8) & 255) > 210 && (pixel & 255) > 210) bright++;
            }
        }
        return visible > 0 && bright > visible * 0.4f ? 0xFF303038 : 0xFFF7F7FA;
    }
    private JSONObject request(String path, String method) throws Exception {
        byte[] raw = bytes(path, method, 4_000_000);
        if (raw.length == 0) return new JSONObject();
        String text = new String(raw, "UTF-8");
        if (text.startsWith("[")) return new JSONObject().put("Items", new JSONArray(text));
        return new JSONObject(text);
    }
    private byte[] bytes(String path, String method, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(config.getString("server") + path).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(6000); c.setReadTimeout(8000);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + config.getString("token") + "\"");
            if ("POST".equals(method)) { c.setDoOutput(true); c.setFixedLengthStreamingMode(0); c.getOutputStream().close(); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("Jellyfin HTTP " + code);
            try (InputStream in = c.getInputStream()) { return read(in, limit); }
        } finally { c.disconnect(); }
    }
    private static String readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) { return new String(read(in, 4_000_000), "UTF-8"); }
    }
    private static byte[] read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1;) {
            if (out.size() + n > limit) throw new Exception("Response too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
