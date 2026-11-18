import SwiftUI

/// The app's look, matched to the Android app: 16-bit console grey plastic
/// with red, yellow, green and purple details, and the two pixel fonts.
enum Look {
    static let hi = Color(red: 0.93, green: 0.93, blue: 0.95)
    static let face = Color(red: 0.79, green: 0.79, blue: 0.82)
    static let faceD = Color(red: 0.70, green: 0.70, blue: 0.75)
    static let shade = Color(red: 0.56, green: 0.56, blue: 0.61)
    static let deep = Color(red: 0.37, green: 0.37, blue: 0.43)
    static let ink = Color(red: 0.15, green: 0.14, blue: 0.18)
    static let red = Color(red: 0.85, green: 0.25, blue: 0.24)
    static let yellow = Color(red: 0.93, green: 0.72, blue: 0.14)
    static let green = Color(red: 0.21, green: 0.64, blue: 0.30)
    static let purple = Color(red: 0.39, green: 0.28, blue: 0.71)
    static let accents = [red, yellow, green, purple]
    static func accent(_ k: Int) -> Color { accents[((k % 4) + 4) % 4] }

    // the night meadow behind everything (from the Android studio)
    static let night = Color(red: 0.10, green: 0.11, blue: 0.20)

    static func head(_ size: CGFloat) -> Font { .custom("PressStart2P-Regular", size: size) }
    static func body(_ size: CGFloat) -> Font { .custom("PixelifySans-Regular", size: size) }
}

/// A raised grey plate with a bevel and an ink outline.
struct Plate: ViewModifier {
    var face: Color = Look.face
    func body(content: Content) -> some View {
        content
            .background(
                ZStack {
                    Rectangle().fill(Look.ink)
                    Rectangle().fill(Look.shade).padding(2)
                    Rectangle().fill(Look.hi).padding(EdgeInsets(top: 2, leading: 2, bottom: 4, trailing: 4))
                    Rectangle().fill(face).padding(4)
                }
            )
    }
}

extension View {
    func plate(_ face: Color = Look.face) -> some View { modifier(Plate(face: face)) }
}

/// A console key: grey, or lit in its colour when on.
struct Key: View {
    let title: String
    var color: Color = Look.purple
    var on = false
    var minWidth: CGFloat = 44
    let action: () -> Void
    @State private var pressed = false

    var body: some View {
        Text(title)
            .font(Look.body(15))
            .lineLimit(1).minimumScaleFactor(0.6)
            .foregroundColor(on ? .white : Look.ink)
            .padding(.horizontal, 10)
            .frame(minWidth: minWidth, minHeight: 38)
            .plate(pressed ? Look.hi : (on ? color : Look.face))
            .offset(y: pressed ? 1 : 0)
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in pressed = true }
                .onEnded { _ in pressed = false; action() })
    }
}

/// "◀ value ▶" with a small label above.
struct Stepper5: View {
    let label: String
    let value: String
    let step: (Int) -> Void
    var body: some View {
        HStack(spacing: 0) {
            Text("◀\u{FE0E}").font(Look.body(15)).frame(width: 22).contentShape(Rectangle()).onTapGesture { step(-1) }
            VStack(spacing: 0) {
                Text(label).font(Look.body(10)).foregroundColor(Look.deep).lineLimit(1).minimumScaleFactor(0.7)
                Text(value).font(Look.body(15)).lineLimit(1).minimumScaleFactor(0.6)
            }.frame(maxWidth: .infinity)
            Text("▶\u{FE0E}").font(Look.body(15)).frame(width: 22).contentShape(Rectangle()).onTapGesture { step(1) }
        }
        .foregroundColor(Look.ink)
        .frame(minHeight: 38)
        .plate()
    }
}
