import KinframeKit
import SwiftUI

/// Settings → Photo sources: the sources you set up, across albums and feeds (`GET /v1/photo-sources`), with the
/// same status and actions as on the album or feed.
struct MyPhotoSourcesView: View {
    @Environment(AppModel.self) private var app
    @State private var model: PhotoSourcesModel
    @State private var editing: EditRequest?

    struct EditRequest: Identifiable {
        let source: PhotoSource
        let focus: PhotoSourceSheet.Focus
        var id: String { source.id }
    }

    init(api: API) {
        _model = State(initialValue: PhotoSourcesModel(api: api, scope: .mine))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                PhotoSourcesCard(model: model, canAdd: false, showTarget: true, title: L("ios.ps.mine"), footer: L("ios.ps.mineHint"),
                                 add: {}, edit: { editing = EditRequest(source: $0, focus: $1) })
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await model.load() }
        .pageBackground()
        .pageTitle(L("ios.ps.mine"))
        .task { await model.load() }
        .sheet(item: $editing) { r in
            PhotoSourceSheet(api: model.api, mode: .edit(r.source, r.focus)) { model.upsert($0) }
        }
    }
}
