import KinframeKit
import SwiftUI

/// A frame's family-social settings (`settings.social`, README §5.20): *Show comments* (off by default), *Ask for
/// new photos*, *Prompt cards* and *New photos from…*. Part of the settings document, saved with the rest. The frame
/// apps that understand them report it in `features`; older ones get "update the frame app".
struct FrameSocialSettingsCard: View {
    @Environment(AppModel.self) private var app
    let model: FrameSettingsModel
    let frame: FrameSummary
    @State private var features: [String]?
    @State private var requests: [PhotoRequest] = []

    private var social: SocialFeatures { app.social.features }

    var body: some View {
        if social.social || social.engagement {
            VStack(alignment: .leading, spacing: 8) {
                SectionHeader(L("social.frame.title"))
                VStack(spacing: 0) {
                    if social.social {
                        toggle("social.show_comments", false, L("social.frame.showComments"), L("social.frame.showCommentsHint"), feature: "comments")
                    }
                    if social.engagement {
                        if social.social { Divider().overlay(Palette.border) }
                        toggle("social.ask_for_photos", true, L("social.frame.askForPhotos"), L("social.frame.askForPhotosHint"), feature: "photo_requests")
                        Divider().overlay(Palette.border)
                        toggle("social.prompt_cards", true, L("social.frame.promptCards"), L("social.frame.promptCardsHint"), feature: "prompts")
                        Divider().overlay(Palette.border)
                        toggle("social.new_photos_from", true, L("social.frame.newPhotosFrom"), L("social.frame.newPhotosFromHint"), feature: nil)
                    }
                }
                .card(padding: 0)
                if social.engagement, !requests.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("engagement.request.history")).font(.subheadline.weight(.semibold))
                        ForEach(requests.prefix(5)) { r in
                            HStack {
                                Text(Format.relative(r.createdAt, locale: L10n.locale)).font(.footnote).foregroundStyle(Palette.textMuted)
                                Spacer()
                                Text(r.state == .answered ? (r.answeredBy.map { L("engagement.request.answeredBy", ["name": PersonName.first($0)]) } ?? L("engagement.request.answeredShort"))
                                     : r.state == .open ? L("engagement.request.openShort") : L("engagement.request.expired"))
                                    .font(.footnote)
                            }
                        }
                    }
                    .card(padding: 12)
                }
            }
            .task(id: frame.id) {
                guard let api = app.api else { return }
                let id = frame.id
                features = (try? await api.call { try await $0.getFrameHealth(path: .init(frameId: id)).ok.body.json })?.lastHeartbeat?.features
                if social.engagement { requests = (try? await api.framePhotoRequests(frame.id)) ?? [] }
            }
        }
    }

    private func toggle(_ path: String, _ fallback: Bool, _ title: String, _ hint: String, feature: String?) -> some View {
        Toggle(isOn: model.bool(path, fallback)) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
                if let feature, let features, !features.contains(feature) {
                    Text(L("social.frame.updateApp")).font(.footnote).foregroundStyle(Palette.warn)
                }
            }
        }
        .tint(Palette.accent)
        .padding(14)
    }
}
