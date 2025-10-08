var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
import { WebPlugin } from '@capacitor/core';
export class AudioPlayerWeb extends WebPlugin {
    constructor() {
        super(...arguments);
        this.audio = null;
        this.queue = [];
        this.currentIndex = 0;
        this.state = {
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
    }
    initialize() {
        return __awaiter(this, void 0, void 0, function* () {
            this.audio = new Audio();
            this.setupEventListeners();
        });
    }
    prepare(track) {
        return __awaiter(this, void 0, void 0, function* () {
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
                yield this.audio.load();
            }
            catch (error) {
                this.state.isLoading = false;
                this.notifyListeners('error', { error: error instanceof Error ? error.message : 'Unknown error' });
                throw error;
            }
            finally {
                this.state.isLoading = false;
                this.notifyListeners('loading', { isLoading: false });
                this.notifyStateChange();
            }
        });
    }
    play() {
        return __awaiter(this, void 0, void 0, function* () {
            if (!this.audio) {
                throw new Error('Audio player not initialized');
            }
            if (!this.state.currentTrack) {
                throw new Error('No track prepared');
            }
            try {
                yield this.audio.play();
                this.state.isPlaying = true;
                this.state.isPaused = false;
                this.state.isStopped = false;
                this.notifyStateChange();
            }
            catch (error) {
                this.notifyListeners('error', { error: error instanceof Error ? error.message : 'Unknown error' });
                throw error;
            }
        });
    }
    pause() {
        return __awaiter(this, void 0, void 0, function* () {
            if (!this.audio)
                return;
            this.audio.pause();
            this.state.isPlaying = false;
            this.state.isPaused = true;
            this.notifyStateChange();
        });
    }
    stop() {
        return __awaiter(this, void 0, void 0, function* () {
            if (!this.audio)
                return;
            this.audio.pause();
            this.audio.currentTime = 0;
            this.state.isPlaying = false;
            this.state.isPaused = false;
            this.state.isStopped = true;
            this.state.position = 0;
            this.notifyStateChange();
        });
    }
    previous() {
        return __awaiter(this, void 0, void 0, function* () {
            if (this.currentIndex > 0) {
                this.currentIndex--;
                yield this.loadCurrentTrack();
            }
            else if (this.state.repeatMode === 'all') {
                this.currentIndex = this.queue.length - 1;
                yield this.loadCurrentTrack();
            }
        });
    }
    next() {
        return __awaiter(this, void 0, void 0, function* () {
            if (this.currentIndex < this.queue.length - 1) {
                this.currentIndex++;
                yield this.loadCurrentTrack();
            }
            else if (this.state.repeatMode === 'all') {
                this.currentIndex = 0;
                yield this.loadCurrentTrack();
            }
            else {
                yield this.stop();
            }
        });
    }
    seekTo(position) {
        return __awaiter(this, void 0, void 0, function* () {
            if (!this.audio)
                return;
            this.audio.currentTime = position;
            this.state.position = position;
            this.notifyStateChange();
        });
    }
    setQueue(tracks) {
        return __awaiter(this, void 0, void 0, function* () {
            this.queue = [...tracks];
            this.currentIndex = 0;
            if (tracks.length > 0) {
                yield this.loadCurrentTrack();
            }
        });
    }
    addTracks(tracks) {
        return __awaiter(this, void 0, void 0, function* () {
            this.queue.push(...tracks);
        });
    }
    removeTrack(trackId) {
        return __awaiter(this, void 0, void 0, function* () {
            const index = this.queue.findIndex(track => track.id === trackId);
            if (index !== -1) {
                this.queue.splice(index, 1);
                if (this.currentIndex === index && this.queue.length > 0) {
                    this.currentIndex = Math.min(this.currentIndex, this.queue.length - 1);
                    yield this.loadCurrentTrack();
                }
                else if (this.currentIndex > index) {
                    this.currentIndex--;
                }
            }
        });
    }
    clearQueue() {
        return __awaiter(this, void 0, void 0, function* () {
            this.queue = [];
            this.currentIndex = 0;
            yield this.stop();
        });
    }
    getQueue() {
        return __awaiter(this, void 0, void 0, function* () {
            return [...this.queue];
        });
    }
    getPlayerState() {
        return __awaiter(this, void 0, void 0, function* () {
            return Object.assign({}, this.state);
        });
    }
    setRepeatMode(mode) {
        return __awaiter(this, void 0, void 0, function* () {
            this.state.repeatMode = mode;
            this.notifyStateChange();
        });
    }
    setShuffleMode(enabled) {
        return __awaiter(this, void 0, void 0, function* () {
            this.state.shuffleMode = enabled;
            if (enabled) {
                this.shuffleQueue();
            }
            this.notifyStateChange();
        });
    }
    setVolume(volume) {
        return __awaiter(this, void 0, void 0, function* () {
            if (!this.audio)
                return;
            const clampedVolume = Math.max(0, Math.min(1, volume));
            this.audio.volume = clampedVolume;
            this.state.volume = clampedVolume;
            this.notifyStateChange();
        });
    }
    setPlaybackRate(rate) {
        return __awaiter(this, void 0, void 0, function* () {
            if (!this.audio)
                return;
            const clampedRate = Math.max(0.25, Math.min(4, rate));
            this.audio.playbackRate = clampedRate;
            this.state.playbackRate = clampedRate;
            this.notifyStateChange();
        });
    }
    loadCurrentTrack() {
        return __awaiter(this, void 0, void 0, function* () {
            if (!this.audio || this.queue.length === 0)
                return;
            const track = this.queue[this.currentIndex];
            yield this.prepare(track);
            this.notifyListeners('trackChange', { track });
        });
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
        this.audio.addEventListener('error', () => {
            var _a, _b;
            this.state.isLoading = false;
            this.notifyListeners('loading', { isLoading: false });
            this.notifyListeners('error', {
                error: ((_b = (_a = this.audio) === null || _a === void 0 ? void 0 : _a.error) === null || _b === void 0 ? void 0 : _b.message) || 'Unknown audio error'
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
//# sourceMappingURL=web.js.map