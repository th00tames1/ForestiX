import Foundation

/// Decoder for the fixed one-class YOLO26n segmentation export.
/// Masks are interpolated at RGB resolution before they are sampled in depth coordinates.
public enum YoloAlignmentMask {
    public struct Mask: Sendable {
        public let score: Float
        let logits: [Float]
        let box: [Double]
        let width: Int, height: Int, cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int

        public func contains(x: Int, y: Int) -> Bool {
            guard x >= 0, x < width, y >= 0, y < height,
                  Double(x) >= box[0], Double(x) < box[2],
                  Double(y) >= box[1], Double(y) < box[3] else { return false }
            let px = max(0, min(159, Double(cropX) + (Double(x) + 0.5) * Double(cropWidth) / Double(width) - 0.5))
            let py = max(0, min(159, Double(cropY) + (Double(y) + 0.5) * Double(cropHeight) / Double(height) - 0.5))
            let ix = Int(px), iy = Int(py), dx = px - Double(ix), dy = py - Double(iy)
            func value(_ x: Int, _ y: Int) -> Double { Double(logits[y * 160 + x]) }
            let nextX = min(159, ix + 1), nextY = min(159, iy + 1)
            let top = (1 - dx) * value(ix, iy) + dx * value(nextX, iy)
            let bottom = (1 - dx) * value(ix, nextY) + dx * value(nextX, nextY)
            return (1 - dy) * top + dy * bottom > 0
        }
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
        var best: Mask?, bestCentre = false, bestCoverage = -1.0
        for d in kept {
            var logits = [Float](repeating:0,count:25600)
            for c in 0..<32 {
                let coefficient = head[(5+c)*anchors+d.anchor]
                for p in 0..<25600 { logits[p] += coefficient * prototypes[c*25600+p] }
            }
            let box = [max(0,min(Double(width),(d.box[0]-px)/resize)),
                       max(0,min(Double(height),(d.box[1]-py)/resize)),
                       max(0,min(Double(width),(d.box[2]-px)/resize)),
                       max(0,min(Double(height),(d.box[3]-py)/resize))]
            let mask = Mask(score:d.score,logits:logits,box:box,width:width,height:height,
                            cropX:cropX,cropY:cropY,cropWidth:cropWidth,cropHeight:cropHeight)
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
                best = mask; bestCentre = centre; bestCoverage = coverage
            }
        }
        return best
    }
}
