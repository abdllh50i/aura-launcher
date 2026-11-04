import AMRIKit
import SwiftUI
import UIKit

/// The extension's door. All the work is in `ShareFlowModel`; the sheet stays on screen until the real result is shown.
final class ShareViewController: UIViewController {
    private let model = ShareFlowModel()

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
