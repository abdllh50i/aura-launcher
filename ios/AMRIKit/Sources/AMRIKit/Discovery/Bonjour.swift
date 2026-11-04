import Foundation
import Network

/// What the phone can honestly say about the Local Network permission. iOS has no call that reports it.
public enum LocalNetworkState: String, Codable, Equatable, Sendable {
    /// A connection to a local address worked, so it is on.
    case allowed
    /// iOS said no.
    case denied
    /// Neither was seen.
    case unknown
}

/// `_amri._tcp`, a bonus after the sweep (Android's NsdManager on this firmware is unproven). Browsing is also what makes
/// iOS show the Local Network alert, so the app does it in the foreground during pairing: an extension in the background
/// would be denied silently while the state is still undetermined (TN3179).
public enum Bonjour {
    public static let serviceType = "_amri._tcp"

    public struct Answer: Equatable, Sendable {
        public var addresses: [UnitAddress]
        public var denied: Bool
    }

    public static func browse(type: String = Bonjour.serviceType, timeout: TimeInterval = 2) async -> Answer {
        let browser = NWBrowser(for: .bonjour(type: type, domain: nil), using: NWParameters())
        let queue = DispatchQueue(label: "amri.bonjour")
        let endpoints = Locked<[NWEndpoint]>([])
        let denied = Locked(false)

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let once = OneShot<Void> { _ in continuation.resume() }
            browser.stateUpdateHandler = { state in
                switch state {
                case .waiting(let error), .failed(let error):
                    // kDNSServiceErr_PolicyDenied: the permission is off.
                    if case .dns(let code) = error, code == -65570 {
                        denied.with { $0 = true }
                        once.fire(())
                    }
                default:
                    break
                }
            }
            browser.browseResultsChangedHandler = { results, _ in
                endpoints.with { $0 = results.map(\.endpoint) }
            }
            browser.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { once.fire(()) }
        }
        browser.cancel()

        var addresses: [UnitAddress] = []
        for endpoint in endpoints.current.prefix(4) {
            if let address = await resolve(endpoint) { addresses.append(address) }
        }
        return Answer(addresses: addresses, denied: denied.current)
    }

    /// The address behind a service: connect, and read where the connection went.
    private static func resolve(_ endpoint: NWEndpoint) async -> UnitAddress? {
        let connection = NWConnection(to: endpoint, using: .tcp)
        let queue = DispatchQueue(label: "amri.bonjour.resolve")
        let address: UnitAddress? = await withCheckedContinuation { (continuation: CheckedContinuation<UnitAddress?, Never>) in
            let once = OneShot<UnitAddress?> { continuation.resume(returning: $0) }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    once.fire(unitAddress(from: connection.currentPath?.remoteEndpoint))
                case .failed, .cancelled:
                    once.fire(nil)
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + 1.5) { once.fire(nil) }
        }
        connection.stateUpdateHandler = nil
        connection.cancel()
        return address
    }

    private static func unitAddress(from endpoint: NWEndpoint?) -> UnitAddress? {
        guard case .hostPort(let host, let port)? = endpoint, case .ipv4(let v4) = host else { return nil }
        // Printed as dotted quad, sometimes with a %interface suffix.
        let text = "\(v4)".split(separator: "%").first.map(String.init) ?? "\(v4)"
        return UnitAddress(host: text, port: port.rawValue)
    }
}
