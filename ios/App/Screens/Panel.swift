import AMRIKit
import MapKit
import SwiftUI

/// The bottom sheet: search and what to do with it. One thing at a time: a send under way, a place's card, the search's
/// results, or the start (Home, Work, favourites, the trip, the music).
struct Panel: View {
    static let peekHeight: CGFloat = 210
    static let peek = PresentationDetent.height(peekHeight)

    @Binding var detent: PresentationDetent
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var search: SearchModel
    @EnvironmentObject private var router: Router

    var body: some View {
        VStack(spacing: 0) {
            CarRow()
                .padding(.horizontal, Theme.Space.l)
                .padding(.top, Theme.Space.l)
            if let flow = link.flow {
                SendPanel(model: flow)
            } else if let place = search.selected {
                PlacePanel(place: place)
            } else {
                BrowsePanel(detent: $detent)
            }
        }
        .sheet(item: $router.route) { route in
            RouteSheet(route: route)
        }
    }
}

/// The screens that come up over the panel.
private struct RouteSheet: View {
    let route: Router.Route

    var body: some View {
        NavigationStack {
            switch route {
            case .car:
                CarScreen()
            case .pair(let target):
                PairScreen(target: target)
            case .check:
                CheckScreen()
            case .repair:
                RepairScreen()
            }
        }
        .presentationBackground(Theme.ground)
    }
}

// MARK: The start, and the search

private struct BrowsePanel: View {
    @Binding var detent: PresentationDetent
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var search: SearchModel
    @EnvironmentObject private var location: LocationModel
    @FocusState private var typing: Bool

    var body: some View {
        VStack(spacing: 0) {
            SearchField(typing: $typing)
                .padding(.horizontal, Theme.Space.l)
                .padding(.top, Theme.Space.s)
            ChipRow()
                .padding(.top, Theme.Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.m) {
                    if search.isActive {
                        SearchResults()
                    } else {
                        StartContent()
                    }
                }
                .padding(.horizontal, Theme.Space.l)
                .padding(.top, Theme.Space.s)
                .padding(.bottom, Theme.Space.xl)
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .onChange(of: typing) { _, now in
            if now { detent = .large }
        }
        .onChange(of: search.phase) { old, new in
            // A list to look at: enough of the sheet to read it, and the map above it.
            if new == .results, !typing, detent == Panel.peek { detent = .medium }
        }
    }
}

private struct SearchField: View {
    var typing: FocusState<Bool>.Binding
    @EnvironmentObject private var search: SearchModel

    var body: some View {
        HStack(spacing: Theme.Space.s) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Theme.ink3)
            TextField("وين تبي تروح؟", text: $search.query)
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink)
                .focused(typing)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .onSubmit { search.submit() }
                .accessibilityIdentifier("searchField")
            if search.isActive {
                Button {
                    search.clear()
                    typing.wrappedValue = false
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(Theme.ink3)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("امسح"))
                .accessibilityIdentifier("searchClear")
            }
        }
        .padding(.horizontal, Theme.Space.m)
        .frame(minHeight: Theme.Size.target)
        .background(Theme.sunken, in: RoundedRectangle(cornerRadius: Theme.Radius.field, style: .continuous))
    }
}

/// بنزين، قهوة، صيدلية، صراف، مستشفى، مطعم: each a real MapKit category.
private struct ChipRow: View {
    @EnvironmentObject private var search: SearchModel

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Theme.Space.s) {
                ForEach(PlaceCategory.allCases) { chip in
                    Button {
                        search.choose(chip)
                    } label: {
                        Label {
                            Text(chip.localizedName)
                        } icon: {
                            Image(systemName: chip.symbol)
                        }
                        .font(Theme.Fonts.note.weight(.semibold))
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(search.category == chip ? Theme.keyText : Theme.ink)
                        .padding(.horizontal, Theme.Space.m)
                        .frame(minHeight: Theme.Size.chip)
                        .background(search.category == chip ? Theme.key : Theme.sunken, in: Capsule())
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("chip-\(chip.rawValue)")
                }
            }
            .padding(.horizontal, Theme.Space.l)
        }
    }
}

extension PlaceCategory {
    /// The chip's words in the phone's language. `arabicName` is what is searched for.
    var localizedName: LocalizedStringKey {
        switch self {
        case .gas: return "بنزين"
        case .coffee: return "قهوة"
        case .pharmacy: return "صيدلية"
        case .atm: return "صراف"
        case .hospital: return "مستشفى"
        case .restaurant: return "مطعم"
        }
    }
}

private struct SearchResults: View {
    @EnvironmentObject private var search: SearchModel
    @EnvironmentObject private var location: LocationModel

    var body: some View {
        switch search.phase {
        case .idle:
            EmptyView()
        case .typing:
            if search.suggestions.isEmpty {
                SearchHint()
            } else {
                ForEach(Array(search.suggestions.enumerated()), id: \.element.id) { index, suggestion in
                    Button {
                        search.choose(suggestion)
                    } label: {
                        SuggestionRow(suggestion: suggestion)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("suggestion-\(index)")
                }
            }
            SubmitRow()
        case .searching:
            HStack(spacing: Theme.Space.m) {
                ProgressView()
                Text("أدور…")
                    .font(Theme.Fonts.body)
                    .foregroundStyle(Theme.ink2)
            }
            .frame(minHeight: Theme.Size.target)
        case .results:
            if search.throttled {
                InlineNote(symbol: "hourglass", text: "أبل طلبت نبطّئ البحث. هذي آخر نتائج وصلت.")
            }
            ForEach(Array(search.results.enumerated()), id: \.element.id) { index, place in
                Button {
                    search.selected = place
                } label: {
                    PlaceRowLabel(place: place, distance: location.near.map { place.distance(from: $0) })
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("result-\(index)")
                if index < search.results.count - 1 {
                    Divider().overlay(Theme.rule)
                }
            }
        case .empty:
            InlineNote(symbol: "magnifyingglass", text: search.throttled ? "أبل طلبت نبطّئ البحث. جرّب بعد شوي." : "ما لقيت شيء بهذا الاسم. جرّب كلمة ثانية، أو اكتب العنوان المختصر مثل EDMA2510.")
                .accessibilityIdentifier("searchEmpty")
        case .failed(let text):
            InlineNote(symbol: "exclamationmark.triangle.fill", text: "ما قدرت أبحث. تأكد من الإنترنت.")
            Text(verbatim: text)
                .font(Theme.Fonts.small)
                .foregroundStyle(Theme.ink3)
        }
    }
}

private struct SearchHint: View {
    var body: some View {
        Text("اكتب اسم مكان، أو عنوان مختصر مثل EDMA2510، أو الصق رابط أو إحداثيات.")
            .font(Theme.Fonts.note)
            .foregroundStyle(Theme.ink2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, Theme.Space.s)
    }
}

/// "Search for what I typed", when the suggestions are not it.
private struct SubmitRow: View {
    @EnvironmentObject private var search: SearchModel

    var body: some View {
        Button {
            search.submit()
        } label: {
            HStack(spacing: Theme.Space.m) {
                Image(systemName: "magnifyingglass.circle.fill")
                    .font(.title2)
                    .foregroundStyle(Theme.ink3)
                Text(verbatim: String(format: String(localized: "ابحث عن «%@»"), search.query))
                    .font(Theme.Fonts.body)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .frame(minHeight: Theme.Size.target)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("searchSubmit")
    }
}

private struct SuggestionRow: View {
    let suggestion: PlaceSuggestion

    var body: some View {
        HStack(spacing: Theme.Space.m) {
            Image(systemName: "mappin.circle.fill")
                .font(.title2)
                .foregroundStyle(Theme.ink3)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: suggestion.title)
                    .font(Theme.Fonts.body)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                if !suggestion.subtitle.isEmpty {
                    Text(verbatim: suggestion.subtitle)
                        .font(Theme.Fonts.small)
                        .foregroundStyle(Theme.ink2)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(minHeight: Theme.Size.target)
        .contentShape(Rectangle())
    }
}

/// A short message inside the panel.
struct InlineNote: View {
    let symbol: String
    let text: LocalizedStringKey

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
            Image(systemName: symbol)
                .foregroundStyle(Theme.ink3)
            Text(text)
                .font(Theme.Fonts.note)
                .foregroundStyle(Theme.ink2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: The start

private struct StartContent: View {
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var location: LocationModel

    var body: some View {
        if let note = link.note {
            Text(verbatim: note)
                .font(Theme.Fonts.note)
                .foregroundStyle(Theme.ink2)
                .accessibilityIdentifier("linkNote")
        }
        if case .notFound(denied: true) = link.presence {
            LocalNetworkCard()
        } else if link.pairing == nil || link.tokenRefused {
            PairPrompt()
        }
        if link.isOnline, let trip = link.trip, trip.kind != .idle {
            TripCard(trip: trip)
        }
        ForEach(link.pending) { item in
            PendingRow(item: item)
        }
        QuickTiles()
        ClipboardRow()
        if link.isOnline, link.music?.title != nil {
            MusicStrip()
        }
        SavedList()
    }
}

private struct PairPrompt: View {
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var link: CarLink

    var body: some View {
        Button {
            router.route = .pair(nil)
        } label: {
            HStack(spacing: Theme.Space.m) {
                Image(systemName: "qrcode.viewfinder")
                    .font(.title2)
                    .foregroundStyle(Theme.ink)
                VStack(alignment: .leading, spacing: 2) {
                    Text("اربط السيارة")
                        .font(Theme.Fonts.headline)
                        .foregroundStyle(Theme.ink)
                    Text("امسح الرمز اللي على الشاشة، مرة وحدة بس.")
                        .font(Theme.Fonts.small)
                        .foregroundStyle(Theme.ink2)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .foregroundStyle(Theme.ink3)
            }
            .cardSurface()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("pairPrompt")
    }
}

/// The permission was refused: say where to turn it on. iOS has no way to ask again.
struct LocalNetworkCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Label("الشبكة المحلية مقفلة على عمري", systemImage: "lock.fill")
                .font(Theme.Fonts.headline)
                .foregroundStyle(Theme.ink)
            Text("بدونها ما يوصل الجوال للشاشة. الإعدادات ← الخصوصية والأمان ← الشبكة المحلية، وفعّل عمري.")
                .font(Theme.Fonts.note)
                .foregroundStyle(Theme.ink2)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            } label: {
                Text("افتح الإعدادات")
            }
            .buttonStyle(SecondKeyStyle())
            .accessibilityIdentifier("openSettings")
        }
        .cardSurface()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("localNetworkCard")
    }
}

/// The trip as the unit reports it. The stop button is the only thing that touches it.
struct TripCard: View {
    let trip: NavEvent
    @EnvironmentObject private var link: CarLink

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(spacing: Theme.Space.s) {
                Circle()
                    .fill(trip.kind.isUnderWay ? Theme.signal : Theme.ink3)
                    .frame(width: 10, height: 10)
                Text(title)
                    .font(Theme.Fonts.note.weight(.semibold))
                    .foregroundStyle(Theme.ink2)
            }
            if let name = link.tripName {
                Text(verbatim: name)
                    .font(Theme.Fonts.title)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(2)
                    .accessibilityIdentifier("tripName")
            }
            let numbers = Formats.dotted([(trip.timeLeftS ?? trip.durationS).map { Formats.duration(seconds: $0) },
                                          trip.etaLocal.map { Formats.arrival($0) },
                                          (trip.remainingM ?? trip.distanceM).map { Formats.distance(meters: $0) }])
            if !numbers.isEmpty {
                Text(verbatim: numbers)
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
                    .accessibilityIdentifier("tripNumbers")
            }
            if let next = trip.nextText {
                Text(verbatim: next)
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
                    .lineLimit(2)
            }
            if trip.kind != .arrived {
                Button {
                    link.stopTrip()
                } label: {
                    Label("أوقف الاتجاهات", systemImage: "stop.fill")
                }
                .buttonStyle(SecondKeyStyle())
                .accessibilityIdentifier("stopTrip")
            }
        }
        .cardSurface()
        // A name on a plain stack would replace the names of everything inside it.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("tripCard")
    }

    private var title: LocalizedStringKey {
        switch trip.kind {
        case .routing: return "الشاشة تحسب الطريق"
        case .waitingFix: return "الشاشة تنتظر إشارة الموقع"
        case .arrived: return "وصلت"
        default: return "في الطريق"
        }
    }
}

/// A place the extension or the app could not deliver, waiting for up to 12 hours.
private struct PendingRow: View {
    let item: PendingPlace
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var location: LocationModel

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text("مكان ينتظر الإرسال")
                .font(Theme.Fonts.small)
                .foregroundStyle(Theme.ink2)
            Text(verbatim: item.place.name)
                .font(Theme.Fonts.headline)
                .foregroundStyle(Theme.ink)
            HStack(spacing: Theme.Space.s) {
                Button("أرسله الحين") { link.sendPending(item, fix: location.fix) }
                    .buttonStyle(SecondKeyStyle())
                    .accessibilityIdentifier("pendingSend")
                Button("احذفه") { link.dropPending(item) }
                    .buttonStyle(SecondKeyStyle())
                    .accessibilityIdentifier("pendingDrop")
            }
        }
        .cardSurface()
    }
}

/// البيت والعمل: one tap sends. Not set yet, they say how.
private struct QuickTiles: View {
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var location: LocationModel
    @State private var hint = false

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            HStack(spacing: Theme.Space.s) {
                tile(.home, title: "البيت", symbol: "house.fill")
                tile(.work, title: "العمل", symbol: "briefcase.fill")
            }
            if hint {
                Text("ابحث عن المكان، ومن بطاقته اختر «خلّه البيت» أو «خلّه العمل».")
                    .font(Theme.Fonts.small)
                    .foregroundStyle(Theme.ink2)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("quickHint")
            }
        }
    }

    private func tile(_ slot: PhoneSaved.Slot, title: LocalizedStringKey, symbol: String) -> some View {
        let quick = link.quick(slot)
        return Button {
            if let place = quick.place {
                link.send(place, saved: quick.which, fix: location.fix)
            } else {
                hint = true
            }
        } label: {
            HStack(spacing: Theme.Space.s) {
                Image(systemName: symbol)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(quick.isSet ? Theme.ink : Theme.ink3)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(Theme.Fonts.headline)
                        .foregroundStyle(Theme.ink)
                    Text(verbatim: quick.place?.name ?? (quick.isSet ? "…" : String(localized: "مو محدد")))
                        .font(Theme.Fonts.small)
                        .foregroundStyle(Theme.ink2)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Theme.Space.m)
            .frame(maxWidth: .infinity, minHeight: 62)
            .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("quick-\(slot.rawValue)")
    }
}

/// A maps link he copied: the system's own paste button, which needs no alert.
private struct ClipboardRow: View {
    @EnvironmentObject private var search: SearchModel
    @State private var maybeLink = false

    var body: some View {
        VStack(spacing: 0) {
            if maybeLink {
                HStack(spacing: Theme.Space.m) {
                    Text("فيه رابط منسوخ. لو هو مكان، الصقه هنا.")
                        .font(Theme.Fonts.note)
                        .foregroundStyle(Theme.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    PasteButton(payloadType: String.self) { strings in
                        guard let text = strings.first else { return }
                        Task { @MainActor in
                            search.query = text
                            search.submit()
                            maybeLink = false
                        }
                    }
                    .buttonBorderShape(.capsule)
                    .labelStyle(.titleOnly)
                    .accessibilityIdentifier("pasteLink")
                }
            }
        }
        .frame(maxWidth: .infinity)
        .task {
            // No alert: this only asks what kind of thing is there, without reading it.
            UIPasteboard.general.detectPatterns(for: [.probableWebURL]) { result in
                if case .success(let found) = result, found.contains(.probableWebURL) {
                    Task { @MainActor in maybeLink = true }
                }
            }
        }
    }
}

/// What plays on the unit, in one line. The whole remote is on the car's screen.
private struct MusicStrip: View {
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var router: Router

    var body: some View {
        HStack(spacing: Theme.Space.m) {
            Button {
                router.route = .car
            } label: {
                HStack(spacing: Theme.Space.s) {
                    Image(systemName: "music.note")
                        .foregroundStyle(Theme.ink3)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(verbatim: link.music?.title ?? "")
                            .font(Theme.Fonts.note.weight(.semibold))
                            .foregroundStyle(Theme.ink)
                            .lineLimit(1)
                        if let artist = link.music?.artist {
                            Text(verbatim: artist)
                                .font(Theme.Fonts.small)
                                .foregroundStyle(Theme.ink2)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("musicStrip")
            Button {
                link.remote(.musicToggle)
            } label: {
                Image(systemName: link.music?.playing == true ? "pause.fill" : "play.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(Theme.ink)
                    .frame(width: Theme.Size.target, height: Theme.Size.target)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(link.music?.playing == true ? "إيقاف مؤقت" : "تشغيل"))
            .accessibilityIdentifier("musicToggle")
        }
        .padding(.horizontal, Theme.Space.m)
        .frame(minHeight: 62)
        .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
    }
}

/// Favourites and recents. One tap sends.
private struct SavedList: View {
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var location: LocationModel

    var body: some View {
        if !link.favourites.isEmpty {
            sectionTitle("المفضلة")
            ForEach(Array(link.favourites.enumerated()), id: \.element.id) { index, place in
                row(place, symbol: "star.circle.fill", id: "favourite-\(index)")
                    .contextMenu {
                        Button(role: .destructive) {
                            link.removeFavourite(place)
                        } label: {
                            Label("احذف من المفضلة", systemImage: "trash")
                        }
                    }
            }
        }
        if !link.recents.isEmpty {
            sectionTitle("آخر الوجهات")
            ForEach(Array(link.recents.prefix(8).enumerated()), id: \.element.id) { index, place in
                row(place, symbol: "clock.arrow.circlepath", id: "recent-\(index)")
            }
        }
    }

    private func sectionTitle(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(Theme.Fonts.note.weight(.semibold))
            .foregroundStyle(Theme.ink2)
            .padding(.top, Theme.Space.s)
    }

    private func row(_ place: PhonePlace, symbol: String, id: String) -> some View {
        Button {
            link.send(place, fix: location.fix)
        } label: {
            PlaceRowLabel(place: place, distance: location.near.map { place.distance(from: $0) }, symbol: symbol)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}

// MARK: One place

private struct PlacePanel: View {
    let place: PhonePlace
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var search: SearchModel
    @EnvironmentObject private var location: LocationModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.m) {
                HStack(alignment: .top, spacing: Theme.Space.s) {
                    Button {
                        search.deselect()
                    } label: {
                        Image(systemName: "chevron.backward")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Theme.ink)
                            .frame(width: 44, height: 44)
                            .background(Theme.sunken, in: Circle())
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("رجوع"))
                    .accessibilityIdentifier("placeBack")
                    VStack(alignment: .leading, spacing: Theme.Space.xs) {
                        Text(verbatim: place.name)
                            .font(Theme.Fonts.title)
                            .foregroundStyle(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("placeName")
                        if let detail = place.detail, !detail.isEmpty {
                            Text(verbatim: detail)
                                .font(Theme.Fonts.note)
                                .foregroundStyle(Theme.ink2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if let near = location.near {
                            Text(verbatim: Formats.distance(meters: place.distance(from: near)))
                                .font(Theme.Fonts.small)
                                .foregroundStyle(Theme.ink3)
                                .accessibilityIdentifier("placeDistance")
                        }
                    }
                    Spacer(minLength: 0)
                }
                Button {
                    link.send(place, fix: location.fix)
                } label: {
                    Label("أرسل للسيارة", systemImage: "car.fill")
                }
                .buttonStyle(MainKeyStyle())
                .accessibilityIdentifier("sendKey")
                HStack(spacing: Theme.Space.s) {
                    saveKey("خلّه البيت", symbol: "house", slot: .home)
                    saveKey("خلّه العمل", symbol: "briefcase", slot: .work)
                    saveKey(link.isFavourite(place) ? "في المفضلة" : "المفضلة", symbol: link.isFavourite(place) ? "star.fill" : "star", slot: .favourite)
                }
                if let note = link.note {
                    Text(verbatim: note)
                        .font(Theme.Fonts.note)
                        .foregroundStyle(Theme.ink2)
                        .accessibilityIdentifier("linkNote")
                }
                if place.confidence == .guess, !place.isAppleSourced {
                    Text("هذا المكان تقدير من الرابط. تأكد منه على الخريطة قبل ما ترسله.")
                        .font(Theme.Fonts.note)
                        .foregroundStyle(Theme.warn)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(Theme.Space.l)
        }
    }

    private func saveKey(_ title: LocalizedStringKey, symbol: String, slot: PhoneSaved.Slot) -> some View {
        Button {
            link.save(place, as: slot)
        } label: {
            VStack(spacing: Theme.Space.xs) {
                Image(systemName: symbol)
                    .font(.system(size: 18))
                Text(title)
                    .font(Theme.Fonts.small)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(Theme.ink)
            .frame(maxWidth: .infinity, minHeight: Theme.Size.target + 8)
            .background(Theme.sunken, in: RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("save-\(slot.rawValue)")
    }
}

// MARK: A send

/// The real result on screen. "Started" only after the unit's own started event.
private struct SendPanel: View {
    @ObservedObject var model: ShareFlowModel
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var search: SearchModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                HStack(alignment: .top, spacing: Theme.Space.s) {
                    Text(verbatim: model.stage.place?.name ?? "")
                        .font(Theme.Fonts.title)
                        .foregroundStyle(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("sendPlaceName")
                    Spacer(minLength: 0)
                    // Back to the search: the unit keeps what it was already given.
                    Button {
                        link.closeFlow()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Theme.ink2)
                            .frame(width: 44, height: 44)
                            .background(Theme.sunken, in: Circle())
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("إغلاق"))
                    .accessibilityIdentifier("sendClose")
                }
                SendStageView(model: model) {
                    link.closeFlow()
                    search.clear()
                }
                if case .started = model.stage {
                    Button {
                        link.stopTrip()
                        link.closeFlow()
                        search.clear()
                    } label: {
                        Label("أوقف الاتجاهات", systemImage: "stop.fill")
                    }
                    .buttonStyle(SecondKeyStyle())
                    .accessibilityIdentifier("stopAfterStart")
                }
            }
            .padding(Theme.Space.l)
        }
    }
}
