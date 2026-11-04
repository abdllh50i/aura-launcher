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
                        SendPlaceHeader(place: place, snapshot: model.snapshot)
                    }
                    SendStageView(model: model, onClose: onClose)
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

/// The place on its way: a still map and its name.
struct SendPlaceHeader: View {
    let place: PhonePlace
    let snapshot: UIImage?

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            ZStack {
                RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                    .fill(Theme.sunken)
                if let snapshot {
                    Image(uiImage: snapshot)
                        .resizable()
                        .scaledToFill()
                }
                Image(systemName: "mappin.circle.fill")
                    .font(.system(size: 34))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(Color.white, Theme.signal)
                    .shadow(radius: 3)
            }
            .frame(height: 130)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            .accessibilityHidden(true)
            Text(verbatim: place.name)
                .font(Theme.Fonts.title)
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("sharePlaceName")
            if let detail = place.detail, !detail.isEmpty {
                Text(verbatim: detail)
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// Each stage of a send in plain words, with the one action that fits it. Shared by the share sheet and the app.
struct SendStageView: View {
    @ObservedObject var model: ShareFlowModel
    var onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            content
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.stage {
        case .reading:
            progressRow("أقرأ اللي شاركته…")
        case .confirm(let place):
            note("تأكد إن هذا المكان هو اللي تبيه.")
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
            note("شارك من الخرائط، أو انسخ الإحداثيات أو العنوان المختصر.")
            closeKey
        case .locating:
            progressRow("أدور على السيارة…")
        case .sending(_, let attempt, let total):
            progressRow("أرسل للسيارة…", caption: attempt > 1 ? Formats.attempts(attempt, of: total) : nil)
        case .waiting:
            waitingRow
            closeSecondary
        case .card:
            result(symbol: "hand.tap.fill", color: Theme.ink, title: "وصل للشاشة. اضغط «ابدأ» على شاشة السيارة.")
            small("الجوال ما عنده رمز ربط هنا، فالشاشة تطلب تأكيدك. وما أقدر أشوف من هنا إذا بدأت الاتجاهات.")
            closeKey
        case .started(_, let nav):
            result(symbol: "checkmark.circle.fill", color: Theme.good, title: "بدأت الاتجاهات")
            let line = Formats.dotted([nav.durationS.map { Formats.duration(seconds: $0) }, nav.etaLocal.map { Formats.arrival($0) }, nav.distanceM.map { Formats.distance(meters: $0) }])
            if !line.isEmpty {
                Text(verbatim: line)
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
                    .accessibilityIdentifier("shareTripLine")
            }
            closeKey
        case .handed:
            result(symbol: "arrow.up.forward.app.fill", color: Theme.ink, title: "راح المكان لتطبيق الملاحة اللي مختاره على الشاشة")
            small("ذاك التطبيق ما يرد على عمري، فما أقدر أقول إذا بدأت الاتجاهات.")
            closeKey
        case .ignored:
            result(symbol: "arrow.uturn.backward.circle.fill", color: Theme.warn, title: "الشاشة كملت الرحلة اللي كانت فيها")
            closeKey
        case .expired(let place):
            result(symbol: "clock.badge.xmark.fill", color: Theme.warn, title: "ما أحد رد على الشاشة، فما بدأت الاتجاهات")
            sendKey(place, title: "أرسله مرة ثانية")
        case .cancelled(let place):
            result(symbol: "xmark.circle.fill", color: Theme.warn, title: "انلغى على الشاشة")
            sendKey(place, title: "أرسله مرة ثانية")
        case .routeFailed(let place, let reason, let kept):
            result(symbol: "exclamationmark.triangle.fill", color: Theme.bad, title: "الشاشة ما قدرت تحسب الطريق")
            reasonText(reason)
            if kept {
                small("الرحلة اللي كنت فيها مستمرة.")
            }
            sendKey(place, title: "حاول مرة ثانية")
        case .refused(let place, let reason):
            result(symbol: "xmark.octagon.fill", color: Theme.bad, title: "الشاشة ما قبلت")
            reasonText(reason)
            sendKey(place, title: "حاول مرة ثانية")
        case .notPaired:
            result(symbol: "link.badge.plus", color: Theme.warn, title: "الجوال مو مربوط بهذي الشاشة")
            note("افتح عمري واربط السيارة، أو شغّل الهوتسبوت من الجوال نفسه عشان الشاشة تطلب تأكيدك.")
            copyKey
            closeSecondary
        case .unreachable(let place, let denied, let saved):
            if denied {
                result(symbol: "lock.fill", color: Theme.bad, title: "الشبكة المحلية مقفلة على عمري")
                note("الإعدادات ← الخصوصية والأمان ← الشبكة المحلية، وفعّل عمري.")
            } else {
                result(symbol: "antenna.radiowaves.left.and.right.slash", color: Theme.warn, title: "ما لقيت السيارة")
                note("تأكد إن الهوتسبوت شغّال والشاشة متصلة فيه.")
            }
            if saved {
                small("حفظت المكان. لما تفتح عمري وأنت في السيارة ترسله بضغطة، خلال ١٢ ساعة.")
            }
            sendKey(place, title: "حاول مرة ثانية")
            copyKey
        case .noWord:
            result(symbol: "questionmark.circle.fill", color: Theme.warn, title: "وصل للسيارة، بس ما جاني تأكيد إن الاتجاهات بدأت")
            note("شوف الشاشة.")
            closeKey
        }
    }

    /// What the unit last said about this send. Never more than that.
    @ViewBuilder
    private var waitingRow: some View {
        switch model.unitWord?.kind {
        case .card?:
            switch model.unitWord?.cardKind {
            case "replace"?:
                progressRow("الشاشة تسألك: تبدّل الرحلة الحالية؟ جاوب على الشاشة.")
            case "map"?:
                progressRow("المكان على خريطة الشاشة. اضغط «انطلق» هناك.")
            default:
                progressRow("الشاشة تسألك: تبدأ الاتجاهات؟ جاوب على الشاشة.")
            }
        case .held?:
            progressRow("الشاشة ماسكة المكان لين تخلص المكالمة أو الرجوع للخلف.")
        case .waitingFix?:
            progressRow("الشاشة تنتظر إشارة الموقع عشان تحسب الطريق…")
        default:
            progressRow("وصل للسيارة، الشاشة تحسب الطريق…")
        }
    }

    private func note(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(Theme.Fonts.body)
            .foregroundStyle(Theme.ink2)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func small(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(Theme.Fonts.note)
            .foregroundStyle(Theme.ink2)
            .fixedSize(horizontal: false, vertical: true)
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
        .accessibilityIdentifier("shareCloseSecondary")
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

    private func progressRow(_ title: LocalizedStringKey, caption: String? = nil) -> some View {
        HStack(spacing: Theme.Space.m) {
            ProgressView()
            VStack(alignment: .leading, spacing: Theme.Space.xs) {
                Text(title)
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if let caption {
                    Text(verbatim: caption)
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
        if let text = Formats.reason(reason) {
            Text(verbatim: text)
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("shareReason")
        }
    }
}

/// A place in a list: name, address, distance if known.
struct PlaceRowLabel: View {
    let place: PhonePlace
    let distance: Double?
    var symbol = "mappin.circle.fill"

    var body: some View {
        HStack(spacing: Theme.Space.m) {
            Image(systemName: symbol)
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
