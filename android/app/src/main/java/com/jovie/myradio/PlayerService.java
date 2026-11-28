package com.jovie.myradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays one live stream at a time. Reads the song the station sends (ICY / HLS metadata) and passes it
 * to the media session, which is what the lock screen, Bluetooth car displays and the widget show.
 */
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
    private ExoPlayer player;
    private MediaSession session;
    private Bitmap art;
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

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Store.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);              // handles calls, other apps and ducking
        player.setHandleAudioBecomingNoisy(true); // pause when headphones / car disconnect
        player.setWakeMode(C.WAKE_MODE_NETWORK);   // keep Wi-Fi and CPU awake while streaming
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (!Store.playing(PlayerService.this)) return;
                if (state == Player.STATE_BUFFERING) setStatus("Buffering…");
                else if (state == Player.STATE_READY && player.getPlayWhenReady()) { retries = 0; setStatus("Live"); }
                else if (state == Player.STATE_ENDED) onStreamFailed(); // live streams shouldn't end
            }

            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST || !Store.playing(PlayerService.this)) return;
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    // A call or another app. Stay ready so we can pick up again when it's done.
                    setStatus(playWhenReady ? "Live" : "Paused for another app");
                } else if (!playWhenReady) {
                    // Headphones unplugged or Bluetooth disconnected.
                    pause();
                }
            }

            @Override public void onPlayerError(PlaybackException error) { onStreamFailed(); }

            @Override public void onMediaMetadataChanged(androidx.media3.common.MediaMetadata md) {
                onSong(md.title == null ? "" : md.title.toString(), md.artist == null ? "" : md.artist.toString());
            }
        });
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
                if (Store.playing(this) && player.getPlayWhenReady()) pause(); else resume();
                break;
            case ACTION_NEXT: step(1); break;
            case ACTION_PREV: step(-1); break;
            case ACTION_STOP: stopAll(); break;
            default: idle();
        }
        return START_NOT_STICKY;
    }

    private void idle() {
        if (!Store.playing(this)) { stopForeground(STOP_FOREGROUND_DETACH); refresh(); }
    }

    private void resume() {
        Store.Station cur = Store.current(this);
        if (cur == null || cur.url.isEmpty()) {
            cur = null;
            for (Store.Station s : Store.favs(this)) if (!s.url.isEmpty()) { cur = s; break; }
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
        if (st.url.isEmpty()) { Store.setState(this, "No stream for this station yet", false); refresh(); return; }
        Store.Station prev = Store.current(this);
        if (prev == null || !prev.favicon.equals(st.favicon)) art = Store.cachedLogo(st.favicon);
        Store.setCurrent(this, st);
        Store.setSong(this, "", "");
        retries = 0;
        startStream(st);
        loadArt(st);
    }

    private void startStream(Store.Station st) {
        main.removeCallbacksAndMessages(null);
        Store.setState(this, "Tuning in…", true);
        refresh();
        player.setMediaItem(MediaItem.fromUri(st.url));
        player.prepare();
        player.play();
    }

    private void onStreamFailed() {
        Store.Station cur = Store.current(this);
        if (cur != null && Store.playing(this) && retries < 3) {
            retries++;
            setStatus("Reconnecting…");
            main.postDelayed(() -> { if (Store.playing(this)) startStream(cur); }, 2000L * retries);
            return;
        }
        player.stop();
        Store.setState(this, "Couldn't play this stream", false);
        refresh();
        stopForeground(STOP_FOREGROUND_DETACH);
    }

    private void pause() {
        // Live radio: drop the stream so resuming starts at "now", not where we left off.
        main.removeCallbacksAndMessages(null);
        Store.setState(this, "Paused", false);
        player.stop();
        refresh();
        stopForeground(STOP_FOREGROUND_DETACH);
    }

    private void stopAll() {
        main.removeCallbacksAndMessages(null);
        Store.setState(this, "Paused", false);
        player.stop();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void setStatus(String s) {
        if (s.equals(Store.status(this))) return;
        Store.setState(this, s, true);
        refresh();
    }

    // ---- song info from the stream ----
    private void onSong(String rawTitle, String rawArtist) {
        String title = clean(rawTitle), artist = clean(rawArtist);
        // Most stations send one line: "Artist - Title".
        if (artist.isEmpty()) {
            int dash = title.indexOf(" - ");
            if (dash > 0) { artist = title.substring(0, dash).trim(); title = title.substring(dash + 3).trim(); }
        }
        Store.Station cur = Store.current(this);
        // Skip lines that are just the station's own name.
        if (cur != null && artist.isEmpty() && title.equalsIgnoreCase(cur.name)) title = "";
        if (artist.equals(Store.songArtist(this)) && title.equals(Store.songTitle(this))) return;
        Store.setSong(this, artist, title);
        Store.setState(this, Store.status(this), Store.playing(this)); // tells app + widget
        refresh();
    }

    private static String clean(String s) {
        s = s == null ? "" : s.replace('\u0000', ' ').trim();
        if (s.equals("-") || s.equalsIgnoreCase("unknown") || s.equalsIgnoreCase("unknown - unknown")) return "";
        return s;
    }

    // ---- notification, lock screen, car, widget ----
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
        String station = cur == null ? "My Radio" : cur.name;
        String songTitle = Store.songTitle(this), songArtist = Store.songArtist(this);
        boolean hasSong = !songTitle.isEmpty() || !songArtist.isEmpty();

        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());

        // Car displays show Title / Artist / Album: song, artist, station.
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, hasSong ? (songTitle.isEmpty() ? songArtist : songTitle) : station)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, hasSong ? (songTitle.isEmpty() ? station : songArtist) : Store.status(this))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, station)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, hasSong ? Store.songLine(this) : station)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, station)
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
        String song = Store.songLine(this);
        Notification.Action prev = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_prev), "Previous", pending(this, ACTION_PREV, 1)).build();
        Notification.Action toggle = new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pause" : "Play", pending(this, ACTION_TOGGLE, 2)).build();
        Notification.Action next = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_next), "Next", pending(this, ACTION_NEXT, 3)).build();
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle(song.isEmpty() ? (cur == null ? "My Radio" : cur.name) : song)
                .setContentText(song.isEmpty() ? Store.status(this) : (cur == null ? "" : cur.name))
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
        player.release();
        session.release();
        bg.shutdownNow();
        if (Store.playing(this)) Store.setState(this, "Paused", false);
        super.onDestroy();
    }
}
