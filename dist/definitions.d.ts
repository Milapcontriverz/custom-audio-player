import { PluginListenerHandle } from '@capacitor/core';
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
    volume: number;
}
export interface PlaybackOptions {
    loop?: boolean;
    volume?: number;
    playbackRate?: number;
}
export interface AudioPlayerPlugin {
    /**
     * Initialize the audio player
     */
    initialize(): Promise<void>;
    /**
     * Prepare a track for playback
     */
    prepare(track: AudioTrack): Promise<void>;
    /**
     * Play the current or prepared track
     */
    play(): Promise<void>;
    /**
     * Pause the current playback
     */
    pause(): Promise<void>;
    /**
     * Stop playback and reset position
     */
    stop(): Promise<void>;
    /**
     * Skip to the previous track
     */
    previous(): Promise<void>;
    /**
     * Skip to the next track
     */
    next(): Promise<void>;
    /**
     * Seek to a specific position in the current track
     */
    seekTo(position: number | { position: number }): Promise<void>;
    /**
     * Set the playback queue
     */
    setQueue(tracks: AudioTrack[]): Promise<void>;
    /**
     * Add tracks to the current queue
     */
    addTracks(tracks: AudioTrack[]): Promise<void>;
    /**
     * Remove a track from the queue by ID
     */
    removeTrack(trackId: string): Promise<void>;
    /**
     * Clear the entire queue
     */
    clearQueue(): Promise<void>;
    /**
     * Get the current playback queue
     */
    getQueue(): Promise<AudioTrack[]>;
    /**
     * Get the current player state
     */
    getPlayerState(): Promise<PlayerState>;
    /**
     * Set repeat mode
     */
    setRepeatMode(mode: 'none' | 'one' | 'all'): Promise<void>;
    /**
     * Set shuffle mode
     */
    setShuffleMode(enabled: boolean): Promise<void>;
    /**
     * Set volume (0.0 to 1.0)
     */
    setVolume(volume: number): Promise<void>;
    /**
     * Set playback rate
     */
    setPlaybackRate(options: { rate: number }): Promise<void>;

    /**
     * Listen for player state changes
     */
    addListener(eventName: 'playerStateChange' | 'trackChange' | 'playbackEnd' | 'error' | 'loading', listenerFunc: (data: any) => void): Promise<PluginListenerHandle>;
    /**
     * Remove all listeners
     */
    removeAllListeners(): Promise<void>;
}
