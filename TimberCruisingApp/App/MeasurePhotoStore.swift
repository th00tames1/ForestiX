// Measure-photo store — JPEG snapshots auto-captured at the moment a DBH
// or Height reading is Accepted (map-home feature). One file per entry in
// Documents/measure-photos/; QuickMeasureEntry.photoPath holds just the
// filename. Deleting an entry deletes its photo (QuickMeasureHistory).
//
// The snapshot is the WHOLE window (AR feed + aiming overlay + value
// panel) on purpose: it is evidence of what was aimed at and what the
// instrument showed — richer than a bare camera frame, and identical in
// spirit to the Android PixelCopy capture.

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
