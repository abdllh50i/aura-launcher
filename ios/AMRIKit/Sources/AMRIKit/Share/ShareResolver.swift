import Foundation

/// The answer to one request that does not follow redirects.
public struct RedirectAnswer: Equatable, Sendable {
    public var status: Int?
    public var location: String?

    public init(status: Int?, location: String?) {
        self.status = status
        self.location = location
    }
}

public enum ShareResolution: Equatable, Sendable {
    /// One place: `exact` goes at once, `guess` is shown and asked about.
    case place(PhonePlace)
    /// Search results to pick from by hand.
    case choices([PhonePlace], query: String)
    case nothing
}

public struct ShareOutcome: Equatable, Sendable {
    public var resolution: ShareResolution
    /// What was tried, for the report.
    public var trail: [String]

    public init(resolution: ShareResolution, trail: [String]) {
        self.resolution = resolution
        self.trail = trail
    }
}

/// Walks the parser's plan. The network and MapKit steps are passed in, so the fixtures can run with neither.
public struct ShareResolver: Sendable {
    public var follow: @Sendable (URL) async -> RedirectAnswer
    public var lookupPlaceID: @Sendable (String) async -> PhonePlace?
    public var search: @Sendable (String) async -> [PhonePlace]

    public init(follow: @escaping @Sendable (URL) async -> RedirectAnswer,
                lookupPlaceID: @escaping @Sendable (String) async -> PhonePlace?,
                search: @escaping @Sendable (String) async -> [PhonePlace]) {
        self.follow = follow
        self.lookupPlaceID = lookupPlaceID
        self.search = search
    }

    public func resolve(_ payload: SharePayload) async -> ShareOutcome {
        let plan = ShareParser.plan(for: payload)
        let text = ShareParser.nameAndDetail(for: payload)
        var trail: [String] = []
        var guess: PhonePlace?
        var searches: [String] = []

        func named(_ place: PhonePlace) -> PhonePlace {
            var out = place
            if out.name == PhonePlace.unnamed, let name = text.name { out.name = name }
            if out.detail == nil, let detail = text.detail, detail != out.name { out.detail = detail }
            return out
        }

        for first in plan {
            var step = first
            if case .followRedirect(let url, let hops, _) = step {
                let (next, lines) = await walk(url, hops: hops)
                trail += lines
                guard let next else { continue }
                step = next
            }
            switch step {
            case .place(let place):
                if place.confidence == .exact {
                    return ShareOutcome(resolution: .place(named(place)), trail: trail)
                }
                if guess == nil { guess = named(place) }
            case .placeID(let id, let name, let fallback, let src):
                if let found = await lookupPlaceID(id) {
                    trail.append("Place ID \(id): ok")
                    var place = found
                    place.src = src
                    place.confidence = .exact
                    place.placeID = id
                    if place.name == PhonePlace.unnamed, let name { place.name = name }
                    return ShareOutcome(resolution: .place(named(place)), trail: trail)
                }
                trail.append("Place ID \(id): no answer")
                if let fallback, !searches.contains(fallback) { searches.append(fallback) }
            case .search(let query, _):
                if !searches.contains(query) { searches.append(query) }
            case .followRedirect, .notMaps:
                break
            }
        }

        if let guess { return ShareOutcome(resolution: .place(guess), trail: trail) }
        for query in searches.prefix(2) {
            let results = await search(query)
            trail.append("search \(query): \(results.count)")
            if !results.isEmpty { return ShareOutcome(resolution: .choices(results, query: query), trail: trail) }
        }
        return ShareOutcome(resolution: .nothing, trail: trail)
    }

    /// One request, no redirect, read `Location`; up to `hops` of them.
    private func walk(_ url: URL, hops: Int) async -> (ShareStep?, [String]) {
        var lines: [String] = []
        var current = url
        var left = hops
        while left > 0 {
            left -= 1
            let answer = await follow(current)
            let status = answer.status.map { String($0) } ?? "no answer"
            lines.append("\(current.host ?? "?"): \(status) \(answer.location ?? "")")
            guard let location = answer.location,
                  let next = URL(string: location, relativeTo: current)?.absoluteURL else { return (nil, lines) }
            switch ShareParser.parseURL(next) {
            case .followRedirect(let more, _, _):
                current = more
            case .notMaps:
                return (nil, lines)
            case let step:
                return (step, lines)
            }
        }
        return (nil, lines)
    }
}

extension ShareResolver {
    /// One request with the default user agent, which matters: a browser's gets Google's preview page instead of the
    /// redirect (section 2.5). Redirects are never followed, and page bodies are never read.
    public static func followOnce(_ url: URL) async -> RedirectAnswer {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 6)
        request.httpMethod = "GET"
        do {
            let (_, response) = try await RedirectStopper.session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return RedirectAnswer(status: nil, location: nil) }
            return RedirectAnswer(status: http.statusCode, location: http.value(forHTTPHeaderField: "Location"))
        } catch {
            return RedirectAnswer(status: nil, location: nil)
        }
    }
}

private final class RedirectStopper: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 6
        config.timeoutIntervalForResource = 10
        config.httpCookieStorage = nil
        config.urlCache = nil
        return URLSession(configuration: config, delegate: RedirectStopper(), delegateQueue: nil)
    }()

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
