package com.jovie.myradio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(3);
    private LinearLayout favList, resultList;
    private TextView nName, nStatus, msg;
    private ImageView nLogo;
    private ImageButton nToggle;
    private EditText q;
    private List<Store.Station> results = new ArrayList<>();
    private boolean resolving;

    private final BroadcastReceiver state = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { renderNow(); renderFavs(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        // Keep content clear of the status bar, nav bar and keyboard (Android 11+ draws edge to edge).
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            View root = findViewById(R.id.root);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                View focus = getCurrentFocus();
                if (focus != null && ime.bottom > 0) v.post(() -> scrollToShow(focus));
                return android.view.WindowInsets.CONSUMED;
            });
        }
        findViewById(R.id.n_credits).setOnClickListener(v -> showCredits());
        findViewById(R.id.n_song).setOnClickListener(v -> showCredits());
        favList = findViewById(R.id.favList);
        resultList = findViewById(R.id.resultList);
        nName = findViewById(R.id.n_name);
        nStatus = findViewById(R.id.n_status);
        nLogo = findViewById(R.id.n_logo);
        nToggle = findViewById(R.id.n_toggle);
        msg = findViewById(R.id.msg);
        q = findViewById(R.id.q);
        for (int id : new int[]{R.id.q, R.id.addName, R.id.addUrl}) {
            findViewById(id).setOnFocusChangeListener((v, has) -> { if (has) v.postDelayed(() -> scrollToShow(v), 350); });
        }

        nToggle.setOnClickListener(v -> PlayerService.send(this, new Intent(PlayerService.ACTION_TOGGLE)));
        findViewById(R.id.n_next).setOnClickListener(v -> PlayerService.send(this, new Intent(PlayerService.ACTION_NEXT)));
        findViewById(R.id.go).setOnClickListener(v -> search());
        findViewById(R.id.addTest).setOnClickListener(v -> addByLink(false));
        findViewById(R.id.addSave).setOnClickListener(v -> addByLink(true));
        q.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { search(); return true; }
            return false;
        });

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(Store.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(state, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(state, f);
        renderNow();
        renderFavs();
        resolve();
    }

    @Override protected void onStop() {
        try { unregisterReceiver(state); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override protected void onDestroy() { bg.shutdownNow(); super.onDestroy(); }

    private void resolve() {
        if (resolving) return;
        resolving = true;
        bg.execute(() -> {
            Store.resolveMissing(getApplicationContext());
            main.post(() -> { resolving = false; if (!isFinishing()) renderFavs(); });
        });
    }

    // ---- now playing ----
    private void renderNow() {
        Store.Station cur = Store.current(this);
        boolean playing = Store.playing(this);
        nName.setText(cur == null ? "Pick a station" : cur.name);
        String s = Store.status(this);
        nStatus.setText(cur == null ? "Tap a favourite below" : (s.isEmpty() ? "Paused" : s));
        nStatus.setTextColor(getColor(playing ? R.color.amber : R.color.dim));
        nToggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        setLogo(nLogo, cur == null ? "" : cur.favicon);
        String song = Store.songLine(this);
        findViewById(R.id.n_songRow).setVisibility(song.isEmpty() || cur == null ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.n_song)).setText(song);
    }

    private void showCredits() {
        String title = Store.songTitle(this), artist = Store.songArtist(this);
        if (title.isEmpty() && artist.isEmpty()) return;
        Credits.show(this, artist, title);
    }

    /** Scrolls the list so the field being typed in sits above the keyboard. */
    private void scrollToShow(View focus) {
        android.widget.ScrollView sv = findViewById(R.id.scroll);
        android.graphics.Rect r = new android.graphics.Rect();
        focus.getDrawingRect(r);
        try { sv.offsetDescendantRectToMyCoords(focus, r); } catch (IllegalArgumentException e) { return; }
        int margin = (int) (24 * getResources().getDisplayMetrics().density);
        int bottomVisible = sv.getScrollY() + sv.getHeight();
        if (r.bottom + margin > bottomVisible) sv.smoothScrollTo(0, r.bottom + margin - sv.getHeight());
        else if (r.top < sv.getScrollY()) sv.smoothScrollTo(0, Math.max(0, r.top - margin));
    }

    // ---- favourites ----
    private void renderFavs() {
        List<Store.Station> favs = Store.favs(this);
        Store.Station cur = Store.current(this);
        favList.removeAllViews();
        if (favs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No favourites yet. Search below and tap the star to save one.");
            t.setTextColor(getColor(R.color.dim));
            t.setPadding(0, 24, 0, 24);
            favList.addView(t);
            return;
        }
        for (int i = 0; i < favs.size(); i++) {
            final int idx = i;
            Store.Station s = favs.get(i);
            View row = row(favList, s);
            boolean isCur = cur != null && !s.url.isEmpty() && s.url.equals(cur.url);
            ((TextView) row.findViewById(R.id.r_name)).setTextColor(getColor(isCur ? R.color.amber : R.color.ink));
            String sub = s.url.isEmpty() ? (s.miss ? "Not found yet · tap to search" : "Finding stream…")
                    : (s.sub().isEmpty() ? "Saved station" : s.sub());
            ((TextView) row.findViewById(R.id.r_sub)).setText(sub);
            row.setOnClickListener(v -> {
                if (!s.url.isEmpty()) PlayerService.send(this, new Intent(PlayerService.ACTION_PLAY_INDEX).putExtra(PlayerService.EXTRA_INDEX, idx));
                else if (s.miss) findFor(s);
                else resolve();
            });
            row.setOnLongClickListener(v -> { favMenu(idx); return true; });
            favList.addView(row);
        }
    }

    private void favMenu(int idx) {
        List<Store.Station> favs = Store.favs(this);
        if (idx >= favs.size()) return;
        Store.Station s = favs.get(idx);
        String[] items = {"Move up", "Move down", "Find a different stream", "Remove", "Change stream link"};
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(items, (d, which) -> {
                    List<Store.Station> f = Store.favs(this);
                    if (idx >= f.size()) return;
                    if (which == 0 && idx > 0) { f.add(idx - 1, f.remove(idx)); Store.saveFavs(this, f); }
                    if (which == 1 && idx < f.size() - 1) { f.add(idx + 1, f.remove(idx)); Store.saveFavs(this, f); }
                    if (which == 2) findFor(s);
                    if (which == 3) { f.remove(idx); Store.saveFavs(this, f); }
                    if (which == 4) { editLink(idx); return; }
                    renderFavs();
                    renderResults();
                    RadioWidget.updateAll(this);
                })
                .show();
    }

    // ---- add / change by link ----
    private static boolean looksLikeUrl(String u) { return u.startsWith("http://") || u.startsWith("https://"); }

    private void addByLink(boolean save) {
        EditText nameIn = findViewById(R.id.addName), urlIn = findViewById(R.id.addUrl);
        TextView am = findViewById(R.id.addMsg);
        String url = urlIn.getText().toString().trim();
        String name = nameIn.getText().toString().trim();
        am.setVisibility(View.VISIBLE);
        if (!looksLikeUrl(url)) { am.setText("Paste a link that starts with http:// or https://"); urlIn.requestFocus(); return; }
        if (name.isEmpty()) { String h = android.net.Uri.parse(url).getHost(); name = h == null ? "My station" : h; }
        final String finalName = name;
        am.setText("Checking the link…");
        bg.execute(() -> {
            String stream = Store.resolvePlaylist(url);
            main.post(() -> {
                Store.Station s = new Store.Station();
                s.name = finalName; s.url = stream;
                if (save) {
                    List<Store.Station> f = Store.favs(this);
                    f.add(s);
                    Store.saveFavs(this, f);
                    nameIn.setText(""); urlIn.setText("");
                    am.setText("Added " + finalName + " to your favourites.");
                    renderFavs();
                    RadioWidget.updateAll(this);
                } else {
                    am.setText("Playing it now. If you hear it, tap Add to favourites.");
                }
                PlayerService.send(this, new Intent(PlayerService.ACTION_PLAY_STATION).putExtra(PlayerService.EXTRA_STATION, toJson(s)));
            });
        });
    }

    private void editLink(int idx) {
        List<Store.Station> favs = Store.favs(this);
        if (idx >= favs.size()) return;
        Store.Station s = favs.get(idx);
        EditText in = new EditText(this);
        in.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
        in.setText(s.url);
        in.setSelectAllOnFocus(true);
        in.setPadding(48, 32, 48, 32);
        new AlertDialog.Builder(this)
                .setTitle("Stream link for " + s.name)
                .setView(in)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String url = in.getText().toString().trim();
                    if (!looksLikeUrl(url)) return;
                    bg.execute(() -> {
                        String stream = Store.resolvePlaylist(url);
                        main.post(() -> {
                            List<Store.Station> f = Store.favs(this);
                            if (idx >= f.size()) return;
                            Store.Station st = f.get(idx);
                            st.url = stream; st.q = ""; st.m = ""; st.miss = false;
                            Store.saveFavs(this, f);
                            renderFavs();
                            PlayerService.send(this, new Intent(PlayerService.ACTION_PLAY_INDEX).putExtra(PlayerService.EXTRA_INDEX, idx));
                        });
                    });
                })
                .show();
    }

    private void findFor(Store.Station s) {
        String term = !s.q.isEmpty() ? s.q : s.name.replaceAll("\\s[\\d.]+.*$", "");
        q.setText(term);
        search();
        q.requestFocus();
    }

    // ---- search ----
    private void search() {
        String term = q.getText().toString().trim();
        if (term.isEmpty()) return;
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(q.getWindowToken(), 0);
        msg.setVisibility(View.VISIBLE);
        msg.setText("Searching…");
        resultList.removeAllViews();
        bg.execute(() -> {
            List<Store.Station> r = Store.lookup(term);
            main.post(() -> {
                if (isFinishing()) return;
                if (r == null) { msg.setText("Couldn't reach the station directory. Check your connection."); return; }
                results = r.size() > 25 ? new ArrayList<>(r.subList(0, 25)) : r;
                msg.setText(results.isEmpty() ? "No stations found. Try the call letters or a shorter name." : "Tap to listen · tap the star to save");
                renderResults();
            });
        });
    }

    private boolean isFav(Store.Station s) {
        for (Store.Station f : Store.favs(this)) if (f.url.equals(s.url)) return true;
        return false;
    }

    private void renderResults() {
        resultList.removeAllViews();
        for (Store.Station s : results) {
            View row = row(resultList, s);
            ((TextView) row.findViewById(R.id.r_sub)).setText(s.sub());
            ImageButton star = row.findViewById(R.id.r_star);
            star.setVisibility(View.VISIBLE);
            boolean fav = isFav(s);
            star.setImageResource(fav ? R.drawable.ic_star : R.drawable.ic_star_off);
            star.setContentDescription(fav ? "Remove from favourites" : "Add to favourites");
            star.setOnClickListener(v -> {
                List<Store.Station> f = Store.favs(this);
                if (isFav(s)) {
                    for (int i = f.size() - 1; i >= 0; i--) if (f.get(i).url.equals(s.url)) f.remove(i);
                } else {
                    f.add(s);
                }
                Store.saveFavs(this, f);
                renderFavs();
                renderResults();
            });
            row.setOnClickListener(v -> PlayerService.send(this,
                    new Intent(PlayerService.ACTION_PLAY_STATION).putExtra(PlayerService.EXTRA_STATION, toJson(s))));
            resultList.addView(row);
        }
    }

    private static String toJson(Store.Station s) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("name", s.name); o.put("url", s.url); o.put("favicon", s.favicon); o.put("dir", s.dir);
            o.put("tags", s.tags); o.put("country", s.country); o.put("bitrate", s.bitrate);
        } catch (Exception ignored) { }
        return o.toString();
    }

    // ---- shared row + logos ----
    private View row(LinearLayout parent, Store.Station s) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_station, parent, false);
        ((TextView) row.findViewById(R.id.r_name)).setText(s.name);
        setLogo(row.findViewById(R.id.r_logo), s.favicon);
        return row;
    }

    private void setLogo(ImageView iv, String url) {
        iv.setTag(url);
        Bitmap b = Store.cachedLogo(url);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageResource(R.drawable.ic_launcher_fg);
        if (url == null || url.isEmpty()) return;
        bg.execute(() -> {
            Bitmap got = Store.logo(url);
            if (got != null) main.post(() -> { if (url.equals(iv.getTag())) iv.setImageBitmap(got); });
        });
    }
}
