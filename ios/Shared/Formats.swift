import Foundation

/// Numbers as the phone's language writes them. Only real numbers from the unit or the map pass through here: nothing in
/// the app estimates a time.
enum Formats {
    static var isArabic: Bool {
        (Bundle.main.preferredLocalizations.first ?? "ar").hasPrefix("ar")
    }

    private static let integers: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        formatter.usesGroupingSeparator = false
        return formatter
    }()

    static func number(_ value: Int) -> String {
        integers.string(from: NSNumber(value: value)) ?? String(value)
    }

    private static func oneDecimal(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(1)).grouping(.never))
    }

    /// The unit's clock text ("9:41"), in the phone's digits.
    static func clock(_ text: String) -> String {
        var out = ""
        for character in text {
            if let digit = character.wholeNumberValue, character.isASCII {
                out += number(digit)
            } else {
                out.append(character)
            }
        }
        return out
    }

    /// A time the unit reported, in seconds.
    static func duration(seconds: Double) -> String {
        let total = Int((seconds / 60).rounded())
        if total < 1 { return isArabic ? "أقل من دقيقة" : "under a minute" }
        let hours = total / 60
        let minutes = total % 60
        if hours == 0 { return minutesText(minutes) }
        if minutes == 0 { return hoursText(hours) }
        return isArabic ? "\(hoursText(hours)) و\(minutesText(minutes))" : "\(hoursText(hours)) \(minutesText(minutes))"
    }

    private static func minutesText(_ value: Int) -> String {
        guard isArabic else { return "\(number(value)) min" }
        return arabicCount(value, one: "دقيقة", two: "دقيقتين", few: "دقايق", many: "دقيقة")
    }

    private static func hoursText(_ value: Int) -> String {
        guard isArabic else { return "\(number(value)) h" }
        return arabicCount(value, one: "ساعة", two: "ساعتين", few: "ساعات", many: "ساعة")
    }

    static func days(_ value: Int) -> String {
        guard isArabic else { return value == 1 ? "1 day" : "\(number(value)) days" }
        if value <= 0 { return "أقل من يوم" }
        return arabicCount(value, one: "يوم", two: "يومين", few: "أيام", many: "يوم")
    }

    static func attempts(_ attempt: Int, of total: Int) -> String {
        isArabic ? "المحاولة \(number(attempt)) من \(number(total))" : "Try \(attempt) of \(total)"
    }

    private static func arabicCount(_ value: Int, one: String, two: String, few: String, many: String) -> String {
        switch value {
        case 1: return one
        case 2: return two
        case 3...10: return "\(number(value)) \(few)"
        default: return "\(number(value)) \(many)"
        }
    }

    static func distance(meters: Double) -> String {
        if meters < 1000 {
            let rounded = Int((meters / 10).rounded()) * 10
            return isArabic ? "\(number(rounded)) م" : "\(number(rounded)) m"
        }
        let km = meters / 1000
        let text = km < 10 ? oneDecimal(km) : number(Int(km.rounded()))
        return isArabic ? "\(text) كم" : "\(text) km"
    }

    /// "الوصول ٩:٤١"
    static func arrival(_ etaLocal: String) -> String {
        isArabic ? "الوصول \(clock(etaLocal))" : "Arrive \(clock(etaLocal))"
    }

    /// Joins the parts that exist.
    static func dotted(_ parts: [String?]) -> String {
        parts.compactMap { $0 }.joined(separator: " · ")
    }

    /// The unit's reasons are short codes. In plain words when known, as they came when not.
    static func reason(_ raw: String?) -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        switch raw {
        case "no_fix": return String(localized: "الشاشة ما عندها إشارة موقع")
        case "no_route": return String(localized: "ما لقت طريق لهذا المكان")
        case "timeout": return String(localized: "طال حساب الطريق")
        case "address_not_found": return String(localized: "ما لقت هذا العنوان")
        case "reversing": return String(localized: "السيارة ترجع للخلف")
        case "busy": return String(localized: "الشاشة مشغولة، حاول بعد شوي")
        case "not_set": return String(localized: "هذا المكان مو محدد على الشاشة")
        case "pair", "refused": return String(localized: "الجوال مو مربوط بهذي الشاشة")
        case "apple_not_kept": return String(localized: "الشاشة ما تحفظ أماكن خرائط أبل")
        case "no_code": return String(localized: "افتح صفحة الربط على شاشة السيارة أول")
        case "bad_code": return String(localized: "الرمز غلط")
        default: return raw
        }
    }
}
