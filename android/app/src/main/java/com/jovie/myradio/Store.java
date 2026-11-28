package com.jovie.myradio;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Favourites, playback state, the station directory and logo loading. */
public final class Store {
    public static final String ACTION_STATE = "com.jovie.myradio.STATE";
    private static final String PREFS = "radio";

    public static final class Station {
        public String name = "", url = "", favicon = "", dir = "", tags = "", country = "", q = "", m = "";
        public int bitrate;
        public boolean miss;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name); o.put("url", url); o.put("favicon", favicon); o.put("dir", dir);
                o.put("tags", tags); o.put("country", country); o.put("q", q); o.put("m", m);
                o.put("bitrate", bitrate); o.put("miss", miss);
            } catch (Exception ignored) { }
            return o;
        }

        static Station fromJson(JSONObject o) {
            Station s = new Station();
            s.name = o.optString("name"); s.url = o.optString("url"); s.favicon = o.optString("favicon");
            s.dir = o.optString("dir"); s.tags = o.optString("tags"); s.country = o.optString("country");
            s.q = o.optString("q"); s.m = o.optString("m"); s.bitrate = o.optInt("bitrate");
            s.miss = o.optBoolean("miss");
            return s;
        }

        public String sub() {
            StringBuilder b = new StringBuilder();
            if (!dir.isEmpty() && !dir.equalsIgnoreCase(name)) b.append(dir);
            if (!country.isEmpty()) append(b, country);
            if (!tags.isEmpty()) {
                String[] t = tags.split(",");
                append(b, t.length > 1 ? t[0] + ", " + t[1] : t[0]);
            }
            if (bitrate > 0) append(b, bitrate + " kbps");
            return b.toString();
        }

        private static void append(StringBuilder b, String s) { if (b.length() > 0) b.append(" · "); b.append(s); }
    }

    // Your myTuner favourites: display name, directory search, text the match must contain.
    private static final String[][] SEED = {
            {"WFMU 91.1", "WFMU", "wfmu"},
            {"WBGO Jazz 88.3", "WBGO", "wbgo"},
            {"WPSC 88.7 Brave New Radio", "WPSC", "wpsc"},
            {"WMSC 90.3", "WMSC", "wmsc"},
            {"Alt 92.3 HD2", "Alt 92.3", "alt 92"},
            {"GotRadio Americana", "GotRadio Americana", "americana"},
            {"WWOZ 90.7 New Orleans", "WWOZ", "wwoz"},
            {"80s 90s Hits", "80s 90s Hits", "80"},
            {"WFUV 90.7", "WFUV", "wfuv"},
            {"WFDU 89.1", "WFDU", "wfdu"},
            {"WQXR 105.9", "WQXR", "wqxr"},
            {"WFMT 98.7", "WFMT", "wfmt"},
            {"WRTI 90.1", "WRTI", "wrti"},
            {"Just Jazz – Pat Metheny", "Pat Metheny", "metheny"},
    };

    private static Context app; // for the on-disk logo cache

    private static SharedPreferences p(Context c) {
        if (app == null) app = c.getApplicationContext();
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Station> favs(Context c) {
        List<Station> out = new ArrayList<>();
        String raw = p(c).getString("favs", null);
        if (raw == null) {
            for (String[] s : SEED) {
                Station st = new Station();
                st.name = s[0]; st.q = s[1]; st.m = s[2];
                for (String[] k : KNOWN) if (st.name.equals(k[0])) applyKnown(st, k);
                out.add(st);
            }
            saveFavs(c, out);
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) out.add(Station.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        // Fix-up for installs where the directory lookup couldn't find these.
        boolean fixed = false;
        for (Station s : out) {
            if (!s.url.isEmpty()) continue;
            for (String[] k : KNOWN) if (s.name.equals(k[0])) { applyKnown(s, k); fixed = true; }
        }
        if (fixed) saveFavs(c, out);
        return out;
    }

    // Streams found by hand (not in the public directory): name, stream, website label.
    private static final String[][] KNOWN = {
            {"Just Jazz – Pat Metheny", "https://drive.uber.radio/uber/jjrpatmetheny/icecast.audio", "Just Jazz Radio · Dubai"},
    };

    private static void applyKnown(Station s, String[] k) {
        s.url = k[1]; s.dir = k[2]; s.q = ""; s.m = ""; s.miss = false; s.tags = "jazz";
    }

    public static synchronized void saveFavs(Context c, List<Station> list) {
        JSONArray a = new JSONArray();
        for (Station s : list) a.put(s.toJson());
        p(c).edit().putString("favs", a.toString()).apply();
    }

    // ---- playback state shared by app, notification and widget ----
    public static Station current(Context c) {
        String raw = p(c).getString("cur", null);
        if (raw == null) return null;
        try { return Station.fromJson(new JSONObject(raw)); } catch (Exception e) { return null; }
    }

    public static void setCurrent(Context c, Station s) {
        p(c).edit().putString("cur", s == null ? null : s.toJson().toString()).apply();
    }

    public static String status(Context c) { return p(c).getString("status", ""); }

    public static boolean playing(Context c) { return p(c).getBoolean("playing", false); }

    /** Current song from the station ("" when unknown). */
    public static String songArtist(Context c) { return p(c).getString("songArtist", ""); }

    public static String songTitle(Context c) { return p(c).getString("songTitle", ""); }

    /** "Artist – Title", or "" when the station hasn't sent one. */
    public static String songLine(Context c) {
        String a = songArtist(c), t = songTitle(c);
        if (t.isEmpty()) return a;
        return a.isEmpty() ? t : a + " – " + t;
    }

    public static void setSong(Context c, String artist, String title) {
        p(c).edit().putString("songArtist", artist).putString("songTitle", title).apply();
    }

    public static void setState(Context c, String status, boolean playing) {
        p(c).edit().putString("status", status).putBoolean("playing", playing).apply();
        c.sendBroadcast(new Intent(ACTION_STATE).setPackage(c.getPackageName()));
        RadioWidget.updateAll(c);
    }

    /** Position of the current station in favourites, or -1. */
    public static int indexOfCurrent(Context c, List<Station> favs) {
        Station cur = current(c);
        if (cur == null) return -1;
        for (int i = 0; i < favs.size(); i++) if (favs.get(i).url.equals(cur.url) && !cur.url.isEmpty()) return i;
        return -1;
    }

    // ---- station directory (radio-browser.info) ----
    private static final String[] HOSTS = {
            "de1.api.radio-browser.info", "nl1.api.radio-browser.info",
            "at1.api.radio-browser.info", "all.api.radio-browser.info"};

    /** Blocking. Returns stations most-listened first, or null if the directory can't be reached. */
    public static List<Station> lookup(String query) {
        String qs;
        try {
            qs = "/json/stations/search?name=" + URLEncoder.encode(query, "UTF-8")
                    + "&limit=60&hidebroken=true&order=clickcount&reverse=true";
        } catch (Exception e) { return null; }
        for (String h : HOSTS) {
            try {
                String body = new String(get("https://" + h + qs, 512 * 1024), "UTF-8");
                JSONArray a = new JSONArray(body);
                List<Station> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String url = o.optString("url_resolved");
                    String name = o.optString("name").trim();
                    if (url.isEmpty() || name.isEmpty() || !seen.add(name.toLowerCase(Locale.US))) continue;
                    Station s = new Station();
                    s.name = name; s.dir = name; s.url = url;
                    s.favicon = o.optString("favicon");
                    s.country = o.optString("countrycode");
                    s.tags = o.optString("tags");
                    s.bitrate = o.optInt("bitrate");
                    out.add(s);
                }
                return out;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Blocking. Finds streams for preloaded favourites that don't have one yet. Returns true if anything changed. */
    public static boolean resolveMissing(Context c) {
        List<Station> favs = favs(c);
        boolean changed = false;
        for (Station f : favs) {
            if (!f.url.isEmpty() || f.q.isEmpty()) continue;
            List<Station> r = lookup(f.q);
            if (r == null) break; // offline: try again next time
            Station hit = null;
            for (Station s : r) if (s.name.toLowerCase(Locale.US).contains(f.m)) { hit = s; break; }
            if (hit != null) {
                f.url = hit.url; f.favicon = hit.favicon; f.dir = hit.name; f.country = hit.country;
                f.tags = hit.tags; f.bitrate = hit.bitrate; f.q = ""; f.m = ""; f.miss = false;
            } else {
                f.miss = true;
            }
            changed = true;
            saveFavs(c, mergeInto(c, favs));
        }
        return changed;
    }

    // Keeps edits the user made while we were looking things up (by name).
    private static List<Station> mergeInto(Context c, List<Station> resolved) {
        List<Station> now = favs(c);
        for (Station n : now) {
            if (!n.url.isEmpty()) continue;
            for (Station r : resolved) if (r.name.equals(n.name)) {
                n.url = r.url; n.favicon = r.favicon; n.dir = r.dir; n.country = r.country;
                n.tags = r.tags; n.bitrate = r.bitrate; n.q = r.q; n.m = r.m; n.miss = r.miss;
            }
        }
        return now;
    }

    /** Blocking. Turns a .pls / .m3u playlist link into the stream inside it; other links come back unchanged. */
    public static String resolvePlaylist(String url) {
        String path = url.toLowerCase(Locale.US).replaceAll("[?#].*$", "");
        if (!path.endsWith(".pls") && !path.endsWith(".m3u")) return url;
        try {
            String body = new String(get(url, 64 * 1024), "UTF-8");
            for (String line : body.split("\\r?\\n")) {
                String l = line.trim();
                int eq = l.indexOf('=');
                if (l.toLowerCase(Locale.US).startsWith("file") && eq > 0) l = l.substring(eq + 1).trim();
                if (l.startsWith("http://") || l.startsWith("https://")) return l;
            }
        } catch (Exception ignored) { }
        return url;
    }

    // ---- logos ----
    private static final LruCache<String, Bitmap> LOGOS = new LruCache<>(40);

    public static Bitmap cachedLogo(String url) { return url == null || url.isEmpty() ? null : LOGOS.get(url); }

    private static java.io.File logoFile(String url) {
        return app == null ? null : new java.io.File(app.getCacheDir(), "logo_" + Integer.toHexString(url.hashCode()) + ".png");
    }

    /** Blocking. Memory, then disk, then network. */
    public static Bitmap logo(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = LOGOS.get(url);
        if (b != null) return b;
        java.io.File f = logoFile(url);
        if (f != null && f.exists()) {
            b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) { LOGOS.put(url, b); return b; }
        }
        try {
            byte[] data = get(url, 2 * 1024 * 1024);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 192 && o.outHeight / (sample * 2) >= 192) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            if (b.getWidth() > 256 || b.getHeight() > 256) {
                float k = 256f / Math.max(b.getWidth(), b.getHeight());
                b = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
            }
            LOGOS.put(url, b);
            if (f != null) {
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
                catch (Exception ignored) { }
            }
            return b;
        } catch (Throwable t) { return null; }
    }

    public static final String USER_AGENT = "MyRadio/1.1 ( https://github.com/theoneandonlyjovie/radio )";

    static byte[] get(String url, int max) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("Accept", "application/json, */*");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new Exception("too big");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    private Store() { }
}
