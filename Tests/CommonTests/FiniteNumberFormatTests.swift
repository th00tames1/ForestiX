import XCTest
import Common

final class FiniteNumberFormatTests: XCTestCase {
    func testMissingValuesStayDistinctFromZero() {
        XCTAssertEqual(finiteNumberFormat("%.2f", Double.nan), "Unavailable")
        XCTAssertEqual(finiteNumberFormat("%.2f %@", Float.infinity, "m3"), "Unavailable")
        XCTAssertEqual(finiteNumberFormat("%.2f", 0.0), "0.00")
        XCTAssertEqual(finiteNumberFormat("%.1f %@", 1.25, "m3"), "1.2 m3")
    }
}
