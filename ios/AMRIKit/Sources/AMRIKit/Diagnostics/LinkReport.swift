import Foundation

/// One line of the MapKit part of the report.
public struct MapProbeRow: Equatable, Sendable {
    public var query: String
    public var count: Int
    public var nearestKm: Double?
    public var names: [String]
    public var problem: String?

    public init(query: String, count: Int = 0, nearestKm: Double? = nil, names: [String] = [], problem: String? = nil) {
        self.query = query
        self.count = count
        self.nearestKm = nearestKm
        self.names = names
        self.problem = problem
    }
}

/// Everything "فحص الربط" learned. Unknown stays nil and says so in the text.
public struct LinkFacts: Equatable, Sendable {
    public var appVersion = ""
    public var systemVersion = ""
    public var when = Date()

    public var signing: SigningFacts?
    public var storeIsShared = false
    public var storeGroup: String?

    public var interfaces: [NetInterface] = []
    public var localNetwork: LocalNetworkState = .unknown
    public var localNetworkNote: String?

    public var paired: Pairing?
    public var located: LocateOutcome?
    public var ping: FoundUnit?
    public var diag: String?
    public var diagProblem: String?
    public var snapshot: UnitSnapshot?

    public var adb: Repair.Scan?

    public var lastShare: StoredShare?

    public var nearPhone = false
    public var locationNote: String?
    public var mapRows: [MapProbeRow] = []
    public var categoryRows: [MapProbeRow] = []
    public var directions: String?

    public init() {}
}

/// The Arabic text he copies into a chat: the one channel from the car back to the work (`docs-digest.md:334`).
public enum LinkReport {
    public static func render(_ facts: LinkFacts) -> String {
        var lines: [String] = []
        let when = stamp(facts.when)
        lines.append("فحص الربط · عمري \(facts.appVersion) · iOS \(facts.systemVersion) · \(when)")

        lines.append("")
        lines.append("[الخلاصة]")
        lines.append(contentsOf: conclusion(facts))

        lines.append("")
        lines.append("[التوقيع]")
        if let signing = facts.signing {
            lines.append("معرّف التطبيق بعد التوقيع: \(signing.appBundleID ?? "غير معروف")")
            if signing.shareExtensionPresent {
                lines.append("معرّف الإرسال بعد التوقيع: \(signing.shareBundleID ?? "غير معروف")")
            } else {
                lines.append("الإرسال للسيارة: غير موجود في التطبيق. أداة التوقيع شالته؟")
            }
            lines.append("App Group للتطبيق: \(group(signing.appProfile, present: true))")
            if signing.shareExtensionPresent {
                lines.append("App Group للإرسال: \(group(signing.shareProfile, present: true))")
            }
            lines.append("التخزين المشترك: " + (facts.storeIsShared
                ? "يشتغل (\(facts.storeGroup ?? "")): الإرسال يقرأ الربط"
                : "ما يشتغل: الإرسال يروح بدون رمز، والشاشة تعرض كرت تأكيد"))
            if let profile = signing.appProfile, let expiration = profile.expiration {
                let days = profile.daysLeft(now: facts.when) ?? 0
                lines.append("الملف ينتهي: \(stamp(expiration)) (باقي \(days) يوم)")
                if let name = profile.name { lines.append("اسم الملف: \(name)") }
                lines.append("صلاحيات الملف: \(profile.entitlementKeys.joined(separator: ", "))")
            } else {
                lines.append("ملف التوقيع: ما لقيته (محاكي، أو نسخة غير موقعة)")
            }
        } else {
            lines.append("غير معروف")
        }

        lines.append("")
        lines.append("[الشبكة]")
        let v4 = facts.interfaces.filter { $0.family == .ipv4 && !$0.isLoopback }
        if v4.isEmpty {
            lines.append("IPv4: ما فيه عنوان. لو الهوتسبوت شغّال، الشبكة ممكن IPv6 فقط، والبحث عن السيارة ما يقدر يشتغل")
        }
        for interface in v4 {
            let up = interface.isUp ? "" : " (مطفية)"
            lines.append("\(interface.name): \(interface.address)/\(interface.prefix ?? 0)\(up)")
        }
        let v6 = Set(facts.interfaces.filter { $0.family == .ipv6 && !$0.isLoopback && !$0.address.lowercased().hasPrefix("fe80") }.map(\.name)).sorted()
        lines.append("IPv6 عام: " + (v6.isEmpty ? "ما فيه" : v6.joined(separator: ", ")))
        switch facts.localNetwork {
        case .allowed: lines.append("الشبكة المحلية: مسموحة (نجح اتصال بجهاز محلي)")
        case .denied: lines.append("الشبكة المحلية: مرفوضة. الإعدادات ← الخصوصية والأمان ← الشبكة المحلية")
        case .unknown: lines.append("الشبكة المحلية: غير معروف (iOS ما يعطي الحالة، وما نجح اتصال محلي ولا جاء رفض)")
        }
        if let note = facts.localNetworkNote { lines.append(note) }

        lines.append("")
        lines.append("[البحث عن السيارة]")
        if let paired = facts.paired {
            lines.append("مربوط مع: \(paired.name) (\(paired.unit))، آخر عنوان \(paired.address?.text ?? "غير معروف")")
        } else {
            lines.append("الجوال غير مربوط")
        }
        if let located = facts.located {
            if let sweep = located.sweep {
                lines.append("منفذ 47101: فحصت \(sweep.targetCount) عنوان في \(seconds(sweep.duration)) ث، مفتوح \(sweep.openHosts.count)، مغلق \(sweep.closedCount)، بدون رد \(sweep.timedOutCount)" + (sweep.deniedSeen ? "، ورفض iOS الاتصال" : ""))
                if !sweep.openHosts.isEmpty { lines.append("مفتوح عند: \(sweep.openHosts.joined(separator: ", "))") }
                if let said = notes(sweep.notes) { lines.append("قال النظام: \(said)") }
            }
            if let found = located.found {
                let via = located.via?.rawValue ?? "?"
                lines.append("لقيتها: \(found.address.text) (عن طريق \(via)) في \(seconds(located.duration)) ث")
            } else {
                lines.append("ما لقيت شاشة AMRI تجاوب على 47101 في \(seconds(located.duration)) ث")
            }
            for other in located.otherUnits {
                lines.append("شاشة ثانية: \(other.address.text) (\(other.ping.unit ?? "?"))")
            }
        } else {
            lines.append("ما انفحص")
        }
        if let adb = facts.adb {
            lines.append("منفذ 5555 (ADB، مصافحة فقط، بدون فتح أي شيء): فحصت \(adb.targetCount) عنوان في \(seconds(adb.duration)) ث" + (adb.deniedSeen ? "، ورفض iOS الاتصال" : ""))
            if adb.hosts.isEmpty, adb.openWithoutAdb.isEmpty { lines.append("ما رد أحد") }
            if let said = notes(adb.notes) { lines.append("قال النظام: \(said)") }
            for host in adb.hosts {
                switch host.kind {
                case .k2501: lines.append("\(host.host): K2501 (\(host.banner?.summary ?? ""))")
                case .other: lines.append("\(host.host): جهاز ثاني، ما لمسته (\(host.banner?.summary ?? ""))")
                case .asksAuthorisation: lines.append("\(host.host): يطلب تصريح ADB، ما لمسته")
                }
            }
            for host in adb.openWithoutAdb { lines.append("\(host): المنفذ مفتوح بس ما رد ADB") }
        }

        lines.append("")
        lines.append("[الشاشة]")
        if let ping = facts.ping?.ping {
            lines.append("ping: \(ping.app) \(ping.unit ?? "?") \(ping.name ?? "?") نسخة \(ping.version ?? "?") بروتوكول \(ping.proto.map { String($0) } ?? "?")")
            if let snapshot = facts.snapshot {
                lines.append("الرحلة: \(snapshot.nav.map { describe($0) } ?? "غير معروف")")
                lines.append("الأغنية: \(snapshot.music?.title ?? "غير معروف")")
            }
            if let diag = facts.diag {
                lines.append("/v1/diag:")
                lines.append(contentsOf: diag.split(whereSeparator: \.isNewline).suffix(40).map(String.init))
            } else if let problem = facts.diagProblem {
                lines.append("/v1/diag: \(problem)")
            }
        } else {
            lines.append("ping: ما وصلني رد")
        }

        lines.append("")
        lines.append("[آخر مشاركة وصلت «إرسال للسيارة»]")
        if let share = facts.lastShare {
            lines.append("الوقت: \(stamp(share.payload.at))")
            let types = share.payload.typeIdentifiers.map { $0.joined(separator: ", ") }.joined(separator: " | ")
            lines.append("الأنواع: \(types.isEmpty ? "ما فيه" : types)")
            if let item = share.payload.mapItem {
                lines.append("map-item: \(item.name ?? "بدون اسم") (\(item.lat), \(item.lon)) \(item.placeID ?? "")")
            }
            for url in share.payload.urls { lines.append("الرابط: \(url)") }
            for text in share.payload.texts { lines.append("النص: \(text.prefix(300))") }
            for step in share.trail { lines.append("خطوة: \(step)") }
            if let result = share.result { lines.append("النتيجة: \(result)") }
        } else if facts.storeIsShared {
            lines.append("ما فيه مشاركة بعد. شارك مكان من الخرائط إلى «إرسال للسيارة» وارجع افحص")
        } else {
            lines.append("ما أقدر أقرأها من هنا لأن ما فيه App Group. افتح «التفاصيل» في نافذة الإرسال وانسخها")
        }

        lines.append("")
        lines.append("[بحث الخرائط]")
        lines.append(facts.nearPhone ? "قرب الجوال (الموقع نفسه ما ينكتب هنا)" : "الجوال ما عطى موقع: البحث في كل السعودية")
        if let note = facts.locationNote { lines.append(note) }
        for row in facts.mapRows + facts.categoryRows {
            lines.append(describe(row))
        }
        lines.append("الاتجاهات MKDirections: \(facts.directions ?? "ما انفحصت")")

        return lines.joined(separator: "\n")
    }

    /// What all of it comes to, in a sentence or two. The biggest unknown of the whole plan is whether this phone can reach a client
    /// of its own hotspot at all, and the two ports tell that apart from a server that is just not on.
    public static func conclusion(_ facts: LinkFacts) -> [String] {
        var lines: [String] = []
        if let signing = facts.signing, !signing.shareExtensionPresent {
            lines.append("أداة التوقيع شالت «إرسال للسيارة» من التطبيق: وقّعه من جديد وخل Remove Extensions مطفية.")
        }
        if facts.localNetwork == .denied {
            lines.append("الشبكة المحلية مرفوضة لعمري: هذا أول شيء لازم يتصلح، من الإعدادات ← الخصوصية والأمان ← الشبكة المحلية.")
            return lines
        }
        let k2501 = facts.adb?.units.first
        if let found = facts.ping {
            lines.append("الجوال يوصل للشاشة على \(found.address.text): الطريق بينهم شغال.")
            if facts.paired == nil { lines.append("باقي تربطها: امسح الرمز اللي على الشاشة.") }
            if !facts.storeIsShared { lines.append("ما فيه App Group: «إرسال للسيارة» يروح بدون رمز، والشاشة تطلب تأكيدك.") }
        } else if let k2501 {
            lines.append("الجوال يوصل للشاشة (ردت K2501 على \(k2501.host) منفذ ADB)، بس خادم الجوال 47101 ما رد.")
            lines.append("يعني الطريق شغال، والباقي على الشاشة: لازم نسختها فيها الخادم، وتشغّل «ربط الجوال» من إعداداتها.")
        } else if facts.located?.sweep?.targetCount == 0, facts.adb?.targetCount ?? 0 == 0 {
            lines.append("الجوال ما عنده شبكة محلية يدور فيها: شغّل الهوتسبوت، وخل الشاشة تشبك عليه، وأعد الفحص.")
        } else {
            let count = facts.located?.sweep?.targetCount ?? facts.adb?.targetCount ?? 0
            lines.append("الجوال ما شاف الشاشة على أي منفذ في \(count) عنوان.")
            lines.append("لو الشاشة شابكة على هوتسبوت هذا الجوال الحين، فالجوال ما يقدر يوصل لأجهزة الهوتسبوت، وهذا أكبر سؤال عندنا. أرسل هذا التقرير.")
        }
        return lines
    }

    /// "Connection refused × 12", most said first.
    private static func notes(_ notes: [String: Int]) -> String? {
        guard !notes.isEmpty else { return nil }
        return notes.sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }.map { "\($0.key) × \($0.value)" }.joined(separator: "، ")
    }

    private static func describe(_ row: MapProbeRow) -> String {
        if let problem = row.problem { return "\(row.query): فشل (\(problem))" }
        if row.count == 0 { return "\(row.query): ما فيه نتائج" }
        var line = "\(row.query): \(row.count) نتيجة"
        if let km = row.nearestKm { line += "، الأقرب \(String(format: "%.1f", km)) كم" }
        if !row.names.isEmpty { line += ": " + row.names.joined(separator: "، ") }
        return line
    }

    private static func describe(_ nav: NavEvent) -> String {
        switch nav.kind {
        case .idle: return "ما فيه رحلة"
        case .routing: return "يحسب الطريق"
        case .started, .progress: return "شغالة، \(nav.name ?? "")"
        case .failed: return "فشلت: \(nav.reason ?? "")"
        case .arrived: return "وصل"
        case .stopped: return "انتهت"
        case .card: return "كرت على الشاشة"
        case .waitingFix: return "تنتظر إشارة الموقع"
        case .held: return "ماسكة المكان لين تخلص المكالمة أو الرجوع للخلف"
        case .handed: return "راحت لتطبيق الملاحة الثاني"
        case .ignored: return "الشاشة كملت الرحلة اللي كانت فيها"
        case .expired: return "ما أحد رد على كرت الشاشة"
        case .replaced: return "مكان ثاني أخذ دوره"
        case .other(let raw): return raw
        }
    }

    private static func group(_ profile: ProvisioningProfile?, present: Bool) -> String {
        guard let profile else { return "ما فيه ملف" }
        return profile.appGroups.first ?? "ما فيه"
    }

    private static func seconds(_ value: TimeInterval) -> String { String(format: "%.2f", value) }

    /// A plain Gregorian stamp: the report must read the same whatever the phone's calendar is.
    static func stamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}
