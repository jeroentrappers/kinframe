import KinframeKit
import SwiftUI

/// The photo sources of one album (under the album's header, for album managers) or one feed (in its settings, for
/// the owner and admins), with the add sheet and the edit sheet.
struct TargetPhotoSources: View {
    @Environment(AppModel.self) private var app
    let target: SourceTargetRef
    let targetName: String
    /// The album target (for the "Only in album mode" suggestion) and how to turn it on.
    var album: Album?
    var setAlbumOnly: (() async -> Void)?
    @Binding var adding: Bool
    /// The album or feed changed (photos may have been imported or removed).
    var changed: () -> Void = {}
    @State private var model: PhotoSourcesModel
    @State private var editing: MyPhotoSourcesView.EditRequest?

    init(api: API, target: SourceTargetRef, targetName: String, album: Album? = nil, adding: Binding<Bool>,
         setAlbumOnly: (() async -> Void)? = nil, changed: @escaping () -> Void = {}) {
        self.target = target
        self.targetName = targetName
        self.album = album
        self.setAlbumOnly = setAlbumOnly
        _adding = adding
        self.changed = changed
        _model = State(initialValue: PhotoSourcesModel(api: api, scope: .target(target)))
    }

    var body: some View {
        PhotoSourcesCard(model: model, canAdd: PhotoSourceFeatures(app.discovery).sources,
                         footer: target.isFeed ? L("ios.ps.feedFooter") : nil,
                         add: { adding = true }, edit: { editing = .init(source: $0, focus: $1) })
            .task { await model.load() }
            .sheet(isPresented: $adding) {
                PhotoSourceSheet(api: model.api, mode: .add(target, targetName: targetName), album: album, setAlbumOnly: setAlbumOnly) { s in
                    model.upsert(s)
                    app.toasts.show(L("ios.ps.connected", ["name": s.name]))
                    changed()
                }
            }
            .sheet(item: $editing) { r in
                PhotoSourceSheet(api: model.api, mode: .edit(r.source, r.focus)) { model.upsert($0) }
            }
            .onLiveEvent(app.events) { event in
                // A finished run may have added or removed photos here.
                if case .photoSourceUpdated(let s) = event, !s.running, model.sources.contains(where: { $0.id == s.id }) { changed() }
            }
    }
}
