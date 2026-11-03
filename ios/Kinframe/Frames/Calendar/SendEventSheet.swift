import KinframeKit
import PhotosUI
import SwiftUI

/// Send an event to a frame: it pops up there and they choose whether to add it (web: SendEventDialog.svelte). The
/// optional photo is prepared like every upload before it goes up.
struct SendEventSheet: View {
    @Environment(\.dismiss) private var dismiss
    let api: API
    let frame: FrameSummary
    let senderName: String
    let onSent: (EventInvitation) -> Void

    @State private var form = CalendarLogic.EventForm()
    @State private var photoItem: PhotosPickerItem?
    @State private var photo: UIImage?
    @State private var photoFile: URL?
    @State private var placement: Components.Schemas.EventPhotoPlacement = .top
    @State private var tried = false
    @State private var sending = false
    @State private var progress = 0.0
    @State private var error: String?

    init(api: API, frame: FrameSummary, senderName: String, onSent: @escaping (EventInvitation) -> Void) {
        self.api = api
        self.frame = frame
        self.senderName = senderName
        self.onSent = onSent
        var f = CalendarLogic.EventForm()
        let d = CalendarLogic.defaultEventTimes()
        f.start = d.start
        f.end = d.end
        f.startDate = d.date
        f.endDate = d.date
        _form = State(initialValue: f)
    }

    private var built: Result<Components.Schemas.EventInvitationCreate, CalendarLogic.FormErrorBox> { CalendarLogic.invitation(form) }
    private var formError: CalendarLogic.FormError? { if case .failure(let e) = built { e.error } else { nil } }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        TextField(L("cal.evTitlePlaceholder"), text: $form.title, axis: .vertical)
                            .lineLimit(1...3)
                            .onChange(of: form.title) { if form.title.count > 200 { form.title = String(form.title.prefix(200)) } }
                        if tried, formError == .title { Text(L("cal.evErr.title")).font(.footnote).foregroundStyle(Palette.danger) }
                    } header: { Text(L("cal.evTitle")) }

                    Section {
                        Toggle(L("cal.evAllDay"), isOn: $form.allDay).tint(Palette.accent)
                        if form.allDay {
                            DatePicker(L("cal.evStartDate"), selection: dateBinding(\.startDate), displayedComponents: .date)
                            DatePicker(L("cal.evEndDate"), selection: dateBinding(\.endDate), in: CalendarLogic.pickerDate(form.startDate)..., displayedComponents: .date)
                        } else {
                            DatePicker(L("cal.evStart"), selection: startBinding)
                            DatePicker(L("cal.evEnd"), selection: $form.end, in: form.start...)
                        }
                        if tried, let e = formError, e != .title { Text(L("cal.evErr.\(e.rawValue)")).font(.footnote).foregroundStyle(Palette.danger) }
                    } footer: {
                        if !form.allDay { Text(L("cal.evTimesLocal")) }
                    }

                    Section {
                        TextField(L("cal.evLocation"), text: $form.location)
                        TextField(L("cal.evNotes"), text: $form.notes, axis: .vertical).lineLimit(2...5)
                    } header: { Text("\(L("cal.evLocation")) · \(L("cal.evNotes")) (\(L("common.optional")))") }

                    Section {
                        if photo != nil {
                            Picker(L("cal.evPlacement"), selection: $placement) {
                                Text(L("cal.evPlaceTop")).tag(Components.Schemas.EventPhotoPlacement.top)
                                Text(L("cal.evPlaceBackground")).tag(Components.Schemas.EventPhotoPlacement.background)
                            }
                            .pickerStyle(.segmented)
                            Button(role: .destructive) { setPhoto(nil, file: nil); photoItem = nil } label: { Label(L("cal.evRemovePhoto"), systemImage: "trash") }
                        } else {
                            PhotosPicker(selection: $photoItem, matching: .images, preferredItemEncoding: .current) {
                                Label(L("cal.evAddPhoto"), systemImage: "photo")
                            }
                        }
                    } header: { Text("\(L("cal.evPhoto")) (\(L("common.optional")))") }

                    Section(L("cal.evPreview")) { preview.listRowInsets(EdgeInsets()) }

                    if let error { Section { Text(error).foregroundStyle(Palette.danger) } }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("cal.sendTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if sending {
                        HStack(spacing: 6) {
                            ProgressView()
                            if photoFile != nil, progress < 1 { Text("\(Int(progress * 100))%").monospacedDigit().font(.caption) }
                        }
                    } else {
                        Button(L("cal.evSend")) { Task { await send() } }.fontWeight(.semibold)
                    }
                }
            }
            .onChange(of: photoItem) { Task { await loadPhoto() } }
            .onChange(of: form.startDate) { if form.endDate < form.startDate { form.endDate = form.startDate } }
        }
        .interactiveDismissDisabled(sending)
    }

    /// Moving the start moves the end with it, keeping the length.
    private var startBinding: Binding<Date> {
        Binding(get: { form.start }, set: { new in
            let delta = new.timeIntervalSince(form.start)
            form.start = new
            form.end = form.end.addingTimeInterval(delta)
        })
    }

    private func dateBinding(_ key: WritableKeyPath<CalendarLogic.EventForm, String>) -> Binding<Date> {
        Binding(get: { CalendarLogic.pickerDate(form[keyPath: key]) }, set: { form[keyPath: key] = CalendarLogic.plainDate($0) })
    }

    // MARK: Preview, as the frame shows it

    private var preview: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let photo, placement == .top {
                Image(uiImage: photo).resizable().scaledToFill().frame(height: 140).clipped()
            }
            VStack(alignment: .leading, spacing: 6) {
                Label(L("cal.sentBy", ["name": senderName]), systemImage: "heart.fill").font(.caption).foregroundStyle(background ? .white.opacity(0.9) : Palette.primary)
                Text(form.title.trimmingCharacters(in: .whitespaces).isEmpty ? L("cal.evTitlePlaceholder") : form.title)
                    .font(.display(.title3)).foregroundStyle(background ? .white : Palette.text)
                Text(whenText).font(.subheadline).foregroundStyle(background ? .white.opacity(0.9) : Palette.textMuted)
                if !form.location.trimmingCharacters(in: .whitespaces).isEmpty {
                    Label(form.location, systemImage: "mappin").font(.subheadline).foregroundStyle(background ? .white.opacity(0.9) : Palette.textMuted)
                }
                HStack {
                    Spacer()
                    Image(systemName: "xmark").frame(width: 40, height: 40).background(Circle().fill(Palette.surface2)).foregroundStyle(Palette.textMuted)
                    Image(systemName: "checkmark").frame(width: 40, height: 40).background(Circle().fill(Palette.accent)).foregroundStyle(.white)
                }
            }
            .padding(14)
        }
        .background {
            if let photo, background {
                Image(uiImage: photo).resizable().scaledToFill().overlay(Color.black.opacity(0.45))
            } else {
                Palette.surface2
            }
        }
        .clipped()
        .accessibilityElement(children: .combine)
    }

    private var background: Bool { photo != nil && placement == .background }

    private var whenText: String {
        let f = DateFormatter()
        f.locale = L10n.locale
        f.setLocalizedDateFormatFromTemplate("EEEEdMMMM")
        if form.allDay {
            let s = f.string(from: CalendarLogic.pickerDate(form.startDate))
            if !form.endDate.isEmpty, form.endDate != form.startDate { return "\(s) – \(f.string(from: CalendarLogic.pickerDate(form.endDate)))" }
            return "\(s) · \(L("cal.allDay"))"
        }
        let t = Date.FormatStyle(date: .omitted, time: .shortened).locale(L10n.locale)
        return "\(f.string(from: form.start)) · \(form.start.formatted(t)) – \(form.end.formatted(t))"
    }

    // MARK: Photo

    private func loadPhoto() async {
        guard let item = photoItem else { return }
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else { return }
            let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
            let url = FileManager.default.temporaryDirectory.appending(path: "event-photo-\(UUID().uuidString).\(ext)")
            try data.write(to: url)
            setPhoto(UIImage(data: data)?.preparingThumbnail(of: CGSize(width: 900, height: 900)), file: url)
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func setPhoto(_ image: UIImage?, file: URL?) {
        if let old = photoFile { try? FileManager.default.removeItem(at: old) }
        photo = image
        photoFile = file
    }

    // MARK: Sending

    private func send() async {
        tried = true
        guard case .success(var body) = built, !sending else { return }
        sending = true
        error = nil
        progress = 0
        defer { sending = false }
        do {
            if let file = photoFile {
                body.photoUploadId = try await PhotoUpload.upload(file, filename: file.lastPathComponent, api: api) { p in
                    Task { @MainActor in progress = p }
                }
                body.photoPlacement = placement
            }
            let (fid, b) = (frame.id, body)
            let inv = try await api.call {
                try await $0.sendEventToFrame(path: .init(frameId: fid), headers: .init(idempotencyKey: UUID().uuidString), body: .json(b)).created.body.json
            }
            setPhoto(nil, file: nil)
            onSent(inv)
            dismiss()
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
    }
}
