import Foundation

/// A JSON document, used for settings merge patches (RFC 7396) where unknown fields must survive.
public enum JSONValue: Codable, Equatable, Hashable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n):
            if n.rounded() == n, abs(n) < 1e15 { try c.encode(Int64(n)) } else { try c.encode(n) }
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public var object: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    public subscript(key: String) -> JSONValue? {
        object?[key]
    }

    /// Encode any Encodable into a JSONValue (via JSONEncoder).
    public static func from<T: Encodable>(_ value: T) throws -> JSONValue {
        let data = try JSONEncoder().encode(value)
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }

    /// Decode this value into a Decodable type.
    public func decode<T: Decodable>(_ type: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONEncoder().encode(self))
    }
}

/// RFC 7396 helpers, mirroring web/src/lib/logic.ts.
public enum MergePatch {
    /// The merge patch that turns `from` into `to`. Arrays are replaced whole, removed keys become null.
    public static func diff(from: [String: JSONValue], to: [String: JSONValue]) -> [String: JSONValue] {
        var patch: [String: JSONValue] = [:]
        for (key, b) in to {
            let a = from[key]
            if case .object(let ao)? = a, case .object(let bo) = b {
                let sub = diff(from: ao, to: bo)
                if !sub.isEmpty { patch[key] = .object(sub) }
            } else if a != b {
                patch[key] = b
            }
        }
        for key in from.keys where to[key] == nil {
            patch[key] = .null
        }
        return patch
    }

    /// Apply a merge patch. Returns a new object.
    public static func apply(_ patch: [String: JSONValue], to target: [String: JSONValue]) -> [String: JSONValue] {
        var out = target
        for (key, value) in patch {
            switch value {
            case .null: out[key] = nil
            case .object(let sub): out[key] = .object(apply(sub, to: out[key]?.object ?? [:]))
            default: out[key] = value
            }
        }
        return out
    }

    /// Leaf paths touched by a patch, e.g. ["slideshow.interval_s", "display.sleep_schedules"].
    public static func paths(_ patch: [String: JSONValue], prefix: String = "") -> [String] {
        patch.flatMap { key, value -> [String] in
            let path = prefix.isEmpty ? key : "\(prefix).\(key)"
            if case .object(let sub) = value { return paths(sub, prefix: path) }
            return [path]
        }
    }

    /// Whether two patches touch the same field (or one touches a parent of the other).
    public static func overlap(_ a: [String: JSONValue], _ b: [String: JSONValue]) -> Bool {
        let pa = paths(a), pb = paths(b)
        return pa.contains { x in pb.contains { y in x == y || x.hasPrefix(y + ".") || y.hasPrefix(x + ".") } }
    }

    public enum Rebase: Equatable {
        case retry(patch: [String: JSONValue])
        case review(merged: [String: JSONValue])
    }

    /// After a 412 version_conflict: our patch was computed against `base`, the server now holds `latest`.
    /// If their change doesn't touch our fields, retry with our patch on the new version; otherwise show the
    /// merged result for review.
    public static func rebase(base: [String: JSONValue], latest: [String: JSONValue], ours: [String: JSONValue]) -> Rebase {
        var b = base, l = latest
        b["version"] = nil
        l["version"] = nil
        let theirs = diff(from: b, to: l)
        if !overlap(theirs, ours) { return .retry(patch: ours) }
        return .review(merged: apply(ours, to: latest))
    }
}
