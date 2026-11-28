package com.jovie.myradio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

/** One-row widget: current station / song, previous, play-pause, next. */
public class RadioWidget extends AppWidgetProvider {
    private static final int[] SLOT = {R.id.q0, R.id.q1, R.id.q2, R.id.q3};
    private static final int[] SLOT_IMG = {R.id.q0_img, R.id.q1_img, R.id.q2_img, R.id.q3_img};
    private static final int[] SLOT_TXT = {R.id.q0_txt, R.id.q1_txt, R.id.q2_txt, R.id.q3_txt};
    private static volatile boolean loadingLogos;

    /** Taller widget: the same controls plus one-tap buttons for the top 4 favourites. */
    public static class Favs extends RadioWidget {
        @Override boolean withFavs() { return true; }
    }

    boolean withFavs() { return false; }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        m.updateAppWidget(ids, build(c, withFavs()));
        if (withFavs()) fetchLogos(c);
    }

    public static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] small = m.getAppWidgetIds(new ComponentName(c, RadioWidget.class));
        if (small != null && small.length > 0) m.updateAppWidget(small, build(c, false));
        int[] big = m.getAppWidgetIds(new ComponentName(c, Favs.class));
        if (big != null && big.length > 0) { m.updateAppWidget(big, build(c, true)); fetchLogos(c); }
    }

    // Station logos for the favourites row; redraws once they're in.
    private static void fetchLogos(Context c) {
        if (loadingLogos) return;
        List<Store.Station> favs = Store.favs(c);
        boolean missing = false;
        for (int i = 0; i < Math.min(4, favs.size()); i++)
            if (!favs.get(i).favicon.isEmpty() && Store.cachedLogo(favs.get(i).favicon) == null) missing = true;
        if (!missing) return;
        loadingLogos = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            for (int i = 0; i < Math.min(4, favs.size()); i++) Store.logo(favs.get(i).favicon);
            AppWidgetManager m = AppWidgetManager.getInstance(app);
            int[] big = m.getAppWidgetIds(new ComponentName(app, Favs.class));
            if (big != null && big.length > 0) m.updateAppWidget(big, build(app, true));
            loadingLogos = false;
        }).start();
    }

    private static RemoteViews build(Context c, boolean favs) {
        RemoteViews v = new RemoteViews(c.getPackageName(), favs ? R.layout.widget_favs : R.layout.widget);
        Store.Station cur = Store.current(c);
        boolean playing = Store.playing(c);
        String status = Store.status(c);
        String song = Store.songLine(c);

        v.setTextViewText(R.id.w_name, cur == null ? "My Radio" : cur.name);
        String line = !song.isEmpty() && playing ? song
                : status.isEmpty() ? (cur == null ? "Tap play to start" : "Paused") : status;
        v.setTextViewText(R.id.w_status, line);
        v.setImageViewResource(R.id.w_toggle, playing ? R.drawable.ic_pause : R.drawable.ic_play);

        Bitmap logo = cur == null ? null : Store.cachedLogo(cur.favicon);
        if (logo != null) v.setImageViewBitmap(R.id.w_logo, logo);
        else v.setImageViewResource(R.id.w_logo, R.drawable.ic_launcher_fg);

        v.setOnClickPendingIntent(R.id.w_toggle, PlayerService.pending(c, PlayerService.ACTION_TOGGLE, 12));
        v.setOnClickPendingIntent(R.id.w_next, PlayerService.pending(c, PlayerService.ACTION_NEXT, 13));
        v.setOnClickPendingIntent(R.id.w_prev, PlayerService.pending(c, PlayerService.ACTION_PREV, 11));
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(favs ? R.id.w_top : R.id.w_root, openPi);

        if (favs) {
            List<Store.Station> list = Store.favs(c);
            int curIdx = Store.indexOfCurrent(c, list);
            for (int i = 0; i < 4; i++) {
                if (i >= list.size()) { v.setViewVisibility(SLOT[i], View.INVISIBLE); continue; }
                Store.Station s = list.get(i);
                v.setViewVisibility(SLOT[i], View.VISIBLE);
                v.setTextViewText(SLOT_TXT[i], shortName(s.name));
                v.setTextColor(SLOT_TXT[i], i == curIdx && playing ? 0xFFFF9F43 : 0xFFF3EAD9);
                Bitmap b = Store.cachedLogo(s.favicon);
                if (b != null) v.setImageViewBitmap(SLOT_IMG[i], b);
                else v.setImageViewResource(SLOT_IMG[i], R.drawable.ic_launcher_fg);
                Intent play = new Intent(c, PlayerService.class).setAction(PlayerService.ACTION_PLAY_INDEX)
                        .putExtra(PlayerService.EXTRA_INDEX, i);
                v.setOnClickPendingIntent(SLOT[i], PendingIntent.getForegroundService(c, 20 + i, play,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
        }
        return v;
    }

    // "WFMU 91.1" -> "WFMU", "Just Jazz – Pat Metheny" -> "Pat Metheny"
    private static String shortName(String n) {
        int dash = Math.max(n.lastIndexOf(" – "), n.lastIndexOf(" - "));
        if (dash > 0) return n.substring(dash + 3).trim();
        String first = n.split("\\s+")[0];
        return first.length() >= 3 && first.matches("[A-Z0-9]+") ? first : n;
    }
}
