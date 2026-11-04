import Foundation

/// What the QR code, an `amri://` link or the typed digits say. The unit's QR holds `http://<ip>:47101/p/<code>`.
public struct PairingTarget: Equatable, Sendable {
    public var address: UnitAddress?
    public var code: String

    public init(address: UnitAddress?, code: String) {
        self.address = address
        self.code = code
    }

    public static func parse(_ raw: String) -> PairingTarget? {
        let text = ShareParser.normalize(raw).trimmingCharacters(in: .whitespacesAndNewlines)
        if isCode(text) { return PairingTarget(address: nil, code: text) }
        guard let url = URL(string: text), let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }

        if url.scheme?.lowercased() == "amri" {
            let items = Dictionary((parts.queryItems ?? []).map { ($0.name.lowercased(), $0.value ?? "") }, uniquingKeysWith: { first, _ in first })
            if let inner = items["url"], let target = parse(inner) { return target }
            guard let code = items["code"].map(ShareParser.normalize), isCode(code) else { return nil }
            let host = items["host"] ?? items["ip"] ?? items["addr"]
            let port = items["port"].flatMap { UInt16($0) } ?? UnitAddress.defaultPort
            return PairingTarget(address: host.map { UnitAddress(host: $0, port: port) }, code: code)
        }

        // http://<ip>:47101/p/<code>
        guard url.scheme?.lowercased() == "http", let host = parts.host, isPrivateHost(host) else { return nil }
        let segments = url.path.split(separator: "/").map(String.init)
        guard segments.count == 2, segments[0] == "p", isCode(segments[1]) else { return nil }
        return PairingTarget(address: UnitAddress(host: host, port: parts.port.flatMap { UInt16(exactly: $0) } ?? UnitAddress.defaultPort),
                             code: segments[1])
    }

    private static func isCode(_ text: String) -> Bool {
        text.count == 6 && text.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// The same rule as Naghm's link: private addresses only, or the QR could point the app anywhere.
    private static func isPrivateHost(_ host: String) -> Bool {
        guard let bits = NetworkInterfaces.parseIPv4(host) else { return false }
        return NetworkInterfaces.isPrivate(bits) || host == "127.0.0.1"
    }
}
