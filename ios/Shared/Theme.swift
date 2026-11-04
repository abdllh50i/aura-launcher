import SwiftUI
import UIKit

/// The look, in one place. AMRI 2.0's visual language is still being chosen: these tokens follow the direction now on the
/// table (ink on paper, one signal colour that only ever means "now"), and changing them here changes the whole app and
/// the share sheet.
enum Theme {
    // MARK: Colours

    static let ground = dynamic(light: 0xF4F2EB, dark: 0x0B0B0A)
    static let raised = dynamic(light: 0xFFFFFF, dark: 0x1C1C1A)
    static let sunken = dynamic(light: 0xE8E5DB, dark: 0x262523)
    static let ink = dynamic(light: 0x0E0E0C, dark: 0xF6F2E9)
    static let ink2 = dynamic(light: 0x45433D, dark: 0xBDB7AB)
    static let ink3 = dynamic(light: 0x6A6861, dark: 0x8F8A80)
    static let rule = dynamic(light: 0xCFCCC2, dark: 0x35332E)
    /// Only ever means "now": a trip under way, the unit's own started.
    static let signal = dynamic(light: 0xE03C00, dark: 0xFF4D14)
    static let key = dynamic(light: 0x0E0E0C, dark: 0xF6F2E9)
    static let keyText = dynamic(light: 0xF4F2EB, dark: 0x000000)
    static let good = dynamic(light: 0x0B7A4B, dark: 0x3DDC97)
    static let warn = dynamic(light: 0x9A5200, dark: 0xFFB454)
    static let bad = dynamic(light: 0xB3261E, dark: 0xFF7B72)

    // MARK: Measures

    enum Space {
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
    }

    enum Radius {
        static let chip: CGFloat = 22
        static let field: CGFloat = 18
        static let card: CGFloat = 20
        static let key: CGFloat = 18
    }

    enum Size {
        /// Nothing tappable is smaller than this: it is used in a car.
        static let target: CGFloat = 52
        static let mainKey: CGFloat = 60
        static let chip: CGFloat = 44
    }

    // MARK: Type

    enum Fonts {
        static let title = Font.system(.title2, design: .default).weight(.bold)
        static let headline = Font.system(.headline, design: .default).weight(.semibold)
        static let body = Font.system(.body, design: .default)
        static let note = Font.system(.subheadline, design: .default)
        static let small = Font.system(.footnote, design: .default)
        static let key = Font.system(.title3, design: .default).weight(.semibold)
        static let figure = Font.system(.title, design: .default).weight(.bold)
        static let mono = Font.system(.footnote, design: .monospaced)
    }

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? uiColor(dark) : uiColor(light)
        })
    }

    private static func uiColor(_ rgb: UInt32) -> UIColor {
        UIColor(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}

/// The one main action on a screen.
struct MainKeyStyle: ButtonStyle {
    var fill: Color = Theme.key
    var text: Color = Theme.keyText

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.Fonts.key)
            .foregroundStyle(text)
            .frame(maxWidth: .infinity, minHeight: Theme.Size.mainKey)
            .background(fill.opacity(configuration.isPressed ? 0.75 : 1), in: RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A second action, quieter.
struct SecondKeyStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.Fonts.headline)
            .foregroundStyle(Theme.ink)
            .frame(maxWidth: .infinity, minHeight: Theme.Size.target)
            .background(Theme.sunken.opacity(configuration.isPressed ? 0.6 : 1), in: RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
    }
}

extension View {
    /// The floating pieces over the map: glass on iOS 26, a material before it.
    @ViewBuilder
    func floatingSurface<S: Shape>(in shape: S) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: shape)
        } else {
            self.background(.regularMaterial, in: shape)
        }
    }

    /// A card inside a panel.
    func cardSurface() -> some View {
        self
            .padding(Theme.Space.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
    }
}
