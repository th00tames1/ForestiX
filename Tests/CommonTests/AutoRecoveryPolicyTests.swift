import XCTest
@testable import Common

/// Both platforms execute this same synthetic contract. It contains no measurements.
final class AutoRecoveryPolicyTests: XCTestCase {
    private struct Contract: Decodable {
        struct Delay: Decodable {
            let name: String, cadence_seconds: Double, failed: Bool, expected_seconds: Double
        }
        let schema: Int
        let live_error_retry_seconds, paired_input_wait_seconds, paired_input_poll_seconds: Double
        let capture_processing_seconds, auto_maximum_seconds: Double
        let messages: [String: String]
        let delays: [Delay]
    }
    private func contract() throws -> Contract {
        let root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("validation/fixtures/auto-recovery-v1.json"))
        return try JSONDecoder().decode(Contract.self, from: data)
    }
    func testBothPlatformsShareRecoveryLimitsAndMessages() throws {
        let c = try contract()
        XCTAssertEqual(c.schema, 1)
        XCTAssertEqual(AutoSafetyPolicy.liveErrorRetrySeconds, c.live_error_retry_seconds)
        XCTAssertEqual(AutoSafetyPolicy.pairedInputWaitSeconds, c.paired_input_wait_seconds)
        XCTAssertEqual(AutoSafetyPolicy.pairedInputPollSeconds, c.paired_input_poll_seconds)
        XCTAssertEqual(AutoSafetyPolicy.captureProcessingSeconds, c.capture_processing_seconds)
        XCTAssertEqual(AutoSafetyPolicy.maximumSeconds, c.auto_maximum_seconds)
        XCTAssertEqual(c.messages, [
            "live_retry": AutoSafetyPolicy.liveRetryMessage,
            "input": AutoSafetyPolicy.captureInputMessage,
            "changed": AutoSafetyPolicy.captureChangedMessage,
            "no_stem": AutoSafetyPolicy.captureNoStemMessage,
            "invalid_depth": AutoSafetyPolicy.captureInvalidDepthMessage,
            "inference": AutoSafetyPolicy.captureInferenceMessage,
            "timeout": AutoSafetyPolicy.captureTimeoutMessage,
            "busy": AutoSafetyPolicy.captureBusyMessage
        ])
    }
    func testErrorsBackOffWithoutChangingHealthyCadenceOrMissingStemBehaviour() throws {
        for row in try contract().delays {
            XCTAssertEqual(AutoSafetyPolicy.liveInferenceDelaySeconds(
                cadenceDelay: row.cadence_seconds, failed: row.failed), row.expected_seconds,
                accuracy: 1e-12, row.name)
        }
    }
    func testInvalidCadenceCannotCreateAnErrorSpinLoop() {
        for invalid in [Double.nan, .infinity, -.infinity, -1] {
            for failed in [false, true] {
                XCTAssertEqual(AutoSafetyPolicy.liveInferenceDelaySeconds(cadenceDelay: invalid, failed: failed), 0.75)
            }
        }
    }
}
