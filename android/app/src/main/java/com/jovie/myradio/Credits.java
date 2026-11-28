package com.jovie.myradio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Looks up who played on a song (MusicBrainz), with links out to Discogs and AllMusic. */
public final class Credits {
    private static final String MB = "https://musicbrainz.org/ws/2/";
    private static long lastCall;

    public static void show(Activity a, String artist, String title) {
        TextView body = new TextView(a);
        body.setTextIsSelectable(true);
        body.setTextSize(16);
        body.setLineSpacing(0, 1.2f);
        body.setPadding(56, 24, 56, 24);
        body.setText("Looking up credits for\n" + (artist.isEmpty() ? "" : artist + " – ") + title + "…");
        ScrollView sv = new ScrollView(a);
        sv.addView(body);

        String q = (artist + " " + title).trim();
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title.isEmpty() ? artist : title)
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Discogs", (x, w) -> open(a, "https://www.discogs.com/search/?type=all&q=" + enc(q)))
                .setNegativeButton("AllMusic", (x, w) -> open(a, "https://www.allmusic.com/search/songs/" + enc(q)))
                .show();

        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            CharSequence result;
            try { result = lookup(artist, title); }
            catch (Exception e) { result = "Couldn't reach MusicBrainz right now. Try Discogs or AllMusic below."; }
            CharSequence r = result;
            main.post(() -> { if (d.isShowing()) body.setText(r); });
        }).start();
    }

    private static void open(Activity a, String url) {
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // ---- MusicBrainz ----
    private static CharSequence lookup(String artist, String title) throws Exception {
        String t = title.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]\\s*$", "").trim(); // drop "(Live)", "[Remastered]"
        if (t.isEmpty()) t = title;
        String query = "recording:\"" + esc(t) + "\"" + (artist.isEmpty() ? "" : " AND artist:\"" + esc(artist) + "\"");
        JSONArray recs = json(MB + "recording?fmt=json&limit=10&query=" + enc(query)).optJSONArray("recordings");
        if (recs == null || recs.length() == 0) return "No match found in MusicBrainz. Try Discogs or AllMusic below.";

        // Studio album versions first (they carry the personnel); live bootlegs last.
        List<String> official = new ArrayList<>(), other = new ArrayList<>();
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.getJSONObject(i);
            if (r.optInt("score") < 70) continue;
            boolean off = false;
            JSONArray rs = r.optJSONArray("releases");
            if (rs != null) for (int j = 0; j < rs.length(); j++) {
                JSONObject x = rs.getJSONObject(j);
                if ("Official".equals(x.optString("status"))) { off = true; break; }
            }
            (off ? official : other).add(r.getString("id"));
        }
        List<String> ids = new ArrayList<>(official);
        ids.addAll(other);
        if (ids.size() > 4) ids = new ArrayList<>(ids.subList(0, 4));
        if (ids.isEmpty()) ids.add(recs.getJSONObject(0).getString("id"));

        JSONObject chosen = null;
        Map<String, Set<String>> musicians = new LinkedHashMap<>(), production = new LinkedHashMap<>();
        for (String id : ids) {
            JSONObject rec = json(MB + "recording/" + id + "?fmt=json&inc=artist-rels+work-rels+releases+artist-credits");
            if (chosen == null) chosen = rec;
            Map<String, Set<String>> m = new LinkedHashMap<>(), p = new LinkedHashMap<>();
            collect(rec.optJSONArray("relations"), m, p);
            if (!m.isEmpty()) { chosen = rec; musicians = m; production = p; break; }
            if (production.isEmpty()) production = p;
        }

        // Album info, and album-level credits when the song itself has none.
        JSONObject release = pickRelease(chosen.optJSONArray("releases"));
        boolean albumCredits = false;
        if (release != null && musicians.isEmpty()) {
            JSONObject rel = json(MB + "release/" + release.getString("id") + "?fmt=json&inc=artist-rels");
            Map<String, Set<String>> m = new LinkedHashMap<>(), p = new LinkedHashMap<>();
            collect(rel.optJSONArray("relations"), m, p);
            if (!m.isEmpty()) albumCredits = true;
            musicians = m;
            if (production.isEmpty()) production = p;
        }

        // Writers come from the composition ("work").
        Map<String, Set<String>> writers = new LinkedHashMap<>();
        JSONArray rels = chosen.optJSONArray("relations");
        if (rels != null) for (int i = 0; i < rels.length(); i++) {
            JSONObject r = rels.getJSONObject(i);
            if (!"work".equals(r.optString("target-type")) || r.optJSONObject("work") == null) continue;
            JSONObject work = json(MB + "work/" + r.getJSONObject("work").getString("id") + "?fmt=json&inc=artist-rels");
            JSONArray wr = work.optJSONArray("relations");
            if (wr != null) for (int j = 0; j < wr.length(); j++) {
                JSONObject x = wr.getJSONObject(j);
                String type = x.optString("type");
                if (x.optJSONObject("artist") == null) continue;
                if (type.equals("composer") || type.equals("lyricist") || type.equals("writer") || type.equals("arranger"))
                    add(writers, x.getJSONObject("artist").optString("name"), type);
            }
            break;
        }

        SpannableStringBuilder out = new SpannableStringBuilder();
        out.append(chosen.optString("title"));
        span(out, 0, out.length(), true, 1.15f);
        out.append("\n").append(credit(chosen.optJSONArray("artist-credit")));
        if (release != null) {
            String date = release.optString("date");
            out.append("\nFrom ").append(release.optString("title"))
                    .append(date.length() >= 4 ? " (" + date.substring(0, 4) + ")" : "");
        }
        section(out, albumCredits ? "Musicians (album credits)" : "Musicians", musicians);
        section(out, "Written by", writers);
        section(out, "Production", production);
        if (musicians.isEmpty() && writers.isEmpty() && production.isEmpty())
            out.append("\n\nMusicBrainz knows this song but doesn't list who played on it yet. Discogs usually has the full personnel.");
        out.append("\n\nSource: MusicBrainz. Matches can be off for live or rare recordings.");
        return out;
    }

    private static void collect(JSONArray rels, Map<String, Set<String>> musicians, Map<String, Set<String>> production) throws Exception {
        if (rels == null) return;
        for (int i = 0; i < rels.length(); i++) {
            JSONObject r = rels.getJSONObject(i);
            JSONObject artist = r.optJSONObject("artist");
            if (artist == null) continue;
            String name = artist.optString("name");
            String type = r.optString("type");
            List<String> attrs = new ArrayList<>();
            JSONArray at = r.optJSONArray("attributes");
            if (at != null) for (int j = 0; j < at.length(); j++) {
                String s = at.optString(j);
                if (!s.equals("additional") && !s.equals("guest") && !s.equals("solo")) attrs.add(s);
            }
            switch (type) {
                case "instrument":
                    if (attrs.isEmpty()) add(musicians, name, "instruments"); else for (String s : attrs) add(musicians, name, s);
                    break;
                case "vocal":
                    add(musicians, name, attrs.isEmpty() ? "vocals" : String.join(", ", attrs));
                    break;
                case "performer":
                case "performing orchestra":
                case "conductor":
                case "chorus master":
                case "concertmaster":
                    if (attrs.isEmpty() || type.equals("conductor")) add(musicians, name, type.equals("performer") ? "performer" : type);
                    else for (String s : attrs) add(musicians, name, s);
                    break;
                case "producer": case "engineer": case "mix": case "recording": case "mastering": case "arranger":
                case "orchestrator": case "audio": case "sound":
                    add(production, name, type.equals("mix") ? "mixing" : type.equals("recording") ? "recording engineer" : type);
                    break;
                default:
            }
        }
    }

    private static JSONObject pickRelease(JSONArray releases) throws Exception {
        if (releases == null || releases.length() == 0) return null;
        JSONObject best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i);
            if (!"Official".equals(r.optString("status"))) continue;
            String d = r.optString("date");
            if (best == null || (!d.isEmpty() && (best.optString("date").isEmpty() || d.compareTo(best.optString("date")) < 0))) best = r;
        }
        return best != null ? best : releases.getJSONObject(0);
    }

    private static String credit(JSONArray ac) {
        if (ac == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ac.length(); i++) {
            JSONObject x = ac.optJSONObject(i);
            if (x == null) continue;
            b.append(x.optString("name")).append(x.optString("joinphrase"));
        }
        return b.toString();
    }

    private static void add(Map<String, Set<String>> m, String name, String role) {
        Set<String> s = m.get(name);
        if (s == null) { s = new LinkedHashSet<>(); m.put(name, s); }
        s.add(role);
    }

    private static void section(SpannableStringBuilder out, String head, Map<String, Set<String>> m) {
        if (m.isEmpty()) return;
        out.append("\n\n");
        int start = out.length();
        out.append(head);
        span(out, start, out.length(), true, 1.0f);
        for (Map.Entry<String, Set<String>> e : m.entrySet())
            out.append("\n").append(e.getKey()).append(" — ").append(String.join(", ", e.getValue()));
    }

    private static void span(SpannableStringBuilder b, int s, int e, boolean bold, float size) {
        if (bold) b.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (size != 1f) b.setSpan(new RelativeSizeSpan(size), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    /** MusicBrainz allows about one request a second. */
    private static synchronized JSONObject json(String url) throws Exception {
        long wait = 1100 - (System.currentTimeMillis() - lastCall);
        if (wait > 0) Thread.sleep(wait);
        lastCall = System.currentTimeMillis();
        return new JSONObject(new String(Store.get(url, 1024 * 1024), "UTF-8"));
    }

    private Credits() { }
}
