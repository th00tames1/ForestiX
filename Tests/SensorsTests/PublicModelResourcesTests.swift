import XCTest
import Foundation
import CryptoKit
@testable import Sensors

final class PublicModelResourcesTests: XCTestCase {
    func testRetiredResearchModelNeverAppearsInResourceBundle() throws {
        let root = try XCTUnwrap(Bundle.module.resourceURL)
        let files = try XCTUnwrap(FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: nil))
        let forbidden = files.compactMap { $0 as? URL }.filter {
            $0.lastPathComponent.lowercased() == "tree_seg_640.onnx"
        }
        XCTAssertTrue(forbidden.isEmpty, "Private research weights must not be bundled")
    }

    func testUnchangedAutoLookupResolvesProcessedModelOrRemainsUnavailable() {
        let resource = Bundle.module.url(forResource: "yolo26_alignment", withExtension: "onnx")
        XCTAssertEqual(YoloModelIdentity.bundledURL?.standardizedFileURL,
                       resource?.standardizedFileURL)
        XCTAssertNotNil(Bundle.module.url(forResource: "README", withExtension: "md"),
                        "A model-free clone must still generate the Sensors resource bundle")
    }

    func testBundledAutoModelBytesAndIdentityMatchLocalProductionSource() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let source = root.appendingPathComponent("TimberCruisingApp/Sensors/Models/yolo26_alignment.onnx")
        guard FileManager.default.fileExists(atPath: source.path) else {
            XCTAssertNil(YoloModelIdentity.bundledURL)
            return
        }
        let bundled = try XCTUnwrap(YoloModelIdentity.bundledURL)
        let sourceData = try Data(contentsOf: source)
        let bundledData = try Data(contentsOf: bundled)
        XCTAssertEqual(bundledData, sourceData, "Resource processing must not alter the Auto model")
        let expected = SHA256.hash(data: sourceData).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(try YoloModelIdentity.sha256(), expected)
    }
}
