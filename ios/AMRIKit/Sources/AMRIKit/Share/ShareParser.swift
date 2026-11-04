import Foundation

/// One thing to try, in the parsing order of the plan's section 2.5. The parser itself touches no network.
public enum ShareStep: Equatable, Sendable {
    case place(PhonePlace)
    /// A short link: one request with no redirect, then read `Location`.
    case followRedirect(URL, hops: Int, src: String)
    /// `fallback`: words to search for if Apple's lookup finds nothing.
    case placeID(String, name: String?, fallback: String?, src: String)
    /// A search whose result must be picked by hand.
    case search(String, src: String)
    case notMaps
}

/// Reads what was shared. The formats were cross-checked against Geo Share's tests (GPL), used as a reference only: nothing
/// is copied from it. It never looks at Google's pages: the only network step is the redirect of a link he shared himself.
public enum ShareParser {

    // MARK: The plan

    /// Everything worth trying, best first: the map item, then each URL, then coordinates in the text, then a Saudi short
    /// address, then the text as a search. A step that can only guess waits behind every step that may be exact.
    public static func plan(for payload: SharePayload) -> [ShareStep] {
        var steps: [ShareStep] = []
        if let item = payload.mapItem {
            steps.append(.place(PhonePlace(name: item.name, detail: item.detail, lat: item.lat, lon: item.lon,
                                           confidence: .exact, src: "share.apple", placeID: item.placeID)))
        }

        let texts = payload.texts.map(normalize)
        var urls: [String] = []
        for raw in payload.urls {
            let text = normalize(raw).trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty, !urls.contains(text) { urls.append(text) }
        }
        var rest: [String] = []
        for text in texts {
            let (found, remainder) = extractURLs(from: text)
            for url in found where !urls.contains(url) { urls.append(url) }
            let trimmed = remainder.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, !rest.contains(trimmed) { rest.append(trimmed) }
        }

        for text in urls {
            guard let url = URL(string: text) else { continue }
            let step = parseURL(url)
            if step != .notMaps { steps.append(step) }
        }
        for text in rest {
            if let pair = coordinates(in: text) {
                // Two decimals could be anything (a price, a score), so that much is only ever a guess.
                steps.append(.place(PhonePlace(name: nameFromText(text, removing: pair.matched), lat: pair.lat, lon: pair.lon,
                                               confidence: pair.precise ? .exact : .guess, src: "share.text")))
            }
        }
        for text in rest {
            if let code = shortAddress(in: text) { steps.append(.search(code, src: "share.shortcode")) }
        }
        let searchable = rest.filter { coordinates(in: $0) == nil && shortAddress(in: $0) == nil }
        if let text = searchable.first { steps.append(.search(firstLines(text), src: "share.text")) }

        // Stable: a guess or a search never beats anything that can still turn out exact.
        var ranked: [ShareStep] = steps.filter { rank(of: $0) == 0 }
        ranked += steps.filter { rank(of: $0) == 1 }
        ranked += steps.filter { rank(of: $0) == 2 }
        return ranked
    }

    private static func rank(of step: ShareStep) -> Int {
        switch step {
        case .place(let place): return place.confidence == .exact ? 0 : 1
        case .search: return 2
        default: return 0
        }
    }

    /// The shared text around the link, as a name and a detail: first line and second line.
    public static func nameAndDetail(for payload: SharePayload) -> (name: String?, detail: String?) {
        for text in payload.texts {
            let (_, remainder) = extractURLs(from: normalize(text))
            // "موقعي: https://..." leaves the colon behind.
            let lines = remainder.split(whereSeparator: \.isNewline)
                .map { $0.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ":-–—"))) }
                .filter { !$0.isEmpty && coordinates(in: $0) == nil }
            if let first = lines.first {
                return (first, lines.count > 1 ? lines[1] : nil)
            }
        }
        return (nil, nil)
    }

    // MARK: Text

    /// Arabic-Indic and Persian digits to ASCII, the Arabic comma and decimal mark to their ASCII forms, and no direction marks.
    public static func normalize(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x0660...0x0669:
                out.append(Unicode.Scalar(scalar.value - 0x0660 + 0x30) ?? scalar)
            case 0x06F0...0x06F9:
                out.append(Unicode.Scalar(scalar.value - 0x06F0 + 0x30) ?? scalar)
            case 0x060C:
                out.append(",")
            case 0x066B:
                out.append(".")
            case 0x061B:
                out.append(";")
            case 0x066C, 0x200E, 0x200F, 0x202A...0x202E, 0x2066...0x2069, 0xFEFF:
                break
            default:
                out.append(scalar)
            }
        }
        return String(out)
    }

    /// Every URL in a text, and the text without them.
    static func extractURLs(from text: String) -> (urls: [String], remainder: String) {
        var urls: [String] = []
        var ranges: [Range<String.Index>] = []
        let whole = NSRange(text.startIndex..., in: text)

        // geo: and waze: first, because the detector does not know them.
        for match in matches(#"(?i)\b(?:geo:|waze://)[^\s]+"#, in: text) {
            if let range = Range(match.range, in: text) {
                urls.append(String(text[range]))
                ranges.append(range)
            }
        }
        if let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
            for match in detector.matches(in: text, options: [], range: whole) {
                guard let range = Range(match.range, in: text), let url = match.url else { continue }
                if ranges.contains(where: { $0.overlaps(range) }) { continue }
                // The detector adds "http://" to a bare maps.apple/p/... Keep what it made of it.
                urls.append(url.absoluteString)
                ranges.append(range)
            }
        }
        var remainder = text
        for range in ranges.sorted(by: { $0.lowerBound > $1.lowerBound }) {
            remainder.replaceSubrange(range, with: " ")
        }
        return (urls, remainder)
    }

    /// Decimal or degrees-minutes-seconds, in a normalised text.
    public static func coordinates(in text: String) -> (lat: Double, lon: Double, matched: String, precise: Bool)? {
        if let match = firstGroups(#"(?<![\d.])([-+]?\d{1,2}\.(\d{2,}))\s*[,;\s]\s*([-+]?\d{1,3}\.(\d{2,}))(?![\d.])"#, in: text),
           let lat = Double(match[1]), let lon = Double(match[3]), isValid(lat, lon) {
            return (lat, lon, match[0], match[2].count >= 3 && match[4].count >= 3)
        }
        // 26°17'25.6"N 50°11'04.4"E, with the marks people really type.
        let dms = #"(\d{1,3})\s*[°ºd]\s*(?:(\d{1,2}(?:\.\d+)?)\s*['′’m]\s*)?(?:(\d{1,2}(?:\.\d+)?)\s*["″”s]\s*)?([NSEWnsew])"#
        let found = matches(dms, in: text)
        if found.count >= 2 {
            var lat: Double?
            var lon: Double?
            for match in found.prefix(2) {
                guard let parts = groups(of: match, in: text), let degrees = Double(parts[1]) else { continue }
                let minutes = Double(parts[2]) ?? 0
                let seconds = Double(parts[3]) ?? 0
                let value = degrees + minutes / 60 + seconds / 3600
                switch parts[4].uppercased() {
                case "N": lat = value
                case "S": lat = -value
                case "E": lon = value
                case "W": lon = -value
                default: break
                }
            }
            if let lat, let lon, isValid(lat, lon) {
                let first = Range(found[0].range, in: text)
                let second = Range(found[1].range, in: text)
                var matched = ""
                if let first, let second { matched = String(text[first.lowerBound..<second.upperBound]) }
                return (lat, lon, matched, true)
            }
        }
        // 26.29N, 50.18E
        if let match = firstGroups(#"(?i)(?<![\d.])(\d{1,2}\.\d{2,})\s*°?\s*([NS])\b\s*[,;\s]?\s*(\d{1,3}\.\d{2,})\s*°?\s*([EW])\b"#, in: text),
           let a = Double(match[1]), let b = Double(match[3]) {
            let lat = match[2].uppercased() == "S" ? -a : a
            let lon = match[4].uppercased() == "W" ? -b : b
            if isValid(lat, lon) { return (lat, lon, match[0], true) }
        }
        // N26.29 E50.18
        if let match = firstGroups(#"(?i)\b([NS])\s*(\d{1,2}\.\d{2,})\s*[,;\s]?\s*([EW])\s*(\d{1,3}\.\d{2,})\b"#, in: text),
           let a = Double(match[2]), let b = Double(match[4]) {
            let lat = match[1].uppercased() == "S" ? -a : a
            let lon = match[3].uppercased() == "W" ? -b : b
            if isValid(lat, lon) { return (lat, lon, match[0], true) }
        }
        return nil
    }

    /// Four letters and four digits: Saudi National Address short form (`EDMA2510`).
    public static func shortAddress(in text: String) -> String? {
        firstGroups(#"(?<![A-Za-z0-9])([A-Za-z]{4}\d{4})(?![A-Za-z0-9])"#, in: normalize(text))?[1].uppercased()
    }

    private static func nameFromText(_ text: String, removing matched: String) -> String? {
        let cleaned = text.replacingOccurrences(of: matched, with: " ")
        let line = cleaned.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(.punctuationCharacters)) }
            .first { !$0.isEmpty }
        return line
    }

    private static func firstLines(_ text: String) -> String {
        let lines = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return String(lines.prefix(2).joined(separator: " ").prefix(120))
    }

    // MARK: URLs

    public static func parseURL(_ url: URL) -> ShareStep {
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "geo" { return parseGeo(url.absoluteString) }
        if scheme == "waze" { return parseWaze(url, path: url.path, host: url.host ?? "") }
        guard scheme == "http" || scheme == "https", let host = url.host?.lowercased() else { return .notMaps }
        let path = url.path

        if host == "maps.apple" || host == "maps.apple.com" || host == "www.maps.apple.com" {
            if let step = parseAppleMaps(url) { return step }
            if host == "maps.apple", path.hasPrefix("/p/") || path.count > 1 {
                return .followRedirect(httpsVersion(url), hops: 1, src: "share.apple")
            }
            return .notMaps
        }
        if isGoogleShort(host: host, path: path) {
            if let inner = query(of: url)["link"], let innerURL = URL(string: inner) {
                let step = parseURL(innerURL)
                if step != .notMaps { return step }
            }
            return .followRedirect(httpsVersion(url), hops: 2, src: "share.google")
        }
        if host == "preview.app.goo.gl" || host == "consent.google.com" {
            let items = query(of: url)
            if let inner = items["link"] ?? items["continue"], let innerURL = URL(string: inner) { return parseURL(innerURL) }
            return .notMaps
        }
        if isGoogleMaps(host: host, path: path) { return parseGoogleMaps(url) }
        if host == "waze.com" || host.hasSuffix(".waze.com") { return parseWaze(url, path: path, host: host) }
        // WhatsApp's own send link can hold a location's link in its text. Nothing else in it is a place.
        if host == "wa.me" || host == "api.whatsapp.com", let text = query(of: url)["text"] {
            for inner in extractURLs(from: text).urls {
                if let innerURL = URL(string: inner) {
                    let step = parseURL(innerURL)
                    if step != .notMaps { return step }
                }
            }
        }
        return .notMaps
    }

    private static func httpsVersion(_ url: URL) -> URL {
        guard url.scheme?.lowercased() == "http", var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        parts.scheme = "https"
        return parts.url ?? url
    }

    /// Case-insensitive query parameters, percent-decoded, with `+` read as a space (Google writes both).
    static func query(of url: URL) -> [String: String] {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false), let items = parts.queryItems else { return [:] }
        var out: [String: String] = [:]
        for item in items {
            guard let value = item.value else { continue }
            let key = item.name.lowercased()
            if out[key] == nil { out[key] = normalize(value.replacingOccurrences(of: "+", with: " ")) }
        }
        return out
    }

    /// `lat,lon` with optional `loc:` or `ll.` before it and `(Name)` after it.
    static func latLon(_ text: String) -> (lat: Double, lon: Double, name: String?)? {
        guard let match = firstGroups(#"^\s*(?:loc:|ll\.)?\s*([-+]?\d{1,3}(?:\.\d+)?)\s*[,;]\s*([-+]?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$"#, in: normalize(text)),
              let lat = Double(match[1]), let lon = Double(match[2]), isValid(lat, lon) else { return nil }
        return (lat, lon, match[3].isEmpty ? nil : match[3])
    }

    private static func isValid(_ lat: Double, _ lon: Double) -> Bool {
        abs(lat) <= 90 && abs(lon) <= 180 && !(lat == 0 && lon == 0)
    }

    private static func parseAppleMaps(_ url: URL) -> ShareStep? {
        let items = query(of: url)
        let address = items["address"]
        var name = items["name"]
        if name == nil, let q = items["q"], latLon(q) == nil { name = q }
        if name == nil, let address, let first = address.split(separator: ",").first { name = String(first) }

        for key in ["coordinate", "ll", "q", "destination", "daddr"] {
            if let text = items[key], let pair = latLon(text) {
                return .place(PhonePlace(name: name ?? pair.name, detail: address, lat: pair.lat, lon: pair.lon,
                                         confidence: .exact, src: "share.apple", placeID: items["place-id"]))
            }
        }
        if let id = items["place-id"], !id.isEmpty { return .placeID(id, name: name, fallback: address, src: "share.apple") }
        for key in ["address", "destination", "daddr", "q"] {
            if let text = items[key], !text.isEmpty { return .search(text, src: "share.apple") }
        }
        return nil
    }

    private static func isGoogleShort(host: String, path: String) -> Bool {
        switch host {
        case "maps.app.goo.gl", "share.google": return true
        case "goo.gl", "app.goo.gl": return path.hasPrefix("/maps")
        case "g.co": return path.hasPrefix("/kgs")
        default: return false
        }
    }

    private static func isGoogleMaps(host: String, path: String) -> Bool {
        let isGoogle = firstGroups(#"^(?:[a-z0-9-]+\.)*google\.[a-z.]{2,}$"#, in: host) != nil
        guard isGoogle else { return false }
        return host.hasPrefix("maps.") || path.hasPrefix("/maps")
    }

    private static func parseGoogleMaps(_ url: URL) -> ShareStep {
        let absolute = normalize(url.absoluteString)
        let fullName = googlePlaceName(url)
        let (name, detail) = splitName(fullName)
        let items = query(of: url)

        // 1. The last !3d!4d pair is the place itself.
        let pairs = matches(#"!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"#, in: absolute)
        if let last = pairs.last, let group = groups(of: last, in: absolute),
           let lat = Double(group[1]), let lon = Double(group[2]), isValid(lat, lon) {
            return .place(PhonePlace(name: name, detail: detail, lat: lat, lon: lon, confidence: .exact, src: "share.google"))
        }
        // 2. Parameters that hold lat,lon.
        for key in ["q", "query", "ll", "destination", "daddr"] {
            if let text = items[key], let pair = latLon(text) {
                return .place(PhonePlace(name: pair.name ?? name, detail: detail, lat: pair.lat, lon: pair.lon, confidence: .exact, src: "share.google"))
            }
        }
        // 3. A /lat,lon path segment (not the /@ viewport). The last one: on a /dir/ link it is the destination.
        for segment in url.path.split(separator: "/").map(String.init).reversed() where !segment.hasPrefix("@") {
            let spaced = segment.replacingOccurrences(of: "+", with: " ")
            if let pair = latLon(spaced) {
                return .place(PhonePlace(name: name, detail: detail, lat: pair.lat, lon: pair.lon, confidence: .exact, src: "share.google"))
            }
        }
        // 4. /@lat,lon is only where the map was looking: the weakest, so it is shown and asked about.
        if let match = firstGroups(#"/@(-?\d+\.\d+),(-?\d+\.\d+)"#, in: absolute),
           let lat = Double(match[1]), let lon = Double(match[2]), isValid(lat, lon) {
            return .place(PhonePlace(name: name, detail: detail, lat: lat, lon: lon, confidence: .guess, src: "share.google"))
        }
        // 5. A name to search.
        if let fullName { return .search(fullName, src: "share.google") }
        for key in ["q", "query", "destination", "daddr"] {
            if let text = items[key], !text.isEmpty { return .search(text, src: "share.google") }
        }
        return .notMaps
    }

    private static func googlePlaceName(_ url: URL) -> String? {
        let segments = url.path.split(separator: "/").map(String.init)
        guard let index = segments.firstIndex(of: "place"), index + 1 < segments.count else { return nil }
        let raw = segments[index + 1].replacingOccurrences(of: "+", with: " ")
        let text = normalize(raw.removingPercentEncoding ?? raw).trimmingCharacters(in: .whitespaces)
        if text.isEmpty || text.hasPrefix("@") || latLon(text) != nil { return nil }
        return text
    }

    /// "Al Rashid Mall, King Khalid St, Al Khobar": the name, then where it is.
    private static func splitName(_ text: String?) -> (name: String?, detail: String?) {
        guard let text, let comma = text.firstIndex(where: { $0 == "," }) else { return (text, nil) }
        let name = text[..<comma].trimmingCharacters(in: .whitespaces)
        let detail = text[text.index(after: comma)...].trimmingCharacters(in: .whitespaces)
        return (name.isEmpty ? text : name, detail.isEmpty ? nil : detail)
    }

    private static func parseWaze(_ url: URL, path: String, host: String) -> ShareStep {
        let items = query(of: url)
        for key in ["ll", "latlng", "to"] {
            if let text = items[key], let pair = latLon(text) {
                return .place(PhonePlace(name: nil, lat: pair.lat, lon: pair.lon, confidence: .exact, src: "share.waze"))
            }
        }
        // waze.com/ul/h<geohash>
        let segments = path.split(separator: "/").map(String.init)
        if let index = segments.firstIndex(of: "ul"), index + 1 < segments.count {
            let code = segments[index + 1]
            if code.hasPrefix("h"), let point = Geohash.decode(String(code.dropFirst())) {
                return .place(PhonePlace(name: nil, lat: point.lat, lon: point.lon, confidence: .exact, src: "share.waze"))
            }
        }
        if let text = items["q"], !text.isEmpty { return .search(text, src: "share.waze") }
        return .notMaps
    }

    private static func parseGeo(_ text: String) -> ShareStep {
        let body = normalize(text).dropFirst(4)
        let parts = body.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        var head = ""
        if let start = parts.first, let beforeOptions = start.split(separator: ";").first { head = String(beforeOptions) }
        let queryText = parts.count > 1 ? String(parts[1]) : ""
        var q: String?
        for item in queryText.split(separator: "&") where item.lowercased().hasPrefix("q=") {
            let raw = String(item.dropFirst(2)).replacingOccurrences(of: "+", with: " ")
            q = raw.removingPercentEncoding ?? raw
        }
        if let q, let pair = latLon(q) {
            return .place(PhonePlace(name: pair.name, lat: pair.lat, lon: pair.lon, confidence: .exact, src: "share.geo"))
        }
        if let pair = latLon(head) {
            // A name near a point says where to look, not which place: a guess, shown and asked about.
            return .place(PhonePlace(name: q, lat: pair.lat, lon: pair.lon, confidence: q == nil ? .exact : .guess, src: "share.geo"))
        }
        if let q, !q.isEmpty { return .search(q, src: "share.geo") }
        return .notMaps
    }

    // MARK: Regular expressions

    private static func matches(_ pattern: String, in text: String) -> [NSTextCheckingResult] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: text, options: [], range: NSRange(text.startIndex..., in: text))
    }

    /// The groups of a match; a group that took no part is the empty string.
    private static func groups(of match: NSTextCheckingResult, in text: String) -> [String]? {
        var out: [String] = []
        for index in 0..<match.numberOfRanges {
            if let range = Range(match.range(at: index), in: text) {
                out.append(String(text[range]))
            } else {
                out.append("")
            }
        }
        return out
    }

    private static func firstGroups(_ pattern: String, in text: String) -> [String]? {
        guard let match = matches(pattern, in: text).first else { return nil }
        return groups(of: match, in: text)
    }
}
