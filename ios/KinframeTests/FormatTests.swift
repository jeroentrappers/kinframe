import KinframeKit
import XCTest

final class FormatTests: XCTestCase {
    func testFormatCode() {
        XCTAssertEqual(Format.formatCode("48213907"), "4821 3907")
    }
}
