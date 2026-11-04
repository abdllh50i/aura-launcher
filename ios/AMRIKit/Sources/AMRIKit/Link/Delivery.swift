import Foundation

/// "Ack within 1.5 s. On no answer retry with the same id, three tries over 20 s": long enough for the hotspot's 5 to 8 s
/// re-association, and the unit remembers the id, so a retry never starts a trip twice.
public struct DeliveryPolicy: Equatable, Sendable {
    public var attempts: Int
    public var ackTimeout: TimeInterval
    public var window: TimeInterval

    public init(attempts: Int, ackTimeout: TimeInterval, window: TimeInterval) {
        self.attempts = attempts
        self.ackTimeout = ackTimeout
        self.window = window
    }

    public static let standard = DeliveryPolicy(attempts: 3, ackTimeout: 1.5, window: 20)

    /// When each try starts, counted from the first: the last one still has its full ack time inside the window.
    public func start(ofAttempt attempt: Int) -> TimeInterval {
        guard attempts > 1 else { return 0 }
        return max(0, window - ackTimeout) * Double(attempt) / Double(attempts - 1)
    }
}

public enum DeliveryProgress: Equatable, Sendable {
    case locating
    case sending(attempt: Int, of: Int)
}

public enum DeliveryOutcome: Equatable, Sendable {
    /// The unit has it. This is not "started".
    case accepted(GoAck, address: UnitAddress, usedToken: Bool)
    /// 409, with the unit's reason.
    case conflict(String)
    /// The unit will not take it from this phone: not paired, and not the gateway.
    case notPaired(String?)
    case refused(Int, String?)
    /// Nothing answered in the whole window.
    case unreachable(localNetworkDenied: Bool, unitSeen: Bool)
}

/// The moving parts of a delivery, so tests can run it with no network and no waiting.
public struct DeliveryEnvironment: Sendable {
    public var locate: @Sendable () async -> LocateOutcome
    public var post: @Sendable (_ address: UnitAddress, _ request: GoRequest, _ useToken: Bool, _ timeout: TimeInterval) async throws -> GoAck
    public var sleep: @Sendable (TimeInterval) async -> Void
    public var now: @Sendable () -> Date

    public init(locate: @escaping @Sendable () async -> LocateOutcome,
                post: @escaping @Sendable (UnitAddress, GoRequest, Bool, TimeInterval) async throws -> GoAck,
                sleep: @escaping @Sendable (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64(max(0, $0) * 1_000_000_000)) },
                now: @escaping @Sendable () -> Date = { Date() }) {
        self.locate = locate
        self.post = post
        self.sleep = sleep
        self.now = now
    }

    /// The real thing: the locator, then HTTP, with the pairing's token when there is one.
    public static func live(token: String?, locate: @escaping @Sendable () async -> LocateOutcome) -> DeliveryEnvironment {
        DeliveryEnvironment(locate: locate, post: { address, request, useToken, timeout in
            try await LinkClient(address: address, token: useToken ? token : nil).go(request, timeout: timeout)
        })
    }
}

public enum Delivery {
    /// Sends one place, with the same id on every try. `known` is an address to try first.
    public static func run(_ request: GoRequest,
                           hasToken: Bool,
                           known: UnitAddress? = nil,
                           policy: DeliveryPolicy = .standard,
                           environment: DeliveryEnvironment,
                           progress: @escaping @Sendable (DeliveryProgress) -> Void = { _ in }) async -> DeliveryOutcome {
        let startedAt = environment.now()
        var address = known
        var useToken = hasToken
        var denied = false
        var seen = false
        var attempt = 0
        while attempt < policy.attempts {
            if Task.isCancelled { return .unreachable(localNetworkDenied: denied, unitSeen: seen) }
            let due = policy.start(ofAttempt: attempt)
            let elapsed = environment.now().timeIntervalSince(startedAt)
            if due > elapsed { await environment.sleep(due - elapsed) }

            if address == nil {
                progress(.locating)
                let found = await environment.locate()
                denied = denied || found.localNetworkDenied
                address = found.found?.address
            }
            guard let target = address else {
                attempt += 1
                continue
            }
            seen = true
            progress(.sending(attempt: attempt + 1, of: policy.attempts))
            do {
                let ack = try await environment.post(target, request, useToken, policy.ackTimeout)
                return .accepted(ack, address: target, usedToken: useToken)
            } catch let error as LinkError {
                switch error {
                case .conflict(let reason):
                    return .conflict(reason)
                case .unauthorized(let reason):
                    // The token is no good any more. Same id without it: from the gateway that makes the screen's card.
                    guard useToken else { return .notPaired(reason) }
                    useToken = false
                    continue
                case .refused(let status, let reason):
                    return .refused(status, reason)
                case .localNetworkDenied:
                    denied = true
                    address = nil
                case .cancelled:
                    return .unreachable(localNetworkDenied: denied, unitSeen: seen)
                case .noAnswer, .badReply:
                    // The address may be stale after the hotspot came back: look again before the next try.
                    address = nil
                }
            } catch {
                address = nil
            }
            attempt += 1
        }
        return .unreachable(localNetworkDenied: denied, unitSeen: seen)
    }
}
