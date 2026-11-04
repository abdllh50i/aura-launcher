import AMRIKit
import SwiftUI
import UIKit

/// فحص الربط: one button, one text report to copy. It doubles as the probe of section 8 P0, so it must finish and say
/// something useful even when no unit is reachable.
struct CheckScreen: View {
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var location: LocationModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = CheckModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.m) {
                if model.report == nil, !model.running {
                    Text("يفحص التوقيع، الشبكة، السيارة، آخر مشاركة وصلت، وبحث الخرائط. ما يغيّر شيء في السيارة. يطلع لك نص تنسخه وترسله.")
                        .font(Theme.Fonts.body)
                        .foregroundStyle(Theme.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button {
                    model.run(link: link, location: location)
                } label: {
                    Text(model.report == nil ? "افحص الآن" : "افحص مرة ثانية")
                }
                .buttonStyle(MainKeyStyle())
                .disabled(model.running)
                .opacity(model.running ? 0.5 : 1)
                .accessibilityIdentifier("checkRun")
                if model.running {
                    HStack(spacing: Theme.Space.m) {
                        ProgressView()
                        Text(verbatim: model.step)
                            .font(Theme.Fonts.body)
                            .foregroundStyle(Theme.ink2)
                    }
                    .accessibilityIdentifier("checkStep")
                }
                if let report = model.report {
                    Button {
                        model.copy()
                    } label: {
                        Label(model.copied ? "تم النسخ" : "انسخ التقرير", systemImage: model.copied ? "checkmark" : "doc.on.doc")
                    }
                    .buttonStyle(SecondKeyStyle())
                    .accessibilityIdentifier("checkCopy")
                    ShareLink(item: report) {
                        Label("أرسل التقرير", systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(SecondKeyStyle())
                    .accessibilityIdentifier("checkShare")
                    Text(verbatim: report)
                        .font(Theme.Fonts.mono)
                        .foregroundStyle(Theme.ink)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(Theme.Space.m)
                        .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
                        .accessibilityIdentifier("checkReport")
                }
            }
            .padding(Theme.Space.l)
        }
        .background(Theme.ground)
        .navigationTitle("فحص الربط")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("إغلاق") { dismiss() }
                    .accessibilityIdentifier("checkClose")
            }
        }
    }
}

@MainActor
final class CheckModel: ObservableObject {
    @Published private(set) var running = false
    @Published private(set) var step = ""
    @Published private(set) var report: String?
    @Published private(set) var copied = false

    func run(link: CarLink, location: LocationModel) {
        guard !running else { return }
        running = true
        copied = false
        step = ""
        var note: String?
        if location.isDenied {
            note = "الموقع مرفوض لعمري"
        } else if location.near == nil {
            note = "الجوال ما عطى موقع بعد"
        }
        let input = LinkCheck.Input(appVersion: AppLaunch.version, systemVersion: UIDevice.current.systemVersion, store: link.store,
                                    near: location.near, locationNote: note, locator: link.locator, adbExtra: AppLaunch.fakeAdb,
                                    runMapKit: !AppLaunch.skipMapKitCheck)
        Task {
            let facts = await LinkCheck.run(input) { [weak self] text in
                Task { @MainActor in self?.step = text }
            }
            report = LinkReport.render(facts)
            running = false
        }
    }

    func copy() {
        UIPasteboard.general.string = report
        copied = true
    }

    #if DEBUG
    func setDemo(report: String) {
        self.report = report
    }
    #endif
}
