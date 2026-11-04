import Foundation

/// How a send ended, as far as the phone can truly know.
public enum StartOutcome: Equatable, Sendable {
    /// The unit's own `started` event (or a `progress` for this very id, which proves it started).
    case started(NavEvent)
    /// The unit's own `failed` event.
    case failed(String?)
    /// Given to another navigation app, which says nothing back: not "started".
    case handed
    /// The screen kept the trip it had.
    case ignored
    /// Cancelled on the screen, or another place took its turn.
    case cancelled
    /// No word in time. The phone must not claim anything.
    case noWord(routingSeen: Bool)
}

/// AMRI Link v2 over HTTP, as in section 5.4 of the plan. No retries here: `Delivery` owns those.
public final class LinkClient: @unchecked Sendable {
    public let address: UnitAddress
    public let token: String?
    private let session: URLSession
    private let streamSession: URLSession

    public init(address: UnitAddress, token: String? = nil) {
        self.address = address
        self.token = token
        self.session = LinkClient.requests
        self.streamSession = LinkClient.streams
    }

    private static func configuration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.waitsForConnectivity = false
        config.allowsExpensiveNetworkAccess = true
        config.allowsConstrainedNetworkAccess = true
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.connectionProxyDictionary = [:]
        return config
    }

    private static let requests: URLSession = {
        let config = configuration()
        config.timeoutIntervalForRequest = 8
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()

    private static let streams: URLSession = {
        let config = configuration()
        // The unit sends a heartbeat every 15 s. This much silence means the link is gone.
        config.timeoutIntervalForRequest = 40
        config.timeoutIntervalForResource = 60 * 60 * 24
        return URLSession(configuration: config)
    }()

    // MARK: Requests

    private func request(_ method: String, _ path: String, timeout: TimeInterval, json: Data? = nil) -> URLRequest {
        var request = URLRequest(url: address.baseURL.appendingPathComponent(path), cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = method
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let json {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = json
        }
        return request
    }

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw LinkError.badReply }
            switch http.statusCode {
            case 200..<300:
                return (data, http)
            case 401, 403:
                throw LinkError.unauthorized(LinkClient.reason(in: data))
            case 409:
                throw LinkError.conflict(LinkClient.reason(in: data) ?? "")
            default:
                throw LinkError.refused(http.statusCode, LinkClient.reason(in: data))
            }
        } catch {
            throw LinkError.from(error)
        }
    }

    private static func reason(in data: Data) -> String? {
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        return JSONField.string(json?["reason"])
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return encoder
    }()

    private func body<T: Encodable>(_ value: T) throws -> Data {
        do { return try LinkClient.encoder.encode(value) } catch { throw LinkError.badReply }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do { return try JSONDecoder().decode(type, from: data) } catch { throw LinkError.badReply }
    }

    // MARK: Endpoints

    public func ping(timeout: TimeInterval = 1.5) async throws -> UnitPing {
        let (data, _) = try await perform(request("GET", "v1/ping", timeout: timeout))
        return try decode(UnitPing.self, data)
    }

    public struct PairReply: Decodable, Equatable, Sendable {
        public var token: String
        public var unit: String
        public var name: String?
    }

    public func pair(code: String, device: String, timeout: TimeInterval = 5) async throws -> PairReply {
        struct Body: Encodable { let code: String; let device: String }
        let (data, _) = try await perform(request("POST", "v1/pair", timeout: timeout, json: try body(Body(code: code, device: device))))
        return try decode(PairReply.self, data)
    }

    /// One try. The ack is due within 1.5 s; `Delivery` retries with the same id.
    public func go(_ go: GoRequest, timeout: TimeInterval = 1.5) async throws -> GoAck {
        let (data, _) = try await perform(request("POST", "v1/nav/go", timeout: timeout, json: try body(go)))
        return try decode(GoAck.self, data)
    }

    public func saved(which: String, id: String = RequestID.new(), timeout: TimeInterval = 3) async throws -> GoAck {
        struct Body: Encodable { let which: String; let id: String }
        let (data, _) = try await perform(request("POST", "v1/nav/saved", timeout: timeout, json: try body(Body(which: which, id: id))))
        return try decode(GoAck.self, data)
    }

    public func stop(id: String = RequestID.new(), timeout: TimeInterval = 3) async throws {
        struct Body: Encodable { let id: String }
        _ = try await perform(request("POST", "v1/nav/stop", timeout: timeout, json: try body(Body(id: id))))
    }

    public func state(timeout: TimeInterval = 3) async throws -> UnitSnapshot {
        let (data, _) = try await perform(request("GET", "v1/state", timeout: timeout))
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { throw LinkError.badReply }
        return UnitSnapshot(json: json)
    }

    public func places(timeout: TimeInterval = 4) async throws -> PlacesDoc {
        let (data, _) = try await perform(request("GET", "v1/places", timeout: timeout))
        return try decode(PlacesDoc.self, data)
    }

    public func putPlaces(_ places: PlacesDoc, timeout: TimeInterval = 4) async throws {
        _ = try await perform(request("PUT", "v1/places", timeout: timeout, json: try body(places)))
    }

    /// What really happened comes back; a refusal throws with the unit's reason.
    @discardableResult
    public func remote(_ command: RemoteCommand, id: String = RequestID.new(), timeout: TimeInterval = 3) async throws -> RemoteReply {
        struct Body: Encodable { let cmd: String; let id: String }
        let (data, _) = try await perform(request("POST", "v1/remote", timeout: timeout, json: try body(Body(cmd: command.rawValue, id: id))))
        return (try? JSONDecoder().decode(RemoteReply.self, from: data)) ?? RemoteReply()
    }

    public func diag(timeout: TimeInterval = 4) async throws -> String {
        let (data, _) = try await perform(request("GET", "v1/diag", timeout: timeout))
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: Live events

    /// `GET /v1/events`, until the stream ends or the task is cancelled.
    public func events() -> AsyncThrowingStream<LinkEvent, Error> {
        var request = self.request("GET", "v1/events", timeout: 40)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        let stream = streamSession
        let frozen = request
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await stream.bytes(for: frozen)
                    guard let http = response as? HTTPURLResponse else { throw LinkError.badReply }
                    guard http.statusCode == 200 else {
                        if http.statusCode == 401 || http.statusCode == 403 { throw LinkError.unauthorized(nil) }
                        throw LinkError.refused(http.statusCode, nil)
                    }
                    var parser = SSEParser()
                    for try await byte in bytes {
                        if let message = parser.feed(byte) {
                            continuation.yield(LinkEvent(message: message))
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: LinkError.from(error))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Watches the unit's events until this send's real outcome, for the time given. Reconnects when the hotspot blinks.
    /// `update` hears every step on the way (routing, a card on the screen, waiting for GPS), as the unit says them.
    public func waitForStart(id: String?, timeout: TimeInterval, update: (@Sendable (NavEvent) -> Void)? = nil) async -> StartOutcome {
        let routing = Locked(false)
        let result: StartOutcome = await withTaskGroup(of: StartOutcome?.self) { group in
            group.addTask { [self] in
                while !Task.isCancelled {
                    do {
                        for try await event in events() {
                            guard case .nav(let nav) = event else {
                                if case .snapshot(let snapshot) = event, let nav = snapshot.nav {
                                    if let outcome = LinkClient.outcome(of: nav, forID: id, routing: routing) { return outcome }
                                    if nav.id == id { update?(nav) }
                                }
                                continue
                            }
                            if let outcome = LinkClient.outcome(of: nav, forID: id, routing: routing) { return outcome }
                            if nav.id == nil || nav.id == id { update?(nav) }
                        }
                    } catch {
                        if let link = error as? LinkError, case .unauthorized = link { return nil }
                    }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
                return nil
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? .noWord(routingSeen: routing.current)
        }
        return result
    }

    /// The rule for "started": only the unit's word, and for this send when the unit names one.
    static func outcome(of nav: NavEvent, forID id: String?, routing: Locked<Bool>) -> StartOutcome? {
        let sameSend = nav.id == nil || id == nil || nav.id == id
        guard sameSend else { return nil }
        switch nav.kind {
        case .routing, .waitingFix:
            routing.with { $0 = true }
            return nil
        case .started:
            // A "started" with no id at all is the snapshot of an earlier trip only if it says "navigating", which reads
            // as progress. The unit's real start always names its id or comes alone.
            return .started(nav)
        case .progress:
            // A progress with this send's own id proves the start. One without an id could be an earlier trip.
            if nav.id != nil, nav.id == id { return .started(nav) }
            return nil
        case .failed:
            return nav.id == nil && id != nil && !routing.current ? nil : .failed(nav.reason)
        case .handed:
            return nav.id == id ? .handed : nil
        case .ignored:
            return nav.id == id ? .ignored : nil
        case .replaced:
            return nav.id == id ? .cancelled : nil
        case .stopped:
            return nav.id != nil && nav.id == id ? .cancelled : nil
        default:
            return nil
        }
    }
}
