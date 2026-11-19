import KinframeKit
import SwiftUI

/// Under a chosen family in the composer: "Share with: whole family / The cousins" (a circle you're in) and "Answer
/// this week's prompt" (README §5.18, §5.20). Hidden while the server has neither.
struct ComposeAudience: View {
    @Environment(AppModel.self) private var app
    let feed: FeedSummary
    let model: ComposeModel
    @State private var circles: [FamilyCircle] = []

    private var prompt: FeedPrompt? { app.social.prompts[feed.id] }

    var body: some View {
        let audience = CircleLogic.audience(circles)
        VStack(alignment: .leading, spacing: 8) {
            if !audience.isEmpty {
                HStack {
                    Text(L("social.compose.shareWith")).font(.subheadline).foregroundStyle(Palette.textMuted)
                    Spacer()
                    Picker(L("social.compose.shareWith"), selection: Binding(get: { model.circleByFeed[feed.id] ?? "" }, set: { id in
                        model.circleByFeed[feed.id] = id.isEmpty ? nil : id
                    })) {
                        Text(L("social.compose.wholeFamily")).tag("")
                        ForEach(audience) { c in Text(c.name).tag(c.id) }
                    }
                    .labelsHidden()
                }
                if let id = model.circleByFeed[feed.id], let c = audience.first(where: { $0.id == id }) {
                    Text(L("social.compose.circleHint", ["circle": c.name, "frames": ListText.join((c.framesReached ?? []).map(\.frameName))]))
                        .font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            if let prompt {
                Toggle(isOn: Binding(get: { model.promptId == prompt.id }, set: { on in model.promptId = on ? prompt.id : nil })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("social.compose.answerPrompt"))
                        Text(prompt.title).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                }
                .tint(Palette.accent)
            }
        }
        .padding(.horizontal, 14)
        .padding(.bottom, audienceVisible ? 10 : 0)
        .task(id: feed.id) {
            if app.social.features.social { circles = await app.social.circles(of: feed.id) }
        }
    }

    private var audienceVisible: Bool { !CircleLogic.audience(circles).isEmpty || prompt != nil }
}
