import Foundation
import Network
import XCTest
@testable import AMRIKit

enum TestPaths {
    /// ios/, from ios/AMRIKit/Tests/AMRIKitTests/TestSupport.swift
    static var iosRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}

/// A port nothing listens on right now.
func freePort() -> UInt16 {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    defer { close(fd) }
    var address = sockaddr_in()
    address.sin_family = sa_family_t(AF_INET)
    address.sin_port = 0
    address.sin_addr.s_addr = inet_addr("127.0.0.1")
    var length = socklen_t(MemoryLayout<sockaddr_in>.size)
    _ = withUnsafePointer(to: &address) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, length) }
    }
    _ = withUnsafeMutablePointer(to: &address) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
    }
    return UInt16(bigEndian: address.sin_port)
}

/// A small HTTP server on loopback: whatever `respond` says, for every request.
final class TestHTTPServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "test.http")
    private let hits = Locked<[String]>([])
    let port: UInt16

    init(respond: @escaping @Sendable (String) -> (Int, String)) throws {
        let chosen = freePort()
        guard let nwPort = NWEndpoint.Port(rawValue: chosen) else { throw NSError(domain: "test", code: 1) }
        listener = try NWListener(using: .tcp, on: nwPort)
        port = chosen
        let hits = self.hits
        listener.newConnectionHandler = { [queue] connection in
            connection.start(queue: queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { data, _, _, _ in
                let text = data.map { String(decoding: $0, as: UTF8.self) } ?? ""
                let line = text.split(separator: "\r\n").first.map(String.init) ?? ""
                hits.with { $0.append(line) }
                let (status, body) = respond(line)
                let reply = "HTTP/1.1 \(status) OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
                connection.send(content: Data(reply.utf8), completion: .contentProcessed { _ in connection.cancel() })
            }
        }
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in
            if case .ready = state { ready.signal() }
        }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 3)
    }

    var requests: [String] { hits.current }
    var address: UnitAddress { UnitAddress(host: "127.0.0.1", port: port) }
    var target: SweepTarget { SweepTarget(host: "127.0.0.1", port: port) }

    func stop() { listener.cancel() }

    static func amri(unit: String = "k2501-test") throws -> TestHTTPServer {
        try TestHTTPServer { _ in (200, #"{"app":"AMRI","unit":"\#(unit)","name":"K2501","version":"2.0.0","proto":2}"#) }
    }
}

#if os(macOS)
/// tools/fake_unit.mjs as a child process. Skips the test where there is no Node.
final class FakeUnit: @unchecked Sendable {
    private let process = Process()
    let port: UInt16
    let adbPort: UInt16
    let otherAdbPort: UInt16

    init(routeDelay: Int = 100, progressMs: Int = 200, gateway: String = "127.0.0.1") async throws {
        port = freePort()
        adbPort = freePort()
        otherAdbPort = freePort()
        let script = TestPaths.iosRoot.appendingPathComponent("tools/fake_unit.mjs").path
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["node", script, "--port", "\(port)", "--route-delay", "\(routeDelay)", "--progress-ms", "\(progressMs)",
                             "--gateway", gateway, "--adb", "\(adbPort):K2501", "--adb", "\(otherAdbPort):Pixel 7"]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
        } catch {
            throw XCTSkip("no node here")
        }
        let client = LinkClient(address: address)
        for _ in 0..<50 {
            if (try? await client.ping(timeout: 0.3)) != nil { return }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        process.terminate()
        throw XCTSkip("the fake unit did not start (no node?)")
    }

    var address: UnitAddress { UnitAddress(host: "127.0.0.1", port: port) }

    func stop() {
        process.terminate()
        process.waitUntilExit()
    }

    func control(_ path: String, _ body: [String: Any]? = nil) async throws -> [String: Any] {
        var request = URLRequest(url: address.baseURL.appendingPathComponent("__fake/\(path)"))
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, _) = try await URLSession.shared.data(for: request)
        return (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    /// The bodies of the go requests the unit saw.
    func goBodies() async throws -> [[String: Any]] {
        let log = try await control("log")
        let requests = log["requests"] as? [[String: Any]] ?? []
        return requests.filter { $0["path"] as? String == "/v1/nav/go" }.compactMap { $0["body"] as? [String: Any] }
    }

    func starts() async throws -> [[String: Any]] {
        try await control("log")["starts"] as? [[String: Any]] ?? []
    }

    func adbOpens() async throws -> [[String: Any]] {
        try await control("log")["adbOpens"] as? [[String: Any]] ?? []
    }
}
#endif
