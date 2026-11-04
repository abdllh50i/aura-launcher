import AMRIKit
import SwiftUI
import UIKit

/// Pairing: scan the unit's QR, or type its six digits. The app does this in the foreground, which is also what makes iOS
/// ask about the Local Network: an extension in the background would be refused silently (TN3179).
struct PairScreen: View {
    let target: PairingTarget?
    @EnvironmentObject private var link: CarLink
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = PairModel()
    @State private var code = ""
    @State private var camera: QRCamera.State = .starting
    @FocusState private var typing: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                switch model.stage {
                case .done(let name):
                    doneCard(name)
                default:
                    scanner
                    typedCode
                    stageNote
                }
            }
            .padding(Theme.Space.l)
        }
        .background(Theme.ground)
        .navigationTitle("اربط السيارة")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("إغلاق") { dismiss() }
                    .accessibilityIdentifier("pairClose")
            }
        }
        .task {
            model.askForLocalNetwork()
            if let target { model.pair(target, link: link) }
        }
    }

    private var scanner: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text("امسح الرمز اللي على شاشة السيارة")
                .font(Theme.Fonts.title)
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            Text("على الشاشة: الإعدادات ← السيارة والنظام ← ربط الجوال، شغّله واضغط «اعرض رمز الربط».")
                .font(Theme.Fonts.note)
                .foregroundStyle(Theme.ink2)
                .fixedSize(horizontal: false, vertical: true)
            ZStack {
                RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                    .fill(Theme.sunken)
                if model.isScanning {
                    QRCamera(state: $camera) { text in
                        if let scanned = PairingTarget.parse(text) {
                            model.pair(scanned, link: link)
                        } else {
                            model.notOurs()
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
                }
                switch camera {
                case .starting, .running:
                    EmptyView()
                case .denied:
                    cameraNote("الكاميرا مقفلة على عمري. فعّلها من الإعدادات، أو اكتب الرمز تحت.")
                case .unavailable:
                    cameraNote("الكاميرا مو متاحة هنا. اكتب الرمز تحت.")
                }
            }
            .frame(height: 240)
            .accessibilityIdentifier("qrCamera")
        }
    }

    private func cameraNote(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(Theme.Fonts.note)
            .foregroundStyle(Theme.ink2)
            .multilineTextAlignment(.center)
            .padding(Theme.Space.l)
            .accessibilityIdentifier("cameraNote")
    }

    private var typedCode: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text("أو اكتب الرمز، ستة أرقام")
                .font(Theme.Fonts.headline)
                .foregroundStyle(Theme.ink)
            TextField("", text: $code, prompt: Text(verbatim: "••••••"))
                .font(.system(.title2, design: .monospaced).weight(.semibold))
                .multilineTextAlignment(.center)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($typing)
                .frame(minHeight: Theme.Size.target)
                .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field, style: .continuous))
                .environment(\.layoutDirection, .leftToRight)
                .accessibilityIdentifier("pairCode")
            Button {
                typing = false
                model.pairTyped(code, link: link)
            } label: {
                Text("اربط")
            }
            .buttonStyle(MainKeyStyle())
            .disabled(ShareParser.normalize(code).filter(\.isNumber).count != 6 || model.isWorking)
            .opacity(ShareParser.normalize(code).filter(\.isNumber).count != 6 ? 0.5 : 1)
            .accessibilityIdentifier("pairGo")
        }
    }

    @ViewBuilder
    private var stageNote: some View {
        switch model.stage {
        case .idle:
            Text("لو طلع لك طلب «الشبكة المحلية» اختر «سماح». بدونها ما يوصل عمري للشاشة.")
                .font(Theme.Fonts.note)
                .foregroundStyle(Theme.ink2)
                .fixedSize(horizontal: false, vertical: true)
        case .working:
            HStack(spacing: Theme.Space.m) {
                ProgressView()
                Text("أكلم الشاشة…")
                    .font(Theme.Fonts.body)
                    .foregroundStyle(Theme.ink2)
            }
            .accessibilityIdentifier("pairWorking")
        case .failed(let text):
            Text(verbatim: text)
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.bad)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("pairError")
        case .done:
            EmptyView()
        }
    }

    private func doneCard(_ name: String) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Label {
                Text(verbatim: String(format: String(localized: "تم الربط مع %@"), name))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("pairDone")
            } icon: {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Theme.good)
            }
            .font(Theme.Fonts.title)
            .foregroundStyle(Theme.ink)
            Text("الحين ترسل أي مكان من عمري، أو من زر المشاركة في أي تطبيق: «إرسال للسيارة».")
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
                .fixedSize(horizontal: false, vertical: true)
            Button("تم") { dismiss() }
                .buttonStyle(MainKeyStyle())
                .accessibilityIdentifier("pairFinish")
        }
    }
}

@MainActor
final class PairModel: ObservableObject {
    enum Stage: Equatable {
        case idle, working
        case failed(String)
        case done(String)
    }

    @Published private(set) var stage: Stage = .idle
    /// A code that was just read is not read again while it is being tried.
    private var busy = false
    private var asked = false

    var isWorking: Bool { stage == .working }
    var isScanning: Bool { stage != .working }

    /// Browsing is what brings up the Local Network alert, in the foreground, where he can answer it.
    func askForLocalNetwork() {
        guard !asked, AppLaunch.fakeUnit == nil else { return }
        asked = true
        Task {
            let answer = await Bonjour.browse(timeout: 1.5)
            if answer.denied, stage == .idle {
                stage = .failed(String(localized: "الشبكة المحلية مقفلة على عمري. الإعدادات ← الخصوصية والأمان ← الشبكة المحلية، وفعّل عمري."))
            }
        }
    }

    func notOurs() {
        guard !busy else { return }
        stage = .failed(String(localized: "هذا الرمز مو حق شاشة السيارة"))
    }

    func pairTyped(_ text: String, link: CarLink) {
        guard let target = PairingTarget.parse(text) else {
            stage = .failed(String(localized: "الرمز ستة أرقام"))
            return
        }
        pair(target, link: link)
    }

    func pair(_ target: PairingTarget, link: CarLink) {
        guard !busy else { return }
        busy = true
        stage = .working
        Task {
            defer { busy = false }
            let address: UnitAddress
            if let known = target.address {
                address = known
            } else {
                let outcome = await link.locator.locate(expectedUnit: nil, last: link.store.lastAddress)
                guard let found = outcome.found else {
                    stage = .failed(outcome.localNetworkDenied
                        ? String(localized: "الشبكة المحلية مقفلة على عمري. الإعدادات ← الخصوصية والأمان ← الشبكة المحلية، وفعّل عمري.")
                        : String(localized: "ما لقيت الشاشة. شغّل الهوتسبوت، وتأكد إن الشاشة شبكت عليه."))
                    return
                }
                address = found.address
            }
            do {
                let reply = try await LinkClient(address: address).pair(code: target.code, device: UIDevice.current.name)
                link.didPair(Pairing(token: reply.token, unit: reply.unit, name: reply.name ?? "K2501", address: address))
                stage = .done(reply.name ?? "K2501")
            } catch let error as LinkError {
                switch error {
                case .unauthorized(let reason), .refused(_, let reason):
                    stage = .failed(Formats.reason(reason) ?? String(localized: "الشاشة ما قبلت الرمز"))
                case .localNetworkDenied:
                    stage = .failed(String(localized: "الشبكة المحلية مقفلة على عمري. الإعدادات ← الخصوصية والأمان ← الشبكة المحلية، وفعّل عمري."))
                default:
                    stage = .failed(String(localized: "ما ردت الشاشة. حاول مرة ثانية."))
                }
            } catch {
                stage = .failed(String(localized: "ما ردت الشاشة. حاول مرة ثانية."))
            }
        }
    }
}
