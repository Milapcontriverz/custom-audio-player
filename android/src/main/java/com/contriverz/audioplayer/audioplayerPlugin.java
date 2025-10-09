package com.contriverz.audioplayer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.PluginMethod;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.C;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(name = "AudioPlayer")
public class AudioPlayerPlugin extends Plugin {

    private static final String TAG = "AudioPlayerPlugin";
    private static final String CHANNEL_ID = "audio_player_channel";
    private static final int NOTIFICATION_ID = 1;

    private ExoPlayer exoPlayer;
    private List<AudioTrack> trackQueue = new ArrayList<>();
    private int currentIndex = 0;
    private String repeatMode = "none"; // none, one, all
    private boolean shuffleMode = false;
    private Handler mainHandler;
    private MediaSessionCompat mediaSession;

    private long currentPlaybackPosition = 0;

    private Handler positionHandler = new Handler(Looper.getMainLooper());
    private Runnable positionRunnable = new Runnable() {
        @Override
        public void run() {
            if (exoPlayer != null && exoPlayer.isPlaying()) sendPlayerState();
            positionHandler.postDelayed(this, 1000); // update every 1 sec
        }
    };

    @Override
    public void load() {
        super.load();
        mainHandler = new Handler(Looper.getMainLooper());

        exoPlayer = new ExoPlayer.Builder(getContext()).build();
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) sendPlayerState();
                if (state == Player.STATE_ENDED) handleTrackCompletion();
            }

            @Override
            public void onPlayerError(@NonNull com.google.android.exoplayer2.PlaybackException error) {
                notifyError("ExoPlayer error: " + error.getMessage());
            }
        });

        setupMediaSession();
        createNotificationChannel();
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(getContext(), "AudioPlayerSession");

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() { playInternal(); }
            @Override
            public void onPause() { pauseInternal(); }
            @Override
            public void onSkipToNext() { nextInternal(); }
            @Override
            public void onSkipToPrevious() { previousInternal(); }
            @Override
            public void onSeekTo(long pos) {
                if (exoPlayer != null) {
                    exoPlayer.seekTo(pos);
                    sendPlayerState(); // update JS + notification
                }
            }
        });

        mediaSession.setActive(true);
    }


    private AudioTrack parseTrack(JSObject json) {
        try {
            String id = json.optString("id", "track-" + System.currentTimeMillis());
            String title = json.optString("title", "Unknown Track");
            String artist = json.optString("artist", "Unknown Artist");
            String album = json.optString("album", "Unknown Album");
            String url = json.optString("url", "");
            String artwork = json.optString("artwork", "");
            double duration = json.optDouble("duration", 0.0);
            if (url.isEmpty()) return null;
            return new AudioTrack(id, title, artist, album, duration, url, artwork);
        } catch (Exception e) {
            Log.e(TAG, "Track parsing error", e);
            return null;
        }
    }

    private List<AudioTrack> parseTracks(JSArray array) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            try {
                Object item = array.get(i);
                if (item instanceof JSObject) {
                    AudioTrack track = parseTrack((JSObject) item);
                    if (track != null) tracks.add(track);
                }
            } catch (Exception e) {
                Log.e(TAG, "Track array parse error", e);
            }
        }
        return tracks;
    }

    @PluginMethod
    public void prepare(PluginCall call) {
        mainHandler.post(() -> {
            trackQueue.clear();
            currentIndex = 0;
            currentPlaybackPosition = 0;
            JSObject trackData = call.getObject("track");
            AudioTrack track = null;
            if (trackData != null) track = parseTrack(trackData);
            else {
                try { track = parseTrack(JSObject.fromJSONObject(call.getData())); }
                catch (Exception e) { Log.e(TAG, "Error parsing track", e); }
            }

            if (track != null) trackQueue.add(track);

            JSArray tracksArray = call.getArray("tracks");
            if (tracksArray != null && tracksArray.length() > 0) trackQueue.addAll(parseTracks(tracksArray));

            if (trackQueue.isEmpty()) { call.reject("No track data provided"); return; }

            loadCurrentTrack();
            call.resolve();
        });
    }

    private void loadCurrentTrack() {
        if (trackQueue.isEmpty() || currentIndex < 0 || currentIndex >= trackQueue.size()) {
            notifyError("No track to play");
            return;
        }

        AudioTrack track = trackQueue.get(currentIndex);
        try {
            // Stop only if player is playing a different track
            exoPlayer.stop();
            exoPlayer.clearMediaItems();

            Uri trackUri = track.getUrl().startsWith("http") ? Uri.parse(track.getUrl()) :
                    Uri.fromFile(new File(track.getUrl()));

            MediaItem mediaItem = new MediaItem.Builder().setUri(trackUri).build();
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();

            // Seek to last known position
            exoPlayer.seekTo(currentPlaybackPosition);
            exoPlayer.play();
            startPositionUpdates();

            notifyTrackChange(track);
            showNotification(track);
        } catch (Exception e) { notifyError("Load track failed: " + e.getMessage()); }
    }


    private void handleTrackCompletion() {
        if ("one".equals(repeatMode)) { exoPlayer.seekTo(0); exoPlayer.play(); }
        else { currentIndex++; if (currentIndex >= trackQueue.size()) { if ("all".equals(repeatMode)) currentIndex = 0; else currentIndex = trackQueue.size() - 1; } loadCurrentTrack(); }
        notifyListeners("playbackEnd", new JSObject());
    }

    @PluginMethod public void play(PluginCall call) { mainHandler.post(() -> { playInternal(); call.resolve(); }); }
    @PluginMethod public void pause(PluginCall call) { mainHandler.post(() -> { pauseInternal(); call.resolve(); }); }
    @PluginMethod public void stop(PluginCall call) { mainHandler.post(() -> { exoPlayer.stop(); stopPositionUpdates(); call.resolve(); }); }
    @PluginMethod public void next(PluginCall call) { mainHandler.post(() -> { nextInternal(); call.resolve(); }); }
    @PluginMethod public void previous(PluginCall call) { mainHandler.post(() -> { previousInternal(); call.resolve(); }); }
    @PluginMethod public void seekTo(PluginCall call) {
        mainHandler.post(() -> {
            Double pos = call.getDouble("position");
            if (pos != null) exoPlayer.seekTo((long) (pos * 1000));
            sendPlayerState();
            call.resolve();
        });
    }

    private void playInternal() { if (!exoPlayer.isPlaying()) exoPlayer.play(); startPositionUpdates(); sendPlayerState(); }
    private void pauseInternal() { if (exoPlayer.isPlaying()) exoPlayer.pause(); stopPositionUpdates(); sendPlayerState(); }
    private void nextInternal() {
        sendTrackActionToJS("next");
    }

    private void previousInternal() {
        sendTrackActionToJS("previous");
    }
    private void startPositionUpdates() { positionHandler.removeCallbacks(positionRunnable); positionHandler.post(positionRunnable); }
    private void stopPositionUpdates() { positionHandler.removeCallbacks(positionRunnable); }

    private void sendTrackActionToJS(String action) {
        JSObject data = new JSObject();
        data.put("action", action);
        notifyListeners("trackChange", data); // your TS listener will handle it
    }

    private void sendPlayerState() {
        if(trackQueue.isEmpty()) return;
        AudioTrack track = trackQueue.get(currentIndex);
        JSObject data = new JSObject();

        boolean isLoading = exoPlayer.getPlaybackState() == Player.STATE_BUFFERING || exoPlayer.getDuration() <= 0;

        long position = exoPlayer.getCurrentPosition(); // save current position
        currentPlaybackPosition = position;

        data.put("isPlaying", exoPlayer.isPlaying());
        data.put("position", position / 1000.0);
        data.put("duration", exoPlayer.getDuration() > 0 ? exoPlayer.getDuration()/1000.0 : 0);
        data.put("trackId", track.getId());
        data.put("isLoading", isLoading);

        notifyListeners("playerStateChange", data);

        updateMediaSession();
        showNotification(track);
    }


    private void updateMediaSession() {
        if (mediaSession == null || exoPlayer == null) return;

        boolean isPlaying = exoPlayer.isPlaying();
        long position = exoPlayer.getCurrentPosition();
        long duration = exoPlayer.getDuration() > 0 ? exoPlayer.getDuration() : 0;

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                        isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        position,
                        1f
                )
                .setBufferedPosition(duration); // <-- important for seekbar

        mediaSession.setPlaybackState(stateBuilder.build());
    }




    private void notifyTrackChange(AudioTrack track) {
        JSObject data = new JSObject();
        data.put("id", track.getId());
        data.put("title", track.getTitle());
        data.put("artist", track.getArtist());
        data.put("album", track.getAlbum());
        data.put("duration", track.getDuration());
        data.put("url", track.getUrl());
        data.put("artwork", track.getArtwork());
        notifyListeners("trackChange", data);
    }

    private void notifyError(String message) {
        JSObject data = new JSObject();
        data.put("error", message);
        notifyListeners("error", data);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Audio Player", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getContext().getSystemService(NotificationManager.class);
            if(manager!=null) manager.createNotificationChannel(channel);
        }
    }

    private void showNotification(AudioTrack track) {
        Context context = getContext();
        if (context == null) return;

        boolean isPlaying = exoPlayer != null && exoPlayer.isPlaying();

        PendingIntent playPauseIntent = PendingIntent.getBroadcast(context, 1,
                new Intent(context, AudioPlayerReceiver.class)
                        .setAction(isPlaying ? "ACTION_PAUSE" : "ACTION_PLAY"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent nextPendingIntent = PendingIntent.getBroadcast(context, 2,
                new Intent(context, AudioPlayerReceiver.class).setAction("ACTION_NEXT"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevPendingIntent = PendingIntent.getBroadcast(context, 3,
                new Intent(context, AudioPlayerReceiver.class).setAction("ACTION_PREV"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(track.getTitle())
                .setContentText(track.getArtist())
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)) // previous, play/pause, next
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
                .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying ? "Pause" : "Play", playPauseIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent);

        // Load artwork in background
        if (track.getArtwork() != null && !track.getArtwork().isEmpty()) {
            new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL(track.getArtwork());
                    java.io.InputStream input = url.openStream();
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(input);
                    builder.setLargeIcon(bitmap);

                    NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());

                    input.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
                }
            }).start();
        } else {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
        }

    }



    // BroadcastReceiver to handle notification actions
    public static class AudioPlayerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            AudioPlayerPlugin plugin = AudioPlayerPlugin.getInstance();
            if (plugin == null) return;

            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case "ACTION_PLAY":
                    plugin.playInternal();
                    break;
                case "ACTION_PAUSE":
                    plugin.pauseInternal();
                    break;
                case "ACTION_NEXT":
                    plugin.nextInternal();
                    break;
                case "ACTION_PREV":
                    plugin.previousInternal();
                    break;
            }
        }
    }


    // Helper to get plugin instance
    private static AudioPlayerPlugin instance;
    public AudioPlayerPlugin() { instance = this; }
    public static AudioPlayerPlugin getInstance() { return instance; }
}
