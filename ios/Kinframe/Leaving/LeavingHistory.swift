import KinframeKit
import SwiftUI

/// In the sent history (only with discovery `leaving`): keep requests waiting for your answer, and the families and
/// frames you left (or were removed from) that still show your photos, with "Take my photos away from Janssens".
struct LeavingHistorySection: View {
    @Environment(AppModel.self) private var app
    let api: API
    @State private var requests: [KeepRequest] = []
    @State private var places: FormerPlaces?
    @State private var answering: KeepRequestRef?
    @State private var takingAway: TakeAwayTarget?

    struct KeepRequestRef: Identifiable { let id: String }

    struct TakeAwayTarget: Identifiable {
        let scope: LeavingScope
        let name: String
        let photos: Int
        var id: String { "\(scope)" }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if !requests.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(L("leaving.requestsTitle"))
                    ForEach(requests) { r in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(LeavingText.requestToMe(r)).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                            Text(L("leaving.toMe.until", ["date": LeavingText.day(r.expiresAt)])).font(.footnote).foregroundStyle(Palette.textMuted)
                            Button(L("common.review")) { answering = KeepRequestRef(id: r.id) }.buttonStyle(.kf(.secondary))
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .card(padding: 14)
                    }
                }
            }
            if let places, !(places.feeds.isEmpty && places.frames.isEmpty) {
                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(L("leaving.formerTitle"))
                    Text(L("leaving.former.body")).font(.footnote).foregroundStyle(Palette.textMuted)
                    ForEach(places.feeds, id: \.feedId) { f in
                        placeRow(name: f.name, photos: f.photos) {
                            takingAway = TakeAwayTarget(scope: .feed(f.feedId), name: f.name, photos: f.photos)
                        }
                    }
                    ForEach(places.frames, id: \.frameId) { f in
                        placeRow(name: f.name, photos: f.photos) {
                            takingAway = TakeAwayTarget(scope: .frame(f.frameId), name: f.name, photos: f.photos)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .card(padding: 14)
            }
        }
        .frame(maxWidth: PageWidth.text, alignment: .leading)
        .task { await load() }
        .onChange(of: app.historyVersion) { Task { await load() } }
        .onLiveEvent(app.events) { e in
            if case .leaving(.keepRequestUpdated) = e { Task { await load() } }
        }
        .sheet(item: $answering) { ref in
            KeepRequestAnswerSheet(api: api, requestId: ref.id) { Task { await load() } }
        }
        .sheet(item: $takingAway) { t in
            TakeAwaySheet(api: api, scope: t.scope, name: t.name, photos: t.photos) { Task { await load() } }
        }
    }

    private func placeRow(name: String, photos: Int, take: @escaping () -> Void) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.headline).foregroundStyle(Palette.text)
                Text(Lp("leaving.group.count", count: photos)).font(.footnote).foregroundStyle(Palette.textMuted)
            }
            Spacer()
            Button(L("leaving.takeAway.short"), role: .destructive, action: take).buttonStyle(.kf(.dangerGhost))
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(L("leaving.takeAwayFrom", ["name": name]))
    }

    private func load() async {
        if let list = try? await api.myKeepRequests() { requests = list.filter { $0.status == .open } }
        if let p = try? await api.formerPlaces() { places = p }
    }
}

/// Leaving's app-wide state: a keep request to answer (a sheet over the tabs, from a notification or a toast), and a
/// frame whose trash to show.
@MainActor
@Observable
final class LeavingState {
    var answering: LeavingHistorySection.KeepRequestRef?
    var trashFrameId: String?

    /// The library asks once whether to open straight on the trash (from a notification).
    func takeTrashFocus(_ frameId: String) -> Bool {
        guard trashFrameId == frameId else { return false }
        trashFrameId = nil
        return true
    }
}
