import Foundation

/// The connect banner: `device::ro.product.name=...;ro.product.model=...;ro.product.device=...;features=...`
public struct AdbBanner: Equatable, Sendable {
    public var raw: String
    public var properties: [String: String]

    public init(raw: String) {
        self.raw = raw
        var properties: [String: String] = [:]
        let body = raw.components(separatedBy: "::").dropFirst().joined(separator: "::")
        for part in body.split(separator: ";") {
            let pair = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            if pair.count == 2 { properties[String(pair[0])] = String(pair[1]) }
        }
        self.properties = properties
    }

    public var model: String? { properties["ro.product.model"] }
    public var device: String? { properties["ro.product.device"] }

    /// The strict rule: the banner itself must say K2501. Anything else is left untouched.
    public var isK2501: Bool { model?.trimmingCharacters(in: .whitespaces).uppercased() == "K2501" }

    /// For the screen and the report, with control characters gone: this text came from another machine.
    public var summary: String {
        let text = "model \(model ?? "?"), device \(device ?? "?")"
        let mark: Unicode.Scalar = "?"
        var scalars = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            scalars.append(scalar.value < 0x20 || scalar.value == 0x7F ? mark : scalar)
        }
        return String(scalars)
    }
}

/// The few ADB packets `LocalAdb.kt` and the installers use: CNXN, OPEN, WRTE, OKAY, CLSE, 24-byte little-endian header.
public enum AdbPacket {
    public static let cnxn: UInt32 = 0x4E58_4E43
    public static let auth: UInt32 = 0x4854_5541
    public static let open: UInt32 = 0x4E45_504F
    public static let okay: UInt32 = 0x5941_4B4F
    public static let clse: UInt32 = 0x4553_4C43
    public static let wrte: UInt32 = 0x4554_5257
    public static let version: UInt32 = 0x0100_0000
    public static let maxData: UInt32 = 256 * 1024

    public struct Header: Equatable, Sendable {
        public var command: UInt32
        public var arg0: UInt32
        public var arg1: UInt32
        public var length: UInt32
    }

    public static func encode(command: UInt32, arg0: UInt32, arg1: UInt32, payload: Data = Data()) -> Data {
        var out = Data(capacity: 24 + payload.count)
        var sum: UInt32 = 0
        for byte in payload { sum = sum &+ UInt32(byte) }
        for word in [command, arg0, arg1, UInt32(payload.count), sum, ~command] {
            out.append(contentsOf: [UInt8(word & 0xFF), UInt8((word >> 8) & 0xFF), UInt8((word >> 16) & 0xFF), UInt8((word >> 24) & 0xFF)])
        }
        out.append(payload)
        return out
    }

    public static func decodeHeader(_ data: Data) -> Header? {
        guard data.count == 24 else { return nil }
        let bytes = [UInt8](data)
        func word(_ at: Int) -> UInt32 {
            UInt32(bytes[at]) | UInt32(bytes[at + 1]) << 8 | UInt32(bytes[at + 2]) << 16 | UInt32(bytes[at + 3]) << 24
        }
        let header = Header(command: word(0), arg0: word(4), arg1: word(8), length: word(12))
        guard word(20) == ~header.command, header.length <= maxData * 4 else { return nil }
        return header
    }
}

public enum AdbHello: Equatable, Sendable {
    case banner(AdbBanner)
    /// It asks for authorisation. A K2501 never does (`ro.adb.secure=0`), so this is something else.
    case asksAuthorisation
    /// The port took the connection, but no ADB answered.
    case noAdb
    /// Nothing there.
    case unreachable(TCPReach)
}

public struct AdbRunResult: Equatable, Sendable {
    public var ran: Bool
    public var output: String
    /// True when the connection ended before the shell closed its stream. Expected when the app is replaced.
    public var connectionEnded: Bool
    /// Why it did not run, in plain words for the log.
    public var problem: String?

    public init(ran: Bool, output: String, connectionEnded: Bool = false, problem: String? = nil) {
        self.ran = ran
        self.output = output
        self.connectionEnded = connectionEnded
        self.problem = problem
    }
}

/// A tiny ADB client. `hello` shakes hands, reads the banner and stops: it never opens anything. `runShell` opens a shell
/// only when the banner it reads itself says K2501.
public enum AdbClient {
    public static let port: UInt16 = 5555

    public static func hello(host: String, port: UInt16 = AdbClient.port, connectTimeout: TimeInterval = 0.6, timeout: TimeInterval = 1.5) async -> AdbHello {
        await helloWithNote(host: host, port: port, connectTimeout: connectTimeout, timeout: timeout).hello
    }

    /// `note`: what the system said if the connection did not open.
    static func helloWithNote(host: String, port: UInt16 = AdbClient.port, connectTimeout: TimeInterval = 0.6, timeout: TimeInterval = 1.5) async -> (hello: AdbHello, note: String?) {
        guard let socket = NWSocket(host: host, port: port) else { return (.unreachable(.closed), nil) }
        defer { socket.close() }
        do {
            try await socket.connect(timeout: connectTimeout)
        } catch SocketError.localNetworkDenied {
            return (.unreachable(.denied), socket.note)
        } catch SocketError.timeout {
            return (.unreachable(.timeout), socket.note)
        } catch {
            return (.unreachable(.closed), socket.note)
        }
        return (await handshake(socket, timeout: timeout), nil)
    }

    private static func handshake(_ socket: NWSocket, timeout: TimeInterval) async -> AdbHello {
        do {
            let identity = Data("host::\u{0}".utf8)
            try await socket.send(AdbPacket.encode(command: AdbPacket.cnxn, arg0: AdbPacket.version, arg1: AdbPacket.maxData, payload: identity))
            for _ in 0..<4 {
                let (header, payload) = try await readPacket(socket, timeout: timeout)
                if header.command == AdbPacket.auth { return .asksAuthorisation }
                if header.command == AdbPacket.cnxn {
                    let text = String(decoding: payload.prefix { $0 != 0 }, as: UTF8.self)
                    return .banner(AdbBanner(raw: text))
                }
            }
            return .noAdb
        } catch {
            return .noAdb
        }
    }

    private static func readPacket(_ socket: NWSocket, timeout: TimeInterval) async throws -> (AdbPacket.Header, Data) {
        let raw = try await socket.read(exactly: 24, timeout: timeout)
        guard let header = AdbPacket.decodeHeader(raw) else { throw SocketError.failed("bad header") }
        let payload = try await socket.read(exactly: Int(header.length), timeout: timeout)
        return (header, payload)
    }

    /// Runs one command through `shell:`, on a host whose banner says K2501, and nothing else.
    public static func runShell(host: String, port: UInt16 = AdbClient.port, command: String, timeout: TimeInterval = 20) async -> AdbRunResult {
        guard let socket = NWSocket(host: host, port: port) else { return AdbRunResult(ran: false, output: "", problem: "bad address") }
        defer { socket.close() }
        do {
            try await socket.connect(timeout: 2)
        } catch {
            return AdbRunResult(ran: false, output: "", problem: "connect: \(error)")
        }
        guard case .banner(let banner) = await handshake(socket, timeout: 3) else {
            return AdbRunResult(ran: false, output: "", problem: "no ADB banner")
        }
        guard banner.isK2501 else {
            return AdbRunResult(ran: false, output: "", problem: "not a K2501: \(banner.summary)")
        }

        let local: UInt32 = 1
        var remote: UInt32 = 0
        var output = Data()
        do {
            let service = Data("shell:\(command)\u{0}".utf8)
            try await socket.send(AdbPacket.encode(command: AdbPacket.open, arg0: local, arg1: 0, payload: service))
            while true {
                let (header, payload) = try await readPacket(socket, timeout: timeout)
                switch header.command {
                case AdbPacket.okay:
                    remote = header.arg0
                case AdbPacket.wrte:
                    output.append(payload)
                    try await socket.send(AdbPacket.encode(command: AdbPacket.okay, arg0: local, arg1: header.arg0))
                case AdbPacket.clse:
                    // Closed before it was ever accepted: adbd refused the shell.
                    if remote == 0 { return AdbRunResult(ran: false, output: "", problem: "adbd refused the shell") }
                    try? await socket.send(AdbPacket.encode(command: AdbPacket.clse, arg0: local, arg1: remote))
                    return AdbRunResult(ran: true, output: clean(output))
                default:
                    break
                }
            }
        } catch {
            // The script detaches and replaces the app, so the line may drop. What arrived is still reported.
            if remote != 0 { return AdbRunResult(ran: true, output: clean(output), connectionEnded: true) }
            return AdbRunResult(ran: false, output: clean(output), problem: "\(error)")
        }
    }

    private static func clean(_ data: Data) -> String {
        String(decoding: data, as: UTF8.self).replacingOccurrences(of: "\r\n", with: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
