import Foundation

/// Decoder for the fixed one-class YOLO26n segmentation export.
/// Masks are interpolated at RGB resolution before they are sampled in depth coordinates.
public enum YoloAlignmentMask {
    /// Immutable RGB -> prototype interpolation, shared by every candidate.
    /// Arithmetic/order matches contains() exactly, but is not repeated per pixel.
    fileprivate struct Sampling: Sendable {
        let xs: [Int], ys: [Int], dx: [Double], dy: [Double]
        init(width: Int, height: Int, cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int) {
            let px: [Double] = (0..<width).map { x in
                let scaled = (Double(x)+0.5)*Double(cropWidth)/Double(width)
                return max(0.0,min(159.0,Double(cropX)+scaled-0.5))
            }
            let py: [Double] = (0..<height).map { y in
                let scaled = (Double(y)+0.5)*Double(cropHeight)/Double(height)
                return max(0.0,min(159.0,Double(cropY)+scaled-0.5))
            }
            xs = px.map(Int.init); ys = py.map(Int.init)
            dx = px.map { $0-Double(Int($0)) }; dy = py.map { $0-Double(Int($0)) }
        }
    }
    public struct Mask: Sendable {
        public let score: Float
        let logits: [Float]
        let box: [Double]
        let width: Int, height: Int, cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int
        fileprivate let sampling: Sampling
        func captureGeometry() -> RawYoloMaskGeometry {
            .init(score: Double(score),box: box,width: width,height: height,cropX: cropX,cropY: cropY,cropWidth: cropWidth,cropHeight: cropHeight)
        }

        public func contains(x: Int, y: Int) -> Bool {
            guard x >= 0, x < width, y >= 0, y < height,
                  Double(x) >= box[0], Double(x) < box[2],
                  Double(y) >= box[1], Double(y) < box[3] else { return false }
            let ix = sampling.xs[x], iy = sampling.ys[y], dx = sampling.dx[x], dy = sampling.dy[y]
            func value(_ x: Int, _ y: Int) -> Double { Double(logits[y * 160 + x]) }
            let nextX = min(159, ix + 1), nextY = min(159, iy + 1)
            let top = (1 - dx) * value(ix, iy) + dx * value(nextX, iy)
            let bottom = (1 - dx) * value(ix, nextY) + dx * value(nextX, nextY)
            return (1 - dy) * top + dy * bottom > 0
        }
    }

    public static func restore(geometry g: RawYoloMaskGeometry,logits: [Float]) throws -> Mask {
        guard logits.count == 25600,g.width > 0,g.height > 0,g.width <= 8192,g.height <= 8192,
              g.box.count == 4,g.box.allSatisfy(\.isFinite),g.score.isFinite,
              g.cropX >= 0,g.cropY >= 0,g.cropX <= 160,g.cropY <= 160,g.cropWidth > 0,g.cropHeight > 0,g.cropWidth <= 160,g.cropHeight <= 160,
              g.cropX+g.cropWidth <= 160,g.cropY+g.cropHeight <= 160 else {
            throw NSError(domain: "ForestiX.RawAI",code: 1,userInfo: [NSLocalizedDescriptionKey:"Invalid recorded mask geometry"])
        }
        return Mask(score: Float(g.score),logits: logits,box: g.box,width: g.width,height: g.height,
            cropX: g.cropX,cropY: g.cropY,cropWidth: g.cropWidth,cropHeight: g.cropHeight,
            sampling: Sampling(width: g.width,height: g.height,cropX: g.cropX,cropY: g.cropY,cropWidth: g.cropWidth,cropHeight: g.cropHeight))
    }

    public static func select(head: [Float], prototypes: [Float], width: Int, height: Int) -> Mask? {
        let anchors = 8400
        guard head.count == 37 * anchors, prototypes.count == 32 * 160 * 160,
              width > 0, height > 0 else { return nil }
        struct Detection { let anchor: Int; let score: Float; let box: [Double] }
        var detections: [Detection] = []
        for i in 0..<anchors where head[4 * anchors + i].isFinite && head[4 * anchors + i] >= 0.25 {
            let cx = Double(head[i]), cy = Double(head[anchors+i])
            let w = Double(head[2*anchors+i]), h = Double(head[3*anchors+i])
            guard cx.isFinite, cy.isFinite, w.isFinite, h.isFinite, w > 0, h > 0 else { continue }
            detections.append(Detection(anchor:i, score:head[4*anchors+i], box:[cx-w/2,cy-h/2,cx+w/2,cy+h/2]))
        }
        detections.sort { $0.score == $1.score ? $0.anchor < $1.anchor : $0.score > $1.score }
        var kept: [Detection] = []
        for d in detections {
            let overlaps = kept.contains { k in
                let ix = max(0,min(d.box[2],k.box[2])-max(d.box[0],k.box[0]))
                let iy = max(0,min(d.box[3],k.box[3])-max(d.box[1],k.box[1]))
                let a = (d.box[2]-d.box[0])*(d.box[3]-d.box[1])
                let b = (k.box[2]-k.box[0])*(k.box[3]-k.box[1])
                return ix*iy / (a+b-ix*iy) > 0.45
            }
            if !overlaps { kept.append(d) }
            if kept.count == 300 { break }
        }
        let resize = 640.0 / Double(max(width,height))
        let nw = Int(Double(width)*resize+0.5), nh = Int(Double(height)*resize+0.5)
        let px = ((640-Double(nw))/2-0.1).rounded(), py = ((640-Double(nh))/2-0.1).rounded()
        let gain = min(160.0/Double(width),160.0/Double(height))
        let padx = (160-Double(width)*gain)/2, pady = (160-Double(height)*gain)/2
        let cropX = Int((padx-0.1).rounded()), cropY = Int((pady-0.1).rounded())
        let cropWidth = 160-cropX-Int((padx+0.1).rounded())
        let cropHeight = 160-cropY-Int((pady+0.1).rounded())
        let sampling = Sampling(width:width,height:height,cropX:cropX,cropY:cropY,cropWidth:cropWidth,cropHeight:cropHeight)
        let minX = min(width/2, Int(Double(width)*0.45)), maxX = max(width/2, Int(Double(width)*0.55)-1)
        let minY = min(height/2, Int(Double(height)*0.45)), maxY = max(height/2, Int(Double(height)*0.55)-1)
        let x0 = sampling.xs[minX], x1 = min(159,sampling.xs[maxX]+1)
        let y0 = sampling.ys[minY], y1 = min(159,sampling.ys[maxY]+1)
        var scratch = [Float](repeating:0,count:25600)
        var best: Detection?, bestBox: [Double]?, bestCentre = false, bestCoverage = -1.0
        for d in kept {
            let box = [max(0,min(Double(width),(d.box[0]-px)/resize)),
                       max(0,min(Double(height),(d.box[1]-py)/resize)),
                       max(0,min(Double(width),(d.box[2]-px)/resize)),
                       max(0,min(Double(height),(d.box[3]-py)/resize))]
            // Pixels outside a detection box can never contribute to the
            // centre/central-10% selection. Skip their expensive mask decode.
            guard box[2] > Double(minX), box[0] <= Double(maxX),
                  box[3] > Double(minY), box[1] <= Double(maxY) else { continue }
            // Selection reads only central pixels. Compose exactly those
            // prototype cells, including all four bilinear neighbours.
            // A partial mask is local to this iteration and never escapes.
            for y in y0...y1 { for x in x0...x1 { scratch[y*160+x] = 0 } }
            for c in 0..<32 {
                let coefficient = head[(5+c)*anchors+d.anchor]
                for y in y0...y1 { for x in x0...x1 {
                    let p = y*160+x; scratch[p] += coefficient * prototypes[c*25600+p]
                } }
            }
            let mask = Mask(score:d.score,logits:scratch,box:box,width:width,height:height,
                            cropX:cropX,cropY:cropY,cropWidth:cropWidth,cropHeight:cropHeight,sampling:sampling)
            let centre = mask.contains(x:width/2,y:height/2)
            var count = 0, total = 0
            for y in Int(Double(height)*0.45)..<Int(Double(height)*0.55) {
                for x in Int(Double(width)*0.45)..<Int(Double(width)*0.55) {
                    if mask.contains(x:x,y:y) { count += 1 }; total += 1
                }
            }
            let coverage = total > 0 ? Double(count)/Double(total) : 0
            guard centre || coverage >= 0.1 else { continue }
            if best == nil || (centre && !bestCentre) || (centre == bestCentre &&
                (coverage > bestCoverage || (coverage == bestCoverage && d.score > best!.score))) {
                best = d; bestBox = box; bestCentre = centre; bestCoverage = coverage
            }
        }
        guard let best, let box = bestBox else { return nil }
        // Materialize the chosen FULL mask in the original accumulation order.
        // It owns its logits; the next inference cannot alter an old overlay.
        var logits = [Float](repeating:0,count:25600)
        for c in 0..<32 {
            let coefficient = head[(5+c)*anchors+best.anchor]
            for p in 0..<25600 { logits[p] += coefficient * prototypes[c*25600+p] }
        }
        return Mask(score:best.score,logits:logits,box:box,width:width,height:height,
                    cropX:cropX,cropY:cropY,cropWidth:cropWidth,cropHeight:cropHeight,sampling:sampling)
    }
}
