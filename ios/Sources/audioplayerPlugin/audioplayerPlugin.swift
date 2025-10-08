import Foundation
import Capacitor
import AVFoundation
import MediaPlayer
import UIKit // needed for UIImage & artwork

public struct AudioTrack: Codable {
    let id: String
    let title: String
    let artist: String
    let album: String?
    let duration: Double?
    let url: String
    let artwork: String?
}

@objc(AudioPlayerPlugin)
public class AudioPlayerPlugin: CAPPlugin {
    
    // MARK: - Player Properties
    private var audioPlayer: AVAudioPlayer?
    private var player: AVPlayer?
    private var playerItem: AVPlayerItem?
    
    private var trackQueue: [AudioTrack] = []
    private var currentIndex: Int = 0
    private var repeatMode: String = "none" // "none" | "one" | "all"
    private var shuffleMode: Bool = false
    private var nowPlayingInfoCenter: MPNowPlayingInfoCenter?
    
    // Observation and Update Properties
    private var playerTimeObserver: Any?
    private var stateUpdateTimer: Timer?
    private var lastErrorMessage: String?
    private var playerItemStatusObserver: NSKeyValueObservation?
    private var playerItemEndObserver: NSObjectProtocol?
    
    // MARK: - Plugin Lifecycle
    override public func load() {
        super.load()
        setupAudioSession()
        setupRemoteControls()
        nowPlayingInfoCenter = MPNowPlayingInfoCenter.default()
    }
    
    // MARK: - Audio Session Setup
    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.allowAirPlay, .allowBluetooth])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            handleError(error, context: "Audio Session Setup")
        }
    }
    
    // MARK: - Remote Controls Setup
    private func setupRemoteControls() {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        commandCenter.playCommand.removeTarget(nil)
        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.playCurrentTrack()
            return .success
        }
        
        commandCenter.pauseCommand.removeTarget(nil)
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.pauseCurrentTrack()
            return .success
        }
        
        commandCenter.stopCommand.removeTarget(nil)
        commandCenter.stopCommand.addTarget { [weak self] _ in
            self?.stopCurrentTrack()
            return .success
        }
        
        commandCenter.nextTrackCommand.removeTarget(nil)
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.nextTrack()
            return .success
        }
        
        commandCenter.previousTrackCommand.removeTarget(nil)
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.previousTrack()
            return .success
        }
        
        commandCenter.changePlaybackPositionCommand.removeTarget(nil)
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            if let event = event as? MPChangePlaybackPositionCommandEvent {
                self?.seekTo(position: event.positionTime)
                return .success
            }
            return .commandFailed
        }
    }
    
    // MARK: - State Update Methods
    private func setupPeriodicStateUpdates() {
        // Timer for state updates (every 1s)
        if stateUpdateTimer == nil {
            DispatchQueue.main.async {
                self.stateUpdateTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                    guard let self = self else { return }
                    self.runBackgroundSafe {
                        self.notifyPlayerStateChange()
                    }
                }
            }
        }
        
        // Time observer for AVPlayer (for more frequent position updates)
        if let player = player, playerTimeObserver == nil {
            playerTimeObserver = player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 1, preferredTimescale: CMTimeScale(NSEC_PER_SEC)),
                queue: .main
            ) { [weak self] _ in
                guard let self = self else { return }
                self.runBackgroundSafe {
                    self.notifyPlayerStateChange()
                }
            }
        }
    }

    private func runBackgroundSafe(_ block: @escaping () -> Void) {
        let taskId = UIApplication.shared.beginBackgroundTask(expirationHandler: nil)
        DispatchQueue.main.async {
            block()
            UIApplication.shared.endBackgroundTask(taskId)
        }
    }
    
    private func cleanupStateUpdates() {
        // Remove time observer safely
        if let player = player, let timeObserver = playerTimeObserver {
            player.removeTimeObserver(timeObserver)
            playerTimeObserver = nil
        }
        
        // Invalidate timer
        stateUpdateTimer?.invalidate()
        stateUpdateTimer = nil
        
        // Remove KVO / Notification for player item
        playerItemStatusObserver?.invalidate()
        playerItemStatusObserver = nil
        
        if let token = playerItemEndObserver {
            NotificationCenter.default.removeObserver(token)
            playerItemEndObserver = nil
        }
    }
    
    // MARK: - Seeking Method
    private func seekTo(position: TimeInterval) {
        if let audioPlayer = audioPlayer {
            audioPlayer.currentTime = position
            runBackgroundSafe {
                self.updateNowPlayingInfo()
                self.notifyPlayerStateChange()
            }
        } else if let player = player {
            guard let currentItem = player.currentItem, currentItem.status == .readyToPlay else {
                // Wait until ready
                playerItemStatusObserver = player.currentItem?.observe(\.status, options: [.new, .initial]) { [weak self] item, _ in
                    if item.status == .readyToPlay {
                        self?.seekTo(position: position)
                    }
                }
                return
            }

            let time = CMTime(seconds: position, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            runBackgroundSafe {
                player.seek(to: time) { [weak self] _ in
                    self?.updateNowPlayingInfo()
                    self?.notifyPlayerStateChange()
                }
            }
        }
    }
    
    // MARK: - Track Loading
    private func loadCurrentTrack() {
        guard !trackQueue.isEmpty else { return }
        guard currentIndex >= 0 && currentIndex < trackQueue.count else { return }
        
        let track = trackQueue[currentIndex]
        
        // Clean up existing players & observers
        cleanupStateUpdates()
        audioPlayer = nil
        player?.pause()
        player = nil
        playerItem = nil
        
        do {
            if track.url.hasPrefix("http") {
                // Remote URL: Use AVPlayer
                guard let url = URL(string: track.url) else {
                    throw NSError(domain: "AudioPlayerPlugin", code: -2, userInfo: [NSLocalizedDescriptionKey: "Invalid track URL"])
                }
                
                playerItem = AVPlayerItem(url: url)
                player = AVPlayer(playerItem: playerItem)
                
                playerItemStatusObserver = playerItem?.observe(\.status, options: [.initial, .new], changeHandler: { [weak self] (item, _) in
                    guard let self = self else { return }
                    switch item.status {
                    case .readyToPlay:
                        self.player?.play()
                        self.setupPeriodicStateUpdates()
                        self.updateNowPlayingInfo()
                        self.notifyTrackChange(track: track)
                    case .failed:
                        if let err = item.error {
                            self.handleError(err, context: "AVPlayerItem failed")
                        } else {
                            let err = NSError(domain: "AudioPlayerPlugin", code: -3, userInfo: [NSLocalizedDescriptionKey: "AVPlayerItem failed with unknown error"])
                            self.handleError(err, context: "AVPlayerItem failed")
                        }
                    default:
                        break
                    }
                })
                
                playerItemEndObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: playerItem, queue: .main) { [weak self] _ in
                    self?.playerDidFinishPlaying()
                }
                
            } else {
                // Local file: Use AVAudioPlayer
                let url = URL(fileURLWithPath: track.url)
                audioPlayer = try AVAudioPlayer(contentsOf: url)
                audioPlayer?.delegate = self
                audioPlayer?.enableRate = true
                audioPlayer?.prepareToPlay()
                audioPlayer?.play()
                
                setupPeriodicStateUpdates()
                updateNowPlayingInfo()
                notifyTrackChange(track: track)
            }
            
            lastErrorMessage = nil
        } catch {
            handleError(error, context: "loadCurrentTrack")
        }
    }
    
    // MARK: - Playback Control Methods
    private func playCurrentTrack() {
        if let audioPlayer = audioPlayer {
            audioPlayer.play()
        } else if let player = player {
            player.play()
        } else {
            if !trackQueue.isEmpty {
                loadCurrentTrack()
                return
            }
        }
        
        // Start timer/observer if not running
        setupPeriodicStateUpdates()
        
        updateNowPlayingInfo()
        notifyPlayerStateChange()
    }
    
    private func pauseCurrentTrack() {
        audioPlayer?.pause()
        player?.pause()
        
        // Stop interval/observer when paused
        cleanupStateUpdates()
        
        updateNowPlayingInfo()
        notifyPlayerStateChange()
    }
    
    private func stopCurrentTrack() {
        audioPlayer?.stop()
        audioPlayer?.currentTime = 0
        if let player = player {
            player.pause()
            seekTo(position: 0)
        }
        
        cleanupStateUpdates()
        
        updateNowPlayingInfo()
        notifyPlayerStateChange()
    }
    
    private func nextTrack() {
        if shuffleMode {
            currentIndex = Int.random(in: 0..<trackQueue.count)
        } else {
            if currentIndex < trackQueue.count - 1 {
                currentIndex += 1
            } else if repeatMode == "all" {
                currentIndex = 0
            } else {
                stopCurrentTrack()
                return
            }
        }
        loadCurrentTrack()
    }
    
    private func previousTrack() {
        let currentPosition = audioPlayer?.currentTime ?? player?.currentTime().seconds ?? 0
        if currentPosition > 3 {
            seekTo(position: 0)
            return
        }
        if currentIndex > 0 {
            currentIndex -= 1
            loadCurrentTrack()
        } else if repeatMode == "all" {
            currentIndex = max(0, trackQueue.count - 1)
            loadCurrentTrack()
        } else {
            seekTo(position: 0)
        }
    }
    
    private func playerDidFinishPlaying() {
        if repeatMode == "one" {
            if let player = player {
                player.seek(to: .zero) { [weak self] _ in
                    self?.player?.play()
                }
            } else {
                audioPlayer?.currentTime = 0
                audioPlayer?.play()
            }
        } else {
            nextTrack()
        }

        // Background-safe notification to JS
        runBackgroundSafe { [weak self] in
            self?.notifyListeners("playbackEnd", data: [:])
        }
    }
    
    // MARK: - Now Playing Info
    private func updateNowPlayingInfo() {
        guard currentIndex >= 0 && currentIndex < trackQueue.count else {
            nowPlayingInfoCenter?.nowPlayingInfo = nil
            return
        }
        
        let track = trackQueue[currentIndex]
        
        var nowPlayingInfo: [String: Any] = [:]
        nowPlayingInfo[MPMediaItemPropertyTitle] = track.title
        nowPlayingInfo[MPMediaItemPropertyArtist] = track.artist
        if let album = track.album { nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album }
        
        let elapsed = audioPlayer?.currentTime ?? player?.currentTime().seconds ?? 0
        let duration = audioPlayer?.duration ?? player?.currentItem?.duration.seconds ?? track.duration ?? 0
        
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
        
        let isPlaying = audioPlayer?.isPlaying ?? (player?.rate ?? 0 > 0)
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        
        if let artworkUrl = track.artwork, let url = URL(string: artworkUrl) {
            if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
                let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
            }
        }
        
        nowPlayingInfoCenter?.nowPlayingInfo = nowPlayingInfo
    }
    
    private func notifyPlayerStateChange() {
        let isPlaying = audioPlayer?.isPlaying ?? (player?.rate ?? 0 > 0)
        let position = Double(audioPlayer?.currentTime ?? player?.currentTime().seconds ?? 0)
        let duration = Double(audioPlayer?.duration ?? player?.currentItem?.duration.seconds ?? 0)
        let playbackRate = Double(audioPlayer?.rate ?? player?.rate ?? 1.0)
        let volume = Double(audioPlayer?.volume ?? player?.volume ?? 1.0)
        
        let currentTrackData: Any = (currentIndex < trackQueue.count && currentIndex >= 0) ? serializeTrack(trackQueue[currentIndex]) : NSNull()
        
        let state: [String: Any] = [
            "isPlaying": isPlaying,
            "isPaused": !isPlaying,
            "isStopped": !isPlaying && position == 0,
            "isLoading": (player?.currentItem?.status == .unknown) || false,
            "currentTrack": currentTrackData,
            "duration": duration,
            "position": position,
            "playbackRate": playbackRate,
            "repeatMode": repeatMode,
            "shuffleMode": shuffleMode,
            "volume": volume,
            "lastError": lastErrorMessage ?? NSNull()
        ]
        
        notifyListeners("playerStateChange", data: state)
    }
    
    private func serializeTrack(_ track: AudioTrack) -> [String: Any] {
        return [
            "id": track.id,
            "title": track.title,
            "artist": track.artist,
            "album": track.album ?? "",
            "duration": track.duration ?? 0.0,
            "url": track.url,
            "artwork": track.artwork ?? ""
        ]
    }
    
    private func handleError(_ error: Error, context: String? = nil) {
        let errorMessage = error.localizedDescription
        lastErrorMessage = errorMessage
        
        var errorData: [String: Any] = ["error": errorMessage]
        if let context = context {
            errorData["context"] = context
        }
        
        notifyListeners("error", data: errorData)
        print("Audio Player Error (\(context ?? "Unknown")): \(errorMessage)")
    }
    
    private func parseTrack(dict: [String: Any]) -> AudioTrack? {
        guard let id = dict["id"] as? String,
              let title = dict["title"] as? String,
              let artist = dict["artist"] as? String,
              let url = dict["url"] as? String else {
            return nil
        }
        let album = dict["album"] as? String
        let duration = dict["duration"] as? Double
        let artwork = dict["artwork"] as? String
        return AudioTrack(id: id, title: title, artist: artist, album: album, duration: duration, url: url, artwork: artwork)
    }
    
    private func parseTrackArray(array: [[String: Any]]) -> [AudioTrack] {
        return array.compactMap { parseTrack(dict: $0) }
    }
    
    // MARK: - Capacitor Plugin Methods (exposed)
    @objc func initialize(_ call: CAPPluginCall) {
        call.resolve()
    }
    
    @objc func prepare(_ call: CAPPluginCall) {
        if let trackDict = call.getObject("track") {
            guard let track = parseTrack(dict: trackDict) else {
                call.reject("Invalid track data")
                return
            }
            trackQueue = [track]
            currentIndex = 0
            loadCurrentTrack()
            call.resolve()
            return
        } else if let trackArray = call.getArray("tracks") as? [[String: Any]] {
            let tracks = parseTrackArray(array: trackArray)
            if tracks.isEmpty {
                call.reject("Invalid tracks array")
                return
            }
            trackQueue = tracks
            currentIndex = 0
            loadCurrentTrack()
            call.resolve()
            return
        }
        
        if let trackDict = call.options as? [String: Any], let track = parseTrack(dict: trackDict) {
            trackQueue = [track]
            currentIndex = 0
            loadCurrentTrack()
            call.resolve()
            return
        }
        
        call.reject("Invalid track data")
    }
    
    @objc func play(_ call: CAPPluginCall) {
        playCurrentTrack()
        call.resolve()
    }
    
    @objc func pause(_ call: CAPPluginCall) {
        pauseCurrentTrack()
        call.resolve()
    }
    
    @objc func stop(_ call: CAPPluginCall) {
        stopCurrentTrack()
        call.resolve()
    }
    
    @objc func next(_ call: CAPPluginCall) {
        nextTrack()
        call.resolve()
    }
    
    @objc func previous(_ call: CAPPluginCall) {
        previousTrack()
        call.resolve()
    }
    
    @objc func seekTo(_ call: CAPPluginCall) {
        var position: Double? = nil

        // Case 1: called as { position: 123 } (optional)
        if let pos = call.getDouble("position") {
            position = pos
        }
        // Case 2: called as raw number 123
        else if let posNumber = call.options as? NSNumber {
            position = posNumber.doubleValue
        }

        guard let position = position else {
            call.reject("Position must be a number")
            return
        }

        seekTo(position: position)
        call.resolve()
    }
    
    @objc func setQueue(_ call: CAPPluginCall) {
        guard let tracks = call.getArray("tracks") as? [[String: Any]] else {
            call.reject("tracks must be an array")
            return
        }
        let parsed = parseTrackArray(array: tracks)
        if parsed.isEmpty {
            call.reject("Invalid tracks")
            return
        }
        trackQueue = parsed
        currentIndex = call.getInt("currentIndex") ?? 0
        loadCurrentTrack()
        call.resolve()
    }
    
    @objc func setRepeatMode(_ call: CAPPluginCall) {
        if let mode = call.getString("mode") {
            repeatMode = mode
            call.resolve()
        } else {
            call.reject("mode is required")
        }
    }
    
    @objc func setShuffleMode(_ call: CAPPluginCall) {
        if let shuffle = call.getBool("shuffle") {
            shuffleMode = shuffle
            call.resolve()
        } else {
            call.reject("shuffle is required")
        }
    }
    
    @objc func setVolume(_ call: CAPPluginCall) {
        guard let volume = call.getDouble("volume") else {
            call.reject("volume must be provided as number (0.0 - 1.0)")
            return
        }
        let clamped = Float(max(0.0, min(1.0, volume)))
        if let audioPlayer = audioPlayer {
            audioPlayer.volume = clamped
        }
        if let player = player {
            player.volume = clamped
        }
        call.resolve()
    }
    
    @objc func setPlaybackRate(_ call: CAPPluginCall) {

        guard let rate = call.getDouble("rate") else {
            print("⚡ Rate not provided or invalid")
            call.reject("rate must be provided as number")
            return
        }

        print("⚡ Received playback rate:", rate)

        let rateF = Float(rate)
        if let audioPlayer = audioPlayer {
            audioPlayer.enableRate = true
            audioPlayer.rate = rateF
        }
        if let player = player {
            player.rate = rateF
        }
        updateNowPlayingInfo()
        call.resolve()
    }
    
    @objc func getState(_ call: CAPPluginCall) {
        let isPlaying = audioPlayer?.isPlaying ?? (player?.rate ?? 0 > 0)
        let position = Double(audioPlayer?.currentTime ?? player?.currentTime().seconds ?? 0)
        let duration = Double(audioPlayer?.duration ?? player?.currentItem?.duration.seconds ?? 0)
        let playbackRate = Double(audioPlayer?.rate ?? player?.rate ?? 1.0)
        let volume = Double(audioPlayer?.volume ?? player?.volume ?? 1.0)
        let currentTrackData: Any = (currentIndex < trackQueue.count && currentIndex >= 0) ? serializeTrack(trackQueue[currentIndex]) : NSNull()
        
        let state: [String: Any] = [
            "isPlaying": isPlaying,
            "position": position,
            "duration": duration,
            "playbackRate": playbackRate,
            "repeatMode": repeatMode,
            "shuffleMode": shuffleMode,
            "volume": volume,
            "currentTrack": currentTrackData,
            "lastError": lastErrorMessage ?? NSNull()
        ]
        call.resolve(state)
    }
    
    private func notifyTrackChange(track: AudioTrack) {
        notifyListeners("trackChange", data: serializeTrack(track))
    }
    
    deinit {
        cleanupStateUpdates()
        try? AVAudioSession.sharedInstance().setActive(false, options: [])
    }
}

// MARK: - AVAudioPlayerDelegate
extension AudioPlayerPlugin: AVAudioPlayerDelegate {
    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        if repeatMode == "one" {
            player.currentTime = 0
            player.play()
        } else {
            nextTrack()
        }
        notifyListeners("playbackEnd", data: [:])
    }
}
