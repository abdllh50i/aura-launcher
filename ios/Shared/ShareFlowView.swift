import AMRIKit
import SwiftUI

/// What the share sheet shows: the place, a still map, one action, then the real result.
struct ShareFlowView: View {
    @ObservedObject var model: ShareFlowModel
    var onClose: () -> Void
    @State private var showDetails = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.l) {
                    if let place = model.stage.place {
                        placeHeader(place)
                    }
                    stageContent
                    details
                }
                .padding(Theme.Space.l)
            }
            .background(Theme.ground)
            .navigationTitle("إرسال للسيارة")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إغلاق") {
                        model.cancel()
                        onClose()
                    }
                    .accessibilityIdentifier("shareClose")
                }
            }
        }
    }

    private func placeHeader(_ place: PhonePlace) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            snapshotView
            Text(verbatim: place.name)
                .font(Theme.Fonts.title)
                .foregroundStyle(Theme.ink)
                .accessibilityIdentifier("sharePlaceName")
            if let detail = place.detail, !detail.isEmpty {
                Text(verbatim: detail)
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
            }
        }
    }

    private var snapshotView: some View {
        ZStack {
            RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                .fill(Theme.sunken)
            if let image = model.snapshot {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            }
            Image(systemName: "mappin.circle.fill")
                .font(.system(size: 34))
                .symbolRenderingMode(.palette)
                .foregroundStyle(Color.white, Theme.signal)
                .shadow(radius: 3)
        }
        .frame(height: 150)
        .frame(maxWidth: .infinity)
        .clipped()
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var stageContent: some View {
        switch model.stage {
        case .reading:
            progressRow("أقرأ اللي شاركته…")
        case .confirm(let place):
            Text("تأكد إن هذا المكان هو اللي تبيه.")
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
            sendKey(place)
        case .pick(let places, _):
            Text("اختر المكان")
                .font(Theme.Fonts.title)
                .foregroundStyle(Theme.ink)
            ForEach(Array(places.prefix(12).enumerated()), id: \.element.id) { index, place in
                Button {
                    model.send(place)
                } label: {
                    PlaceRowLabel(place: place, distance: nil)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("sharePick-\(index)")
            }
        case .nothing:
            result(symbol: "questionmark.circle.fill", color: Theme.warn, title: "ما لقيت مكان في اللي شاركته")
            Text("شارك من الخرائط، أو انسخ الإحداثيات أو العنوان المختصر.")
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
            closeKey
        case .locating:
            progressRow("أدور على السيارة…")
        case .sending(_, let attempt, let total):
            progressRow("أرسل للسيارة…", note: attempt > 1 ? Formats.attempts(attempt, of: total) : nil)
        case .waiting:
            progressRow("وصل للسيارة، الشاشة تحسب الطريق…")
            closeSecondary
        case .card:
            result(symbol: "hand.tap.fill", color: Theme.ink, title: "وصل للشاشة. اضغط «ابدأ» على شاشة السيارة.")
            Text("الجوال ما عنده رمز ربط هنا، فالشاشة تطلب تأكيدك. وما أقدر أشوف من هنا إذا بدأت الاتجاهات.")
                .font(Theme.Fonts.note)
                .foregroundStyle(Theme.ink2)
            closeKey
        case .started(_, let nav):
            result(symbol: "checkmark.circle.fill", color: Theme.signal, title: "بدأت الاتجاهات")
            let line = Formats.dotted([nav.durationS.map { Formats.duration(seconds: $0) }, nav.etaLocal.map { Formats.arrival($0) }, nav.distanceM.map { Formats.distance(meters: $0) }])
            if !line.isEmpty {
                Text(verbatim: line)
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
                    .accessibilityIdentifier("shareTripLine")
            }
            closeKey
        case .routeFailed(let place, let reason):
            result(symbol: "exclamationmark.triangle.fill", color: Theme.bad, title: "الشاشة ما قدرت تحسب الطريق")
            reasonText(reason)
            sendKey(place, title: "حاول مرة ثانية")
        case .refused(let place, let reason):
            result(symbol: "xmark.octagon.fill", color: Theme.bad, title: "الشاشة ما قبلت")
            reasonText(reason)
            sendKey(place, title: "حاول مرة ثانية")
        case .notPaired:
            result(symbol: "link.badge.plus", color: Theme.warn, title: "الجوال مو مربوط بهذي الشاشة")
            Text("افتح عمري واربط السيارة، أو شغّل الهوتسبوت من الجوال نفسه عشان الشاشة تطلب تأكيدك.")
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
            copyKey
            closeSecondary
        case .unreachable(let place, let denied, let saved):
            if denied {
                result(symbol: "lock.fill", color: Theme.bad, title: "الشبكة المحلية مقفلة على عمري")
                Text("الإعدادات ← الخصوصية والأمان ← الشبكة المحلية، وفعّل عمري.")
                    .font(Theme.Fonts.body)
                    .foregroundStyle(Theme.ink2)
            } else {
                result(symbol: "antenna.radiowaves.left.and.right.slash", color: Theme.warn, title: "ما لقيت السيارة")
                Text("تأكد إن الهوتسبوت شغّال والشاشة متصلة فيه.")
                    .font(Theme.Fonts.body)
                    .foregroundStyle(Theme.ink2)
            }
            if saved {
                Text("حفظت المكان. لما تفتح عمري وأنت في السيارة ترسله بضغطة، خلال ١٢ ساعة.")
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
            }
            sendKey(place, title: "حاول مرة ثانية")
            copyKey
        case .noWord:
            result(symbol: "questionmark.circle.fill", color: Theme.warn, title: "وصل للسيارة، بس ما جاني تأكيد إن الاتجاهات بدأت")
            Text("شوف الشاشة.")
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
            closeKey
        }
    }

    private func sendKey(_ place: PhonePlace, title: LocalizedStringKey = "أرسل للسيارة") -> some View {
        Button {
            model.send(place)
        } label: {
            Text(title)
        }
        .buttonStyle(MainKeyStyle())
        .accessibilityIdentifier("shareSend")
    }

    private var closeKey: some View {
        Button("تم") { onClose() }
            .buttonStyle(MainKeyStyle())
            .accessibilityIdentifier("shareDone")
    }

    private var closeSecondary: some View {
        Button("إغلاق") {
            model.cancel()
            onClose()
        }
        .buttonStyle(SecondKeyStyle())
    }

    private var copyKey: some View {
        Button {
            model.copyPlace()
        } label: {
            Label(model.copied ? "تم النسخ" : "انسخ المكان", systemImage: model.copied ? "checkmark" : "doc.on.doc")
        }
        .buttonStyle(SecondKeyStyle())
        .accessibilityIdentifier("shareCopy")
    }

    private func progressRow(_ title: LocalizedStringKey, note: String? = nil) -> some View {
        HStack(spacing: Theme.Space.m) {
            ProgressView()
            VStack(alignment: .leading, spacing: Theme.Space.xs) {
                Text(title)
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
                if let note {
                    Text(verbatim: note)
                        .font(Theme.Fonts.note)
                        .foregroundStyle(Theme.ink2)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("shareStatus")
    }

    private func result(symbol: String, color: Color, title: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.m) {
            Image(systemName: symbol)
                .foregroundStyle(color)
            Text(title)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(Theme.Fonts.title)
        .foregroundStyle(Theme.ink)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("shareStatus")
    }

    @ViewBuilder
    private func reasonText(_ reason: String?) -> some View {
        if let reason, !reason.isEmpty {
            Text(verbatim: reason)
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
        }
    }

    /// The raw payload, right here, because without an App Group the app cannot read it.
    @ViewBuilder
    private var details: some View {
        if let payload = model.payload {
            DisclosureGroup(isExpanded: $showDetails) {
                VStack(alignment: .leading, spacing: Theme.Space.s) {
                    Text(verbatim: payload.typeIdentifiers.map { $0.joined(separator: ", ") }.joined(separator: " | "))
                    ForEach(payload.urls, id: \.self) { Text(verbatim: $0) }
                    ForEach(payload.texts, id: \.self) { Text(verbatim: String($0.prefix(400))) }
                    ForEach(model.trail, id: \.self) { Text(verbatim: $0) }
                    Button {
                        model.copyDetails()
                    } label: {
                        Label("انسخ التفاصيل", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(SecondKeyStyle())
                    .accessibilityIdentifier("shareCopyDetails")
                }
                .font(Theme.Fonts.mono)
                .foregroundStyle(Theme.ink2)
                .textSelection(.enabled)
                .padding(.top, Theme.Space.s)
            } label: {
                Text("التفاصيل")
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
                    .frame(minHeight: 44)
            }
            .tint(Theme.ink2)
            .accessibilityIdentifier("shareDetails")
        }
    }
}

/// A place in a list: name, address, distance if known.
struct PlaceRowLabel: View {
    let place: PhonePlace
    let distance: Double?

    var body: some View {
        HStack(spacing: Theme.Space.m) {
            Image(systemName: "mappin.circle.fill")
                .font(.title2)
                .foregroundStyle(Theme.ink3)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: place.name)
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                if let detail = place.detail, !detail.isEmpty {
                    Text(verbatim: detail)
                        .font(Theme.Fonts.small)
                        .foregroundStyle(Theme.ink2)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
            }
            Spacer(minLength: Theme.Space.s)
            if let distance {
                Text(verbatim: Formats.distance(meters: distance))
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
            }
        }
        .frame(minHeight: Theme.Size.target)
        .contentShape(Rectangle())
    }
}
