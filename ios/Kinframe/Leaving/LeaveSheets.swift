import KinframeKit
import SwiftUI

/// Leaving a family (docs/spec/leaving.md story 1): your photos stay (default) or go with you, your comments stay or
/// are deleted, and "Leave this frame too" for the frames that get this family's photos. Numbers from
/// `getLeavePreview`; the result line from `TakeAwayResult`.
struct LeaveFeedSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let feedId: String
    let name: String
    /// After leaving: the result line (nil when nothing was taken away) and the frames left too.
    let onLeft: (String?, [String]) -> Void
    @State private var preview: LeavePreview?
    @State private var error: String?
    @State private var choices = LeaveChoices()
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                if let preview {
                    photosSection(count: preview.photos)
                    if (preview.comments ?? 0) > 0 {
                        Section {
                            Picker(Lp("leaving.comments.title", count: preview.comments ?? 0), selection: $choices.comments) {
                                Text(L("leaving.comments.keep")).tag(CommentsChoice.keep)
                                Text(L("leaving.comments.delete")).tag(CommentsChoice.delete)
                            }
                            .pickerStyle(.inline)
                            .labelsHidden()
                        } header: {
                            Text(Lp("leaving.comments.title", count: preview.comments ?? 0))
                        }
                        .listRowBackground(Palette.surface)
                    }
                    let frames = LeaveChoices.leavableFrames(preview)
                    if !frames.isEmpty {
                        Section {
                            ForEach(frames, id: \.frameId) { f in
                                Toggle(isOn: Binding(get: { choices.leaveFrameIds.contains(f.frameId) },
                                                     set: { on in if on { choices.leaveFrameIds.insert(f.frameId) } else { choices.leaveFrameIds.remove(f.frameId) } })) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(f.frameName)
                                        Text(L("leaving.frames.leaveToo")).font(.footnote).foregroundStyle(Palette.textMuted)
                                    }
                                }
                                .tint(Palette.accent)
                            }
                        } header: {
                            Text(L("leaving.frames.title"))
                        }
                        .listRowBackground(Palette.surface)
                    }
                    Section {
                        Button(role: .destructive) { Task { await leave() } } label: {
                            HStack {
                                Text(L("leaving.leaveFeed", ["name": name]))
                                if busy { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(busy)
                    } footer: {
                        Text(L("leaving.laterHint"))
                    }
                    .listRowBackground(Palette.surface)
                } else if let error {
                    Section { ErrorRetryView(message: error) { Task { await load() } } }
                } else {
                    Section { LoadingView() }
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("leaving.leaveFeedTitle", ["name": name]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() }.disabled(busy) }
            }
            .task { await load() }
        }
        .interactiveDismissDisabled(busy)
    }

    @ViewBuilder private func photosSection(count: Int) -> some View {
        LeavePhotosSection(count: count, photos: $choices.photos)
    }

    private func load() async {
        error = nil
        do {
            let p = try await api.leavePreview(.feed(feedId))
            preview = p
            choices = LeaveChoices(preview: p)
        } catch let e as APIError where e.status == 404 {
            // Nothing of yours in the family: leave without questions.
            preview = LeavePreview(scope: .feed, name: name, member: true, photos: 0)
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func leave() async {
        guard let preview else { return }
        busy = true
        defer { busy = false }
        do {
            let result = try await api.leaveFeed(feedId, photos: choices.photos, comments: choices.comments,
                                                 leaveFrameIds: choices.frameIds(for: preview))
            dismiss()
            onLeft(result.flatMap { LeavingText.result($0) }, result?.leftFrameIds ?? [])
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Leaving a frame directly (story 6): the same photo question for the photos you sent to it.
struct LeaveFrameSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let frame: FrameSummary
    let onLeft: (String?) -> Void
    @State private var photosCount: Int?
    @State private var photos: PhotosChoice = .keep
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                LeavePhotosSection(count: photosCount, photos: $photos)
                Section {
                    Button(role: .destructive) { Task { await leave() } } label: {
                        HStack {
                            Text(L("overview.leaveButton"))
                            if busy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(busy)
                } footer: {
                    Text(L("leaving.laterHint"))
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("overview.leaveConfirm", ["name": frame.name]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() }.disabled(busy) }
            }
            .task { photosCount = (try? await api.leavePreview(.frame(frame.id)))?.photos ?? 0 }
        }
        .interactiveDismissDisabled(busy)
    }

    private func leave() async {
        busy = true
        defer { busy = false }
        do {
            let result = try await api.leaveFrame(frame.id, photos: photos)
            dismiss()
            onLeft(result.flatMap { LeavingText.result($0) })
        } catch {
            app.toasts.error(error)
        }
    }
}

/// "Your photos (214)": leave them (default) or take them with you, with what each means.
struct LeavePhotosSection: View {
    let count: Int?
    @Binding var photos: PhotosChoice

    var body: some View {
        Section {
            Picker(L("leaving.photos.choice"), selection: $photos) {
                Text(L("leaving.photos.keep")).tag(PhotosChoice.keep)
                Text(L("leaving.photos.take")).tag(PhotosChoice.take)
            }
            .pickerStyle(.inline)
            .labelsHidden()
        } header: {
            if let count { Text(Lp("leaving.photos.title", count: count)) } else { Text(L("leaving.photos.choice")) }
        } footer: {
            Text(photos == .keep ? L("leaving.photos.keepHint") : L("leaving.photos.takeHint"))
        }
        .listRowBackground(Palette.surface)
    }
}

/// "Take my photos away from Janssens" for a family or frame you left or were removed from (or still belong to).
struct TakeAwaySheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let scope: LeavingScope
    let name: String
    let photos: Int
    let onDone: () -> Void
    @State private var comments: CommentsChoice = .keep
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(Lp(scope.feedId != nil ? "leaving.takeAway.bodyFeed" : "leaving.takeAway.bodyFrame", count: photos, ["name": name]))
                } footer: {
                    Text(L("leaving.photos.takeHint"))
                }
                .listRowBackground(Palette.surface)
                if scope.feedId != nil {
                    Section {
                        Toggle(L("leaving.takeAway.deleteComments"), isOn: Binding(get: { comments == .delete }, set: { comments = $0 ? .delete : .keep }))
                            .tint(Palette.danger)
                    }
                    .listRowBackground(Palette.surface)
                }
                Section {
                    Button(role: .destructive) { Task { await takeAway() } } label: {
                        HStack {
                            Text(L("leaving.takeAway.button", ["name": name]))
                            if busy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(busy)
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("leaving.takeAway.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() }.disabled(busy) }
            }
        }
        .interactiveDismissDisabled(busy)
    }

    private func takeAway() async {
        busy = true
        defer { busy = false }
        do {
            let result = try await api.takeAway(scope, comments: scope.feedId != nil ? comments : nil)
            dismiss()
            app.toasts.show(LeavingText.result(result) ?? L("leaving.takeAway.nothing"))
            onDone()
        } catch {
            app.toasts.error(error)
        }
    }
}
