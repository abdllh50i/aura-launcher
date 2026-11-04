import Foundation

/// Bringing the screen back to 1.19 from the phone (plan: platform.md 6.3, item 3). The script is prepared on the unit by
/// AMRI 2.0 at its first start; it detaches itself, because replacing the package kills whoever called it.
public enum Repair {
    /// The only command this app ever runs on the unit.
    public static let rollbackCommand = "sh /data/local/tmp/amri-rollback/rollback.sh"

    public struct Host: Identifiable, Equatable, Sendable {
        public enum Kind: Equatable, Sendable {
            case k2501
            /// An ADB device that is not a K2501. Left untouched.
            case other
            /// It asks for authorisation, so it cannot be a K2501. Left untouched.
            case asksAuthorisation
        }

        public var host: String
        public var port: UInt16
        public var kind: Kind
        public var banner: AdbBanner?
        public var id: String { "\(host):\(port)" }

        public init(host: String, port: UInt16, kind: Kind, banner: AdbBanner?) {
            self.host = host
            self.port = port
            self.kind = kind
            self.banner = banner
        }
    }

    public struct Scan: Equatable, Sendable {
        public var targetCount = 0
        public var duration: TimeInterval = 0
        /// Every host that speaks ADB, K2501 or not, for the report.
        public var hosts: [Host] = []
        public var openWithoutAdb: [String] = []
        public var deniedSeen = false

        public init() {}

        public var units: [Host] { hosts.filter { $0.kind == .k2501 } }
    }

    /// Hello only, on the phone's own networks. Never a shell.
    public static func scan(targets: [SweepTarget], concurrency: Int = 32) async -> Scan {
        let startedAt = Date()
        var scan = Scan()
        scan.targetCount = targets.count
        await withTaskGroup(of: (SweepTarget, AdbHello).self) { group in
            var next = 0
            while next < targets.count, next < concurrency {
                let target = targets[next]
                next += 1
                group.addTask { (target, await AdbClient.hello(host: target.host, port: target.port)) }
            }
            while let (target, hello) = await group.next() {
                switch hello {
                case .banner(let banner):
                    scan.hosts.append(Host(host: target.host, port: target.port, kind: banner.isK2501 ? .k2501 : .other, banner: banner))
                case .asksAuthorisation:
                    scan.hosts.append(Host(host: target.host, port: target.port, kind: .asksAuthorisation, banner: nil))
                case .noAdb:
                    scan.openWithoutAdb.append(target.host)
                case .unreachable(let reach):
                    if reach == .denied { scan.deniedSeen = true }
                }
                if next < targets.count {
                    let target = targets[next]
                    next += 1
                    group.addTask { (target, await AdbClient.hello(host: target.host, port: target.port)) }
                }
            }
        }
        scan.duration = Date().timeIntervalSince(startedAt)
        scan.hosts.sort { $0.host < $1.host }
        return scan
    }

    public static func targets(extra: [SweepTarget] = [], interfaces: [NetInterface] = NetworkInterfaces.current()) -> [SweepTarget] {
        extra + NetworkInterfaces.sweepHosts(from: interfaces).map { SweepTarget(host: $0, port: AdbClient.port) }
    }

    /// Runs the rollback on one K2501 and nowhere else (`AdbClient.runShell` reads the banner again itself).
    public static func rollback(on host: Host) async -> AdbRunResult {
        guard host.kind == .k2501 else { return AdbRunResult(ran: false, output: "", problem: "not a K2501") }
        return await AdbClient.runShell(host: host.host, port: host.port, command: rollbackCommand)
    }

    /// True when the shell said the script is not there: this unit runs a version with no rollback prepared.
    public static func scriptMissing(in output: String) -> Bool {
        let text = output.lowercased()
        return text.contains("no such file") || text.contains("can't open") || text.contains("not found")
    }
}
