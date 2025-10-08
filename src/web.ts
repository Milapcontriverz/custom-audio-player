import { WebPlugin } from '@capacitor/core';
import type { AudioPlayerPlugin, AudioTrack, PlayerState } from './definitions';

export class AudioPlayerWeb implements AudioPlayerPlugin {
  private state: PlayerState = {
    isPlaying: false,
    isPaused: false,
    isStopped: true,
    isLoading: false,
    duration: 0,
    position: 0,
    playbackRate: 1.0,
    repeatMode: 'none',
    shuffleMode: false,
    volume: 1.0,
  };

  async initialize(): Promise<void> {
    console.log('Web AudioPlayer initialized');
  }

  // 🎯 FIXED: Updated signature
  async prepare(track: AudioTrack): Promise<void> {
    console.log('Web AudioPlayer prepare:', track);
    this.state.currentTrack = track;
    this.state.duration = track.duration || 0;
    console.log('Web AudioPlayer track prepared:', track.title);
  }

  async play(): Promise<void> {
    console.log('Web AudioPlayer play');
    this.state.isPlaying = true;
    this.state.isPaused = false;
    this.state.isStopped = false;
    
    // Simulate web audio playback
    this.simulatePlayback();
  }

  async pause(): Promise<void> {
    console.log('Web AudioPlayer pause');
    this.state.isPlaying = false;
    this.state.isPaused = true;
  }

  async stop(): Promise<void> {
    console.log('Web AudioPlayer stop');
    this.state.isPlaying = false;
    this.state.isPaused = false;
    this.state.isStopped = true;
    this.state.position = 0;
  }

  async previous(): Promise<void> {
    console.log('Web AudioPlayer previous');
  }

  async next(): Promise<void> {
    console.log('Web AudioPlayer next');
  }

  async seekTo(position: number): Promise<void> {
    console.log('Web AudioPlayer seekTo:', position);
    this.state.position = position;
  }

  async setQueue(tracks: AudioTrack[]): Promise<void> {
    console.log('Web AudioPlayer setQueue:', tracks.length);
  }

  async addTracks(tracks: AudioTrack[]): Promise<void> {
    console.log('Web AudioPlayer addTracks:', tracks.length);
  }

  async removeTrack(trackId: string): Promise<void> {
    console.log('Web AudioPlayer removeTrack:', trackId);
  }

  async clearQueue(): Promise<void> {
    console.log('Web AudioPlayer clearQueue');
  }

  async getQueue(): Promise<AudioTrack[]> {
    console.log('Web AudioPlayer getQueue');
    return [];
  }

  async getPlayerState(): Promise<PlayerState> {
    console.log('Web AudioPlayer getPlayerState');
    return this.state;
  }

  async setRepeatMode(mode: 'none' | 'one' | 'all'): Promise<void> {
    console.log('Web AudioPlayer setRepeatMode:', mode);
    this.state.repeatMode = mode;
  }

  async setShuffleMode(enabled: boolean): Promise<void> {
    console.log('Web AudioPlayer setShuffleMode:', enabled);
    this.state.shuffleMode = enabled;
  }

  async setVolume(volume: number): Promise<void> {
    console.log('Web AudioPlayer setVolume:', volume);
    this.state.volume = Math.max(0, Math.min(1, volume));
  }

  async setPlaybackRate(rateOrOptions: number | { rate: number }): Promise<void> {
    let rate: number;
  
    if (typeof rateOrOptions === 'number') {
      rate = rateOrOptions;
    } else if (rateOrOptions && typeof rateOrOptions.rate === 'number') {
      rate = rateOrOptions.rate;
    } else {
      console.error('Invalid playback rate input:', rateOrOptions);
      throw new Error('rate must be provided as number');
    }
  
    console.log('Web AudioPlayer setPlaybackRate:', rate);
    this.state.playbackRate = rate;
  }

  async addListener(
    eventName: string,
    listenerFunc: (data: any) => void,
  ): Promise<any> {
    console.log('Web AudioPlayer addListener:', eventName);
    return {
      remove: async () => {
        console.log('Web AudioPlayer listener removed');
      }
    };
  }

  async removeAllListeners(): Promise<void> {
    console.log('Web AudioPlayer removeAllListeners');
  }

  // 🎯 HELPER: Simulate playback for web
  private simulatePlayback() {
    if (!this.state.isPlaying) return;
    
    const interval = setInterval(() => {
      if (!this.state.isPlaying) {
        clearInterval(interval);
        return;
      }
      
      this.state.position += 0.1;
      
      // Simulate track end
      if (this.state.position >= (this.state.duration || 100)) {
        this.state.position = 0;
        this.state.isPlaying = false;
        this.state.isStopped = true;
        clearInterval(interval);
        
        // Notify track end
        if (this.eventListeners?.['playbackEnd']) {
          this.eventListeners['playbackEnd'].forEach((listener: any) => listener({}));
        }
      }
    }, 100);
  }

  private eventListeners: { [key: string]: any[] } = {};
}