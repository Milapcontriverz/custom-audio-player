package com.contriverz.audioplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
                notifyStateChange();
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
            public void onPlay() {
                play(null);
            }

            @Override
            public void onPause() {
                pause(null);
            }

            @Override
            public void onStop() {
                stop(null);
            }

            @Override
            public void onSkipToNext() {
                next(null);
            }

            @Override
            public void onSkipToPrevious() {
                previous(null);
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo(new PluginCall(getBridge(), "seekTo", new JSObject().put("position", pos), null));
            }
        });

        mediaSession.setActive(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Audio Player",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Audio playback controls");

            NotificationManager notificationManager = getContext().getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @PluginMethod
    public void initialize(PluginCall call) {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                setupMediaPlayerListeners();
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to initialize player: " + e.getMessage());
        }
    }

    @PluginMethod
    public void prepare(PluginCall call) {
        try {
            JSObject trackData = call.getObject("track");
            if (trackData == null) {
                call.reject("Track data is required");
                return;
            }

            AudioTrack track = parseTrack(trackData);
            trackQueue.clear();
            trackQueue.add(track);
            currentIndex = 0;

            loadCurrentTrack();

            call.resolve();
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
        } catch (Exception e) {
            call.reject("Failed to play: " + e.getMessage());
        }
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
        } catch (Exception e) {
            call.reject("Failed to pause: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.seekTo(0);
                stopPeriodicUpdates();
                updatePlaybackState();
                updateNotification();
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to stop: " + e.getMessage());
        }
    }

    @PluginMethod
    public void next(PluginCall call) {
        try {
            if (shuffleMode) {
                currentIndex = (int) (Math.random() * trackQueue.size());
            } else {
                if (currentIndex < trackQueue.size() - 1) {
                    currentIndex++;
                } else if ("all".equals(repeatMode)) {
                    currentIndex = 0;
                } else {
                    stop(null);
                    call.resolve();
                    return;
                }
            }
            loadCurrentTrack();
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to go to next track: " + e.getMessage());
        }
    }

    @PluginMethod
    public void previous(PluginCall call) {
        try {
            int currentPos = mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
            if (currentPos > 3000) {
                seekToPosition(0);
                call.resolve();
                return;
            }
            if (currentIndex > 0) {
                currentIndex--;
            } else if ("all".equals(repeatMode)) {
                currentIndex = trackQueue.size() - 1;
            } else {
                seekToPosition(0);
                call.resolve();
                return;
            }
            loadCurrentTrack();
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to go to previous track: " + e.getMessage());
        }
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        try {
            Double pos = call.getDouble("position");
            if (pos != null) {
                seekToPosition(pos.intValue());
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to seek: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setQueue(PluginCall call) {
        try {
            List<Object> tracks = call.getArray("tracks");
            if (tracks != null) {
                trackQueue.clear();
                for (Object obj : tracks) {
                    if (obj instanceof JSObject) {
                        trackQueue.add(parseTrack((JSObject) obj));
                    }
                }
                currentIndex = 0;
                loadCurrentTrack();
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to set queue: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setRepeatMode(PluginCall call) {
        String mode = call.getString("mode");
        if (mode != null) repeatMode = mode;
        notifyStateChange();
        call.resolve();
    }

    @PluginMethod
    public void setShuffleMode(PluginCall call) {
        Boolean enabled = call.getBoolean("shuffle");
        if (enabled != null) shuffleMode = enabled;
        if (shuffleMode) Collections.shuffle(trackQueue);
        notifyStateChange();
        call.resolve();
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        Double vol = call.getDouble("volume");
        if (vol != null && mediaPlayer != null) {
            float fvol = vol.floatValue();
            mediaPlayer.setVolume(fvol, fvol);
        }
        notifyStateChange();
        call.resolve();
    }

    @PluginMethod
    public void setPlaybackRate(PluginCall call) {
        Double rate = call.getDouble("rate");
        if (rate != null && mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(rate.floatValue()));
        }
        call.resolve();
    }

    @PluginMethod
    public void getState(PluginCall call) {
        JSObject state = new JSObject();
        state.put("isPlaying", mediaPlayer != null && mediaPlayer.isPlaying());
        state.put("position", mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0);
        state.put("duration", mediaPlayer != null ? mediaPlayer.getDuration() : 0);
        state.put("repeatMode", repeatMode);
        state.put("shuffleMode", shuffleMode);
        state.put("volume", mediaPlayer != null ? 1.0 : 0.0);
        state.put("currentTrack", currentIndex < trackQueue.size() ? serializeTrack(trackQueue.get(currentIndex)) : null);
        state.put("lastError", lastError != null ? lastError : "");
        call.resolve(state);
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
                    updateMetadata(track);
                    notifyTrackChange(track);
                    play(null);
                });
            }
        } catch (IOException e) {
            lastError = e.getMessage();
            notifyError(e.getMessage());
        }
    }

    private void seekToPosition(int pos) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(pos);
        }
        notifyStateChange();
    }

    private void startPeriodicUpdates() {
        handler.post(stateUpdateRunnable);
    }

    private void stopPeriodicUpdates() {
        handler.removeCallbacks(stateUpdateRunnable);
    }

    private void setupMediaPlayerListeners() {
        if (mediaPlayer == null) return;

        mediaPlayer.setOnCompletionListener(mp -> {
            if ("one".equals(repeatMode)) {
                mp.seekTo(0);
                mp.start();
            } else {
                next(null);
            }
            notifyListeners("playbackEnd", new JSObject());
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            lastError = "MediaPlayer error: " + what;
            notifyError(lastError);
            return true;
        });
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        int state = mediaPlayer != null && mediaPlayer.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateNotification() {
        if (mediaSession == null || currentIndex >= trackQueue.size()) return;

        AudioTrack track = trackQueue.get(currentIndex);
        Bitmap artwork = null;
        if (track.getArtwork() != null) {
            artwork = getBitmapFromURL(track.getArtwork());
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
                .setContentTitle(track.getTitle())
                .setContentText(track.getArtist())
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(artwork)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken()));

        NotificationManagerCompat.from(getContext()).notify(NOTIFICATION_ID, builder.build());
    }

    private void notifyTrackChange(AudioTrack track) {
        JSObject data = new JSObject();
        data.put("track", serializeTrack(track));
        notifyListeners("trackChange", data);
    }

    private void notifyStateChange() {
        JSObject state = new JSObject();
        state.put("isPlaying", mediaPlayer != null && mediaPlayer.isPlaying());
        state.put("position", mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0);
        state.put("duration", mediaPlayer != null ? mediaPlayer.getDuration() : 0);
        state.put("repeatMode", repeatMode);
        state.put("shuffleMode", shuffleMode);
        state.put("volume", mediaPlayer != null ? 1.0 : 0.0);
        state.put("currentTrack", currentIndex < trackQueue.size() ? serializeTrack(trackQueue.get(currentIndex)) : null);
        state.put("lastError", lastError != null ? lastError : "");
        notifyListeners("playerStateChange", state);
    }

    private void notifyError(String msg) {
        JSObject data = new JSObject();
        data.put("error", msg);
        notifyListeners("error", data);
    }

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
        return new AudioTrack(
                json.getString("id"),
                json.getString("title"),
                json.getString("artist"),
                json.getString("album"),
                json.getDouble("duration"),
                json.getString("url"),
                json.getString("artwork")
        );
    }

    private Bitmap getBitmapFromURL(String src) {
        try {
            URL url = new URL(src);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (Exception e) {
            return null;
        }
    }
}
