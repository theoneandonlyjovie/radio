package com.jovie.myradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plays one live stream at a time; drives the media notification, lock screen and widget. */
public class PlayerService extends Service {
    public static final String ACTION_PLAY_INDEX = "com.jovie.myradio.PLAY_INDEX";
    public static final String ACTION_PLAY_STATION = "com.jovie.myradio.PLAY_STATION";
    public static final String ACTION_TOGGLE = "com.jovie.myradio.TOGGLE";
    public static final String ACTION_NEXT = "com.jovie.myradio.NEXT";
    public static final String ACTION_PREV = "com.jovie.myradio.PREV";
    public static final String ACTION_STOP = "com.jovie.myradio.STOP";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_STATION = "station";

    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private WifiManager.WifiLock wifi;
    private Bitmap art;
    private boolean noisyRegistered;
    private int retries;

    /** Starts the service with an action; safe to call from the app, notification or widget. */
    public static void send(Context c, Intent i) {
        i.setClass(c, PlayerService.class);
        c.startForegroundService(i);
    }

    public static PendingIntent pending(Context c, String action, int req) {
        Intent i = new Intent(c, PlayerService.class).setAction(action);
        return PendingIntent.getForegroundService(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "MyRadio");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { step(1); }
            @Override public void onSkipToPrevious() { step(-1); }
        });
        session.setSessionActivity(openApp());
        session.setActive(true);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MyRadio:stream");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Must go foreground right away whenever we were started with startForegroundService.
        startForeground(NOTIF_ID, buildNotification());
        String a = intent == null ? null : intent.getAction();
        if (a == null) a = "";
        switch (a) {
            case ACTION_PLAY_INDEX: {
                List<Store.Station> favs = Store.favs(this);
                int i = intent.getIntExtra(EXTRA_INDEX, -1);
                if (i >= 0 && i < favs.size()) play(favs.get(i)); else idle();
                break;
            }
            case ACTION_PLAY_STATION: {
                String json = intent.getStringExtra(EXTRA_STATION);
                try { play(Store.Station.fromJson(new org.json.JSONObject(json))); } catch (Exception e) { idle(); }
                break;
            }
            case ACTION_TOGGLE:
                if (player != null && Store.playing(this)) pause(); else resume();
                break;
            case ACTION_NEXT: step(1); break;
            case ACTION_PREV: step(-1); break;
            case ACTION_STOP: stopAll(); break;
            default: idle();
        }
        return START_NOT_STICKY;
    }

    private void idle() {
        if (player == null) { stopForeground(STOP_FOREGROUND_DETACH); refresh(); }
    }

    private void resume() {
        Store.Station cur = Store.current(this);
        if (cur == null || cur.url.isEmpty()) {
            List<Store.Station> favs = Store.favs(this);
            cur = null;
            for (Store.Station s : favs) if (!s.url.isEmpty()) { cur = s; break; }
        }
        if (cur == null) { Store.setState(this, "Open the app to pick a station", false); idle(); return; }
        play(cur);
    }

    private void step(int d) {
        List<Store.Station> favs = Store.favs(this);
        if (favs.isEmpty()) return;
        int i = Store.indexOfCurrent(this, favs);
        for (int n = 0; n < favs.size(); n++) {
            i = ((i + d) % favs.size() + favs.size()) % favs.size();
            if (!favs.get(i).url.isEmpty()) { play(favs.get(i)); return; }
        }
    }

    private void play(Store.Station st) {
        if (st.url.isEmpty()) { Store.setState(this, "No stream for this station yet", false); return; }
        Store.Station prev = Store.current(this);
        if (prev == null || !prev.favicon.equals(st.favicon)) art = Store.cachedLogo(st.favicon);
        Store.setCurrent(this, st);
        retries = 0;
        if (!requestFocus()) { Store.setState(this, "Another app is using audio", false); refresh(); return; }
        startStream(st);
        loadArt(st);
    }

    private void startStream(Store.Station st) {
        releasePlayer();
        Store.setState(this, "Tuning in…", true);
        refresh();
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(m -> {
            if (m != player) return;
            m.start();
            Store.setState(this, "Live", true);
            refresh();
        });
        mp.setOnInfoListener((m, what, extra) -> {
            if (m != player) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) { Store.setState(this, "Buffering…", true); refresh(); }
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) { Store.setState(this, "Live", true); refresh(); }
            return false;
        });
        mp.setOnErrorListener((m, what, extra) -> { if (m == player) onStreamFailed(); return true; });
        mp.setOnCompletionListener(m -> { if (m == player) onStreamFailed(); }); // live streams shouldn't end
        try {
            mp.setDataSource(st.url);
            mp.prepareAsync();
            if (wifi != null && !wifi.isHeld()) wifi.acquire();
            registerNoisy();
        } catch (Exception e) {
            onStreamFailed();
        }
    }

    private void onStreamFailed() {
        Store.Station cur = Store.current(this);
        if (cur != null && retries < 2) {
            retries++;
            Store.setState(this, "Reconnecting…", true);
            refresh();
            main.postDelayed(() -> { if (Store.playing(this)) startStream(cur); }, 2000L * retries);
            return;
        }
        releasePlayer();
        Store.setState(this, "Couldn't play this stream", false);
        refresh();
        stopForeground(STOP_FOREGROUND_DETACH);
    }

    private void pause() {
        // Live radio: drop the stream so resuming starts at "now", not where we left off.
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        Store.setState(this, "Paused", false);
        refresh();
        stopForeground(STOP_FOREGROUND_DETACH);
    }

    private void stopAll() {
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        Store.setState(this, "Paused", false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            MediaPlayer m = player;
            player = null;
            try { m.reset(); } catch (Exception ignored) { }
            m.release();
        }
        if (wifi != null && wifi.isHeld()) wifi.release();
        unregisterNoisy();
    }

    // ---- audio focus & headphones ----
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && player != null) player.setVolume(0.3f, 0.3f);
        else if (change == AudioManager.AUDIOFOCUS_GAIN && player != null) player.setVolume(1f, 1f);
    };

    private boolean requestFocus() {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .setWillPauseWhenDucked(false)
                    .build();
        }
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() { if (focus != null) audio.abandonAudioFocusRequest(focus); }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, f);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        noisyRegistered = false;
    }

    // ---- notification, lock screen, widget ----
    private void loadArt(Store.Station st) {
        if (st.favicon.isEmpty()) { art = null; refresh(); return; }
        bg.execute(() -> {
            Bitmap b = Store.logo(st.favicon);
            main.post(() -> {
                Store.Station cur = Store.current(this);
                if (cur != null && cur.favicon.equals(st.favicon)) { art = b; refresh(); RadioWidget.updateAll(this); }
            });
        });
    }

    private void refresh() {
        boolean playing = Store.playing(this);
        Store.Station cur = Store.current(this);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, cur == null ? "My Radio" : cur.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, Store.status(this))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1);
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        session.setMetadata(md.build());
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Store.Station cur = Store.current(this);
        boolean playing = Store.playing(this);
        Notification.Action prev = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_prev), "Previous",
                pending(this, ACTION_PREV, 1)).build();
        Notification.Action toggle = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pause" : "Play", pending(this, ACTION_TOGGLE, 2)).build();
        Notification.Action next = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_next), "Next",
                pending(this, ACTION_NEXT, 3)).build();
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle(cur == null ? "My Radio" : cur.name)
                .setContentText(Store.status(this))
                .setContentIntent(openApp())
                .setDeleteIntent(pending(this, ACTION_STOP, 4))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(prev).addAction(toggle).addAction(next)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        session.release();
        bg.shutdownNow();
        if (Store.playing(this)) Store.setState(this, "Paused", false);
        super.onDestroy();
    }
}
