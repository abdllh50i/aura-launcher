import Foundation

public struct SweepTarget: Hashable, Sendable {
    public var host: String
    public var port: UInt16

    public init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }
}

public enum TCPReach: String, Sendable {
    case open, closed, timeout, denied
}

public enum TCPProbe {
    /// Can a TCP connection be opened? The connection is closed again at once. `note`: what the system said if not.
    public static func check(host: String, port: UInt16, timeout: TimeInterval) async -> (reach: TCPReach, note: String?) {
        guard let socket = NWSocket(host: host, port: port) else { return (.closed, nil) }
        defer { socket.close() }
        do {
            try await socket.connect(timeout: timeout)
            return (.open, nil)
        } catch SocketError.localNetworkDenied {
            return (.denied, socket.note)
        } catch SocketError.timeout {
            return (.timeout, socket.note)
        } catch {
            return (.closed, socket.note)
        }
    }
}

public struct FoundUnit: Equatable, Sendable {
    public var address: UnitAddress
    public var ping: UnitPing

    public init(address: UnitAddress, ping: UnitPing) {
        self.address = address
        self.ping = ping
    }
}

public struct SweepReport: Equatable, Sendable {
    public var targetCount = 0
    public var duration: TimeInterval = 0
    /// Every address with the port open, AMRI or not.
    public var openHosts: [String] = []
    /// Every address that answered `/v1/ping` as AMRI.
    public var units: [FoundUnit] = []
    public var closedCount = 0
    public var timedOutCount = 0
    /// iOS itself refused at least one connection: Local Network is off.
    public var deniedSeen = false
    /// What the system said about the hosts that did not open, and how many times.
    public var notes: [String: Int] = [:]

    public init() {}
}

/// Parallel connections, a short timeout, then `GET /v1/ping`. On a hotspot's /28 that is 13 hosts in about a second: missing a
/// unit on a slow moment costs far more than a little more waiting for addresses nobody is on.
public enum Sweep {
    private struct Hit: Sendable {
        var target: SweepTarget
        var reach: TCPReach
        var note: String?
        var ping: UnitPing?
    }

    public static func run(targets: [SweepTarget],
                           connectTimeout: TimeInterval = 1.0,
                           pingTimeout: TimeInterval = 2.0,
                           concurrency: Int = 32,
                           stopWhen: (@Sendable (UnitPing) -> Bool)? = nil) async -> SweepReport {
        let startedAt = Date()
        var report = SweepReport()
        report.targetCount = targets.count
        await withTaskGroup(of: Hit.self) { group in
            var next = 0
            func start() -> Sweep.PendingStart? {
                guard next < targets.count else { return nil }
                defer { next += 1 }
                return Sweep.PendingStart(target: targets[next])
            }
            for _ in 0..<max(1, concurrency) {
                guard let pending = start() else { break }
                group.addTask { await probe(pending.target, connectTimeout: connectTimeout, pingTimeout: pingTimeout) }
            }
            while let hit = await group.next() {
                switch hit.reach {
                case .open: report.openHosts.append(hit.target.host)
                case .closed: report.closedCount += 1
                case .timeout: report.timedOutCount += 1
                case .denied: report.deniedSeen = true
                }
                if let note = hit.note { report.notes[note, default: 0] += 1 }
                if let ping = hit.ping {
                    report.units.append(FoundUnit(address: UnitAddress(host: hit.target.host, port: hit.target.port), ping: ping))
                    if let stopWhen, stopWhen(ping) {
                        group.cancelAll()
                        break
                    }
                }
                if let pending = start() {
                    group.addTask { await probe(pending.target, connectTimeout: connectTimeout, pingTimeout: pingTimeout) }
                }
            }
        }
        report.duration = Date().timeIntervalSince(startedAt)
        return report
    }

    private struct PendingStart: Sendable {
        var target: SweepTarget
    }

    private static func probe(_ target: SweepTarget, connectTimeout: TimeInterval, pingTimeout: TimeInterval) async -> Hit {
        let tried = await TCPProbe.check(host: target.host, port: target.port, timeout: connectTimeout)
        guard tried.reach == .open else { return Hit(target: target, reach: tried.reach, note: tried.note, ping: nil) }
        let client = LinkClient(address: UnitAddress(host: target.host, port: target.port))
        let ping = try? await client.ping(timeout: pingTimeout)
        return Hit(target: target, reach: .open, note: nil, ping: ping?.isAMRI == true ? ping : nil)
    }
}
