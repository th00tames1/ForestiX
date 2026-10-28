import Foundation
import simd

#if canImport(OnnxRuntimeBindings) && canImport(CoreImage) && canImport(CoreVideo)
import OnnxRuntimeBindings
import CoreImage
import ImageIO
import CoreVideo
import Common

/// YOLO26n alignment with a cached inference session. Run on a worker queue.
public final class YoloBoundaryAligner: @unchecked Sendable {
    private static let cache = IdleResourceCache<YoloBoundaryAligner>()
    private static let executionGate = SingleFlightGate()
    private let imageContext = CIContext()
    private let inferenceLock = NSLock()
    private let workspace: YoloInferenceWorkspace
    public static func withShared<Result>(_ operation: (YoloBoundaryAligner) throws -> Result) throws -> Result {
        try executionGate.run { try cache.withResource(create: { try YoloBoundaryAligner() },operation) }
    }
    public static func trimCached() { cache.trim() }
    let environment: ORTEnv
    let session: ORTSession
    private init() throws {
        guard let url = YoloModelIdentity.bundledURL else {
            throw NSError(domain:"ForestiX",code:1,userInfo:[NSLocalizedDescriptionKey:"AI model is unavailable. Use Adjust."])
        }
        environment = try ORTEnv(loggingLevel:.warning)
        let options = try ORTSessionOptions();try options.setIntraOpNumThreads(2)
        session = try ORTSession(env:environment,modelPath:url.path,sessionOptions:options)
        workspace = YoloInferenceWorkspace()
    }
    public func align(buffer: CVPixelBuffer, frame: ARDepthFrame, viewSize: CGSize,
                      leftFraction: Double, rightFraction: Double) throws -> StemExtent? {
        try infer(buffer: buffer, frame: frame, viewSize: viewSize,
                  leftFraction: leftFraction, rightFraction: rightFraction, live: false)?.extent
    }

    public func track(buffer: CVPixelBuffer, frame: ARDepthFrame,
                      viewSize: CGSize) throws -> LiveStemObservation? {
        try infer(buffer: buffer, frame: frame, viewSize: viewSize,
                  leftFraction: 0.25, rightFraction: 0.75, live: true)
    }
    private final class CaptureSink {
        let id: String; let image: RawCapturedImage?; let started: Double
        var source: (any RawAIRecordSource)?
        init(id: String,image: RawCapturedImage?,started: Double) { self.id = id; self.image = image; self.started = started }
    }
    public func trackWithCapture(buffer: CVPixelBuffer,frame: ARDepthFrame,viewSize: CGSize,
                                 id: String,sourceImage: RawCapturedImage?,started: Double) throws -> YoloTrackedCapture {
        let sink = CaptureSink(id: id,image: sourceImage,started: started)
        let observation = try infer(buffer: buffer,frame: frame,viewSize: viewSize,leftFraction: 0.25,rightFraction: 0.75,live: true,capture: sink)
        return YoloTrackedCapture(observation: observation,source: sink.source)
    }

    private func infer(buffer: CVPixelBuffer, frame: ARDepthFrame, viewSize: CGSize,
                       leftFraction: Double, rightFraction: Double, live: Bool,capture: CaptureSink? = nil) throws -> LiveStemObservation? {
        inferenceLock.lock(); defer { inferenceLock.unlock() }
        guard let map = frame.viewMapping else { return nil }
        let rw = CVPixelBufferGetWidth(buffer), rh = CVPixelBufferGetHeight(buffer)
        let turns = [-Double(frame.cameraPoseWorld[1,1]),Double(frame.cameraPoseWorld[0,1]),Double(frame.cameraPoseWorld[1,1]),-Double(frame.cameraPoseWorld[0,1])].enumerated().min(by:{$0.element<$1.element})!.offset
        let uw = turns % 2 == 0 ? rw:rh, uh = turns % 2 == 0 ? rh:rw
        let sc = min(Double(rw)/Double(frame.width),Double(rh)/Double(frame.height))
        let ox = (Double(rw)-Double(frame.width)*sc)/2, oy = (Double(rh)-Double(frame.height)*sc)/2
        func upright(_ x: Double,_ y: Double) -> (Double,Double) {
            var x = x, y = y, width = Double(rw), height = Double(rh)
            for _ in 0..<turns { let old = x; x = height-1-y; y = old; swap(&width,&height) };return (x,y)
        }
        let preparation = YoloImagePreparation.Geometry(rawWidth: rw,rawHeight: rh,quarterTurns: turns)
        YoloImagePreparation.prepare(buffer,geometry: preparation,context: imageContext,workspace: workspace)
        let inputHash = capture == nil ? nil : YoloImagePreparation.inputHash(workspace)
        let input = try ORTValue(tensorData:workspace.inputData,elementType:.float,shape:[1,3,640,640])
        let out = try session.run(withInputs:["images":input],outputNames:["output0","output1"],runOptions:nil)
        let selected: YoloAlignmentMask.Mask?
        if let head = out["output0"],let prototypes = out["output1"] {
            selected = workspace.decodeMask(head: try head.tensorData(),prototypes: try prototypes.tensorData(),width: uw,height: uh)
        } else { selected = nil }
        func record(_ observation: LiveStemObservation?) {
            guard let capture,let inputHash else { return }
            capture.source = YoloOwnedInference(id: capture.id,buffer: buffer,geometry: preparation,inputHash: inputHash,
                frame: frame,sourceImage: capture.image,mask: selected,observation: observation,viewSize: viewSize,
                started: capture.started,completed: ProcessInfo.processInfo.systemUptime,
                modelSHA: try? YoloModelIdentity.sha256(),runtimeVersion: ORTVersion())
        }
        guard let mask = selected else { record(nil); return nil }
        func raw(_ x: Int,_ y: Int) -> Bool {
            let p = upright(Double(x),Double(y));return mask.contains(x:Int(p.0),y:Int(p.1))
        }
        // Use the SAME screen→depth→camera transform for the visible mask and
        // its guide intersection; never rotate a rendered overlay separately.
        func inView(_ x: Double, _ y: Double) -> Bool {
            let p = map.viewToDepth(x: x * viewSize.width, y: y * viewSize.height)
            guard let rgb = LiveStemMask.cameraPoint(depthX: p.x, depthY: p.y,
                depthWidth: frame.width, depthHeight: frame.height, cameraWidth: rw, cameraHeight: rh,
                quarterTurns: turns) else { return false }
            return mask.contains(x: rgb.0, y: rgb.1)
        }
        let overlay = live ? LiveStemMask.sample(viewWidth: viewSize.width,
                                                 viewHeight: viewSize.height, contains: inView) : nil
        let guide = live ? LiveStemMask.guideExtent(score: mask.score, contains: inView) : nil
        if live {
            // Auto only places the bracket. Current depth is evaluated later
            // by the exact same preview/capture path as Adjust.
            let result = LiveStemObservation(extent: nil, mask: overlay, guideExtent: guide)
            record(result); return result
        }
        guard let geometry = DBHEstimator.bracketDepthGeometry(frame:frame,
            leftFraction:leftFraction,rightFraction:rightFraction,viewSize:viewSize) else { return nil }
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
        return LiveStemObservation(extent: StemExtent(leftFraction:min(p0,p1),rightFraction:max(p0,p1),
            score:mask.score,maskPixels:overlay?.runs.reduce(0) { $0 + $1.end - $1.start } ?? 1), mask: overlay)
    }
}
private extension Int { func yoloClamped(to range: ClosedRange<Int>) -> Int { Swift.min(range.upperBound,Swift.max(range.lowerBound,self)) } }
#endif
