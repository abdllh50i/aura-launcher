import AMRIKit
import SwiftUI

/// Taking the screen back to 1.19 from the phone, with no PC. It says what it does before it does it, and asks twice: once by
/// a button after reading, and once by typing a word, because this replaces the app on the car.
struct RepairScreen: View {
    @EnvironmentObject private var link: CarLink
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = RepairModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                switch model.stage {
                case .idle, .scanning:
                    intro
                case .scanned(let scan):
                    results(scan)
                case .confirmFirst(let host):
                    confirmFirst(host)
                case .confirmSecond(let host):
                    confirmSecond(host)
                case .running(let host):
                    running(host)
                case .finished(let host, let result):
                    finished(host, result)
                }
            }
            .padding(Theme.Space.l)
        }
        .background(Theme.ground)
        .navigationTitle("إصلاح الشاشة")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("إغلاق") { dismiss() }
                    .accessibilityIdentifier("repairClose")
            }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            title("وش يسوي هذا؟")
            para("لو نسخة 2.0 تعطلت على الشاشة، هذا يرجعها لنسخة 1.19 من الجوال، بدون كمبيوتر.")
            para("يشغّل على الشاشة أمر واحد بس: السكربت اللي جهزته نسخة 2.0 لنفسها وقت أول تشغيل. يركّب 1.19 مكانها، وتبقى إعداداتك. الشاشة بتعيد فتح الواجهة.")
            para("الحين بس أدور على الشاشة، بدون ما ألمس شيء. لازم تكون شابكة على هوتسبوت جوالك.")
            Text(verbatim: Repair.rollbackCommand)
                .font(Theme.Fonts.mono)
                .foregroundStyle(Theme.ink2)
                .environment(\.layoutDirection, .leftToRight)
                .accessibilityIdentifier("repairCommand")
            Button {
                model.scan()
            } label: {
                if case .scanning = model.stage {
                    HStack(spacing: Theme.Space.s) {
                        ProgressView()
                        Text("أدور على الشاشة…")
                    }
                } else {
                    Text("دوّر على الشاشة")
                }
            }
            .buttonStyle(MainKeyStyle())
            .disabled(model.isBusy)
            .accessibilityIdentifier("repairScan")
        }
    }

    private func results(_ scan: Repair.Scan) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            if scan.units.isEmpty {
                title("ما لقيت شاشة K2501")
                para(scan.deniedSeen ? "الشبكة المحلية مقفلة على عمري. الإعدادات ← الخصوصية والأمان ← الشبكة المحلية، وفعّل عمري." : "تأكد إن الهوتسبوت شغّال والشاشة شابكة عليه. بعد تشغيل بارد ممكن تحتاج شوي وقت.")
            } else {
                title("لقيت الشاشة")
                ForEach(scan.units) { host in
                    Button {
                        model.choose(host)
                    } label: {
                        HStack(spacing: Theme.Space.m) {
                            Image(systemName: "car.fill")
                                .foregroundStyle(Theme.ink)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: "K2501 · \(host.host)")
                                    .font(Theme.Fonts.headline)
                                    .foregroundStyle(Theme.ink)
                                if let banner = host.banner {
                                    Text(verbatim: banner.summary)
                                        .font(Theme.Fonts.small)
                                        .foregroundStyle(Theme.ink2)
                                        .lineLimit(1)
                                }
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.forward")
                                .foregroundStyle(Theme.ink3)
                        }
                        .cardSurface()
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("repairUnit-\(host.port)")
                }
            }
            let others = scan.hosts.filter { $0.kind != .k2501 }
            if !others.isEmpty {
                Text("أجهزة ثانية ما لمستها:")
                    .font(Theme.Fonts.note)
                    .foregroundStyle(Theme.ink2)
                ForEach(others) { host in
                    Text(verbatim: "\(host.host): \(host.banner?.summary ?? "ADB")")
                        .font(Theme.Fonts.small)
                        .foregroundStyle(Theme.ink3)
                        .accessibilityIdentifier("repairOther")
                }
            }
            Button("دوّر مرة ثانية") { model.scan() }
                .buttonStyle(SecondKeyStyle())
                .accessibilityIdentifier("repairRescan")
        }
    }

    private func confirmFirst(_ host: Repair.Host) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            title("اقرأ قبل ما تكمل")
            bullet("بيرجع التطبيق على الشاشة لنسخة 1.19، وتروح كل مزايا 2.0.")
            bullet("الشاشة بتقفل وتفتح الواجهة من جديد. سوّها والسيارة واقفة، مو وأنت تسوق.")
            bullet("لو ما كان السكربت موجود على الشاشة (نسخة قبل 2.0) ما يصير شيء، وأقولك.")
            Text(verbatim: "K2501 · \(host.host)")
                .font(Theme.Fonts.headline)
                .foregroundStyle(Theme.ink)
            Button("فهمت، أكمل") { model.confirmOnce(host) }
                .buttonStyle(MainKeyStyle())
                .accessibilityIdentifier("repairUnderstood")
            Button("لا، رجوع") { model.back() }
                .buttonStyle(SecondKeyStyle())
                .accessibilityIdentifier("repairBack")
        }
    }

    private func confirmSecond(_ host: Repair.Host) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            title("آخر خطوة")
            para("عشان ما يصير بالغلط: اكتب كلمة «رجوع» هنا.")
            TextField("رجوع", text: $model.typed)
                .font(Theme.Fonts.title)
                .multilineTextAlignment(.center)
                .autocorrectionDisabled()
                .frame(minHeight: Theme.Size.target)
                .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field, style: .continuous))
                .accessibilityIdentifier("repairWord")
            Button {
                model.runRollback(on: host)
            } label: {
                Text("رجّع الشاشة لنسخة 1.19")
            }
            .buttonStyle(MainKeyStyle(fill: Theme.bad, text: .white))
            .disabled(!model.wordIsRight)
            .opacity(model.wordIsRight ? 1 : 0.4)
            .accessibilityIdentifier("repairGo")
            Button("لا، رجوع") { model.back() }
                .buttonStyle(SecondKeyStyle())
                .accessibilityIdentifier("repairBack2")
        }
    }

    private func running(_ host: Repair.Host) -> some View {
        HStack(spacing: Theme.Space.m) {
            ProgressView()
            Text("أشغّل السكربت على الشاشة…")
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
        }
        .accessibilityIdentifier("repairRunning")
    }

    private func finished(_ host: Repair.Host, _ result: AdbRunResult) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            if !result.ran {
                title("ما اشتغل")
                para("ما تغيّر شيء على الشاشة.")
                if let problem = result.problem {
                    Text(verbatim: problem)
                        .font(Theme.Fonts.mono)
                        .foregroundStyle(Theme.ink2)
                }
            } else if Repair.scriptMissing(in: result.output) {
                title("السكربت مو موجود على الشاشة")
                para("هذي الشاشة ما عليها نسخة جهزت طريق الرجوع، فما صار شيء. لو تحتاج ترجع، استخدم الكمبيوتر.")
            } else if result.connectionEnded {
                title("بدأ السكربت، وانقطع الاتصال")
                para("هذا طبيعي لأن التطبيق يتبدل. انتظر دقيقة وشوف الشاشة.")
            } else {
                title("اشتغل السكربت")
                para("هذا اللي ردت به الشاشة. انتظر دقيقة وشوف الواجهة.")
            }
            if !result.output.isEmpty {
                Text(verbatim: result.output)
                    .font(Theme.Fonts.mono)
                    .foregroundStyle(Theme.ink)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(Theme.Space.m)
                    .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
                    .environment(\.layoutDirection, .leftToRight)
                    .accessibilityIdentifier("repairOutput")
            }
            Button("تم") { dismiss() }
                .buttonStyle(MainKeyStyle())
                .accessibilityIdentifier("repairDone")
        }
    }

    private func title(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(Theme.Fonts.title)
            .foregroundStyle(Theme.ink)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("repairTitle")
    }

    private func para(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(Theme.Fonts.body)
            .foregroundStyle(Theme.ink2)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func bullet(_ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
            Text(verbatim: "•")
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(Theme.Fonts.body)
        .foregroundStyle(Theme.ink)
    }
}

@MainActor
final class RepairModel: ObservableObject {
    enum Stage: Equatable {
        case idle
        case scanning
        case scanned(Repair.Scan)
        case confirmFirst(Repair.Host)
        case confirmSecond(Repair.Host)
        case running(Repair.Host)
        case finished(Repair.Host, AdbRunResult)
    }

    @Published var stage: Stage = .idle
    @Published var typed = ""
    private var lastScan: Repair.Scan?

    /// The word, as typed: spaces and the kind of letter do not matter, the word does.
    static let word = "رجوع"

    var wordIsRight: Bool { typed.trimmingCharacters(in: .whitespacesAndNewlines) == RepairModel.word }

    var isBusy: Bool {
        if case .scanning = stage { return true }
        return false
    }

    /// Hello only: reads each banner and stops. Anything that is not a K2501 is left alone.
    func scan() {
        stage = .scanning
        typed = ""
        let targets = Repair.targets(extra: AppLaunch.fakeAdb)
        Task {
            let found = await Repair.scan(targets: targets)
            lastScan = found
            stage = .scanned(found)
        }
    }

    func choose(_ host: Repair.Host) {
        guard host.kind == .k2501 else { return }
        stage = .confirmFirst(host)
    }

    func confirmOnce(_ host: Repair.Host) {
        typed = ""
        stage = .confirmSecond(host)
    }

    func back() {
        typed = ""
        if let lastScan {
            stage = .scanned(lastScan)
        } else {
            stage = .idle
        }
    }

    func runRollback(on host: Repair.Host) {
        guard wordIsRight, host.kind == .k2501 else { return }
        stage = .running(host)
        Task {
            let result = await Repair.rollback(on: host)
            stage = .finished(host, result)
        }
    }
}
