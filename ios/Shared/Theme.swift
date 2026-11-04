import SwiftUI
import UIKit

/// The look, in one place. These are the colours of AMRI 2.0's final design (tmp/v2/design/final2/tokens.json, color.day and
/// color.night): a familiar modern car screen, one blue accent. Changing them here changes the whole app and the share sheet.
/// The type stays the system's: a phone needs no font of its own.
enum Theme {
    // MARK: Colours

    static let ground = dynamic(light: 0xEBEEF3, dark: 0x0B0D12)
    /// The bottom sheet over the map. Solid: glass lets the map's colours through, and this is read in a car in the sun.
    static let panel = dynamic(light: 0xF6F7F9, dark: 0x14171D)
    static let raised = dynamic(light: 0xFFFFFF, dark: 0x1F232C)
    /// A field or a quiet key, on any of the above.
    static let sunken = dynamicAlpha(light: (0x0E141E, 0.07), dark: (0xFFFFFF, 0.1))
    static let ink = dynamic(light: 0x0E131B, dark: 0xF4F6F9)
    static let ink2 = dynamic(light: 0x3F4856, dark: 0xB7BFCC)
    static let ink3 = dynamic(light: 0x596270, dark: 0x8D96A5)
    static let rule = dynamicAlpha(light: (0x0E141E, 0.09), dark: (0xFFFFFF, 0.09))
    /// The accent: a trip under way, the place in question, the one main key.
    static let signal = dynamic(light: 0x2E63F9, dark: 0x5C8BFF)
    static let key = dynamic(light: 0x2E63F9, dark: 0x2E63F9)
    static let keyText = dynamic(light: 0xFFFFFF, dark: 0xFFFFFF)
    static let good = dynamic(light: 0x1BA548, dark: 0x30D158)
    static let warn = dynamic(light: 0xA85B00, dark: 0xFFB454)
    static let bad = dynamic(light: 0xD92D20, dark: 0xFF6B61)

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

    private static func dynamicAlpha(light: (UInt32, CGFloat), dark: (UInt32, CGFloat)) -> Color {
        Color(uiColor: UIColor { traits in
            let pick = traits.userInterfaceStyle == .dark ? dark : light
            return uiColor(pick.0).withAlphaComponent(pick.1)
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
    /// What floats over the map: solid, as the panel is, because over a map's colours glass is hard to read in a car.
    func floatingSurface<S: Shape>(in shape: S) -> some View {
        self
            .background(Theme.raised, in: shape)
            .shadow(color: .black.opacity(0.16), radius: 12, y: 4)
    }

    /// A card inside a panel.
    func cardSurface() -> some View {
        self
            .padding(Theme.Space.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
    }
}
