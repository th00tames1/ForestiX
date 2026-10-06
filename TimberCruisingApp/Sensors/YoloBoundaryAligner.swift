import Foundation
import simd

#if canImport(OnnxRuntimeBindings) && canImport(CoreImage) && canImport(CoreVideo)
import OnnxRuntimeBindings
import CoreImage
import ImageIO
import CoreVideo

/// YOLO26n alignment with a cached inference session. Run on a worker queue.
public final class YoloBoundaryAligner: @unchecked Sendable {
    private static let cacheLock = NSLock()
    nonisolated(unsafe) private static var cached: YoloBoundaryAligner?
    private let imageContext = CIContext()
    public static func shared() throws -> YoloBoundaryAligner {
        cacheLock.lock(); defer { cacheLock.unlock() }
        if let cached { return cached }
        let model = try YoloBoundaryAligner(); cached = model; return model
    }
    let environment: ORTEnv
    let session: ORTSession
    private init() throws {
        guard let url = Bundle.module.url(forResource:"yolo26_alignment",withExtension:"onnx",subdirectory:"Models") ?? Bundle.module.url(forResource:"yolo26_alignment",withExtension:"onnx") else {
            throw NSError(domain:"ForestiX",code:1,userInfo:[NSLocalizedDescriptionKey:"AI model is unavailable. Use Adjust."])
        }
        environment = try ORTEnv(loggingLevel:.warning)
        let options = try ORTSessionOptions();try options.setIntraOpNumThreads(2)
        session = try ORTSession(env:environment,modelPath:url.path,sessionOptions:options)
    }
    public func align(buffer: CVPixelBuffer, frame: ARDepthFrame, viewSize: CGSize,
                      leftFraction: Double, rightFraction: Double) throws -> StemExtent? {
        guard let map = frame.viewMapping,
              let geometry = DBHEstimator.bracketDepthGeometry(frame:frame,
                leftFraction:leftFraction,rightFraction:rightFraction,viewSize:viewSize)
        else { return nil }
        let a = map.viewToDepth(x:leftFraction*viewSize.width,y:viewSize.height/2)
        let b = map.viewToDepth(x:rightFraction*viewSize.width,y:viewSize.height/2)
        let col: Bool
        switch geometry.axis { case .row: col = false; case .col: col = true }
        let w = col ? frame.height : frame.width, h = col ? frame.width : frame.height
        let lo = geometry.left * Double(w), hi = geometry.right * Double(w)
        let y: Int
        switch geometry.axis { case .row(let row): y = row; case .col(let column): y = column }
        let depths = (0..<h).flatMap { yy in (0..<w).map { xx in Double(frame.depth(atX:col ? yy:xx,y:col ? xx:yy)) } }
        let grid = BoundaryAlignment.Grid(width:w,height:h,row:y,depth:depths,left:lo,right:hi,focal:Double(frame.intrinsics[col ? 1:0,col ? 1:0]))
        guard hi > lo, abs((col ? b.y:b.x)-(col ? a.y:a.x)) > 0.000001 else { return nil }
        let rw = CVPixelBufferGetWidth(buffer), rh = CVPixelBufferGetHeight(buffer)
        let turns = [-Double(frame.cameraPoseWorld[1,1]),Double(frame.cameraPoseWorld[0,1]),Double(frame.cameraPoseWorld[1,1]),-Double(frame.cameraPoseWorld[0,1])].enumerated().min(by:{$0.element<$1.element})!.offset
        let uw = turns % 2 == 0 ? rw:rh, uh = turns % 2 == 0 ? rh:rw
        let sc = min(Double(rw)/Double(frame.width),Double(rh)/Double(frame.height))
        let ox = (Double(rw)-Double(frame.width)*sc)/2, oy = (Double(rh)-Double(frame.height)*sc)/2
        func upright(_ x: Double,_ y: Double) -> (Double,Double) {
            var x = x, y = y, width = Double(rw), height = Double(rh)
            for _ in 0..<turns { let old = x; x = height-1-y; y = old; swap(&width,&height) };return (x,y)
        }
        let resize = 640.0/Double(max(uw,uh)), nw = Int(Double(uw)*resize+0.5), nh = Int(Double(uh)*resize+0.5)
        let px = Int(((640-Double(nw))/2-0.1).rounded()), py = Int(((640-Double(nh))/2-0.1).rounded())
        let orientation: CGImagePropertyOrientation = [.up,.right,.down,.left][turns]
        var ci = CIImage(cvPixelBuffer:buffer).oriented(orientation)
        ci = ci.transformed(by:CGAffineTransform(translationX:-ci.extent.minX,y:-ci.extent.minY))
        ci = ci.transformed(by:CGAffineTransform(scaleX:Double(nw)/Double(uw),y:Double(nh)/Double(uh)))
        var rgba = [UInt8](repeating:0,count:nw*nh*4)
        imageContext.render(ci,toBitmap:&rgba,rowBytes:nw*4,bounds:CGRect(x:0,y:0,width:nw,height:nh),format:.RGBA8,colorSpace:CGColorSpaceCreateDeviceRGB())
        var image = [Float](repeating:114.0/255.0,count:3*640*640)
        for yy in 0..<nh { for xx in 0..<nw { for c in 0..<3 {
            image[c*640*640+(yy+py)*640+xx+px] = Float(rgba[((nh-1-yy)*nw+xx)*4+c])/255.0
        } } }
        let data = image.withUnsafeBufferPointer { NSMutableData(bytes:$0.baseAddress,length:$0.count*4) }
        let input = try ORTValue(tensorData:data,elementType:.float,shape:[1,3,640,640])
        let out = try session.run(withInputs:["images":input],outputNames:["output0","output1"],runOptions:nil)
        func array(_ key: String) throws -> [Float] {
            guard let value = out[key] else { return [] }
            return (try value.tensorData() as Data).withUnsafeBytes { Array($0.bindMemory(to:Float.self)) }
        }
        guard let mask = YoloAlignmentMask.select(head:try array("output0"),prototypes:try array("output1"),width:uw,height:uh) else { return nil }
        func raw(_ x: Int,_ y: Int) -> Bool {
            let p = upright(Double(x),Double(y));return mask.contains(x:Int(p.0),y:Int(p.1))
        }
        let mw = col ? rh:rw, mh = col ? rw:rh
        let offsetX = col ? oy:ox, offsetY = col ? ox:oy
        func line(_ row: Int) -> [Bool] {
            let ry = Int(floor((Double(row)+0.5)*sc+offsetY+0.5));guard ry>=0,ry<mh else { return [] }
            return (0..<mw).map { raw(col ? ry:$0,col ? $0:ry) }
        }
        var lines: [Int:[Bool]] = [:]
        func getLine(_ row: Int) -> [Bool] { if let l = lines[row] { return l };let l = line(row);lines[row] = l;return l }
        let result = BoundaryAlignment.correct(grid,rgbPixelsPerDepthPixel:sc,mask:{ x,y in
            let l = getLine(y), rx = Int(floor((Double(x)+0.5)*sc+offsetX+0.5));return rx>=0 && rx<l.count && l[rx]
        },rowSegments:{ row in
            let l = getLine(row);var out: [(Double,Double)] = [];var start: Int?
            for x in 0...l.count {
                if x<l.count && l[x] { if start == nil { start = x } }
                else if let s = start {
                    out.append((s>0 ? (Double(s)-0.5-offsetX)/sc-0.5 : -0.5, x<l.count ? (Double(x)-0.5-offsetX)/sc-0.5 : Double(w)-0.5));start = nil
                }
            };return out
        })
        guard let result else { return nil }
        let aa = col ? a.y:a.x, bb = col ? b.y:b.x
        let p0 = leftFraction+(result.left-aa)/(bb-aa)*(rightFraction-leftFraction)
        let p1 = leftFraction+(result.right-aa)/(bb-aa)*(rightFraction-leftFraction)
        return StemExtent(leftFraction:min(p0,p1),rightFraction:max(p0,p1),score:mask.score,maskPixels:1)
    }
}
private extension Int { func yoloClamped(to range: ClosedRange<Int>) -> Int { Swift.min(range.upperBound,Swift.max(range.lowerBound,self)) } }
#endif
