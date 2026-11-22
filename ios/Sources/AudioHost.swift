import AVFoundation
import Shared

/// Drives the shared Kotlin sound core from AVAudioEngine: a source node whose
/// render callback asks `IosAudio` for each buffer (two non-interleaved channels).
final class AudioHost {
    static let shared = AudioHost()
    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode?

    func start() {
        guard source == nil else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
        try? session.setPreferredIOBufferDuration(0.005)
        try? session.setActive(true)
        let rate = session.sampleRate > 0 ? session.sampleRate : 48_000
        IosAudio.shared.configure(sampleRate: rate)
        guard let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2) else { return }
        let node = AVAudioSourceNode(format: format) { _, _, frameCount, audioBufferList -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard buffers.count >= 2,
                  let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                  let right = buffers[1].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            IosAudio.shared.render(left: left, right: right, frames: Int32(frameCount))
            return noErr
        }
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        do { try engine.start() } catch { print("Audio didn't start: \(error)") }
        source = node
        // after a call or an alarm: take the audio session back and restart the engine
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .ended else { return }
            try? AVAudioSession.sharedInstance().setActive(true)
            if self?.engine.isRunning == false { try? self?.engine.start() }
        }
        // headphones or an interface plugged in or pulled out: the engine stops itself; start it again
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            if self?.engine.isRunning == false { try? self?.engine.start() }
        }
    }
}
