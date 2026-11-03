import Foundation

public typealias ShopReservation = Components.Schemas.ShopReservation
public typealias ReservationStatus = Components.Schemas.ShopReservationStatus

extension Components.Schemas.ShopReservation: Identifiable {}

/// Ownership transfer (web: TransferDialog), free of UI so it can be unit tested.
public enum TransferLogic {
    /// Everyone but me, admins first (they already manage the frame), then by name.
    public static func candidates(_ members: [Member], me: String?) -> [Member] {
        members
            .filter { $0.userId != me && $0.role != .owner }
            .sorted { a, b in
                if (a.role == .admin) != (b.role == .admin) { return a.role == .admin }
                return a.displayName.localizedCaseInsensitiveCompare(b.displayName) == .orderedAscending
            }
    }

    /// The member suggested first: the one asked for (from People), else the first admin, else the first one.
    public static func suggestion(_ candidates: [Member], preselect: String?) -> String? {
        if let preselect, candidates.contains(where: { $0.userId == preselect }) { return preselect }
        return (candidates.first { $0.role == .admin } ?? candidates.first)?.userId
    }
}

/// Frame name and location as the overview edits them (`PATCH /v1/frames/{id}`, at most 40 characters each).
public enum FrameDetailsForm {
    public static let maxLength = 40

    public static func clean(_ s: String) -> String {
        String(s.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxLength))
    }

    /// Something changed and the name isn't empty.
    public static func canSave(name: String, location: String, current: FrameSummary) -> Bool {
        let n = clean(name), l = clean(location)
        return !n.isEmpty && (n != current.name || l != (current.location ?? ""))
    }
}

/// The operator's view of shop pre-orders (web: settings/reservations).
public enum ReservationLogic {
    /// Frames reserved: the quantities of every reservation that isn't cancelled.
    public static func framesCount(_ list: [ShopReservation]) -> Int {
        list.filter { $0.status != .cancelled }.reduce(0) { $0 + $1.items.reduce(0) { $0 + $1.quantity } }
    }

    /// Whether a reservation still belongs in the list after its status changed under a filter.
    public static func matches(_ r: ShopReservation, filter: ReservationStatus?) -> Bool {
        filter == nil || r.status == filter
    }

    /// "€ 249.00" in the reservation's currency and the app's language.
    public static func money(_ cents: Int, currency: String, locale: Locale) -> String {
        (Double(cents) / 100).formatted(.currency(code: currency.uppercased()).locale(locale))
    }

    /// The internal note as the server wants it: trimmed, at most 2000 characters; empty clears it.
    public static func note(_ s: String) -> String {
        String(s.trimmingCharacters(in: .whitespacesAndNewlines).prefix(2000))
    }

    /// The CSV export's file name: `reservations-2026-11-03.csv`.
    public static func exportName(now: Date = Date(), calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: now)
        return String(format: "reservations-%04d-%02d-%02d.csv", c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    /// The country's name in the app's language ("BE" → "Belgium").
    public static func countryName(_ code: String, locale: Locale) -> String {
        locale.localizedString(forRegionCode: code) ?? code
    }
}

/// Pages on the server's web app that the phone only links to (privacy, terms, the shop, converting a frame).
public enum WebLinks {
    public static func page(_ path: String, web: String?) -> URL? {
        guard let web, var c = URLComponents(string: web.trimmingCharacters(in: .whitespaces)), c.scheme != nil, c.host != nil else { return nil }
        let base = c.path.hasSuffix("/") ? String(c.path.dropLast()) : c.path
        c.path = base + (path.hasPrefix("/") ? path : "/" + path)
        return c.url
    }
}
