import AMRIKit
import MapKit
import SwiftUI

/// A real MapKit map with everything else floating over it: the car's status on top, the panel below. Apple's results are
/// only ever shown here, on Apple's map (section 2.6).
struct MapScreen: View {
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var search: SearchModel
    @EnvironmentObject private var location: LocationModel
    @EnvironmentObject private var router: Router

    @State private var camera: MapCameraPosition = .userLocation(fallback: .region(MapScreen.startRegion))
    @State private var pick: PhonePlace?
    @State private var detent: PresentationDetent = Panel.peek
    @State private var panelUp = true

    /// The Eastern Province until the phone says where it is.
    private static let startRegion = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 26.35, longitude: 50.12),
                                                        latitudinalMeters: 60_000, longitudinalMeters: 60_000)

    var body: some View {
        GeometryReader { geometry in
            Map(position: $camera, selection: $pick) {
                UserAnnotation()
                ForEach(search.pins) { place in
                    Marker(place.name, systemImage: "mappin", coordinate: place.coordinate)
                        .tint(place == search.selected ? Theme.signal : Theme.ink)
                        .tag(place)
                }
            }
            .mapControls {
                MapUserLocationButton()
                MapCompass()
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                TopBar()
                    .padding(.horizontal, Theme.Space.m)
                    .padding(.top, Theme.Space.xs)
                    .padding(.bottom, Theme.Space.s)
            }
            .safeAreaPadding(.bottom, mapInset(height: geometry.size.height))
            .accessibilityIdentifier("map")
        }
        .ignoresSafeArea(.keyboard)
        .sheet(isPresented: $panelUp) {
            Panel(detent: $detent)
                .presentationDetents([Panel.peek, .medium, .large], selection: $detent)
                .presentationBackgroundInteraction(.enabled(upThrough: .medium))
                .presentationDragIndicator(.visible)
                .interactiveDismissDisabled()
        }
        .onChange(of: pick) { _, new in
            if let new { search.selected = new }
        }
        .onChange(of: search.selected) { _, new in
            pick = new
            if let new {
                withAnimation { camera = .region(MKCoordinateRegion(center: new.coordinate, latitudinalMeters: 1600, longitudinalMeters: 1600)) }
                detent = .medium
            }
        }
        .onChange(of: search.results) { _, new in
            guard search.selected == nil, let region = MapScreen.region(fitting: new) else { return }
            withAnimation { camera = .region(region) }
        }
    }

    /// So what the camera centres on sits above the panel, not behind it.
    private func mapInset(height: CGFloat) -> CGFloat {
        detent == Panel.peek ? Panel.peekHeight : height * 0.5
    }

    private static func region(fitting places: [PhonePlace]) -> MKCoordinateRegion? {
        guard let first = places.first else { return nil }
        var minLat = first.lat, maxLat = first.lat, minLon = first.lon, maxLon = first.lon
        for place in places {
            minLat = min(minLat, place.lat)
            maxLat = max(maxLat, place.lat)
            minLon = min(minLon, place.lon)
            maxLon = max(maxLon, place.lon)
        }
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLon + maxLon) / 2)
        let span = MKCoordinateSpan(latitudeDelta: max(0.012, (maxLat - minLat) * 1.5), longitudeDelta: max(0.012, (maxLon - minLon) * 1.5))
        return MKCoordinateRegion(center: center, span: span)
    }
}

/// The car's status and the way to its screen, floating on the map.
private struct TopBar: View {
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var router: Router

    var body: some View {
        VStack(spacing: Theme.Space.s) {
            HStack(spacing: Theme.Space.s) {
                Button {
                    switch link.presence {
                    case .unpaired:
                        router.route = .pair(nil)
                    default:
                        router.route = .car
                    }
                } label: {
                    StatusPill()
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("statusPill")
                Spacer(minLength: 0)
                Button {
                    router.route = .car
                } label: {
                    Image(systemName: "car.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.ink)
                        .frame(width: 46, height: 46)
                        .floatingSurface(in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("السيارة"))
                .accessibilityIdentifier("carButton")
            }
            if let profile = link.profile, profile.needsRefresh() {
                ExpiryBanner(days: profile.daysLeft() ?? 0)
            }
        }
    }
}

/// "السيارة متصلة · ٢٣ دقيقة · الوصول ٩:٤١": only from real events.
struct StatusPill: View {
    @EnvironmentObject private var link: CarLink

    var body: some View {
        HStack(spacing: Theme.Space.s) {
            Group {
                if case .looking = link.presence {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Circle()
                        .fill(dot)
                        .frame(width: 10, height: 10)
                }
            }
            .frame(width: 16, height: 16)
            Text(verbatim: line)
                .font(Theme.Fonts.note.weight(.semibold))
                .foregroundStyle(Theme.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(.horizontal, Theme.Space.m)
        .frame(minHeight: 46)
        .floatingSurface(in: Capsule())
        .contentShape(Capsule())
        .accessibilityElement(children: .combine)
    }

    private var dot: Color {
        switch link.presence {
        case .online:
            return link.trip?.kind.isUnderWay == true ? Theme.signal : Theme.good
        case .unpaired:
            return Theme.warn
        case .notFound(let denied):
            return denied ? Theme.bad : Theme.ink3
        case .looking:
            return Theme.ink3
        }
    }

    private var line: String {
        switch link.presence {
        case .looking:
            return String(localized: "أدور على السيارة…")
        case .unpaired:
            return link.tokenRefused ? String(localized: "الشاشة ما تعرف الجوال. اربطها") : String(localized: "لقيت الشاشة. اربطها")
        case .notFound(let denied):
            return denied ? String(localized: "الشبكة المحلية مقفلة") : String(localized: "ما لقيت السيارة")
        case .online:
            guard let trip = link.trip else { return String(localized: "السيارة متصلة") }
            let connected = String(localized: "السيارة متصلة")
            switch trip.kind {
            case .started, .progress:
                return Formats.dotted([connected, (trip.timeLeftS ?? trip.durationS).map { Formats.duration(seconds: $0) }, trip.etaLocal.map { Formats.arrival($0) }])
            case .routing:
                return Formats.dotted([connected, String(localized: "تحسب الطريق")])
            case .waitingFix:
                return Formats.dotted([connected, String(localized: "تنتظر إشارة الموقع")])
            case .arrived:
                return Formats.dotted([connected, String(localized: "وصلت")])
            default:
                return connected
            }
        }
    }
}

/// Three days or fewer left on the 7-day signature, with the Sideloadly step.
struct ExpiryBanner: View {
    let days: Int

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Space.s) {
            Image(systemName: "clock.badge.exclamationmark.fill")
                .foregroundStyle(Theme.warn)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: days < 0 ? String(localized: "انتهى توقيع عمري") : String(format: String(localized: "باقي %@ على انتهاء توقيع عمري"), Formats.days(days)))
                    .font(Theme.Fonts.note.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                Text("وصّل الجوال بالكمبيوتر، افتح Sideloadly، واختر ملف عمري ووقّعه من جديد.")
                    .font(Theme.Fonts.small)
                    .foregroundStyle(Theme.ink2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(Theme.Space.m)
        .floatingSurface(in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("expiryBanner")
    }
}
