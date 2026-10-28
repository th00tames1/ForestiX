import XCTest
import UIKit
@testable import UI
@testable import Sensors

/// Synthetic quadrants only; no camera, AR session or user records.
final class DBHSnapshotPhotoTests:XCTestCase {
    @MainActor func testShutterPhotoRegistrationAcrossAllFourRotations() async throws {
        let format=UIGraphicsImageRendererFormat();format.scale=1;format.opaque=true
        let source=UIGraphicsImageRenderer(size:CGSize(width:64,height:64),format:format).image { context in
            for (color,rect) in [(UIColor.red,CGRect(x:0,y:0,width:32,height:32)),
                                 (.green,CGRect(x:32,y:0,width:32,height:32)),
                                 (.blue,CGRect(x:0,y:32,width:32,height:32)),
                                 (.yellow,CGRect(x:32,y:32,width:32,height:32))] {
                color.setFill();context.fill(rect)
            }
        }
        let jpeg=try XCTUnwrap(source.jpegData(compressionQuality:1))
        let mappings:[[Double]] = [[1,0,0,0,1,0],[0,1,0,-1,0,64],[-1,0,64,0,-1,64],[0,-1,64,1,0,0]]
        let expected=[[true,false,false],[false,false,true],[true,true,false],[false,true,false]]
        let guide=StemExtent(leftFraction:0.4,rightFraction:0.6,score:0.9,maskPixels:1)
        for (index,mapping) in mappings.enumerated() {
            let metadata=RawCapturedImage(role:"reference",file:nil,width:64,height:64,cameraFrameSeconds:12,
                imageSensorSeconds:nil,timeSource:"synthetic",intrinsics:[1,0,0,0,1,0,0,0,1],
                cameraPose:Array(repeating:0,count:16),viewToImage:mapping,coordinateSpace:"camera_pixels_native")
            let shot=try XCTUnwrap(MeasurePhotoStore.captureSnapshot(.init(data:jpeg,metadata:metadata),mask:nil,
                guide:guide,viewport:CGSize(width:64,height:64)))
            let written=await shot.written.value;XCTAssertTrue(written)
            defer{MeasurePhotoStore.delete(shot.name)}
            let result=try XCTUnwrap(UIImage(contentsOfFile:MeasurePhotoStore.url(for:shot.name).path))
            let probe=UIGraphicsImageRenderer(size:CGSize(width:1,height:1),format:format).image { _ in
                result.draw(at:CGPoint(x:-result.size.width/4,y:-result.size.height/4))
            }
            var rgba=[UInt8](repeating:0,count:4)
            let cg=try XCTUnwrap(probe.cgImage)
            let context=try XCTUnwrap(CGContext(data:&rgba,width:1,height:1,bitsPerComponent:8,bytesPerRow:4,
                space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(cg,in:CGRect(x:0,y:0,width:1,height:1))
            for channel in 0..<3 {
                if expected[index][channel]{XCTAssertGreaterThan(rgba[channel],150,"rotation \(index), channel \(channel)")}
                else{XCTAssertLessThan(rgba[channel],80,"rotation \(index), channel \(channel)")}
            }
        }
    }
}
