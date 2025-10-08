package com.contriverz.audioplayer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.PluginMethod;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.app.NotificationCompat.MediaStyle; // for media notification style

@CapacitorPlugin(name = "AudioPlayer")
public class AudioPlayerPlugin extends Plugin {

    private static final String CHANNEL_ID = "audio_player_channel";
    private static final int NOTIFICATION_ID = 1;

    private MediaPlayer mediaPlayer;
    private List<AudioTrack> trackQueue = new ArrayList<>();
    private int currentIndex = 0;
    private String repeatMode = "none";
    private boolean shuffleMode = false;

    private MediaSessionCompat mediaSession;
    private Handler handler;
    private Runnable stateUpdateRunnable;

    private String lastError = null;

    @Override
    public void load() {
        super.load();
        createNotificationChannel();
        setupMediaSession();
        setupHandler();
    }

    private void setupHandler() {
        handler = new Handler(Looper.getMainLooper());
        stateUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                notifyPlayerStateChange();
                handler.postDelayed(this, 1000);
            }
        };
    }

    private void setupMediaSession() {
        Context context = getContext();
        if (context == null) return;

        mediaSession = new MediaSessionCompat(context, "AudioPlayer");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() { play(null); }
            @Override
            public void onPause() { pause(null); }
            @Override
            public void onStop() { stop(null); }
            @Override
            public void onSkipToNext() { notifyListeners("trackChange", new JSObject().put("action", "next")); }
            @Override
            public void onSkipToPrevious() { notifyListeners("trackChange", new JSObject().put("action", "previous")); }
            @Override
            public void onSeekTo(long pos) { seekToPosition((int) pos); }
        });

        mediaSession.setActive(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Audio Player", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Audio playback controls");
            NotificationManager manager = getContext().getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    @PluginMethod
    public void initialize(PluginCall call) {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                );
                setupMediaPlayerListeners();
            }
            call.resolve();
        } catch (Exception e) { call.reject("Failed to initialize player: " + e.getMessage()); }
    }

    @PluginMethod
    public void prepare(PluginCall call) {
        try {
            // Case 1: Single track inside "track"
            JSObject trackData = call.getObject("track");
            if (trackData != null) {
                AudioTrack track = parseTrack(trackData);
                trackQueue.clear();
                trackQueue.add(track);
                currentIndex = 0;
                loadCurrentTrack();
                call.resolve();
                return;
            }

            // Case 2: Multiple tracks inside "tracks"
            JSObject tracksArrayObj = call.getObject("tracks");
            if (tracksArrayObj != null) {
                // convert JSObject array to List<JSObject>
                List<Object> tracksList = call.getArray("tracks").toList();
                if (tracksList.isEmpty()) {
                    call.reject("Invalid tracks array");
                    return;
                }
                trackQueue.clear();
                for (Object obj : tracksList) {
                    if (obj instanceof JSObject) {
                        trackQueue.add(parseTrack((JSObject) obj));
                    }
                }
                currentIndex = 0;
                loadCurrentTrack();
                call.resolve();
                return;
            }

            // Case 3: Track data directly in options
            JSObject options = call.getData();
            if (options != null && options.getString("id") != null && options.getString("url") != null) {
                AudioTrack track = parseTrack(options);
                trackQueue.clear();
                trackQueue.add(track);
                currentIndex = 0;
                loadCurrentTrack();
                call.resolve();
                return;
            }

            // If none of the above matched
            call.reject("Track data is required");
        } catch (Exception e) {
            call.reject("Failed to prepare track: " + e.getMessage());
        }
    }

    @PluginMethod
    public void play(PluginCall call) {
        try {
            if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                mediaPlayer.start();
                startPeriodicUpdates();
                updatePlaybackState();
                updateNotification();
            }
            call.resolve();
        } catch (Exception e) { call.reject("Failed to play: " + e.getMessage()); }
    }

    @PluginMethod
    public void pause(PluginCall call) {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                stopPeriodicUpdates();
                updatePlaybackState();
                updateNotification();
            }
            call.resolve();
        } catch (Exception e) { call.reject("Failed to pause: " + e.getMessage()); }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop(); mediaPlayer.seekTo(0);
                stopPeriodicUpdates(); updatePlaybackState(); updateNotification();
            }
            call.resolve();
        } catch (Exception e) { call.reject("Failed to stop: " + e.getMessage()); }
    }

    @PluginMethod
    public void next(PluginCall call) { notifyListeners("trackChange", new JSObject().put("action", "next")); call.resolve(); }
    @PluginMethod
    public void previous(PluginCall call) { notifyListeners("trackChange", new JSObject().put("action", "previous")); call.resolve(); }

    @PluginMethod
    public void seekTo(PluginCall call) {
        try {
            Double pos = call.getDouble("position");
            if (pos != null) seekToPosition((int) (pos * 1000));
            call.resolve();
        } catch (Exception e) { call.reject("Failed to seek: " + e.getMessage()); }
    }

    private void loadCurrentTrack() {
        try {
            if (currentIndex >= trackQueue.size()) return;
            AudioTrack track = trackQueue.get(currentIndex);
            if (mediaPlayer != null) {
                mediaPlayer.reset();
                mediaPlayer.setDataSource(track.getUrl());
                mediaPlayer.prepareAsync();
                mediaPlayer.setOnPreparedListener(mp -> {
                    updateMetadata(track); notifyTrackChange(track); play(null);
                });
            }
        } catch (Exception e) { lastError = e.getMessage(); notifyError(e.getMessage()); }
    }

    private void updateMetadata(AudioTrack track) {
        if (mediaSession == null) return;
        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder();
        metadata.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.getTitle());
        metadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.getArtist());
        metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.getAlbum() != null ? track.getAlbum() : "");
        if (track.getDuration() != null) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.getDuration().longValue());
        mediaSession.setMetadata(metadata.build());
    }

    private void seekToPosition(int posMs) { if (mediaPlayer != null) mediaPlayer.seekTo(posMs); notifyPlayerStateChange(); }
    private void startPeriodicUpdates() { handler.post(stateUpdateRunnable); }
    private void stopPeriodicUpdates() { handler.removeCallbacks(stateUpdateRunnable); }

    private void setupMediaPlayerListeners() {
        if (mediaPlayer == null) return;
        mediaPlayer.setOnCompletionListener(mp -> {
            if ("one".equals(repeatMode)) { mp.seekTo(0); mp.start(); }
            else next(null);
            notifyListeners("playbackEnd", new JSObject());
        });
        mediaPlayer.setOnErrorListener((mp, what, extra) -> { lastError = "MediaPlayer error: " + what; notifyError(lastError); return true; });
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        int state = mediaPlayer != null && mediaPlayer.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        int posMs = mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, posMs, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateNotification() {
        if (mediaSession == null || currentIndex >= trackQueue.size()) return;

        AudioTrack track = trackQueue.get(currentIndex);
        Bitmap artwork = track.getArtwork() != null ? getBitmapFromURL(track.getArtwork()) : null;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
                .setContentTitle(track.getTitle())
                .setContentText(track.getArtist())
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(artwork)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken()));

        NotificationManagerCompat manager = NotificationManagerCompat.from(getContext());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For Android 13+, request notification permission
            if (getContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return; // permission not granted
            }
        }
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private void notifyPlayerStateChange() {
        JSObject state = new JSObject();
        boolean isPlaying = mediaPlayer != null && mediaPlayer.isPlaying();
        int posMs = mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        int durationMs = mediaPlayer != null ? mediaPlayer.getDuration() : 0;

        state.put("isPlaying", isPlaying);
        state.put("position", Math.round(posMs / 1000.0));
        state.put("duration", Math.round(durationMs / 1000.0));
        state.put("repeatMode", repeatMode);
        state.put("shuffleMode", shuffleMode);
        state.put("volume", mediaPlayer != null ? 1.0 : 0.0);
        state.put("currentTrack", currentIndex < trackQueue.size() ? serializeTrack(trackQueue.get(currentIndex)) : null);
        state.put("lastError", lastError != null ? lastError : "");
        notifyListeners("playerStateChange", state);
    }

    private void notifyTrackChange(AudioTrack track) { notifyListeners("trackChange", new JSObject().put("track", serializeTrack(track))); }
    private void notifyError(String msg) { notifyListeners("error", new JSObject().put("error", msg)); }

    private JSObject serializeTrack(AudioTrack track) {
        JSObject obj = new JSObject();
        obj.put("id", track.getId());
        obj.put("title", track.getTitle());
        obj.put("artist", track.getArtist());
        obj.put("album", track.getAlbum() != null ? track.getAlbum() : "");
        obj.put("duration", track.getDuration() != null ? track.getDuration() : 0);
        obj.put("url", track.getUrl());
        obj.put("artwork", track.getArtwork() != null ? track.getArtwork() : "");
        return obj;
    }

    private AudioTrack parseTrack(JSObject json) {
        String id = json.getString("id") != null ? json.getString("id") : "";
        String title = json.getString("title") != null ? json.getString("title") : "";
        String artist = json.getString("artist") != null ? json.getString("artist") : "";
        String album = json.getString("album") != null ? json.getString("album") : "";
        String url = json.getString("url") != null ? json.getString("url") : "";
        String artwork = json.getString("artwork") != null ? json.getString("artwork") : "";

        double duration = 0;
        try {
            Object d = json.get("duration");
            if (d != null) {
                duration = Double.parseDouble(d.toString());
            }
        } catch (Exception e) {
            duration = 0;
        }

        return new AudioTrack(id, title, artist, album, duration, url, artwork);
    }

    private Bitmap getBitmapFromURL(String src) {
        try { URL url = new URL(src); HttpURLConnection conn = (HttpURLConnection) url.openConnection(); conn.setDoInput(true); conn.connect(); InputStream input = conn.getInputStream(); return BitmapFactory.decodeStream(input); }
        catch (Exception e) { return null; }
    }
}
