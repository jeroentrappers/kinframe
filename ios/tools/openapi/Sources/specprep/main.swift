// specprep rewrites docs/api/openapi.yaml into a form swift-openapi-generator digests, without changing the
// contract's meaning (the same idea as server/tools/specprep, but staying on OpenAPI 3.1):
//
//   - references deeper than a component schema (e.g. #/components/schemas/Album/properties/schedule) are inlined;
//   - oneOf [ {$ref}, {type: 'null'} ] becomes the referenced type. The properties using it are optional, and the
//     generated decoder treats null as absent;
//   - the string enums in `openEnums` lose their `enum` list and become plain strings. Generated enums reject values
//     they don't know, and these are the ones a newer server is expected to extend (a new provider, status or error
//     code must not make a whole list of sources, or an item's `via`, fail to decode). KinframeKit maps them to Swift
//     enums whose `init?(rawValue:)` is nil for unknown values (Core/PhotoSources.swift, Core/EmailInbox.swift,
//     Core/Telegram.swift). An entry may also name an inline enum property, "Schema.property".
//   - the server-to-server paths (`/federation/v1/...`, README §5.22) are dropped: they are signed by servers, never
//     called by apps.
//
// The output is only generator input; docs/api/openapi.yaml stays authoritative.
import Foundation
import Yams

let args = CommandLine.arguments
guard args.count == 3 else {
    FileHandle.standardError.write(Data("usage: specprep <in.yaml> <out.yaml>\n".utf8))
    exit(2)
}

let text = try String(contentsOfFile: args[1], encoding: .utf8)
guard let root = try Yams.compose(yaml: text) else { fatalError("empty document") }

func lookup(_ ref: String) -> Node {
    var cur = root
    for part in ref.dropFirst(2).split(separator: "/") {
        guard let next = cur.mapping?[Node(String(part))] else { fatalError("unresolvable \(ref)") }
        cur = next
    }
    return cur
}

func rewrite(_ node: Node) -> Node {
    switch node {
    case .mapping(var mapping):
        if let ref = mapping["$ref"]?.string, ref.split(separator: "/").count > 4 {
            return rewrite(lookup(ref))
        }
        if let oneOf = mapping["oneOf"]?.sequence, oneOf.count == 2 {
            let nonNull = oneOf.filter { $0.mapping?["type"]?.string != "null" }
            if nonNull.count == 1, let only = nonNull.first {
                mapping["oneOf"] = nil
                var merged = mapping
                for (key, value) in only.mapping ?? [:] { merged[key] = value }
                return rewrite(.mapping(merged))
            }
        }
        var out = Node.Mapping([])
        for (key, value) in mapping { out[key] = rewrite(value) }
        out.style = mapping.style
        return .mapping(out)
    case .sequence(let seq):
        return .sequence(Node.Sequence(seq.map(rewrite), seq.tag, seq.style))
    default:
        return node
    }
}

// A component schema ("EmailOutcome"), or an enum property inside one ("EmailSender.origin").
let openEnums = [
    "PhotoService", "PhotoSourceProvider", "PhotoSourceStatus", "PhotoSourceErrorCode", "PhotoSourceWarning",
    "PhotoSourceHistory", "PhotoSourceDeletePolicy", "GoogleImportStatus", "GoogleImportErrorCode",
    // Join requests from outside channels (email, Telegram; later channels add a value).
    "JoinRequest.via",
    // Email inbox (docs/spec/email-inbox.md).
    "EmailAddressStatus", "EmailSenderState", "EmailHeldReason", "EmailOutcome", "EmailReplyKind",
    "EmailAddressTarget.kind", "EmailSender.origin", "EmailAuthResult.spf", "EmailAuthResult.dkim",
    "EmailAuthResult.dmarc",
    // Telegram (docs/spec/telegram.md).
    "TelegramChatKind", "TelegramCodePurpose", "TelegramCodeStatus", "TelegramCodeErrorCode", "TelegramChatStatus",
    "TelegramChatErrorCode", "TelegramChatWarning", "TelegramChatEndedReason", "TelegramContributorsMode",
    "TelegramChatTarget.kind",
]

func opened(_ node: Node?, _ path: ArraySlice<Substring>, _ name: String) -> Node {
    guard var schema = node?.mapping else { fatalError("open enum \(name): no such schema or property") }
    guard let key = path.first else {
        guard schema["enum"] != nil else { fatalError("open enum \(name) is not a string enum in the spec") }
        schema["enum"] = nil
        return .mapping(schema)
    }
    guard var props = schema["properties"]?.mapping else { fatalError("open enum \(name): no properties") }
    props[Node(String(key))] = opened(props[Node(String(key))], path.dropFirst(), name)
    schema["properties"] = .mapping(props)
    return .mapping(schema)
}

func openingEnums(_ root: Node) -> Node {
    guard var top = root.mapping, var components = top["components"]?.mapping,
          var schemas = components["schemas"]?.mapping else { fatalError("no components.schemas") }
    for name in openEnums {
        let parts = name.split(separator: ".")
        let schema = Node(String(parts[0]))
        schemas[schema] = opened(schemas[schema], parts.dropFirst(), name)
    }
    components["schemas"] = .mapping(schemas)
    top["components"] = .mapping(components)
    return .mapping(top)
}

func withoutFederation(_ root: Node) -> Node {
    guard var top = root.mapping, let paths = top["paths"]?.mapping else { return root }
    var kept = Node.Mapping([])
    for (key, value) in paths where !(key.string ?? "").hasPrefix("/federation/") { kept[key] = value }
    kept.style = paths.style
    top["paths"] = .mapping(kept)
    return .mapping(top)
}

let output = try Yams.serialize(node: openingEnums(rewrite(withoutFederation(root))))
try ("# Generated by ios/tools/openapi specprep from docs/api/openapi.yaml. Do not edit.\n" + output)
    .write(toFile: args[2], atomically: true, encoding: .utf8)
