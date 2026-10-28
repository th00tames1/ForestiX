import Foundation

/// Private scratch storage, used only while YoloBoundaryAligner's inference
/// lock is held. Nothing here is a camera/depth cache or escapes in a result.
final class YoloInferenceWorkspace {
    static let side = 640
    static let inputCount = 3 * side * side
    private static let headCount = 37 * 8400
    private static let prototypeCount = 32 * 25600

    // ORT reads this caller-owned storage synchronously. Its size/address never
    // changes; the next prepare must wait until run AND mask decoding finish.
    let inputData = NSMutableData(length: inputCount * MemoryLayout<Float>.stride)!
    private var rgba: [UInt8] = []
    private var head = [Float](repeating: 0, count: headCount)
    private var prototypes = [Float](repeating: 0, count: prototypeCount)

    func prepareInput(width: Int, height: Int, padX: Int, padY: Int,
                      renderRGBA: (UnsafeMutableRawPointer) -> Void) {
        precondition(width > 0 && height > 0 && padX >= 0 && padY >= 0 &&
                     padX + width <= Self.side && padY + height <= Self.side)
        let count = width * height * 4
        if rgba.count != count { rgba = [UInt8](repeating: 0, count: count) }
        rgba.withUnsafeMutableBytes { bytes in
            // Match a fresh zero-filled bitmap, including any pixels a render
            // does not write. No preceding frame can bleed into this one.
            bytes.initializeMemory(as: UInt8.self, repeating: 0)
            renderRGBA(bytes.baseAddress!)
        }
        let image = inputData.mutableBytes.bindMemory(to: Float.self, capacity: Self.inputCount)
        image.update(repeating: 114.0 / 255.0, count: Self.inputCount)
        // Deliberately the original loop/order, vertical flip and arithmetic.
        // Write directly into the tensor instead of Float array -> NSData.
        let area = Self.side * Self.side
        for yy in 0..<height {
            let sourceRow = (height-1-yy)*width*4, targetRow = (yy+padY)*Self.side+padX
            for xx in 0..<width {
                let source = sourceRow+xx*4, target = targetRow+xx
                image[target] = Float(rgba[source])/255.0
                image[area+target] = Float(rgba[source+1])/255.0
                image[2*area+target] = Float(rgba[source+2])/255.0
            }
        }
    }

    func decodeMask(head headData: NSData, prototypes prototypeData: NSData,
                    width: Int, height: Int) -> YoloAlignmentMask.Mask? {
        let stride = MemoryLayout<Float>.stride
        guard headData.length / stride == Self.headCount,
              prototypeData.length / stride == Self.prototypeCount else { return nil }
        head.withUnsafeMutableBytes { headData.getBytes($0.baseAddress!, length: $0.count) }
        prototypes.withUnsafeMutableBytes { prototypeData.getBytes($0.baseAddress!, length: $0.count) }
        // The decoder owns its logits; it never retains these scratch arrays.
        return YoloAlignmentMask.select(head: head, prototypes: prototypes, width: width, height: height)
    }
}
