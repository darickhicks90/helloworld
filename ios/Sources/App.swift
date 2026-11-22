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

/// The title scene (the same as on Android): the little guy from the icon standing in a field at
/// night, mountains behind him and the sky full of stars. He breathes; the bright stars twinkle.
struct TitleView: View {
    let start: () -> Void
    @State private var blink = true
    private static func image(_ name: String, _ ext: String) -> UIImage? {
        Bundle.main.url(forResource: name, withExtension: ext).flatMap { UIImage(contentsOfFile: $0.path) }
    }
    private static let scene = image("title_scene", "jpg")
    private static let yeti = image("title_yeti", "png")
    // scene pixels (art/title/title_scene.py writes these to title_scene.json)
    private static let sceneW: CGFloat = 2400, sceneH: CGFloat = 1080, yetiX: CGFloat = 1880, yetiFeet: CGFloat = 774
    private static let twinkles: [(CGFloat, CGFloat, CGFloat)] = [(180, 274, 22.5), (794, 374, 18), (360, 85, 18), (1136, 103, 18), (1359, 87, 18),
        (1047, 173, 18), (2284, 313, 17.5), (2376, 358, 14), (535, 134, 14), (2255, 369, 14), (536, 323, 11.25), (61, 344, 11), (1226, 306, 9)]
    private static let mint = Color(red: 0.87, green: 0.95, blue: 0.83)

    var body: some View {
        ZStack(alignment: .topLeading) {
            // the scene, its twinkles and the yeti in one canvas exactly the size of the screen
            // (an oversized image in the layout would stretch it and push the title off the edge)
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                Canvas { ctx, size in
                    let w = size.width, h = size.height
                    let t = tl.date.timeIntervalSinceReferenceDate
                    // fill the screen, keeping the right edge (where he stands)
                    let k = max(w / Self.sceneW, h / Self.sceneH)
                    let ox = w - Self.sceneW * k, oy = (h - Self.sceneH * k) / 2
                    ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Look.night))
                    if let s = Self.scene { ctx.draw(Image(uiImage: s), in: CGRect(x: ox, y: oy, width: Self.sceneW * k, height: Self.sceneH * k)) }
                    for (i, s) in Self.twinkles.enumerated() {
                        let p = 0.5 + 0.5 * sin(t * (0.9 + 0.23 * Double(i % 5)) + Double(i) * 2.1)
                        if p < 0.55 { continue }
                        let a = (p - 0.55) / 0.45
                        let x = ox + s.0 * k, y = oy + s.1 * k, r = s.2 * k * (1.2 + 0.9 * a)
                        var path = Path()
                        path.move(to: CGPoint(x: x, y: y - r)); path.addLine(to: CGPoint(x: x, y: y + r))
                        path.move(to: CGPoint(x: x - r * 0.8, y: y)); path.addLine(to: CGPoint(x: x + r * 0.8, y: y))
                        ctx.stroke(path, with: .color(Self.mint.opacity(0.8 * a)), style: StrokeStyle(lineWidth: max(1, s.2 * k * 0.14), lineCap: .round))
                    }
                    if let y = Self.yeti {
                        let breathe = 1 + 0.014 * CGFloat(sin(t * 1.7))
                        let yw = y.size.width * k * (2 - breathe), yh = y.size.height * k * breathe
                        ctx.draw(Image(uiImage: y), in: CGRect(x: ox + Self.yetiX * k - yw / 2, y: oy + Self.yetiFeet * k - yh, width: yw, height: yh))
                    }
                }
            }
            .ignoresSafeArea()
            VStack(alignment: .leading, spacing: 14) {
                Text("5 EYE YETI")
                    .font(Look.head(30))
                    .foregroundColor(Self.mint)
                    .shadow(color: Color.black.opacity(0.7), radius: 0, x: 3, y: 3)
                Text("TAP TO START")
                    .font(Look.head(11))
                    .foregroundColor(Self.mint)
                    .opacity(blink ? 1 : 0.2)
                    .animation(.easeInOut(duration: 0.8).repeatForever(), value: blink)
            }
            .padding(.leading, 24).padding(.top, 20)   // inside the safe area, so clear of the notch
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: start)
        .onAppear { blink.toggle() }
    }
}
