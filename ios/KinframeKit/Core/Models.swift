import Foundation

// Short names for the generated API schemas used throughout the app.
public typealias FrameSummary = Components.Schemas.FrameSummary
public typealias FrameDetail = Components.Schemas.Frame
public typealias FrameHealth = Components.Schemas.FrameHealth
public typealias FrameSettings = Components.Schemas.FrameSettings
public typealias User = Components.Schemas.User
public typealias Member = Components.Schemas.Member
public typealias JoinRequest = Components.Schemas.JoinRequest
public typealias Invite = Components.Schemas.Invite
public typealias InvitePreview = Components.Schemas.InvitePreview
public typealias RedeemResult = Components.Schemas.RedeemResult
public typealias Send = Components.Schemas.Send
public typealias SendFrame = Components.Schemas.Send.FramesPayloadPayload
public typealias SendPlacement = Components.Schemas.Send.FramesPayloadPayload.PlacementsPayloadPayload
public typealias Item = Components.Schemas.Item
public typealias Placement = Components.Schemas.Placement
public typealias PlacementStatus = Components.Schemas.PlacementStatus
public typealias Permissions = Components.Schemas.Permissions
public typealias Role = Components.Schemas.Role
public typealias Reaction = Components.Schemas.Reaction
public typealias ReplyPreset = Components.Schemas.ReplyPreset
public typealias FocalPoint = Components.Schemas.FocalPoint
public typealias Fit = Components.Schemas.Fit
public typealias Place = Components.Schemas.Place
public typealias SleepSchedule = Components.Schemas.SleepSchedule

extension Components.Schemas.Frame: Identifiable {
    public var id: String { value1.id }
    public var summary: FrameSummary { value1 }
}

extension Components.Schemas.FrameSummary: Identifiable {}
extension Components.Schemas.Send: Identifiable {}
extension Components.Schemas.Placement: Identifiable {}
extension Components.Schemas.Invite: Identifiable {}
extension Components.Schemas.JoinRequest: Identifiable {}

extension Components.Schemas.Member: Identifiable {
    public var id: String { userId }
}

extension Components.Schemas.Permissions {
    public static let all = Permissions(send: true, invite: true, seeLibrary: true, manageLibrary: true, changeSettings: true)
}

extension Components.Schemas.FrameSummary {
    public var isManager: Bool { role == .owner || role == .admin }

    /// Owner and admin have every permission; members what the owner granted.
    public func can(_ permission: KeyPath<Permissions, Bool?>) -> Bool {
        isManager || permissions[keyPath: permission] == true
    }
}

extension Components.Schemas.Reaction {
    public var emoji: String {
        switch self {
        case .love: "❤️"
        case .laugh: "😂"
        case .wow: "😮"
        case .sad: "😢"
        }
    }
}
