import KinframeKit
import SwiftUI

/// Adding a frame with the 8-digit code: claim an unowned frame, join an owned one, or wait for approval
/// (`POST /v1/frames/redeem`, docs/api/README.md §5.1).
@MainActor
@Observable
final class AddFrameModel {
    enum State: Equatable {
        case entering
        case checking
        case done(RedeemResult)
    }

    private(set) var digits = ""
    private(set) var state: State = .entering
    var error: String?
    var name = ""
    var location = ""
    private(set) var saving = false

    private let api: API
    private var idempotencyKey = UUID().uuidString

    init(api: API, prefill: String? = nil) {
        self.api = api
        if let prefill { digits = Format.normalizeCode(prefill) }
    }

    /// "4821 3907" as shown in the field.
    var formatted: String { Format.formatCode(digits) }
    var isComplete: Bool { Format.isCompleteCode(digits) }
    var canSubmit: Bool { isComplete && state == .entering }

    var result: RedeemResult? {
        if case .done(let r) = state { return r }
        return nil
    }

    /// Accepts typing and pasting ("Code: 4821-3907"); keeps 8 digits.
    func setCode(_ input: String) {
        let next = Format.normalizeCode(input)
        guard next != digits else { return }
        digits = next
        error = nil
    }

    @discardableResult
    func redeem() async -> RedeemResult? {
        guard canSubmit else { return nil }
        state = .checking
        error = nil
        do {
            let code = digits
            let key = idempotencyKey
            let result = try await api.call {
                try await $0.redeemFrameCode(headers: .init(idempotencyKey: key), body: .json(.init(code: code))).ok.body.json
            }
            name = result.frame.name
            location = result.frame.location ?? ""
            state = .done(result)
            return result
        } catch {
            self.error = errorMessage(error)
            idempotencyKey = UUID().uuidString
            state = .entering
            return nil
        }
    }

    /// After a claim: give the frame a name and a place.
    func saveName() async throws -> FrameSummary? {
        guard let result else { return nil }
        saving = true
        defer { saving = false }
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let location = location.trimmingCharacters(in: .whitespacesAndNewlines)
        let id = result.frame.id
        let frame = try await api.call {
            try await $0.updateFrame(path: .init(frameId: id), body: .json(.init(name: name.isEmpty ? nil : String(name.prefix(40)), location: String(location.prefix(40))))).ok.body.json
        }
        return frame.summary
    }

    func reset() {
        state = .entering
        digits = ""
        error = nil
        idempotencyKey = UUID().uuidString
    }
}
