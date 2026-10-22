@testable import KinframeKit
import XCTest

final class FormatTests: XCTestCase {
    func testNormalizeCodeKeepsDigitsOnly() {
        XCTAssertEqual(Format.normalizeCode("Code: 4821-3907"), "48213907")
        XCTAssertEqual(Format.normalizeCode("4821 3907 99"), "48213907")
        XCTAssertEqual(Format.normalizeCode("abc"), "")
        // Non-ASCII digits are not frame codes.
        XCTAssertEqual(Format.normalizeCode("١٢٣"), "")
    }

    func testFormatCodeGroupsInFours() {
        XCTAssertEqual(Format.formatCode("48213907"), "4821 3907")
        XCTAssertEqual(Format.formatCode("482"), "482")
        XCTAssertEqual(Format.formatCode("4821"), "4821")
        XCTAssertEqual(Format.formatCode("48213"), "4821 3")
        XCTAssertEqual(Format.formatCode(" 4821 3907 "), "4821 3907")
    }

    func testCompleteCode() {
        XCTAssertTrue(Format.isCompleteCode("4821 3907"))
        XCTAssertFalse(Format.isCompleteCode("4821 390"))
    }

    func testBytes() {
        let en = Locale(identifier: "en")
        XCTAssertEqual(Format.bytes(512, locale: en), "512 B")
        XCTAssertEqual(Format.bytes(1536, locale: en), "1.5 KB")
        XCTAssertEqual(Format.bytes(2 << 30, locale: en), "2 GB")
        XCTAssertEqual(Format.bytes(1536, locale: Locale(identifier: "nl")), "1,5 KB")
    }

    func testDurationAndInterval() {
        XCTAssertEqual(Format.duration(75.4), "1:15")
        XCTAssertEqual(Format.duration(3725), "1:02:05")
        XCTAssertEqual(Format.duration(-1), "0:00")
        XCTAssertEqual(Format.interval(30), "30 s")
        XCTAssertEqual(Format.interval(300), "5 min")
        XCTAssertEqual(Format.interval(86400), "24 h")
    }

    func testCaptionCountsGraphemes() {
        XCTAssertEqual(Format.captionRemaining("👨‍👩‍👧 hi"), 280 - 4)
        XCTAssertEqual(Format.captionRemaining(String(repeating: "a", count: 281)), -1)
    }

    func testInitials() {
        XCTAssertEqual(Format.initials("Oma Trappers"), "OT")
        XCTAssertEqual(Format.initials("jeroen"), "J")
        XCTAssertEqual(Format.initials("  "), "?")
        XCTAssertEqual(Format.initials(nil), "?")
    }

    func testSignalBars() {
        XCTAssertEqual(Format.signalBars(-50), 4)
        XCTAssertEqual(Format.signalBars(-70), 2)
        XCTAssertEqual(Format.signalBars(-90), 0)
        XCTAssertEqual(Format.signalBars(nil), 0)
    }

    func testDayBucket() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Brussels")!
        let now = ISO8601DateFormatter().date(from: "2026-10-22T10:00:00Z")!
        XCTAssertEqual(Format.dayBucket(now.addingTimeInterval(-3600), now: now, calendar: cal), .today)
        XCTAssertEqual(Format.dayBucket(now.addingTimeInterval(-86400), now: now, calendar: cal), .yesterday)
        if case .day = Format.dayBucket(now.addingTimeInterval(-3 * 86400), now: now, calendar: cal) {} else { XCTFail() }
    }

    func testUptime() {
        XCTAssertEqual(Format.uptime(90061), "1d 1h")
        XCTAssertEqual(Format.uptime(3700), "1h 1m")
        XCTAssertEqual(Format.uptime(59), "0m")
    }
}

final class DiscoveryTests: XCTestCase {
    func testParsesLiveDocument() throws {
        let json = """
        {"name":"kinframe","version":"0.1.0","api":"https://kinframe.appmire.be/v1","web":"https://kinframe.appmire.be",
         "auth":{"issuer":"https://auth.kinframe.appmire.be/oidc","mobile_client_id":"o7tjvpv0uz2bcoazbedgo",
                 "web_client_id":"w","api_resource":"https://kinframe.appmire.be/v1"},
         "limits":{"max_items_per_send":200,"max_video_seconds":300,"max_upload_bytes":2147483648,"trash_retention_days":30},
         "weather":true,"something_new":{"a":1}}
        """
        let d = try JSONDecoder().decode(Discovery.self, from: Data(json.utf8))
        XCTAssertEqual(d.name, "kinframe")
        XCTAssertTrue(d.logtoConfigured)
        XCTAssertFalse(d.devAuth)
        XCTAssertTrue(d.weather)
        XCTAssertEqual(d.limits.maxVideoSeconds, 300)
        XCTAssertEqual(d.limits.maxUploadBytes, 2_147_483_648)
        XCTAssertEqual(d.logtoEndpoint, "https://auth.kinframe.appmire.be")
        XCTAssertEqual(d.apiBase(fallback: ServerAddress.defaultServer).absoluteString, "https://kinframe.appmire.be")
    }

    func testDevServerWithoutLogto() throws {
        let json = #"{"name":"dev","api":"http://localhost:8110/v1/","auth":{"issuer":"","mobile_client_id":""},"limits":{"max_items_per_send":2},"dev_auth":true}"#
        let d = try JSONDecoder().decode(Discovery.self, from: Data(json.utf8))
        XCTAssertTrue(d.devAuth)
        XCTAssertFalse(d.logtoConfigured)
        XCTAssertEqual(d.limits.maxItemsPerSend, 2)
        XCTAssertEqual(d.limits.maxVideoSeconds, 300, "missing limits fall back to the defaults")
        XCTAssertEqual(d.apiBase(fallback: ServerAddress.defaultServer).absoluteString, "http://localhost:8110")
    }

    func testRejectsNonDiscovery() {
        XCTAssertThrowsError(try JSONDecoder().decode(Discovery.self, from: Data(#"{"hello":"world"}"#.utf8)))
    }

    func testFetchValidatesServer() async throws {
        let session = StubURLProtocol.session { r in
            guard r.url.path == "/.well-known/kinframe" else { return .response(status: 404) }
            if r.url.host == "frames.example.org" {
                return .response(status: 200, body: Data(#"{"name":"Trappers family","version":"0.1.0","api":"https://frames.example.org/v1"}"#.utf8))
            }
            if r.url.host == "down.example.org" { return .failure(.cannotConnectToHost) }
            return .response(status: 200, body: Data("<html>".utf8))
        }
        let d = try await DiscoveryClient.fetch(URL(string: "https://frames.example.org")!, session: session)
        XCTAssertEqual(d.name, "Trappers family")
        await XCTAssertThrowsAsync(try await DiscoveryClient.fetch(URL(string: "https://down.example.org")!, session: session), DiscoveryError.unreachable)
        await XCTAssertThrowsAsync(try await DiscoveryClient.fetch(URL(string: "https://blog.example.org")!, session: session), DiscoveryError.notKinframe)
    }

    func testServerAddressNormalization() {
        XCTAssertEqual(ServerAddress.normalize("frames.example.org")?.absoluteString, "https://frames.example.org")
        XCTAssertEqual(ServerAddress.normalize(" https://Frames.Example.org/ ")?.absoluteString, "https://frames.example.org")
        XCTAssertEqual(ServerAddress.normalize("https:/frames.example.org")?.absoluteString, "https://frames.example.org")
        XCTAssertEqual(ServerAddress.normalize("https://frames.example.org/f/12345678")?.absoluteString, "https://frames.example.org")
        XCTAssertEqual(ServerAddress.normalize("http://localhost:8110")?.absoluteString, "http://localhost:8110")
        XCTAssertEqual(ServerAddress.normalize("192.168.1.5:8110")?.absoluteString, "https://192.168.1.5:8110")
        XCTAssertNil(ServerAddress.normalize(""))
        XCTAssertNil(ServerAddress.normalize("not a server"))
        XCTAssertNil(ServerAddress.normalize("ftp://frames.example.org"))
        XCTAssertNil(ServerAddress.normalize("frames"))
    }
}

final class DeepLinkTests: XCTestCase {
    let token = "AbCdEfGhIjKlMnOpQrStUv12"

    func testUniversalLinks() {
        XCTAssertEqual(DeepLink.parse(URL(string: "https://kinframe.appmire.be/f/48213907")!),
                       .frameCode(server: URL(string: "https://kinframe.appmire.be"), code: "48213907"))
        XCTAssertEqual(DeepLink.parse(URL(string: "https://frames.example.org/i/\(token)")!),
                       .invite(server: URL(string: "https://frames.example.org"), token: token))
        XCTAssertEqual(DeepLink.parse(URL(string: "https://kinframe.appmire.be/f/48213907/")!),
                       .frameCode(server: URL(string: "https://kinframe.appmire.be"), code: "48213907"))
    }

    func testCustomSchemeFallbacks() {
        XCTAssertEqual(DeepLink.parse(URL(string: "app.kinframe://f/48213907?server=frames.example.org")!),
                       .frameCode(server: URL(string: "https://frames.example.org"), code: "48213907"))
        XCTAssertEqual(DeepLink.parse(URL(string: "app.kinframe:/i/\(token)")!), .invite(server: nil, token: token))
        XCTAssertEqual(DeepLink.parse(URL(string: "app.kinframe://open?url=https%3A%2F%2Fframes.example.org%2Ff%2F48213907")!),
                       .frameCode(server: URL(string: "https://frames.example.org"), code: "48213907"))
    }

    func testRejectsOtherLinks() {
        XCTAssertNil(DeepLink.parse(URL(string: "https://kinframe.appmire.be/f/4821390")!), "7 digits")
        XCTAssertNil(DeepLink.parse(URL(string: "https://kinframe.appmire.be/f/4821390a")!))
        XCTAssertNil(DeepLink.parse(URL(string: "https://kinframe.appmire.be/i/short")!))
        XCTAssertNil(DeepLink.parse(URL(string: "https://kinframe.appmire.be/frames")!))
        XCTAssertNil(DeepLink.parse(URL(string: "app.kinframe:/callback?code=x&state=y")!), "the OIDC redirect is not a deep link")
        XCTAssertNil(DeepLink.parse(URL(string: "mailto:x@y.z")!))
    }

    func testServerSwitch() {
        let link = DeepLink.frameCode(server: URL(string: "https://frames.example.org"), code: "48213907")
        XCTAssertTrue(link.needsServerSwitch(from: URL(string: "https://kinframe.appmire.be")))
        XCTAssertFalse(link.needsServerSwitch(from: URL(string: "https://frames.example.org/")))
        XCTAssertFalse(DeepLink.invite(server: nil, token: token).needsServerSwitch(from: nil))
    }
}

func XCTAssertThrowsAsync<E: Error & Equatable>(_ expression: @autoclosure () async throws -> some Any, _ expected: E,
                                               file: StaticString = #filePath, line: UInt = #line) async {
    do {
        _ = try await expression()
        XCTFail("expected \(expected)", file: file, line: line)
    } catch {
        XCTAssertEqual(error as? E, expected, file: file, line: line)
    }
}
