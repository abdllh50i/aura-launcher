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
    /// Can a TCP connection be opened? The connection is closed again at once.
    public static func check(host: String, port: UInt16, timeout: TimeInterval) async -> TCPReach {
        guard let socket = NWSocket(host: host, port: port) else { return .closed }
        defer { socket.close() }
        do {
            try await socket.connect(timeout: timeout)
            return .open
        } catch SocketError.localNetworkDenied {
            return .denied
        } catch SocketError.timeout {
            return .timeout
        } catch {
            return .closed
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

    public init() {}
}

/// Parallel connections, a short timeout, then `GET /v1/ping`. On a hotspot's /28 that is 13 hosts in under a second.
public enum Sweep {
    private struct Hit: Sendable {
        var target: SweepTarget
        var reach: TCPReach
        var ping: UnitPing?
    }

    public static func run(targets: [SweepTarget],
                           connectTimeout: TimeInterval = 0.6,
                           pingTimeout: TimeInterval = 1.2,
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
        let reach = await TCPProbe.check(host: target.host, port: target.port, timeout: connectTimeout)
        guard reach == .open else { return Hit(target: target, reach: reach, ping: nil) }
        let client = LinkClient(address: UnitAddress(host: target.host, port: target.port))
        let ping = try? await client.ping(timeout: pingTimeout)
        return Hit(target: target, reach: .open, ping: ping?.isAMRI == true ? ping : nil)
    }
}
