import AMRIKit
import SwiftUI
import UIKit

/// The extension's door. All the work is in `ShareFlowModel`; the sheet stays on screen until the real result is shown.
final class ShareViewController: UIViewController {
    private let model = ShareFlowModel(locator: ShareViewController.locator())

    private static func locator() -> UnitLocator {
        #if DEBUG && targetEnvironment(simulator)
        // The real share sheet's test: an extension has no launch arguments, so the stand-in's address waits in a file.
        if let text = try? String(contentsOfFile: "/tmp/amri-fake-unit", encoding: .utf8) {
            let parts = text.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":")
            if parts.count == 2, let port = UInt16(parts[1]) {
                return UnitLocator(sweepEnabled: false, extraTargets: [SweepTarget(host: String(parts[0]), port: port)])
            }
        }
        #endif
        return UnitLocator()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(Theme.ground)
        let host = UIHostingController(rootView: ShareFlowView(model: model) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        })
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        host.didMove(toParent: self)

        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        Task { [model] in
            let payload = await ShareInput.collect(from: items)
            model.start(payload)
        }
    }
}
