package com.jovie.myradio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

/** Home-screen widget: current station, status, previous / play-pause / next. */
public class RadioWidget extends AppWidgetProvider {

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        m.updateAppWidget(ids, build(c));
    }

    public static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, RadioWidget.class));
        if (ids != null && ids.length > 0) m.updateAppWidget(ids, build(c));
    }

    private static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        Store.Station cur = Store.current(c);
        boolean playing = Store.playing(c);
        String status = Store.status(c);

        v.setTextViewText(R.id.w_name, cur == null ? "My Radio" : cur.name);
        v.setTextViewText(R.id.w_status, status.isEmpty() ? (cur == null ? "Tap play to start" : "Paused") : status);
        v.setImageViewResource(R.id.w_toggle, playing ? R.drawable.ic_pause : R.drawable.ic_play);

        Bitmap logo = cur == null ? null : Store.cachedLogo(cur.favicon);
        if (logo != null) v.setImageViewBitmap(R.id.w_logo, logo);
        else v.setImageViewResource(R.id.w_logo, R.drawable.ic_launcher_fg);

        v.setOnClickPendingIntent(R.id.w_toggle, PlayerService.pending(c, PlayerService.ACTION_TOGGLE, 12));
        v.setOnClickPendingIntent(R.id.w_next, PlayerService.pending(c, PlayerService.ACTION_NEXT, 13));
        v.setOnClickPendingIntent(R.id.w_prev, PlayerService.pending(c, PlayerService.ACTION_PREV, 11));
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }
}
