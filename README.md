🎧 capacitor-audio-player-demo

A Capacitor plugin providing a cross-platform audio player with background playback, lock-screen controls, and real-time state listeners.

This plugin supports iOS and Android, offering full transport controls, notifications, and integration with system media sessions.

⚠️ This plugin is currently in active development — some features are still being finalized.

✨ Features

🎵 Play, pause, stop, and seek

🎧 Single-track playback via prepare(track)

🔁 Repeat modes: none, one, all

🔊 Volume and playback-rate control

🧠 Player state queries and event listeners

📱 Background playback with system notification controls (Android) and lock-screen controls (iOS)

🪶 TypeScript definitions included

⚙️ Installation
npm install capacitor-audio-player-demo
npx cap sync

Note: Make sure your Capacitor major version matches this plugin’s supported version.

App Capacitor Version	Required Plugin Peer Dependency
Capacitor 5	@capacitor/core@^5
Capacitor 7	@capacitor/core@^7

🚀 Quick Start
import { registerPlugin } from '@capacitor/core';
import type {
  AudioPlayerPlugin,
  AudioTrack,
  PlayerState,
} from 'capacitor-audio-player-demo';

const AudioPlayer = registerPlugin<AudioPlayerPlugin>('AudioPlayer');

async function demo() {
  await AudioPlayer.initialize();

  const track: AudioTrack = {
    id: 'track-1',
    title: 'Song One',
    artist: 'Artist Name',
    album: 'Sample Album',
    url: 'https://example.com/audio/song1.mp3',
    artwork: 'https://example.com/img/song1.jpg',
    duration: 215, // optional
  };

  await AudioPlayer.prepare(track);
  await AudioPlayer.play();

  // Listen for playback state updates
  await AudioPlayer.addListener('playerStateChange', (state: PlayerState) => {
    console.log('🎶 Player state:', state);
  });
}


🧩 API Reference
🔹 Types

export interface AudioTrack {
  id: string;
  title: string;
  artist: string;
  album?: string;
  duration?: number;
  url: string;
  artwork?: string;
}

export interface PlayerState {
  isPlaying: boolean;
  isPaused: boolean;
  isStopped: boolean;
  isLoading: boolean;
  currentTrack?: AudioTrack;
  duration: number;
  position: number;
  playbackRate: number;
  repeatMode: 'none' | 'one' | 'all';
  shuffleMode: boolean;
  volume: number; // 0.0–1.0
}

export interface PlaybackOptions {
  loop?: boolean;
  volume?: number;        // 0.0–1.0
  playbackRate?: number;  // e.g., 0.5, 1.0, 1.25, 1.5, 2.0
}


🔹 Methods

| Method                                                          | Description                                                 |                                                                                         |                       |
| :-------------------------------------------------------------- | :---------------------------------------------------------- | --------------------------------------------------------------------------------------- | --------------------- |
| **`initialize(): Promise<void>`**                               | Initializes the audio player. Call once at startup.         |                                                                                         |                       |
| **`prepare(track: AudioTrack): Promise<void>`**                 | Loads a single track for playback (does not start playing). |                                                                                         |                       |
| **`play(): Promise<void>`**                                     | Starts playback of the current or prepared track.           |                                                                                         |                       |
| **`pause(): Promise<void>`**                                    | Pauses playback.                                            |                                                                                         |                       |
| **`stop(): Promise<void>`**                                     | Stops playback and resets position.                         |                                                                                         |                       |
| **`seekTo(position: number                                      | { position: number }): Promise<void>`**                     | Seeks to a specific position in seconds. Accepts a number or an object with `position`. |                       |
| **`getPlayerState(): Promise<PlayerState>`**                    | Returns the current player state.                           |                                                                                         |                       |
| **`setRepeatMode(mode: 'none'                                   | 'one'                                                       | 'all'): Promise<void>`**                                                                | Sets the repeat mode. |
| **`setVolume(volume: number): Promise<void>`**                  | Sets playback volume (`0.0–1.0`).                           |                                                                                         |                       |
| **`setPlaybackRate(options: { rate: number }): Promise<void>`** | Adjusts playback speed.                                     |                                                                                         |                       |


🔹 Events
| Event                   | Description                                                         |
| :---------------------- | :------------------------------------------------------------------ |
| **`playerStateChange`** | Fires whenever playback state (position, play/pause, etc.) changes. |
| **`playbackEnd`**       | Fired when the current track finishes playing.                      |
| **`error`**             | Fired when a playback error occurs.                                 |
| **`loading`**           | Fired when a track is buffering or being prepared.                  |


Usage Example:
const sub = await AudioPlayer.addListener('playerStateChange', (state) => {
  console.log('🔄 Player state updated:', state);
});

// Later, unsubscribe:
await sub.remove();

Or remove all listeners at once:

await AudioPlayer.removeAllListeners();

📱 Platform Notes
iOS

Ensure you’ve added proper background audio support in your app:

Go to Xcode → Project → Signing & Capabilities → Background Modes

Enable ✅ “Audio, AirPlay, and Picture in Picture”

If your URLs use non-HTTPS transport, configure ATS exceptions in Info.plist.

Android

Background audio and lock-screen controls are automatically handled via a system notification.

You may need to request Media Notification Permission on Android 13+ if targeting SDK 33+.

Local file playback is supported via absolute file paths (e.g. /storage/emulated/0/Music/song.mp3).


🧰 Development Status
| Feature                               |     Status     |
| :------------------------------------ | :------------: |
| Basic playback (play/pause/stop/seek) |        ✅       |
| Background playback                   |        ✅       |
| Lock-screen controls                  |        ✅       |
| Repeat & shuffle                      | ⚙️ In progress |
| Playlist support                      |   🚧 Planned   |
| Progress seekbar sync                 |     ✅ Fixed    |
| Volume / Rate control                 |        ✅       |
| Offline (local file) support          |        ✅       |
