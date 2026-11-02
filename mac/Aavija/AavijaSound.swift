import AVFoundation
import Foundation

/// Aavija's sound effects.
///
/// Five tiny synthesized WAVs ship in the app bundle:
///   - `capture`            camera-shutter click + glassy chime
///   - `transfer_start`     soft airy whoosh
///   - `transfer_complete`  warm two-note success chime
///   - `eyes_close`         a soft downward "lid" sigh
///   - `eyes_open`          a bright upward "wake" chime
///
/// We keep several players alive so a fast screenshot followed by a transfer
/// start doesn't cut the first sound off. Nothing here touches the network or
/// the disk at play time.
@MainActor
final class AavijaSound {
    static let shared = AavijaSound()

    private var players: [AVAudioPlayer] = []

    private func play(_ name: String, volume: Float) {
        // Reap finished players before adding another.
        players.removeAll { !$0.isPlaying }

        guard let url = Bundle.main.url(forResource: name, withExtension: "wav"),
              let player = try? AVAudioPlayer(contentsOf: url)
        else { return }

        player.volume = volume
        player.prepareToPlay()
        player.play()
        players.append(player)
    }

    /// The screen was captured.
    func screenshot() {
        play("capture", volume: 0.78)
    }

    /// Bytes are about to move.
    func transferStart() {
        play("transfer_start", volume: 0.50)
    }

    /// A transfer finished successfully.
    func transferComplete() {
        play("transfer_complete", volume: 0.62)
    }

    /// The gesture eye closed: a hand grabbed.
    func eyesClose() {
        play("eyes_close", volume: 0.54)
    }

    /// The gesture eye opened: a hand released.
    func eyesOpen() {
        play("eyes_open", volume: 0.58)
    }
}
