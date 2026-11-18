import SwiftUI
import Shared

@main
struct FiveEyeYetiApp: App {
    init() { AudioHost.shared.start() }
    var body: some Scene {
        WindowGroup {
            RootView()
                .statusBarHidden(true)
                .persistentSystemOverlays(.hidden)
        }
    }
}

/// Title screen, then the studio.
struct RootView: View {
    // `-studio` (used by the CI screenshot) opens straight into the studio
    @State private var inStudio = ProcessInfo.processInfo.arguments.contains("-studio")
    var body: some View {
        if inStudio { StudioView() } else { TitleView { inStudio = true } }
    }
}

struct TitleView: View {
    let start: () -> Void
    @State private var blink = true
    var body: some View {
        ZStack {
            Look.night.ignoresSafeArea()
            StarField()
            VStack(spacing: 28) {
                Text("5 EYE YETI")
                    .font(Look.head(34))
                    .foregroundColor(Color(red: 0.98, green: 0.92, blue: 0.70))
                    .shadow(color: Look.ink, radius: 0, x: 4, y: 4)
                Text("TAP TO START")
                    .font(Look.head(12))
                    .foregroundColor(.white)
                    .opacity(blink ? 1 : 0.15)
                    .animation(.easeInOut(duration: 0.7).repeatForever(), value: blink)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: start)
        .onAppear { blink.toggle() }
    }
}

/// A few twinkling pixel stars.
struct StarField: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 0.2)) { tl in
            Canvas { ctx, size in
                let t = tl.date.timeIntervalSinceReferenceDate
                var seed: UInt64 = 7
                func rnd() -> CGFloat { seed = seed &* 6364136223846793005 &+ 1442695040888963407; return CGFloat(seed >> 33) / CGFloat(1 << 31) }
                for k in 0..<70 {
                    let x = rnd() * size.width, y = rnd() * size.height * 0.8
                    let tw = 0.5 + 0.5 * sin(t * (1.0 + Double(k % 5) * 0.4) + Double(k))
                    let s: CGFloat = k % 9 == 0 ? 3 : 2
                    ctx.fill(Path(CGRect(x: x, y: y, width: s, height: s)), with: .color(.white.opacity(0.35 + 0.6 * tw)))
                }
            }
        }
        .ignoresSafeArea()
    }
}
