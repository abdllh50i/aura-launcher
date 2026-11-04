import AMRIKit
import SwiftUI

/// السيارة: what the phone knows about it, the music and volume remote, and the ways to fix the link.
struct CarScreen: View {
    @EnvironmentObject private var link: CarLink
    @Environment(\.dismiss) private var dismiss
    @State private var confirmUnpair = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.m) {
                statusCard
                if link.isOnline, let trip = link.trip, trip.kind != .idle {
                    TripCard(trip: trip)
                }
                if link.isOnline {
                    RemoteCard()
                }
                if let note = link.note {
                    Text(verbatim: note)
                        .font(Theme.Fonts.note)
                        .foregroundStyle(Theme.ink2)
                        .accessibilityIdentifier("linkNote")
                }
                tools
                signing
            }
            .padding(Theme.Space.l)
        }
        .background(Theme.ground)
        .navigationTitle("السيارة")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("إغلاق") { dismiss() }
                    .accessibilityIdentifier("carClose")
            }
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(spacing: Theme.Space.s) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 12, height: 12)
                Text(statusTitle)
                    .font(Theme.Fonts.title)
                    .foregroundStyle(Theme.ink)
                    .accessibilityIdentifier("carStatus")
            }
            if let ping = link.unitPing {
                fact("الشاشة", Formats.dotted([ping.name, ping.unit]))
                if let version = ping.version { fact("النسخة", version) }
            }
            if case .online(let address) = link.presence {
                fact("العنوان", address.text)
            }
            if let pairing = link.pairing {
                fact("مربوط مع", pairing.name)
            }
            if link.isOnline {
                carFacts
            }
            statusHelp
        }
        .cardSurface()
    }

    @ViewBuilder
    private var carFacts: some View {
        if let car = link.car {
            if let fix = car.gpsFix {
                fact("إشارة الموقع", fix ? String(localized: "فيه") : String(localized: "ما فيه"))
            } else {
                fact("إشارة الموقع", String(localized: "غير معروف"))
            }
            if let speed = car.speedKmh {
                fact("السرعة", String(format: String(localized: "%@ كم/س"), Formats.number(Int(speed))))
            }
            if car.reversing == true { fact("الحالة", String(localized: "ترجع للخلف")) }
            if car.inCall == true { fact("الحالة", String(localized: "في مكالمة")) }
        }
    }

    @ViewBuilder
    private var statusHelp: some View {
        switch link.presence {
        case .notFound(let denied):
            if denied {
                Text("الإعدادات ← الخصوصية والأمان ← الشبكة المحلية، وفعّل عمري.")
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text("شغّل الهوتسبوت من الجوال، وتأكد إن الشاشة شبكت عليه.")
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button {
                link.retryNow()
            } label: {
                Label("دوّر مرة ثانية", systemImage: "arrow.clockwise")
            }
            .buttonStyle(SecondKeyStyle())
            .accessibilityIdentifier("retryFind")
        default:
            EmptyView()
        }
    }

    private func fact(_ title: LocalizedStringKey, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
            Text(title)
                .font(Theme.Fonts.note)
                .foregroundStyle(Theme.ink2)
            Spacer(minLength: Theme.Space.s)
            Text(verbatim: value)
                .font(Theme.Fonts.note.weight(.medium))
                .foregroundStyle(Theme.ink)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
        }
        .frame(minHeight: 28)
    }

    private var statusTitle: LocalizedStringKey {
        switch link.presence {
        case .looking: return "أدور على السيارة…"
        case .online: return "السيارة متصلة"
        case .unpaired: return link.tokenRefused ? "الشاشة ما تعرف هذا الجوال" : "لقيت الشاشة، وما هي مربوطة"
        case .notFound(let denied): return denied ? "الشبكة المحلية مقفلة" : "ما لقيت السيارة"
        }
    }

    private var statusColor: Color {
        switch link.presence {
        case .online: return Theme.good
        case .unpaired: return Theme.warn
        case .notFound(let denied): return denied ? Theme.bad : Theme.ink3
        case .looking: return Theme.ink3
        }
    }

    private var tools: some View {
        VStack(spacing: Theme.Space.s) {
            if link.pairing == nil || link.tokenRefused {
                NavigationLink {
                    PairScreen(target: nil)
                } label: {
                    toolRow("اربط السيارة", note: "امسح الرمز اللي على الشاشة", symbol: "qrcode.viewfinder")
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("toolPair")
            }
            NavigationLink {
                CheckScreen()
            } label: {
                toolRow("فحص الربط", note: "تقرير تنسخه وترسله", symbol: "stethoscope")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("toolCheck")
            NavigationLink {
                RepairScreen()
            } label: {
                toolRow("إصلاح الشاشة", note: "رجوع لنسخة 1.19 لو تعطلت 2.0", symbol: "wrench.and.screwdriver")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("toolRepair")
            if link.pairing != nil {
                Button(role: .destructive) {
                    confirmUnpair = true
                } label: {
                    Text("فك الربط")
                        .font(Theme.Fonts.note)
                        .frame(maxWidth: .infinity, minHeight: Theme.Size.target)
                }
                .accessibilityIdentifier("toolUnpair")
                .confirmationDialog("تفك الربط مع هذي الشاشة؟", isPresented: $confirmUnpair, titleVisibility: .visible) {
                    Button("فك الربط", role: .destructive) { link.unpair() }
                }
            }
        }
    }

    private func toolRow(_ title: LocalizedStringKey, note: LocalizedStringKey, symbol: String) -> some View {
        HStack(spacing: Theme.Space.m) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(Theme.ink)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
                Text(note)
                    .font(Theme.Fonts.small)
                    .foregroundStyle(Theme.ink2)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.forward")
                .foregroundStyle(Theme.ink3)
        }
        .padding(.horizontal, Theme.Space.l)
        .frame(minHeight: 66)
        .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
    }

    /// How long the signature has left. The warning on the map starts at three days.
    @ViewBuilder
    private var signing: some View {
        if let profile = link.profile, let days = profile.daysLeft() {
            Text(verbatim: days < 0 ? String(localized: "انتهى توقيع عمري") : String(format: String(localized: "باقي %@ على انتهاء توقيع عمري"), Formats.days(days)))
                .font(Theme.Fonts.small)
                .foregroundStyle(Theme.ink3)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("signingDays")
        }
    }
}

/// What plays on the unit and the car's volume step, as the unit reports them. Transport stays left to right even in
/// Arabic: it speaks of a tape's direction, not of reading.
struct RemoteCard: View {
    @EnvironmentObject private var link: CarLink

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            VStack(alignment: .leading, spacing: 2) {
                Text("الموسيقى")
                    .font(Theme.Fonts.small)
                    .foregroundStyle(Theme.ink2)
                Text(verbatim: link.music?.title ?? String(localized: "ما أدري وش يشتغل"))
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(2)
                    .accessibilityIdentifier("musicTitle")
                if let artist = link.music?.artist {
                    Text(verbatim: artist)
                        .font(Theme.Fonts.note)
                        .foregroundStyle(Theme.ink2)
                        .lineLimit(1)
                }
            }
            HStack(spacing: Theme.Space.s) {
                transport("backward.fill", label: "السابق", id: "musicPrev", .musicPrev)
                transport(link.music?.playing == true ? "pause.fill" : "play.fill",
                          label: link.music?.playing == true ? "إيقاف مؤقت" : "تشغيل", id: "musicPlay", .musicToggle)
                transport("forward.fill", label: "التالي", id: "musicNext", .musicNext)
            }
            .environment(\.layoutDirection, .leftToRight)
            HStack(spacing: Theme.Space.s) {
                key("speaker.minus.fill", label: "وطّي الصوت", id: "volumeDown", .volumeDown)
                VStack(spacing: 0) {
                    Text("الصوت")
                        .font(Theme.Fonts.small)
                        .foregroundStyle(Theme.ink2)
                    Text(verbatim: volumeText)
                        .font(Theme.Fonts.headline)
                        .foregroundStyle(Theme.ink)
                        .monospacedDigit()
                        .accessibilityIdentifier("volumeStep")
                }
                .frame(maxWidth: .infinity)
                key("speaker.plus.fill", label: "علّي الصوت", id: "volumeUp", .volumeUp)
            }
        }
        .cardSurface()
    }

    /// The car's own step, never a percentage. Unknown is said.
    private var volumeText: String {
        guard let step = link.music?.volume ?? link.car?.volumeStep else { return String(localized: "غير معروف") }
        if link.music?.muted == true { return String(localized: "مكتوم") }
        if let top = link.music?.maxVolume ?? link.car?.volumeMax {
            return String(format: String(localized: "%@ من %@"), Formats.number(step), Formats.number(top))
        }
        return Formats.number(step)
    }

    private func transport(_ symbol: String, label: LocalizedStringKey, id: String, _ command: RemoteCommand) -> some View {
        key(symbol, label: label, id: id, command)
    }

    private func key(_ symbol: String, label: LocalizedStringKey, id: String, _ command: RemoteCommand) -> some View {
        Button {
            link.remote(command)
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Theme.ink)
                .frame(maxWidth: .infinity, minHeight: Theme.Size.target)
                .background(Theme.sunken, in: RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.key, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityIdentifier(id)
    }
}
