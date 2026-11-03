import KinframeKit
import OpenAPIRuntime
import SwiftUI

/// Shop pre-orders for the server's operators (web: settings/reservations): filter by status, the frame count, a CSV
/// export, and per reservation the status (saved right away) and an internal note.
@MainActor
@Observable
final class ReservationsModel {
    let api: API
    var filter: ReservationStatus? {
        didSet { if filter != oldValue { Task { await load() } } }
    }
    private(set) var list: [ShopReservation]?
    private(set) var forbidden = false
    var error: String?
    /// Internal notes being edited, by reservation id.
    var notes: [String: String] = [:]

    init(api: API, sample: [ShopReservation]? = nil) {
        self.api = api
        if let sample { apply(sample) }
    }

    func load() async {
        let status = filter
        do {
            let rs = try await api.call { try await $0.operatorListReservations(query: .init(status: status)).ok.body.json.reservations }
            apply(rs)
            error = nil
        } catch let e as APIError where e.status == 403 {
            forbidden = true
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func apply(_ rs: [ShopReservation]) {
        list = rs
        notes = Dictionary(uniqueKeysWithValues: rs.map { ($0.id, $0.internalNote ?? "") })
    }

    var framesCount: Int { ReservationLogic.framesCount(list ?? []) }

    func noteChanged(_ r: ShopReservation) -> Bool {
        ReservationLogic.note(notes[r.id] ?? "") != (r.internalNote ?? "")
    }

    func update(_ r: ShopReservation, status: ReservationStatus? = nil, note: String? = nil) async throws {
        let id = r.id
        let body = Operations.OperatorUpdateReservation.Input.Body.JsonPayload(status: status, internalNote: note.map(ReservationLogic.note))
        let saved = try await api.call { try await $0.operatorUpdateReservation(path: .init(reservationId: id), body: .json(body)).ok.body.json }
        guard var l = list, let i = l.firstIndex(where: { $0.id == id }) else { return }
        if ReservationLogic.matches(saved, filter: filter) { l[i] = saved } else { l.remove(at: i) }
        list = l
        notes[id] = saved.internalNote ?? ""
    }

    /// The CSV of the current filter, in a temporary file for the share sheet.
    func export() async throws -> URL {
        let status = filter
        let body = try await api.call { try await $0.operatorExportReservations(query: .init(status: status)).ok.body.csv }
        let data = try await Data(collecting: body, upTo: 50 * 1024 * 1024)
        let url = FileManager.default.temporaryDirectory.appending(path: ReservationLogic.exportName())
        try data.write(to: url, options: .atomic)
        return url
    }
}

struct ReservationsView: View {
    @Environment(AppModel.self) private var app
    @State private var model: ReservationsModel
    @State private var exported: ExportedFile?
    @State private var exporting = false

    struct ExportedFile: Identifiable {
        let url: URL
        var id: URL { url }
    }

    init(api: API, sample: [ShopReservation]? = nil) {
        _model = State(initialValue: ReservationsModel(api: api, sample: sample))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if model.forbidden {
                    Callout(L("reservations.forbidden"), kind: .warn)
                } else {
                    Text(L("reservations.lede")).foregroundStyle(Palette.textMuted)
                    toolbar
                    if let error = model.error {
                        ErrorRetryView(message: error) { Task { await model.load() } }
                    } else if let list = model.list {
                        if list.isEmpty {
                            Text(model.filter == nil ? L("reservations.empty") : L("reservations.emptyFilter"))
                                .foregroundStyle(Palette.textMuted)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .card(padding: 16)
                        }
                        ForEach(list) { r in card(r) }
                    } else {
                        LoadingView()
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await model.load() }
        .pageBackground()
        .navigationTitle(L("reservations.title"))
        .navigationBarTitleDisplayMode(.inline)
        .task { if model.list == nil { await model.load() } }
        .sheet(item: $exported) { file in ActivitySheet(items: [file.url]) }
    }

    private var toolbar: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L("reservations.filterLabel")).font(.subheadline.weight(.semibold))
                Picker(L("reservations.filterLabel"), selection: $model.filter) {
                    Text(L("reservations.filterAll")).tag(ReservationStatus?.none)
                    ForEach(ReservationStatus.allCases, id: \.self) { s in
                        Text(L("reservations.status.\(s.rawValue)")).tag(ReservationStatus?.some(s))
                    }
                }
                .labelsHidden()
                Spacer()
                Button {
                    Task { await export() }
                } label: {
                    HStack(spacing: 6) {
                        if exporting { ProgressView() } else { Image(systemName: "tray.and.arrow.down") }
                        Text(L("reservations.export"))
                    }
                }
                .buttonStyle(.kf(.secondary))
                .disabled(exporting)
            }
            if let list = model.list {
                Text("\(list.count) · " + L("reservations.framesCount", ["count": model.framesCount]))
                    .font(.footnote.monospacedDigit()).foregroundStyle(Palette.textMuted)
            }
        }
    }

    private func card(_ r: ShopReservation) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(r.reference).font(.headline.monospaced())
                Pill(L("reservations.status.\(r.status.rawValue)"), foreground: statusColor(r.status).0, background: statusColor(r.status).1)
                Spacer()
            }
            Text(L("reservations.reservedOn", ["date": r.createdAt.formatted(.dateTime.day().month(.wide).year().locale(L10n.locale))]))
                .font(.footnote).foregroundStyle(Palette.textFaint)
            VStack(alignment: .leading, spacing: 3) {
                Text(r.name).font(.body.weight(.semibold))
                if let mail = URL(string: "mailto:\(r.email)?subject=\(r.reference.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? r.reference)") {
                    Link(r.email, destination: mail).font(.subheadline)
                }
                Text([ReservationLogic.countryName(r.country, locale: L10n.locale), r.postalCode, r.locale].compactMap { $0 }.joined(separator: " · "))
                    .font(.footnote).foregroundStyle(Palette.textMuted)
            }
            VStack(alignment: .leading, spacing: 3) {
                ForEach(r.items, id: \.productId) { l in Text("\(l.quantity) × \(l.name)").font(.subheadline) }
                HStack(spacing: 6) {
                    Text(ReservationLogic.money(r.totalCents, currency: r.currency, locale: L10n.locale)).font(.subheadline.weight(.bold).monospacedDigit())
                    Text("(\(L("shop.shipping")) \(ReservationLogic.money(r.shippingCents, currency: r.currency, locale: L10n.locale)))")
                        .font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            if let note = r.note, !note.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("reservations.customerNote")).font(.caption.weight(.semibold)).foregroundStyle(Palette.textMuted)
                    Text(note).font(.subheadline).italic()
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.surface2, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            }
            Divider().overlay(Palette.border)
            LabeledContent(L("reservations.statusLabel")) {
                Picker(L("reservations.statusLabel"), selection: Binding(get: { r.status }, set: { s in Task { await save(r, status: s) } })) {
                    ForEach(ReservationStatus.allCases, id: \.self) { s in Text(L("reservations.status.\(s.rawValue)")).tag(s) }
                }
                .labelsHidden()
            }
            .font(.subheadline)
            VStack(alignment: .leading, spacing: 4) {
                Text(L("reservations.internalNote")).font(.subheadline.weight(.semibold))
                HStack {
                    TextField(L("reservations.internalNote"), text: Binding(get: { model.notes[r.id] ?? "" }, set: { model.notes[r.id] = String($0.prefix(2000)) }), axis: .vertical)
                        .textFieldStyle(KFFieldStyle())
                        .lineLimit(1...4)
                    Button(L("common.save")) { Task { await save(r, note: model.notes[r.id] ?? "") } }
                        .buttonStyle(.kf(.secondary))
                        .disabled(!model.noteChanged(r))
                }
                Text(L("reservations.internalNoteHint")).font(.caption).foregroundStyle(Palette.textMuted)
            }
        }
        .card(padding: 16)
    }

    private func statusColor(_ s: ReservationStatus) -> (Color, Color) {
        switch s {
        case .new: (Palette.primarySoftText, Palette.primarySoft)
        case .contacted: (Palette.warn, Palette.warnSoft)
        case .confirmed: (Palette.accentText, Palette.accentSoft)
        case .cancelled: (Palette.textMuted, Palette.surface2)
        }
    }

    private func save(_ r: ShopReservation, status: ReservationStatus? = nil, note: String? = nil) async {
        do {
            try await model.update(r, status: status, note: note)
            app.toasts.show(L("reservations.saved"))
        } catch {
            app.toasts.error(error)
        }
    }

    private func export() async {
        exporting = true
        defer { exporting = false }
        do {
            exported = ExportedFile(url: try await model.export())
        } catch {
            app.toasts.error(error)
        }
    }
}

/// The system share sheet for files (ShareLink can't be triggered after an async export).
struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

#if DEBUG
extension ShopReservation {
    /// Two reservations for the debug preview (`-kfSheet reservations` on a server without a shop).
    static var samples: [ShopReservation] {
        let now = Date()
        return [
            ShopReservation(id: "r1", reference: "KF-24A7", status: .new, name: "Marie Peeters", email: "marie@example.org", country: "BE",
                            postalCode: "9000", note: "Graag voor Kerstmis, het is een cadeau voor mijn moeder.", locale: "nl",
                            items: [.init(productId: "frame-10", name: "kinframe 10\"", quantity: 2, unitCents: 14900, lineCents: 29800)],
                            subtotalCents: 29800, shippingCents: 695, totalCents: 30495, currency: "EUR", internalNote: nil,
                            createdAt: now.addingTimeInterval(-86_400), updatedAt: now),
            ShopReservation(id: "r2", reference: "KF-19C2", status: .confirmed, name: "Tom Janssens", email: "tom@example.org", country: "NL",
                            postalCode: "3511", note: nil, locale: "en",
                            items: [.init(productId: "frame-10", name: "kinframe 10\"", quantity: 1, unitCents: 14900, lineCents: 14900)],
                            subtotalCents: 14900, shippingCents: 0, totalCents: 14900, currency: "EUR", internalNote: "Paid by transfer",
                            createdAt: now.addingTimeInterval(-5 * 86_400), updatedAt: now),
        ]
    }
}
#endif
