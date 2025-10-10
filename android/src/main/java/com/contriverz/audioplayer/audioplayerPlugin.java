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
    private String repeatMode = "none";
    private boolean shuffleMode = false;
    private Handler mainHandler;
    private MediaSessionCompat mediaSession;

    private long currentPlaybackPosition = 0;
    private boolean isCurrentlyPlaying = false;

    // Add these for better state management
    private long seekbarPosition = 0;
    private int lastPlaybackState = PlaybackStateCompat.STATE_PAUSED;

    private Handler positionHandler = new Handler(Looper.getMainLooper());
    private Runnable positionRunnable = new Runnable() {
        private long lastSentPosition = -1;
        private long lastNotificationUpdate = 0;

        @Override
        public void run() {
            if (exoPlayer != null && !trackQueue.isEmpty() && currentIndex < trackQueue.size()) {
                long position = exoPlayer.getCurrentPosition();
                long duration = exoPlayer.getDuration();

                currentPlaybackPosition = position;
                seekbarPosition = position; // Track for MediaSession

                boolean isPlaying = exoPlayer.isPlaying();

                // Always update JS side when playing, less frequently when paused
                boolean shouldUpdateJS = isPlaying || (position != lastSentPosition);
                if (shouldUpdateJS) {
                    lastSentPosition = position;

                    JSObject data = new JSObject();
                    data.put("isPlaying", isPlaying);
                    data.put("position", position / 1000.0);
                    data.put("duration", duration > 0 ? duration / 1000.0 : 0);
                    data.put("trackId", trackQueue.get(currentIndex).getId());
                    notifyListeners("playerStateChange", data);

                    Log.d(TAG, "🔄 Position: " + position + "ms, Playing: " + isPlaying);
                }

                // Update MediaSession for seekbar - ALWAYS when playing, less when paused
                if (isPlaying) {
                    updateMediaSessionPosition(seekbarPosition);
                } else {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastNotificationUpdate > 2000) { // Every 2 seconds when paused
                        updateMediaSessionPosition(seekbarPosition);
                        lastNotificationUpdate = currentTime;
                    }
                }

                // Update notification less frequently
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastNotificationUpdate > 3000) {
                    lastNotificationUpdate = currentTime;
                    showNotification(trackQueue.get(currentIndex));
                }

                // Schedule next update based on play state
                if (isPlaying) {
                    positionHandler.postDelayed(this, 500); // Fast updates when playing
                } else {
                    positionHandler.postDelayed(this, 1000); // Slow updates when paused
                }
            }
        }
    };

    @PluginMethod
    public void forcePositionUpdate(PluginCall call) {
        mainHandler.post(() -> {
            if (exoPlayer != null) {
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
        this.lastPlaybackState = state;

        long duration = exoPlayer.getDuration();
        if (duration <= 0 && !trackQueue.isEmpty()) {
            AudioTrack track = trackQueue.get(currentIndex);
            if (track.getDuration() > 0) {
                duration = (long)(track.getDuration() * 1000);
            }
        }
        if (duration <= 0) {
            duration = 180000;
        }

        long bufferedPosition = exoPlayer.getBufferedPosition();
        if (bufferedPosition <= position) {
            bufferedPosition = duration;
        }

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, position, isPlaying ? 1.0f : 0f, System.currentTimeMillis())
                .setBufferedPosition(bufferedPosition);

        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateMediaMetadata() {
        if (mediaSession == null || trackQueue.isEmpty() || currentIndex >= trackQueue.size()) return;

        AudioTrack track = trackQueue.get(currentIndex);

        long duration = exoPlayer != null ? exoPlayer.getDuration() : 0;
        long finalDuration = duration > 0 ? duration :
                (track.getDuration() > 0 ? (long)(track.getDuration() * 1000) : 180000);

        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.getArtist())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.getAlbum())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, finalDuration);

        mediaSession.setMetadata(metadataBuilder.build());
    }

    @Override
    public void load() {
        super.load();
        mainHandler = new Handler(Looper.getMainLooper());

        exoPlayer = new ExoPlayer.Builder(getContext()).build();
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                Log.d(TAG, "🎵 PlaybackState: " + state);

                switch (state) {
                    case Player.STATE_READY:
                        if (exoPlayer.getDuration() > 0) {
                            updateMediaMetadata();
                        }
                        break;
                    case Player.STATE_ENDED:
                        handleTrackCompletion();
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                Log.d(TAG, "🎵 IsPlayingChanged: " + playing);
                isCurrentlyPlaying = playing;

                // Force immediate updates when play state changes
                mainHandler.post(() -> {
                    long position = exoPlayer.getCurrentPosition();
                    seekbarPosition = position;
                    updateMediaSessionPosition(position);
                    sendPlayerState();
                    if (!trackQueue.isEmpty()) {
                        showNotification(trackQueue.get(currentIndex));
                    }
                });
            }

            @Override
            public void onPlayerError(@NonNull com.google.android.exoplayer2.PlaybackException error) {
                Log.e(TAG, "🎵 Player error: " + error.getMessage());
                notifyError("Playback error: " + error.getMessage());
            }
        });

        setupMediaSession();
        createNotificationChannel();
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(getContext(), "AudioPlayerSession");

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
                    seekbarPosition = pos;
                    exoPlayer.seekTo(pos);
                    updateMediaSessionPosition(pos);
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

        updateMediaSessionPosition(0);
        mediaSession.setActive(true);
    }

    private AudioTrack parseTrack(JSObject json) {
        if (json == null) {
            Log.e(TAG, "parseTrack: JSON is null");
            return null;
        }

        try {
            String id = json.optString("id", "track-" + System.currentTimeMillis());
            String title = json.optString("title", "Unknown Track");
            String artist = json.optString("artist", "Unknown Artist");
            String album = json.optString("album", "Unknown Album");
            String url = json.optString("url", "");
            String artwork = json.optString("artwork", "");
            double duration = json.optDouble("duration", 0.0);

            if (url.isEmpty()) {
                Log.e(TAG, "parseTrack: URL is empty");
                return null;
            }

            return new AudioTrack(id, title, artist, album, duration, url, artwork);
        } catch (Exception e) {
            Log.e(TAG, "Track parsing error", e);
            return null;
        }
    }

    @PluginMethod
    public void prepare(PluginCall call) {
        mainHandler.post(() -> {
            trackQueue.clear();
            currentIndex = 0;
            currentPlaybackPosition = 0;
            seekbarPosition = 0;

            JSObject trackData = call.getObject("track");
            AudioTrack track = null;

            if (trackData != null) {
                track = parseTrack(trackData);
            } else {
                try {
                    JSObject callData = JSObject.fromJSONObject(call.getData());
                    if (callData != null) {
                        track = parseTrack(callData);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing track from call data", e);
                }
            }

            if (track != null) {
                trackQueue.add(track);
                loadCurrentTrack();
                Log.d(TAG, "✅ Prepared: " + track.getTitle());
                call.resolve();
            } else {
                Log.e(TAG, "❌ No valid track data");
                call.reject("No valid track data provided");
            }
        });
    }

    private void loadCurrentTrack() {
        if (trackQueue.isEmpty() || currentIndex < 0 || currentIndex >= trackQueue.size()) {
            notifyError("No track to play");
            return;
        }

        AudioTrack track = trackQueue.get(currentIndex);
        try {
            Log.d(TAG, "🎵 Loading: " + track.getTitle());

            stopPositionUpdates();
            currentPlaybackPosition = 0;
            seekbarPosition = 0;

            exoPlayer.stop();
            exoPlayer.clearMediaItems();

            Uri trackUri;
            String url = track.getUrl();

            if (url.startsWith("http")) {
                // Remote URL
                trackUri = Uri.parse(url);
                Log.d(TAG, "🌐 Loading remote URL: " + url);
            } else if (url.startsWith("file://")) {
                // File URI - need to handle properly
                try {
                    // Remove "file://" prefix and handle the path
                    String filePath = url.replace("file://", "");
                    File file = new File(filePath);

                    if (file.exists()) {
                        trackUri = Uri.fromFile(file);
                        Log.d(TAG, "📁 Loading local file: " + file.getAbsolutePath());
                    } else {
                        // Try alternative path handling
                        trackUri = Uri.parse(url);
                        Log.d(TAG, "⚠️ File not found, trying URI: " + url);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ File path error, using URI directly", e);
                    trackUri = Uri.parse(url);
                }
            } else if (url.startsWith("content://")) {
                // Content URI
                trackUri = Uri.parse(url);
                Log.d(TAG, "📦 Loading content URI: " + url);
            } else {
                // Assume it's a local file path
                File file = new File(url);
                if (file.exists()) {
                    trackUri = Uri.fromFile(file);
                    Log.d(TAG, "📁 Loading local file path: " + file.getAbsolutePath());
                } else {
                    // Fallback to URI parsing
                    trackUri = Uri.parse(url);
                    Log.d(TAG, "⚠️ Using URI fallback: " + url);
                }
            }

            Log.d(TAG, "🔗 Final URI: " + trackUri.toString());

            MediaItem mediaItem = MediaItem.fromUri(trackUri);
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.seekTo(0);

            // Delay metadata update to ensure player is ready
            mainHandler.postDelayed(() -> {
                updateMediaMetadata();
                updateMediaSessionPosition(0);
            }, 100);

            notifyTrackChange(track);
            showNotification(track);

        } catch (Exception e) {
            Log.e(TAG, "❌ Load failed: " + e.getMessage(), e);
            notifyError("Load failed: " + e.getMessage() + " - URL: " + track.getUrl());
        }
    }

    private void handleTrackCompletion() {
        if ("one".equals(repeatMode)) {
            exoPlayer.seekTo(0);
            exoPlayer.play();
        } else {
            notifyListeners("playbackEnd", new JSObject());
        }
    }

    @PluginMethod public void play(PluginCall call) {
        mainHandler.post(() -> {
            playInternal();
            call.resolve();
        });
    }

    @PluginMethod public void pause(PluginCall call) {
        mainHandler.post(() -> {
            pauseInternal();
            call.resolve();
        });
    }

    @PluginMethod public void stop(PluginCall call) {
        mainHandler.post(() -> {
            exoPlayer.stop();
            stopPositionUpdates();
            call.resolve();
        });
    }

    @PluginMethod public void next(PluginCall call) {
        mainHandler.post(() -> {
            nextInternal();
            call.resolve();
        });
    }

    @PluginMethod public void previous(PluginCall call) {
        mainHandler.post(() -> {
            previousInternal();
            call.resolve();
        });
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        mainHandler.post(() -> {
            Double pos = call.getDouble("position");
            if (pos != null && exoPlayer != null) {
                long seekPosition = (long) (pos * 1000);
                seekbarPosition = seekPosition;
                exoPlayer.seekTo(seekPosition);
                updateMediaSessionPosition(seekPosition);
                sendPlayerState();
            }
            call.resolve();
        });
    }

    private void playInternal() {
        if (exoPlayer == null) return;

        Log.d(TAG, "▶️ Starting playback");
        exoPlayer.setPlayWhenReady(true);

        startPositionUpdates();
        sendPlayerState();

        if (!trackQueue.isEmpty()) {
            showNotification(trackQueue.get(currentIndex));
        }
    }

    private void pauseInternal() {
        if (exoPlayer == null) return;

        Log.d(TAG, "⏸️ Pausing playback");
        exoPlayer.setPlayWhenReady(false);

        // Don't stop updates completely, just let runnable handle slower updates
        startPositionUpdates();
        sendPlayerState();

        if (!trackQueue.isEmpty()) {
            showNotification(trackQueue.get(currentIndex));
        }
    }

    private void nextInternal() {
        JSObject data = new JSObject();
        data.put("action", "next");
        notifyListeners("trackChange", data);
    }

    private void previousInternal() {
        JSObject data = new JSObject();
        data.put("action", "previous");
        notifyListeners("trackChange", data);
    }

    private void startPositionUpdates() {
        positionHandler.removeCallbacks(positionRunnable);
        positionHandler.post(positionRunnable);
    }

    private void stopPositionUpdates() {
        positionHandler.removeCallbacks(positionRunnable);
    }

    private void sendPlayerState() {
        if(trackQueue.isEmpty()) return;

        AudioTrack track = trackQueue.get(currentIndex);
        JSObject data = new JSObject();

        long position = exoPlayer.getCurrentPosition();
        long duration = exoPlayer.getDuration();

        data.put("isPlaying", exoPlayer.isPlaying());
        data.put("position", position / 1000.0);
        data.put("duration", duration > 0 ? duration / 1000.0 : 0);
        data.put("trackId", track.getId());

        notifyListeners("playerStateChange", data);
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
            if(manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void showNotification(AudioTrack track) {
        Context context = getContext();
        if (context == null || exoPlayer == null) return;

        boolean isPlaying = exoPlayer.isPlaying();
        long positionMs = exoPlayer.getCurrentPosition();
        long durationMs = exoPlayer.getDuration();

        int positionSec = (int) (positionMs / 1000);
        int durationSec = durationMs > 0 ? (int) (durationMs / 1000) : 0;

        String positionText = String.format("%02d:%02d", positionSec / 60, positionSec % 60);
        String durationText = durationSec > 0 ? String.format("%02d:%02d", durationSec / 60, durationSec % 60) : "--:--";

        Log.d(TAG, "📱 Notification: " + (isPlaying ? "PLAYING" : "PAUSED") + " - " + positionText + "/" + durationText);

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

        Intent contentIntent = new Intent(context, getActivity().getClass());
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(context, 400, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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

        // Use MediaStyle for interactive seekbar
        MediaStyle style = new MediaStyle();
        style.setMediaSession(mediaSession.getSessionToken());
        style.setShowActionsInCompactView(0, 1, 2);
        builder.setStyle(style);

        // REMOVED: Manual progress bar - MediaSession handles seekbar

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
                        }
                        input.close();
                    } catch (Exception e) {
                        showNotificationWithoutArtwork(builder, context);
                    }
                }).start();
                return;
            } catch (Exception e) {
                // Continue without artwork
            }
        }

        showNotificationWithoutArtwork(builder, context);
    }

    private void showNotificationWithoutArtwork(NotificationCompat.Builder builder, Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }

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
                case "ACTION_STOP":
                    if (plugin.exoPlayer != null) {
                        plugin.exoPlayer.stop();
                        plugin.stopPositionUpdates();
                    }
                    break;
            }
        }
    }

    // Helper to get plugin instance
    private static AudioPlayerPlugin instance;
    public AudioPlayerPlugin() { instance = this; }
    public static AudioPlayerPlugin getInstance() { return instance; }
}