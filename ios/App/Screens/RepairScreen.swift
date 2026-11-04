import AMRIKit
import SwiftUI

/// Taking the screen back to its previous version from the phone, with no PC. It says what it does before it does it, and asks
/// twice: once by a button after reading, and once by typing a word, because this replaces the app on the car.
struct RepairScreen: View {
    /// Pushed from the car's screen it has its back button, and needs no second way out.
    var showsClose = true
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
                case .running:
                    running
                case .watching(_, let text):
                    watching(text)
                case .finished(_, let result, let report):
                    finished(result, report)
                }
            }
            .padding(Theme.Space.l)
        }
        .background(Theme.ground)
        .navigationTitle("إصلاح الشاشة")
        .navigationBarTitleDisplayMode(.inline)
        // While the script runs he stays and reads how it goes.
        .navigationBarBackButtonHidden(model.isWorking)
        .interactiveDismissDisabled(model.isWorking)
        .toolbar {
            if showsClose, !model.isWorking {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إغلاق") { dismiss() }
                        .accessibilityIdentifier("repairClose")
                }
            }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            title("وش يسوي هذا؟")
            para("لو نسخة 2.0 تعطلت على الشاشة، هذا يرجعها للنسخة اللي قبلها (1.19) من الجوال، بدون كمبيوتر.")
            para("يشغّل على الشاشة أمر واحد بس: سكربت الرجوع اللي جهزته عمري على الشاشة لنفسها. يركّب النسخة السابقة المحفوظة مكان الحالية، وتبقى إعداداتك. الشاشة بتعيد فتح الواجهة.")
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
            bullet("بيرجع التطبيق على الشاشة للنسخة السابقة المحفوظة فيها (1.19)، وتروح مزايا النسخة الحالية. إعداداتك تبقى.")
            bullet("الشاشة بتقفل وتفتح الواجهة من جديد. سوّها والسيارة واقفة، مو وأنت تسوق.")
            bullet("لو فشل التركيب، الشاشة ترجع للواجهة الأصلية حقت الجهاز، وعمري يتوقف لين تصلحه من الكمبيوتر.")
            bullet("لو ما كان السكربت موجود على الشاشة (نسخة أقدم من 1.19.1) ما يصير شيء، وأقولك.")
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
                Text("رجّع الشاشة للنسخة السابقة")
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

    private var running: some View {
        HStack(spacing: Theme.Space.m) {
            ProgressView()
            Text("أشغّل السكربت على الشاشة…")
                .font(Theme.Fonts.body)
                .foregroundStyle(Theme.ink2)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("repairRunning")
    }

    /// rollback.sh answers at once and works on by itself: what it writes is all that can be said about how it goes.
    private func watching(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            HStack(spacing: Theme.Space.m) {
                ProgressView()
                Text("الشاشة تركّب النسخة السابقة…")
                    .font(Theme.Fonts.headline)
                    .foregroundStyle(Theme.ink)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("repairRunning")
            para("خل السيارة شغالة، وخل هذي الصفحة مفتوحة. ممكن تاخذ دقايق.")
            outputBox(text)
        }
    }

    private func finished(_ result: AdbRunResult, _ report: Repair.Report?) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            if !result.ran {
                title("ما اشتغل")
                para("ما تغيّر شيء على الشاشة.")
                if let problem = result.problem {
                    Text(verbatim: problem)
                        .font(Theme.Fonts.mono)
                        .foregroundStyle(Theme.ink2)
                }
            } else if let report {
                switch report.state {
                case .succeeded:
                    title("رجعت الشاشة للنسخة السابقة")
                    if let version = report.toVersion {
                        Text(verbatim: version)
                            .font(Theme.Fonts.headline)
                            .foregroundStyle(Theme.ink)
                            .accessibilityIdentifier("repairVersion")
                    }
                    para("المفروض تشوف واجهة عمري على الشاشة الحين.")
                case .failed:
                    title("ما نجح الرجوع")
                    para("الشاشة رجعت للواجهة الأصلية حقت الجهاز، وعمري متوقف. أرسل اللي تحت، وصلحها من الكمبيوتر.")
                case .running:
                    title("بدأ الرجوع، وما وصلني آخره")
                    para("شوف الشاشة نفسها. هذا آخر شيء قدرت أقراه منها.")
                }
            } else {
                switch Repair.start(from: result.output) {
                case .noScript:
                    title("السكربت مو موجود على الشاشة")
                    para("هذي الشاشة ما عليها نسخة جهزت طريق الرجوع، فما صار شيء. لو تحتاج ترجع، استخدم الكمبيوتر.")
                case .refused:
                    title("ما اشتغل")
                    para("ما تغيّر شيء على الشاشة. هذا اللي ردت به.")
                case .running, .unclear:
                    title("ما أعرف وش صار")
                    para("هذا اللي ردت به الشاشة. شوف الواجهة نفسها.")
                }
            }
            if !result.output.isEmpty || report != nil {
                outputBox(report == nil ? result.output : model.lastText)
            }
            Button("تم") { dismiss() }
                .buttonStyle(MainKeyStyle())
                .accessibilityIdentifier("repairDone")
        }
    }

    private func outputBox(_ text: String) -> some View {
        Text(verbatim: text.isEmpty ? "…" : text)
            .font(Theme.Fonts.mono)
            .foregroundStyle(Theme.ink)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Theme.Space.m)
            .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            .environment(\.layoutDirection, .leftToRight)
            .accessibilityIdentifier("repairOutput")
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
        /// The script runs by itself on the unit; this is what its result file says so far.
        case watching(Repair.Host, String)
        /// `report` is nil when it never got as far as running.
        case finished(Repair.Host, AdbRunResult, Repair.Report?)
    }

    @Published var stage: Stage = .idle
    @Published var typed = ""
    /// The result file, as last read.
    @Published private(set) var lastText = ""
    private var lastScan: Repair.Scan?

    /// The word, as typed: spaces and the kind of letter do not matter, the word does.
    static let word = "رجوع"

    var wordIsRight: Bool { typed.trimmingCharacters(in: .whitespacesAndNewlines) == RepairModel.word }

    var isBusy: Bool {
        if case .scanning = stage { return true }
        return false
    }

    /// The script is running on the unit.
    var isWorking: Bool {
        switch stage {
        case .running, .watching: return true
        default: return false
        }
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
        lastText = ""
        Task {
            let result = await Repair.rollback(on: host)
            // The line dropping as it went means it did reach the shell, so the file is where to look.
            guard result.ran, result.connectionEnded || Repair.start(from: result.output) == .running else {
                stage = .finished(host, result, nil)
                return
            }
            stage = .watching(host, "")
            let watched = await Repair.watchResult(on: host) { [weak self] text in
                Task { @MainActor in
                    guard let self else { return }
                    self.lastText = text
                    if case .watching = self.stage { self.stage = .watching(host, text) }
                }
            }
            lastText = watched.text
            stage = .finished(host, result, watched.report)
        }
    }
}
