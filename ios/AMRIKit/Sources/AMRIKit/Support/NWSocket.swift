import Foundation
import Network

enum SocketError: Error, Equatable {
    case timeout
    case closed
    case refused
    case localNetworkDenied
    case failed(String)
}

/// One outgoing TCP connection with async calls. `NWConnection` underneath, so iOS's local network rules apply to it the
/// same way they apply to the Link: this is also how the app learns the permission was refused.
final class NWSocket: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "amri.socket")
    private let said = Locked<String?>(nil)

    /// The last thing the system said while connecting. For the report: "no route to host" and "refused" are very different
    /// news about the hotspot, and nobody has tried this on his phone yet.
    var note: String? { said.current }

    private static func describe(_ error: NWError) -> String {
        if case .posix(let code) = error { return String(cString: strerror(code.rawValue)) }
        return error.debugDescription
    }

    init?(host: String, port: UInt16) {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return nil }
        connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
    }

    func connect(timeout: TimeInterval) async throws {
        let connection = self.connection
        let queue = self.queue
        let said = self.said
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let once = OneShot<Result<Void, Error>> { continuation.resume(with: $0) }
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        once.fire(.success(()))
                    case .waiting(let error):
                        said.with { $0 = NWSocket.describe(error) }
                        // Waiting is how the system says "not now": refused by the port, or by the permission. Anything
                        // else may still come good (the alert is up), so the timer decides.
                        if connection.currentPath?.unsatisfiedReason == .localNetworkDenied {
                            once.fire(.failure(SocketError.localNetworkDenied))
                        } else if case .posix(let code) = error, code == .ECONNREFUSED {
                            once.fire(.failure(SocketError.refused))
                        }
                    case .failed(let error):
                        said.with { $0 = NWSocket.describe(error) }
                        if case .posix(let code) = error, code == .ECONNREFUSED {
                            once.fire(.failure(SocketError.refused))
                        } else {
                            once.fire(.failure(SocketError.failed(String(describing: error))))
                        }
                    case .cancelled:
                        once.fire(.failure(SocketError.closed))
                    default:
                        break
                    }
                }
                connection.start(queue: queue)
                queue.asyncAfter(deadline: .now() + timeout) { once.fire(.failure(SocketError.timeout)) }
            }
        } onCancel: {
            connection.cancel()
        }
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: SocketError.failed(String(describing: error)))
                } else {
                    continuation.resume()
                }
            })
        }
    }

    func read(exactly count: Int, timeout: TimeInterval) async throws -> Data {
        if count == 0 { return Data() }
        let connection = self.connection
        let queue = self.queue
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            let once = OneShot<Result<Data, Error>> { continuation.resume(with: $0) }
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
                if let error {
                    once.fire(.failure(SocketError.failed(String(describing: error))))
                } else if let data, data.count == count {
                    once.fire(.success(data))
                } else {
                    once.fire(.failure(SocketError.closed))
                }
            }
            queue.asyncAfter(deadline: .now() + timeout) { once.fire(.failure(SocketError.timeout)) }
        }
    }

    func close() {
        connection.stateUpdateHandler = nil
        connection.cancel()
    }
}
