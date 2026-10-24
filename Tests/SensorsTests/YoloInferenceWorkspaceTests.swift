import XCTest
@testable import Sensors
#if canImport(CoreImage)
import CoreImage
import ImageIO
#endif

final class YoloInferenceWorkspaceTests: XCTestCase {
    private func input(_ workspace: YoloInferenceWorkspace) -> [Float] {
        Array(UnsafeBufferPointer(start: workspace.inputData.bytes.assumingMemoryBound(to: Float.self),
                                  count: YoloInferenceWorkspace.inputCount))
    }

    // Frozen pre-optimization preprocessing, independent of the new helper.
    private func legacy(_ rgba: [UInt8], width: Int, height: Int, padX: Int, padY: Int) -> [Float] {
        var image = [Float](repeating: 114.0 / 255.0, count: 3 * 640 * 640)
        for yy in 0..<height { for xx in 0..<width { for c in 0..<3 {
            image[c * 640 * 640 + (yy + padY) * 640 + xx + padX] =
                Float(rgba[((height - 1 - yy) * width + xx) * 4 + c]) / 255.0
        } } }
        return image
    }

    private func prepare(_ workspace: YoloInferenceWorkspace, rgba: [UInt8],
                         width: Int, height: Int, padX: Int, padY: Int) {
        workspace.prepareInput(width: width, height: height, padX: padX, padY: padY) { dst in
            rgba.withUnsafeBytes { dst.copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
        }
    }

    func testTensorMatchesOriginalEveryBitAcrossSizesAndPadding() {
        let workspace = YoloInferenceWorkspace()
        for (w, h, px, py) in [(320, 640, 160, 0), (640, 320, 0, 160), (640, 640, 0, 0), (7, 5, 13, 17)] {
            let rgba = (0..<(w * h * 4)).map { UInt8(($0 * 73 + 19) % 256) }
            prepare(workspace, rgba: rgba, width: w, height: h, padX: px, padY: py)
            XCTAssertEqual(input(workspace).map(\.bitPattern),
                           legacy(rgba, width: w, height: h, padX: px, padY: py).map(\.bitPattern))
        }
    }

    func testInputAndSameSizeBitmapStorageAreReused() {
        let workspace = YoloInferenceWorkspace()
        let inputAddress = workspace.inputData.mutableBytes
        var bitmapAddresses: [UInt] = []
        for _ in 0..<3 {
            workspace.prepareInput(width: 4, height: 8, padX: 2, padY: 3) { bitmap in
                bitmapAddresses.append(UInt(bitPattern: bitmap))
            }
            XCTAssertEqual(workspace.inputData.mutableBytes, inputAddress)
        }
        XCTAssertEqual(Set(bitmapAddresses).count, 1)
        XCTAssertEqual(workspace.inputData.length, 3 * 640 * 640 * 4)
    }

    #if canImport(CoreImage)
    func testCoreImageRenderingMatchesOriginalAcrossAllFourOrientations() {
        let workspace = YoloInferenceWorkspace()
        let context = CIContext()
        let rw = 20, rh = 12
        let source = Data((0..<(rw * rh * 4)).map { UInt8(($0 * 73 + 19) % 256) })
        let original = CIImage(bitmapData: source, bytesPerRow: rw * 4,
                               size: CGSize(width: rw, height: rh), format: .RGBA8,
                               colorSpace: CGColorSpaceCreateDeviceRGB())
        let orientations: [CGImagePropertyOrientation] = [.up, .right, .down, .left]
        for turns in 0..<4 {
            let uw = turns % 2 == 0 ? rw : rh, uh = turns % 2 == 0 ? rh : rw
            let scale = 640.0 / Double(max(uw, uh))
            let nw = Int(Double(uw) * scale + 0.5), nh = Int(Double(uh) * scale + 0.5)
            let px = Int(((640 - Double(nw)) / 2 - 0.1).rounded())
            let py = Int(((640 - Double(nh)) / 2 - 0.1).rounded())
            var ci = original.oriented(orientations[turns])
            ci = ci.transformed(by: CGAffineTransform(translationX: -ci.extent.minX, y: -ci.extent.minY))
            ci = ci.transformed(by: CGAffineTransform(scaleX: Double(nw) / Double(uw), y: Double(nh) / Double(uh)))
            let bounds = CGRect(x: 0, y: 0, width: nw, height: nh)
            let colorSpace = CGColorSpaceCreateDeviceRGB()
            var oldRGBA = [UInt8](repeating: 0, count: nw * nh * 4)
            context.render(ci, toBitmap: &oldRGBA, rowBytes: nw * 4, bounds: bounds, format: .RGBA8, colorSpace: colorSpace)
            workspace.prepareInput(width: nw, height: nh, padX: px, padY: py) { rgba in
                context.render(ci, toBitmap: rgba, rowBytes: nw * 4, bounds: bounds, format: .RGBA8, colorSpace: colorSpace)
            }
            XCTAssertEqual(input(workspace).map(\.bitPattern),
                           legacy(oldRGBA, width: nw, height: nh, padX: px, padY: py).map(\.bitPattern))
        }
    }
    #endif

    func testPreviousPixelsAndPaddingNeverSurviveNextFrame() {
        let workspace = YoloInferenceWorkspace()
        prepare(workspace, rgba: [UInt8](repeating: 255, count: 640 * 640 * 4),
                width: 640, height: 640, padX: 0, padY: 0)
        // A partial render must see a zero-filled bitmap, just as before.
        workspace.prepareInput(width: 640, height: 640, padX: 0, padY: 0) { bitmap in
            let bytes = bitmap.assumingMemoryBound(to: UInt8.self)
            XCTAssertEqual(bytes[0], 0)
            XCTAssertEqual(bytes[640 * 640 * 4 - 1], 0)
            bytes[0] = 128
        }
        var rgba = [UInt8](repeating: 0, count: 640 * 640 * 4); rgba[0] = 128
        XCTAssertEqual(input(workspace).map(\.bitPattern),
                       legacy(rgba, width: 640, height: 640, padX: 0, padY: 0).map(\.bitPattern))
        let small = [UInt8](repeating: 0, count: 4 * 8 * 4)
        prepare(workspace, rgba: small, width: 4, height: 8, padX: 300, padY: 200)
        XCTAssertEqual(input(workspace).map(\.bitPattern),
                       legacy(small, width: 4, height: 8, padX: 300, padY: 200).map(\.bitPattern))
    }

    private func data(_ floats: [Float]) -> NSData {
        floats.withUnsafeBytes { NSData(bytes: $0.baseAddress!, length: $0.count) }
    }

    private func outputs() -> (head: [Float], prototypes: [Float]) {
        var head = [Float](repeating: 0, count: 37 * 8400)
        head[0] = 320; head[8400] = 320; head[2 * 8400] = 160
        head[3 * 8400] = 640; head[4 * 8400] = 0.8; head[5 * 8400] = 1
        var prototypes = [Float](repeating: 0, count: 32 * 25600)
        for i in 0..<25600 { prototypes[i] = i % 160 < 80 ? -1 : 1 }
        return (head, prototypes)
    }

    func testReusedOutputsMatchOriginalDecoderAndDoNotMutateOldMask() throws {
        let workspace = YoloInferenceWorkspace()
        let tensors = outputs()
        let expected = try XCTUnwrap(YoloAlignmentMask.select(head: tensors.head, prototypes: tensors.prototypes, width: 640, height: 640))
        let actual = try XCTUnwrap(workspace.decodeMask(head: data(tensors.head), prototypes: data(tensors.prototypes), width: 640, height: 640))
        let before = (0..<640).map { actual.contains(x: $0, y: 320) }
        XCTAssertEqual(before, (0..<640).map { expected.contains(x: $0, y: 320) })
        XCTAssertEqual(actual.score, expected.score)
        var different = tensors.prototypes
        for i in 0..<25600 { different[i] = -different[i] }
        _ = workspace.decodeMask(head: data(tensors.head), prototypes: data(different), width: 640, height: 640)
        XCTAssertEqual(before, (0..<640).map { actual.contains(x: $0, y: 320) })
    }

    func testMissingTargetAndMalformedOutputsNeverReusePreviousDetection() throws {
        let workspace = YoloInferenceWorkspace()
        let tensors = outputs()
        XCTAssertNotNil(workspace.decodeMask(head: data(tensors.head), prototypes: data(tensors.prototypes), width: 640, height: 640))
        XCTAssertNil(workspace.decodeMask(head: NSData(), prototypes: data(tensors.prototypes), width: 640, height: 640))
        let zeros = [Float](repeating: 0, count: tensors.head.count)
        XCTAssertNil(workspace.decodeMask(head: data(zeros), prototypes: data(tensors.prototypes), width: 640, height: 640))
        XCTAssertNotNil(workspace.decodeMask(head: data(tensors.head), prototypes: data(tensors.prototypes), width: 640, height: 640))
    }
}
