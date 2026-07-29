// Measure-photo store — JPEG snapshots auto-captured at the moment a DBH
// or Height measurement COMPLETES (map-home feature). One file per entry in
// Documents/measure-photos/; QuickMeasureEntry.photoPath holds just the
// filename. Deleting an entry deletes its photo (QuickMeasureHistory).
//
// WHEN THE SHUTTER FIRES: the instant the 5-frame burst finalises (Diameter)
// or the treetop sighting produces a height (Height) — NOT at Accept. By
// Accept the cruiser has lowered the phone to read the result panel and
// decide, so every photo in the field log was leaf litter and boots. The scan
// screens hold the file in view state and attach it to the reading at Accept;
// they own its lifetime until then (see `heldPhoto` on either screen).
//
// The snapshot is the WHOLE window (AR feed + aiming overlay + scene
// markers) on purpose: it is evidence of what was aimed at — richer than a
// bare camera frame, and identical in spirit to the Android PixelCopy
// capture. The scan screens black their 2D chrome out for the shot, so no
// buttons or panels are baked in.

import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

public enum MeasurePhotoStore {

    public static var directory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("measure-photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    public static func url(for name: String) -> URL {
        directory.appendingPathComponent(name)
    }

    public static func delete(_ name: String) {
        try? FileManager.default.removeItem(at: url(for: name))
    }

    #if canImport(UIKit)
    /// Snapshot the key window (AR feed + overlay chrome) and save as JPEG.
    /// Returns the stored filename, or nil when capture/write fails. Must
    /// run on the main thread; the draw itself takes a few ms.
    @MainActor
    public static func captureWindow() -> String? {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow })
            .first
        else { return nil }
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        guard let data = image.jpegData(compressionQuality: 0.8) else { return nil }
        let name = "m-\(UUID().uuidString).jpg"
        do {
            try data.write(to: url(for: name))
            return name
        } catch {
            return nil
        }
    }
    #else
    @MainActor
    public static func captureWindow() -> String? { nil }
    #endif
}
