import SwiftUI
import Shared

/// The studio, first iOS version: pick a block, play it with the XY pad and
/// the chord buttons (or the pads on Pickup and Minds), with the transport and
/// tempo along the top. The same Kotlin engines as the Android app make the sound.
struct StudioView: View {
    private let audio = IosAudio.shared
    @State private var block = "gusts"
    @State private var playing = false
    @State private var bpm: Float = IosAudio.shared.bpm
    @State private var root = 0
    @State private var octave = 0
    @State private var scale = 0
    @State private var seventh = false

    private var ids: [String] { audio.engineIds() }

    var body: some View {
        ZStack {
            Look.night.ignoresSafeArea()
            VStack(spacing: 6) {
                topBar
                HStack(spacing: 6) {
                    rail
                    XYPad(block: block).aspectRatio(1, contentMode: .fit)
                    if audio.isPads(id: block) {
                        PadGrid(block: block)
                    } else {
                        ChordColumn(block: block, root: $root, octave: $octave, scale: $scale, seventh: $seventh)
                    }
                }
            }
            .padding(8)
        }
        .onAppear { audio.setActive(ids: [block]) }
        .onChange(of: block) { b in audio.setActive(ids: [b]) }
    }

    private var topBar: some View {
        HStack(spacing: 6) {
            Text("5 EYE YETI").font(Look.head(11)).foregroundColor(Look.ink).padding(.horizontal, 10).frame(minHeight: 38).plate(Look.faceD)
            Key(title: "▶\u{FE0E}", color: Look.green, on: playing) { audio.play(); playing = true }
            Key(title: "■\u{FE0E}", color: Look.red) { audio.stop(); playing = false }
            TimelineView(.periodic(from: .now, by: 0.1)) { _ in
                Text(audio.position()).font(Look.body(14)).foregroundColor(Look.ink).frame(width: 80, height: 38).plate(Look.faceD)
            }
            Stepper5(label: "BPM", value: "\(Int(bpm))") { d in bpm = max(40, min(220, bpm + Float(d))); audio.bpm = bpm }.frame(width: 110)
            Spacer()
            Text(audio.title(id: block)).font(Look.head(10)).foregroundColor(.white)
        }
    }

    /// One key per block, down the left like the Android rail.
    private var rail: some View {
        VStack(spacing: 4) {
            ForEach(Array(ids.enumerated()), id: \.element) { k, id in
                Key(title: audio.title(id: id), color: Look.accent(k), on: id == block, minWidth: 96) { block = id }
            }
        }
        .frame(width: 104)
    }
}

/// The XY pad: drag to move the block's two chosen parameters.
struct XYPad: View {
    let block: String
    private let audio = IosAudio.shared
    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation) { _ in
                Canvas { ctx, size in
                    let w = size.width, h = size.height
                    ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Look.ink))
                    ctx.fill(Path(CGRect(x: 4, y: 4, width: w - 8, height: h - 8)), with: .color(Color(red: 0.16, green: 0.17, blue: 0.27)))
                    for k in 1..<8 {
                        let f = CGFloat(k) / 8
                        ctx.fill(Path(CGRect(x: 4 + (w - 8) * f, y: 4, width: 1, height: h - 8)), with: .color(.white.opacity(0.07)))
                        ctx.fill(Path(CGRect(x: 4, y: 4 + (h - 8) * f, width: w - 8, height: 1)), with: .color(.white.opacity(0.07)))
                    }
                    let x = CGFloat(audio.xyX(id: block)), y = CGFloat(audio.xyY(id: block))
                    let px = 4 + (w - 8) * x, py = 4 + (h - 8) * (1 - y)
                    ctx.fill(Path(CGRect(x: px - 9, y: py - 9, width: 18, height: 18)), with: .color(Look.ink))
                    ctx.fill(Path(CGRect(x: px - 7, y: py - 7, width: 14, height: 14)), with: .color(Look.yellow))
                    ctx.fill(Path(CGRect(x: px - 7, y: py - 7, width: 5, height: 5)), with: .color(.white))
                }
            }
            .overlay(alignment: .bottomLeading) {
                Text("X  " + audio.xyXName(id: block)).font(Look.body(12)).foregroundColor(Look.hi).padding(8)
            }
            .overlay(alignment: .topLeading) {
                Text("Y  " + audio.xyYName(id: block)).font(Look.body(12)).foregroundColor(Look.hi).padding(8)
            }
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { v in
                    audio.setXy(id: block, x: Float(v.location.x / geo.size.width), y: Float(1 - v.location.y / geo.size.height), touching: true)
                }
                .onEnded { v in
                    audio.setXy(id: block, x: Float(v.location.x / geo.size.width), y: Float(1 - v.location.y / geo.size.height), touching: false)
                })
        }
    }
}

/// Key, octave and scale, then one button per chord of the scale: hold to play.
struct ChordColumn: View {
    let block: String
    @Binding var root: Int
    @Binding var octave: Int
    @Binding var scale: Int
    @Binding var seventh: Bool
    private let audio = IosAudio.shared

    var body: some View {
        let names = audio.scaleNames()
        let count = Int(audio.chordCount(scale: Int32(scale)))
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                Stepper5(label: "Key", value: audio.noteName(n: Int32(root))) { d in root = ((root + d) % 12 + 12) % 12 }
                Stepper5(label: "Octave", value: audio.noteNameOct(n: Int32(48 + octave * 12 + root))) { d in octave = max(-2, min(2, octave + d)) }
                Stepper5(label: "Scale", value: names[((scale % names.count) + names.count) % names.count]) { d in scale = ((scale + d) % names.count + names.count) % names.count }
                    .frame(minWidth: 130)
            }
            ForEach(0..<count, id: \.self) { d in
                ChordKey(block: block, degree: d, root: root, octave: octave, scale: scale, seventh: seventh)
            }
            HStack {
                Key(title: "7th", color: Look.purple, on: seventh) { seventh.toggle() }
                Spacer()
            }
        }
    }
}

struct ChordKey: View {
    let block: String
    let degree: Int
    let root: Int, octave: Int, scale: Int
    let seventh: Bool
    private let audio = IosAudio.shared
    @State private var held: [Int32] = []

    var body: some View {
        let s = Int32(scale), r = Int32(root), o = Int32(octave), d = Int32(degree)
        HStack {
            Text(audio.chordRoman(scale: s, root: r, octave: o, degree: d, seventh: seventh)).font(Look.head(10)).foregroundColor(Look.accent(degree))
            Spacer()
            Text(audio.chordName(scale: s, root: r, octave: o, degree: d, seventh: seventh)).font(Look.body(18)).foregroundColor(held.isEmpty ? Look.ink : .white)
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .plate(held.isEmpty ? Look.face : Look.accent(degree))
        .gesture(DragGesture(minimumDistance: 0)
            .onChanged { _ in
                guard held.isEmpty else { return }
                let notes = audio.chordNotes(scale: s, root: r, octave: o, degree: d, seventh: seventh).map { $0.int32Value }
                let base = Int32(0x1000_0000) + d * 8
                for (i, n) in notes.enumerated() { audio.noteOn(id: block, key: base + Int32(i), note: n, vel: 0.8) }
                held = notes
            }
            .onEnded { _ in
                let base = Int32(0x1000_0000) + d * 8
                for i in 0..<max(held.count, 4) { audio.noteOff(id: block, key: base + Int32(i)) }
                held = []
            })
    }
}

/// Pickup's 16 pads or Minds' drums: pad 1 bottom left, like the hardware.
struct PadGrid: View {
    let block: String
    private let audio = IosAudio.shared
    var body: some View {
        let n = block == "minds" ? 8 : 16
        let rows = n / 4
        VStack(spacing: 6) {
            ForEach((0..<rows).reversed(), id: \.self) { r in
                HStack(spacing: 6) {
                    ForEach(0..<4, id: \.self) { c in Pad(block: block, index: r * 4 + c) }
                }
            }
        }
        .padding(8)
        .plate(Look.faceD)
    }
}

struct Pad: View {
    let block: String
    let index: Int
    private let audio = IosAudio.shared
    @State private var down = false
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text("\(index + 1)").font(Look.head(9)).foregroundColor(Look.deep)
                Spacer()
                Rectangle().fill(Look.accent(index / 4)).frame(width: 9, height: 9).overlay(Rectangle().stroke(Look.ink, lineWidth: 1))
            }
            Spacer()
            Text(audio.padName(id: block, i: Int32(index))).font(Look.body(12)).foregroundColor(down ? .white : Look.ink).lineLimit(1).minimumScaleFactor(0.6)
        }
        .padding(8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .plate(down ? Look.accent(index / 4) : Look.face)
        .gesture(DragGesture(minimumDistance: 0)
            .onChanged { v in
                guard !down else { return }
                down = true
                audio.noteOn(id: block, key: Int32(0x3200_0000 + index), note: Int32(36 + index), vel: 0.9)
            }
            .onEnded { _ in
                down = false
                audio.noteOff(id: block, key: Int32(0x3200_0000 + index))
            })
    }
}
