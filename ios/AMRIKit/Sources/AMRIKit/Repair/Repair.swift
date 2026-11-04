import Foundation

/// Bringing the screen back to its previous version from the phone (plan: platform.md 6.3, item 3). The script is prepared on
/// the unit by AMRI itself (update/Rollback.kt, from 1.19.1); it detaches itself, because replacing the package kills whoever
/// called it, and writes how it went to a file.
public enum Repair {
    /// The only command this app ever runs on the unit that changes anything.
    public static let rollbackCommand = "sh /data/local/tmp/amri-rollback/rollback.sh"
    /// And the only other one: reading how that went. rollback.sh answers at once, so its answer is not the result.
    public static let resultCommand = "cat /data/local/tmp/amri-rollback/result"

    /// How rollback.sh's own answer reads.
    public enum Start: Equatable, Sendable {
        /// "started", or "already-running": the result file will tell.
        case running
        /// It did not start ("failed-to-start", "needs root").
        case refused(String)
        /// This unit runs a version with no way back prepared.
        case noScript
        /// Anything else the shell said. Not a claim of anything.
        case unclear(String)
    }

    public static func start(from output: String) -> Start {
        let text = output.trimmingCharacters(in: .whitespacesAndNewlines)
        if scriptMissing(in: text) { return .noScript }
        let words = text.lowercased()
        if words.contains("failed-to-start") || words.contains("needs root") { return .refused(text) }
        if words.split(whereSeparator: \.isNewline).contains(where: { $0 == "started" || $0 == "already-running" }) { return .running }
        return .unclear(text)
    }

    /// The result file so far: `MODE=`, `FROM=`, `TO=`, what pm said, `EXIT=n`, then `NOW=` or `HOME=`, then `DONE`.
    public struct Report: Equatable, Sendable {
        public enum State: Equatable, Sendable {
            case running, succeeded, failed
        }

        public var state: State
        public var exitCode: Int?
        /// The version it went back to, as the saved copy names it.
        public var toVersion: String?

        public init(text: String) {
            var done = false
            var code: Int?
            var version: String?
            for line in text.split(whereSeparator: \.isNewline).map({ $0.trimmingCharacters(in: .whitespaces) }) {
                if line == "DONE" { done = true }
                if line.hasPrefix("EXIT=") { code = Int(line.dropFirst(5)) }
                if line.hasPrefix("TO="), line.count > 3 { version = String(line.dropFirst(3)) }
            }
            exitCode = code
            toVersion = version
            state = !done ? .running : (code == 0 ? .succeeded : .failed)
        }
    }

    /// Reads the result file until it says DONE or the time is up. The script gives pm install 300 s, so the wait is a bit longer.
    /// Each look is a new connection on which the banner is read again; a look that fails is just tried again.
    public static func watchResult(on host: Host, every interval: TimeInterval = 3, limit: TimeInterval = 330,
                                   update: @escaping @Sendable (String) -> Void = { _ in }) async -> (text: String, report: Report) {
        let startedAt = Date()
        var last = ""
        while !Task.isCancelled {
            let look = await AdbClient.runShell(host: host.host, port: host.port, command: resultCommand, timeout: 6)
            // Before the script has written its first line, cat says there is no such file: that is not news.
            if look.ran, !look.output.isEmpty, !look.output.hasPrefix("cat:"), look.output != last {
                last = look.output
                update(last)
            }
            let report = Report(text: last)
            if report.state != .running || Date().timeIntervalSince(startedAt) > limit { return (last, report) }
            try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
        }
        return (last, Report(text: last))
    }

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
        /// What the system said about the hosts that did not open, and how many times.
        public var notes: [String: Int] = [:]

        public init() {}

        public var units: [Host] { hosts.filter { $0.kind == .k2501 } }
    }

    /// Hello only, on the phone's own networks. Never a shell.
    public static func scan(targets: [SweepTarget], concurrency: Int = 32) async -> Scan {
        let startedAt = Date()
        var scan = Scan()
        scan.targetCount = targets.count
        await withTaskGroup(of: (SweepTarget, AdbHello, String?).self) { group in
            var next = 0
            while next < targets.count, next < concurrency {
                let target = targets[next]
                next += 1
                group.addTask {
                    let said = await AdbClient.helloWithNote(host: target.host, port: target.port)
                    return (target, said.hello, said.note)
                }
            }
            while let (target, hello, note) = await group.next() {
                if let note { scan.notes[note, default: 0] += 1 }
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
                    group.addTask {
                        let said = await AdbClient.helloWithNote(host: target.host, port: target.port)
                        return (target, said.hello, said.note)
                    }
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
