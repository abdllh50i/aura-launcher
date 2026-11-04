import AMRIKit
import MapKit
import SwiftUI

/// Search on the phone: suggestions while typing, results on a real MapKit map, and the driver picks one by hand (option 1 of
/// section 2.6). Nothing here is kept: results live as long as they are on the screen.
@MainActor
final class SearchModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        /// Typing, with suggestions.
        case typing
        case searching
        case results
        case empty
        case failed(String)
    }

    @Published var query = "" {
        didSet {
            guard query != oldValue, !settingQuery else { return }
            queryChanged()
        }
    }
    @Published private(set) var suggestions: [PlaceSuggestion] = []
    @Published private(set) var results: [PhonePlace] = []
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var category: PlaceCategory?
    /// Apple asked to slow down. The results on the screen stay.
    @Published private(set) var throttled = false
    /// The place whose card is up.
    @Published var selected: PhonePlace?

    private let engine: PlaceSearchEngine
    private var settingQuery = false
    private var running: Task<Void, Never>?
    private let resolver = ShareResolver(follow: { await ShareResolver.followOnce($0) },
                                         lookupPlaceID: { await PlaceQueries.lookup(placeID: $0) },
                                         search: { _ in [] })

    init() {
        #if DEBUG
        engine = AppLaunch.demoSearch ? DemoSearch() : MapKitSearch()
        #else
        engine = MapKitSearch()
        #endif
        engine.onSuggestions = { [weak self] list in self?.take(list) }
    }

    var isActive: Bool { phase != .idle }

    /// The pins on the map.
    var pins: [PhonePlace] {
        var list = results
        if let selected, !list.contains(selected) { list.append(selected) }
        return list
    }

    private func take(_ list: [PlaceSuggestion]) {
        guard phase == .typing else { return }
        suggestions = Array(list.prefix(8))
    }

    private func queryChanged() {
        running?.cancel()
        category = nil
        throttled = false
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            suggestions = []
            results = []
            phase = .idle
            engine.updateQuery("", near: nil)
            return
        }
        phase = .typing
        engine.updateQuery(text, near: lastNear)
    }

    private var lastNear: CLLocationCoordinate2D?

    func noteLocation(_ near: CLLocationCoordinate2D?) {
        lastNear = near
    }

    /// He pressed search, or pasted something. A link, coordinates or a short address are read as such; plain words search.
    func submit() {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let payload = SharePayload(texts: [text])
        let plan = ShareParser.plan(for: payload)
        if let step = plan.first, !isPlainSearch(step) {
            running?.cancel()
            phase = .searching
            running = Task {
                let outcome = await resolver.resolve(payload)
                if Task.isCancelled { return }
                switch outcome.resolution {
                case .place(let place):
                    results = [place]
                    phase = .results
                    selected = place
                case .choices, .nothing:
                    await runSearch { [engine, lastNear] in try await engine.search(text: self.searchWords(in: plan, or: text), near: lastNear) }
                    // A short address that finds its one place: that is the place.
                    if results.count == 1, let only = results.first, !Task.isCancelled { selected = only }
                }
            }
            return
        }
        running?.cancel()
        running = Task {
            await runSearch { [engine, lastNear] in try await engine.search(text: text, near: lastNear) }
        }
    }

    private func isPlainSearch(_ step: ShareStep) -> Bool {
        if case .search(_, let src) = step { return src == "share.text" }
        return false
    }

    private func searchWords(in plan: [ShareStep], or text: String) -> String {
        for step in plan {
            if case .search(let words, _) = step { return words }
        }
        return text
    }

    func choose(_ suggestion: PlaceSuggestion) {
        settingQuery = true
        query = suggestion.title
        settingQuery = false
        running?.cancel()
        running = Task {
            await runSearch { [engine, lastNear] in try await engine.search(suggestion: suggestion, near: lastNear) }
            if results.count == 1, let only = results.first { selected = only }
        }
    }

    func choose(_ chip: PlaceCategory) {
        running?.cancel()
        settingQuery = true
        query = ""
        settingQuery = false
        category = chip
        running = Task {
            await runSearch { [engine, lastNear] in try await engine.search(category: chip, near: lastNear) }
        }
    }

    /// A place he tapped on Apple's own map.
    @available(iOS 18.0, *)
    func choose(feature: MapFeature) {
        running?.cancel()
        running = Task {
            guard let item = try? await MKMapItemRequest(feature: feature).mapItem, let place = PlaceQueries.place(from: item) else { return }
            if Task.isCancelled { return }
            selected = place
        }
    }

    private func runSearch(_ work: @escaping () async throws -> [PhonePlace]) async {
        suggestions = []
        selected = nil
        phase = .searching
        throttled = false
        do {
            let found = try await work()
            if Task.isCancelled { return }
            results = found
            phase = found.isEmpty ? .empty : .results
        } catch PlaceSearchError.throttled {
            // Keep what is on the screen, and say why nothing new came.
            throttled = true
            phase = results.isEmpty ? .empty : .results
        } catch PlaceSearchError.failed(let text) {
            if Task.isCancelled { return }
            phase = .failed(text)
        } catch {
            if Task.isCancelled { return }
            phase = .failed(error.localizedDescription)
        }
    }

    func clear() {
        running?.cancel()
        settingQuery = true
        query = ""
        settingQuery = false
        suggestions = []
        results = []
        category = nil
        selected = nil
        throttled = false
        phase = .idle
        engine.updateQuery("", near: nil)
    }

    /// Back from the place's card to the list, if there is one.
    func deselect() {
        selected = nil
    }

    #if DEBUG
    func setDemo(query: String = "", results: [PhonePlace] = [], suggestions: [PlaceSuggestion] = [], phase: Phase = .idle, category: PlaceCategory? = nil, selected: PhonePlace? = nil, throttled: Bool = false) {
        settingQuery = true
        self.query = query
        settingQuery = false
        self.results = results
        self.suggestions = suggestions
        self.phase = phase
        self.category = category
        self.selected = selected
        self.throttled = throttled
    }
    #endif
}
