import { WebPlugin } from '@capacitor/core';
export class AudioPlayerWeb extends WebPlugin {
    audio = null;
    queue = [];
    currentIndex = 0;
    state = {
        isPlaying: false,
        isPaused: false,
        isStopped: true,
        isLoading: false,
        duration: 0,
        position: 0,
        playbackRate: 1,
        repeatMode: 'none',
        shuffleMode: false,
        volume: 1,
    };
    async initialize() {
        this.audio = new Audio();
        this.setupEventListeners();
    }
    async prepare(track) {
        if (!this.audio) {
            throw new Error('Audio player not initialized');
        }
        this.state.isLoading = true;
        this.notifyListeners('loading', { isLoading: true });
        this.notifyStateChange();
        try {
            this.audio.src = track.url;
            this.state.currentTrack = track;
            this.state.position = 0;
            await this.audio.load();
        }
        catch (error) {
            this.state.isLoading = false;
            this.notifyListeners('error', { error: error.message });
            throw error;
        }
        finally {
            this.state.isLoading = false;
            this.notifyListeners('loading', { isLoading: false });
            this.notifyStateChange();
        }
    }
    async play() {
        if (!this.audio) {
            throw new Error('Audio player not initialized');
        }
        if (!this.state.currentTrack) {
            throw new Error('No track prepared');
        }
        try {
            await this.audio.play();
            this.state.isPlaying = true;
            this.state.isPaused = false;
            this.state.isStopped = false;
            this.notifyStateChange();
        }
        catch (error) {
            this.notifyListeners('error', { error: error.message });
            throw error;
        }
    }
    async pause() {
        if (!this.audio)
            return;
        this.audio.pause();
        this.state.isPlaying = false;
        this.state.isPaused = true;
        this.notifyStateChange();
    }
    async stop() {
        if (!this.audio)
            return;
        this.audio.pause();
        this.audio.currentTime = 0;
        this.state.isPlaying = false;
        this.state.isPaused = false;
        this.state.isStopped = true;
        this.state.position = 0;
        this.notifyStateChange();
    }
    async previous() {
        if (this.currentIndex > 0) {
            this.currentIndex--;
            await this.loadCurrentTrack();
        }
        else if (this.state.repeatMode === 'all') {
            this.currentIndex = this.queue.length - 1;
            await this.loadCurrentTrack();
        }
    }
    async next() {
        if (this.currentIndex < this.queue.length - 1) {
            this.currentIndex++;
            await this.loadCurrentTrack();
        }
        else if (this.state.repeatMode === 'all') {
            this.currentIndex = 0;
            await this.loadCurrentTrack();
        }
        else {
            await this.stop();
        }
    }
    async seekTo(position) {
        if (!this.audio)
            return;
        this.audio.currentTime = position;
        this.state.position = position;
        this.notifyStateChange();
    }
    async setQueue(tracks) {
        this.queue = [...tracks];
        this.currentIndex = 0;
        if (tracks.length > 0) {
            await this.loadCurrentTrack();
        }
    }
    async addTracks(tracks) {
        this.queue.push(...tracks);
    }
    async removeTrack(trackId) {
        const index = this.queue.findIndex(track => track.id === trackId);
        if (index !== -1) {
            this.queue.splice(index, 1);
            if (this.currentIndex === index && this.queue.length > 0) {
                this.currentIndex = Math.min(this.currentIndex, this.queue.length - 1);
                await this.loadCurrentTrack();
            }
            else if (this.currentIndex > index) {
                this.currentIndex--;
            }
        }
    }
    async clearQueue() {
        this.queue = [];
        this.currentIndex = 0;
        await this.stop();
    }
    async getQueue() {
        return [...this.queue];
    }
    async getPlayerState() {
        return { ...this.state };
    }
    async setRepeatMode(mode) {
        this.state.repeatMode = mode;
        this.notifyStateChange();
    }
    async setShuffleMode(enabled) {
        this.state.shuffleMode = enabled;
        if (enabled) {
            this.shuffleQueue();
        }
        this.notifyStateChange();
    }
    async setVolume(volume) {
        if (!this.audio)
            return;
        const clampedVolume = Math.max(0, Math.min(1, volume));
        this.audio.volume = clampedVolume;
        this.state.volume = clampedVolume;
        this.notifyStateChange();
    }
    async setPlaybackRate(rate) {
        if (!this.audio)
            return;
        const clampedRate = Math.max(0.25, Math.min(4, rate));
        this.audio.playbackRate = clampedRate;
        this.state.playbackRate = clampedRate;
        this.notifyStateChange();
    }
    async loadCurrentTrack() {
        if (!this.audio || this.queue.length === 0)
            return;
        const track = this.queue[this.currentIndex];
        await this.prepare(track);
        this.notifyListeners('trackChange', { track });
    }
    setupEventListeners() {
        if (!this.audio)
            return;
        this.audio.addEventListener('loadstart', () => {
            this.state.isLoading = true;
            this.notifyListeners('loading', { isLoading: true });
            this.notifyStateChange();
        });
        this.audio.addEventListener('canplay', () => {
            this.state.isLoading = false;
            this.notifyListeners('loading', { isLoading: false });
            this.notifyStateChange();
        });
        this.audio.addEventListener('timeupdate', () => {
            this.state.position = this.audio.currentTime;
            this.state.duration = this.audio.duration || 0;
            this.notifyStateChange();
        });
        this.audio.addEventListener('ended', () => {
            if (this.state.repeatMode === 'one') {
                this.audio.currentTime = 0;
                this.audio.play();
            }
            else {
                this.next();
            }
            this.notifyListeners('playbackEnd', {});
        });
        this.audio.addEventListener('error', (e) => {
            this.state.isLoading = false;
            this.notifyListeners('loading', { isLoading: false });
            this.notifyListeners('error', {
                error: this.audio?.error?.message || 'Unknown audio error'
            });
            this.notifyStateChange();
        });
    }
    shuffleQueue() {
        for (let i = this.queue.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [this.queue[i], this.queue[j]] = [this.queue[j], this.queue[i]];
        }
    }
    notifyStateChange() {
        this.notifyListeners('playerStateChange', { state: this.state });
    }
}
