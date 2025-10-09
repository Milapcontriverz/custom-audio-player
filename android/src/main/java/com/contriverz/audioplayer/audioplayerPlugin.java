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
import android.support.v4.media.MediaMetadataCompat;
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
    private boolean isCurrentlyPlaying = false;
    private Handler positionHandler = new Handler(Looper.getMainLooper());
    private Runnable positionRunnable = new Runnable() {
        private long lastSentPosition = -1;

        @Override
        public void run() {
            if (exoPlayer != null && !trackQueue.isEmpty() && currentIndex < trackQueue.size()) {
                long position = exoPlayer.getCurrentPosition();
                long duration = exoPlayer.getDuration();

                currentPlaybackPosition = position;

                // Only update if position actually changed (prevents flickering)
                if (position != lastSentPosition) {
                    lastSentPosition = position;

                    // Update JS side (convert to seconds)
                    JSObject data = new JSObject();
                    data.put("isPlaying", exoPlayer.isPlaying());
                    data.put("position", position / 1000.0); // ms to seconds
                    data.put("duration", duration > 0 ? duration / 1000.0 : 0);
                    data.put("trackId", trackQueue.get(currentIndex).getId());
                    notifyListeners("playerStateChange", data);

                    Log.d(TAG, "🔄 Position Update: " + position + "ms");
                }

                // Update MediaSession (keep as milliseconds)
                updateMediaSessionPosition(position);

                // Update notification (but less frequently to reduce flickering)
                if (System.currentTimeMillis() % 3000 < 1000) { // Every 3 seconds
                    showNotification(trackQueue.get(currentIndex));
                }

                positionHandler.postDelayed(this, 1000);
            }
        }
    };


    private void forceNotificationUpdate() {
        if (!trackQueue.isEmpty() && currentIndex < trackQueue.size()) {
            showNotification(trackQueue.get(currentIndex));
        }
    }


    @PluginMethod
    public void forcePositionUpdate(PluginCall call) {
        mainHandler.post(() -> {
            if (exoPlayer != null) {
                long position = exoPlayer.getCurrentPosition();
                long duration = exoPlayer.getDuration();

                Log.d(TAG, "🔍 FORCE POSITION UPDATE - Position: " + position + "ms, Duration: " + duration + "ms");

                sendPlayerState();
                call.resolve();
            } else {
                call.reject("Player is null");
            }
        });
    }


    private void updateMediaSessionPosition(long position) {
        if (mediaSession == null || exoPlayer == null) return;

        boolean isPlaying = exoPlayer.isPlaying();
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;

        // Get duration
        long duration = exoPlayer.getDuration();
        if (duration <= 0 && !trackQueue.isEmpty()) {
            AudioTrack track = trackQueue.get(currentIndex);
            if (track.getDuration() > 0) {
                duration = (long)(track.getDuration() * 1000);
            }
        }
        if (duration <= 0) {
            duration = 180000; // 3 minutes fallback
        }

        // SIMPLE: Just update the position - Android handles the seekbar
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, position, 1.0f, System.currentTimeMillis())
                .setBufferedPosition(duration);

        mediaSession.setPlaybackState(stateBuilder.build());

        Log.d(TAG, "📡 MediaSession Position: " + position + "ms");
    }

    private void updateMediaMetadata() {
        if (mediaSession == null || trackQueue.isEmpty() || currentIndex >= trackQueue.size()) return;

        AudioTrack track = trackQueue.get(currentIndex);

        // Get duration
        long duration = exoPlayer != null ? exoPlayer.getDuration() : 0;
        long finalDuration = duration > 0 ? duration :
                (track.getDuration() > 0 ? (long)(track.getDuration() * 1000) : 180000);

        // CRITICAL: Build complete metadata
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.getArtist())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.getAlbum())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, track.getArtist())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, finalDuration)
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (long) (currentIndex + 1))
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, (long) trackQueue.size());

        mediaSession.setMetadata(metadataBuilder.build());

        Log.d(TAG, "📀 Metadata Updated - Duration: " + finalDuration + "ms");
    }

    @PluginMethod
    public void resetMediaSession(PluginCall call) {
        mainHandler.post(() -> {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.setActive(true);
                Log.d(TAG, "MediaSession reset");
            }
            call.resolve();
        });
    }

    @Override
    public void load() {
        super.load();
        mainHandler = new Handler(Looper.getMainLooper());

        exoPlayer = new ExoPlayer.Builder(getContext()).build();
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                Log.d(TAG, "🎵 onPlaybackStateChanged: " + state +
                        " [IDLE=1, BUFFERING=2, READY=3, ENDED=4]");

                switch (state) {
                    case Player.STATE_READY:
                        long duration = exoPlayer.getDuration();
                        Log.d(TAG, "🎵 Player is READY, duration: " + duration + "ms");

                        // CRITICAL: Update metadata when duration becomes available
                        if (duration > 0) {
                            updateMediaMetadata();
                        }
                        break;
                    case Player.STATE_BUFFERING:
                        Log.d(TAG, "🎵 Player is BUFFERING");
                        break;
                    case Player.STATE_ENDED:
                        Log.d(TAG, "🎵 Track ENDED");
                        handleTrackCompletion();
                        break;
                    case Player.STATE_IDLE:
                        Log.d(TAG, "🎵 Player is IDLE");
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                Log.d(TAG, "🎵 onIsPlayingChanged: " + playing);
                isCurrentlyPlaying = playing;

                // DON'T update position here - let positionRunnable handle it
                // Just update the MediaSession state
                mainHandler.post(() -> {
                    updateMediaSessionPosition(exoPlayer.getCurrentPosition());
                });
            }

            @Override
            public void onPlayerError(@NonNull com.google.android.exoplayer2.PlaybackException error) {
                Log.e(TAG, "🎵 Player error: " + error.getMessage());
                notifyError("ExoPlayer error: " + error.getMessage());
            }
        });

        setupMediaSession();
        createNotificationChannel();
    }

    @PluginMethod
    public void getPlayerState(PluginCall call) {
        mainHandler.post(() -> {
            JSObject result = new JSObject();
            if (exoPlayer != null) {
                result.put("playbackState", exoPlayer.getPlaybackState());
                result.put("isPlaying", exoPlayer.isPlaying());
                result.put("currentPosition", exoPlayer.getCurrentPosition());
                result.put("duration", exoPlayer.getDuration());
                result.put("trackCount", trackQueue.size());
                result.put("currentIndex", currentIndex);

                // Player state constants
                String stateName;
                switch (exoPlayer.getPlaybackState()) {
                    case Player.STATE_IDLE: stateName = "IDLE"; break;
                    case Player.STATE_BUFFERING: stateName = "BUFFERING"; break;
                    case Player.STATE_READY: stateName = "READY"; break;
                    case Player.STATE_ENDED: stateName = "ENDED"; break;
                    default: stateName = "UNKNOWN";
                }
                result.put("playbackStateName", stateName);

                Log.d(TAG, "Player State: " + stateName +
                        ", Playing: " + exoPlayer.isPlaying() +
                        ", Position: " + exoPlayer.getCurrentPosition() +
                        ", Duration: " + exoPlayer.getDuration());
            } else {
                result.put("error", "Player is null");
            }
            call.resolve(result);
        });
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(getContext(), "AudioPlayerSession");

        // CRITICAL: Set flags for media session
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                Log.d(TAG, "MediaSession: onPlay");
                playInternal();
            }

            @Override
            public void onPause() {
                Log.d(TAG, "MediaSession: onPause");
                pauseInternal();
            }

            @Override
            public void onSkipToNext() {
                Log.d(TAG, "MediaSession: onSkipToNext");
                nextInternal();
            }

            @Override
            public void onSkipToPrevious() {
                Log.d(TAG, "MediaSession: onSkipToPrevious");
                previousInternal();
            }

            @Override
            public void onSeekTo(long pos) {
                Log.d(TAG, "MediaSession: onSeekTo " + pos);
                if (exoPlayer != null) {
                    exoPlayer.seekTo(pos);
                    sendPlayerState();
                }
            }

            @Override
            public void onStop() {
                Log.d(TAG, "MediaSession: onStop");
                if (exoPlayer != null) {
                    exoPlayer.stop();
                    stopPositionUpdates();
                }
            }
        });

        // CRITICAL: Set initial state and activate
        updateMediaSessionPosition(0);
        mediaSession.setActive(true);

        Log.d(TAG, "✅ MediaSession setup for notification seekbar");
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

            // Don't auto-play here, let the user call play() separately
            Log.d(TAG, "✅ Prepared track, ready for playback");
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
            Log.d(TAG, "🎵 Loading track: " + track.getTitle());

            stopPositionUpdates();
            currentPlaybackPosition = 0;

            exoPlayer.stop();
            exoPlayer.clearMediaItems();

            Uri trackUri = track.getUrl().startsWith("http") ? Uri.parse(track.getUrl()) :
                    Uri.fromFile(new File(track.getUrl()));

            MediaItem mediaItem = MediaItem.fromUri(trackUri);
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.seekTo(currentPlaybackPosition);

            // CRITICAL: Update metadata for notification seekbar
            updateMediaMetadata();
            notifyTrackChange(track);
            showNotification(track);

            Log.d(TAG, "✅ Track loaded for notification seekbar");

        } catch (Exception e) {
            Log.e(TAG, "❌ Load track failed", e);
            notifyError("Load track failed: " + e.getMessage());
        }
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

    private void playInternal() {
        if (exoPlayer == null) return;

        Log.d(TAG, "▶️ Starting playback");

        exoPlayer.setPlayWhenReady(true);
        if (!exoPlayer.isPlaying()) {
            exoPlayer.play();
        }

        // Start position updates
        startPositionUpdates();

        // Send initial state once
        sendPlayerState();

        // Update notification once
        showNotification(trackQueue.get(currentIndex));
    }

    private void pauseInternal() {
        if (exoPlayer == null) return;
        if (exoPlayer.isPlaying()) exoPlayer.pause();
        startPositionUpdates(); // still run to update seekbar
        sendPlayerState();
    }

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
        notifyListeners("trackChange", data); // fires your TS listener
    }

    private void sendPlayerState() {
        if(trackQueue.isEmpty()) return;
        AudioTrack track = trackQueue.get(currentIndex);
        JSObject data = new JSObject();

        long position = exoPlayer.getCurrentPosition(); // ms
        long duration = exoPlayer.getDuration();        // ms

        currentPlaybackPosition = position;

        data.put("isPlaying", exoPlayer.isPlaying());
        data.put("position", position / 1000.0);       // seconds
        data.put("duration", duration > 0 ? duration / 1000.0 : 0);
        data.put("trackId", track.getId());

        notifyListeners("playerStateChange", data);

        // Update MediaSession but NOT notification (to reduce conflicts)
        updateMediaSessionPosition(position);
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
        if (context == null || exoPlayer == null) return;

        boolean isPlaying = exoPlayer.isPlaying();
        long positionMs = exoPlayer.getCurrentPosition();
        long durationMs = exoPlayer.getDuration();

        // Convert to seconds for display
        int positionSec = (int) (positionMs / 1000);
        int durationSec = durationMs > 0 ? (int) (durationMs / 1000) : 0;

        String positionText = String.format("%02d:%02d", positionSec / 60, positionSec % 60);
        String durationText = durationSec > 0 ? String.format("%02d:%02d", durationSec / 60, durationSec % 60) : "--:--";

        Log.d(TAG, "📱 Building Notification: " + positionText + " / " + durationText);

        // Create pending intents with DIFFERENT request codes
        PendingIntent playPauseIntent = PendingIntent.getBroadcast(context, 100,
                new Intent(context, AudioPlayerReceiver.class)
                        .setAction(isPlaying ? "ACTION_PAUSE" : "ACTION_PLAY"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent nextPendingIntent = PendingIntent.getBroadcast(context, 200,
                new Intent(context, AudioPlayerReceiver.class).setAction("ACTION_NEXT"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevPendingIntent = PendingIntent.getBroadcast(context, 300,
                new Intent(context, AudioPlayerReceiver.class).setAction("ACTION_PREV"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Create content intent
        Intent contentIntent = new Intent(context, getActivity().getClass());
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(context, 400, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // SIMPLE notification without MediaStyle
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(track.getTitle())
                .setContentText(track.getArtist() + " • " + positionText + "/" + durationText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentPendingIntent)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
                .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying ? "Pause" : "Play", playPauseIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false);

        // MANUAL PROGRESS BAR - This is what will show the moving progress
        if (durationMs > 0 && positionMs <= durationMs) {
            builder.setProgress((int) durationMs, (int) positionMs, false);
            Log.d(TAG, "✅ Setting manual progress: " + positionMs + "/" + durationMs);
        } else {
            // Show indeterminate progress if duration not available
            builder.setProgress(0, 0, true);
            Log.d(TAG, "⏳ Showing indeterminate progress");
        }

        // Set large icon
        if (track.getArtwork() != null && !track.getArtwork().isEmpty()) {
            try {
                new Thread(() -> {
                    try {
                        java.net.URL url = new java.net.URL(track.getArtwork());
                        java.io.InputStream input = url.openStream();
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(input);
                        builder.setLargeIcon(bitmap);

                        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                        if (manager != null) {
                            manager.notify(NOTIFICATION_ID, builder.build());
                            Log.d(TAG, "✅ Notification updated with artwork");
                        }

                        input.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error loading artwork", e);
                        showNotificationWithoutArtwork(builder, context);
                    }
                }).start();
                return;
            } catch (Exception e) {
                Log.e(TAG, "Error setting up artwork load", e);
            }
        }

        // Show without artwork
        showNotificationWithoutArtwork(builder, context);
    }

    private void showNotificationWithoutArtwork(NotificationCompat.Builder builder, Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
            Log.d(TAG, "✅ Notification updated successfully");
        } else {
            Log.e(TAG, "❌ NotificationManager is null");
        }
    }

    @PluginMethod
    public void testNotification(PluginCall call) {
        mainHandler.post(() -> {
            if (!trackQueue.isEmpty() && currentIndex < trackQueue.size()) {
                Log.d(TAG, "🧪 TESTING NOTIFICATION DIRECTLY");

                // Force update notification
                showNotification(trackQueue.get(currentIndex));

                // Get current position
                long position = exoPlayer != null ? exoPlayer.getCurrentPosition() : 0;
                long duration = exoPlayer != null ? exoPlayer.getDuration() : 0;

                JSObject result = new JSObject();
                result.put("position", position);
                result.put("duration", duration);
                result.put("positionSeconds", position / 1000);
                result.put("durationSeconds", duration / 1000);

                call.resolve(result);
            } else {
                call.reject("No track loaded");
            }
        });
    }

    private PendingIntent createStopPendingIntent(Context context) {
        return PendingIntent.getBroadcast(context, 4,
                new Intent(context, AudioPlayerReceiver.class).setAction("ACTION_STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @PluginMethod
    public void setPlaybackRate(PluginCall call) {
        Double rate = call.getDouble("rate");
        if (rate == null) {
            call.reject("Playback rate not provided");
            return;
        }

        mainHandler.post(() -> {
            if (exoPlayer != null) {
                float playbackSpeed = rate.floatValue();
                exoPlayer.setPlaybackSpeed(playbackSpeed);
                Log.d(TAG, "Playback rate set to: " + playbackSpeed);
                sendPlayerState(); // optional: update UI
            }
            call.resolve();
        });
    }

    public static class AudioPlayerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            AudioPlayerPlugin plugin = AudioPlayerPlugin.getInstance();
            if (plugin == null) return;

            String action = intent.getAction();
            if (action == null) return;

            Log.d("AudioPlayerReceiver", "Notification Action: " + action);

            switch (action) {
                case "ACTION_PLAY":
                    Log.d("AudioPlayerReceiver", "▶️ Play button pressed");
                    plugin.playInternal();
                    break;

                case "ACTION_PAUSE":
                    Log.d("AudioPlayerReceiver", "⏸️ Pause button pressed");
                    plugin.pauseInternal();
                    break;

                case "ACTION_NEXT":
                    Log.d("AudioPlayerReceiver", "⏭️ Next button pressed");
                    plugin.nextInternal();
                    break;

                case "ACTION_PREV":
                    Log.d("AudioPlayerReceiver", "⏮️ Previous button pressed");
                    plugin.previousInternal();
                    break;

                case "ACTION_STOP":
                    Log.d("AudioPlayerReceiver", "⏹️ Stop button pressed");
                    if (plugin.exoPlayer != null) {
                        plugin.exoPlayer.stop();
                        plugin.stopPositionUpdates();
                    }
                    break;
            }
        }
    }

    @PluginMethod
    public void debugPlayer(PluginCall call) {
        mainHandler.post(() -> {
            JSObject result = new JSObject();
            if (exoPlayer != null) {
                result.put("playbackState", exoPlayer.getPlaybackState());
                result.put("isPlaying", exoPlayer.isPlaying());
                result.put("currentPosition", exoPlayer.getCurrentPosition());
                result.put("duration", exoPlayer.getDuration());
                result.put("trackCount", trackQueue.size());
                result.put("currentIndex", currentIndex);
                result.put("playWhenReady", exoPlayer.getPlayWhenReady());

                String stateName;
                switch (exoPlayer.getPlaybackState()) {
                    case Player.STATE_IDLE: stateName = "IDLE"; break;
                    case Player.STATE_BUFFERING: stateName = "BUFFERING"; break;
                    case Player.STATE_READY: stateName = "READY"; break;
                    case Player.STATE_ENDED: stateName = "ENDED"; break;
                    default: stateName = "UNKNOWN";
                }
                result.put("playbackStateName", stateName);

                Log.d(TAG, "=== DEBUG PLAYER ===");
                Log.d(TAG, "State: " + stateName);
                Log.d(TAG, "isPlaying: " + exoPlayer.isPlaying());
                Log.d(TAG, "playWhenReady: " + exoPlayer.getPlayWhenReady());
                Log.d(TAG, "Position: " + exoPlayer.getCurrentPosition() + "ms");
                Log.d(TAG, "Duration: " + exoPlayer.getDuration() + "ms");
                Log.d(TAG, "Track count: " + trackQueue.size());
                Log.d(TAG, "Current index: " + currentIndex);
                Log.d(TAG, "=== END DEBUG ===");

            } else {
                result.put("error", "Player is null");
            }
            call.resolve(result);
        });
    }

    // Helper to get plugin instance
    private static AudioPlayerPlugin instance;
    public AudioPlayerPlugin() { instance = this; }
    public static AudioPlayerPlugin getInstance() { return instance; }
}
