import KinframeKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Share → kinframe" from Photos and other apps. The composer lives in KinframeKit (ShareComposer.swift).
final class ShareViewController: UIViewController {
    private var model: ShareModel!

    override func viewDidLoad() {
        super.viewDidLoad()
        L10n.bootstrap()
        model = ShareModel(context: extensionContext)
        let host = UIHostingController(rootView: ShareRootView(model: model))
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)
        Task { await model.start() }
    }
}

