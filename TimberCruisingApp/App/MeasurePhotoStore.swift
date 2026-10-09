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
//
// WHAT THIS COSTS, AND WHERE IT IS SPENT. The shutter now fires at the exact
// instant the cruiser is watching the result land, so every millisecond here
// is a millisecond of frozen screen. Measured on the shipped path:
//
//     drawHierarchy at 3.0x (3.0 Mpx)   30-80 ms   main thread, unavoidable
//     jpegData      at 3.0x (3.0 Mpx)   40-80 ms   main thread
//     write                              2-5 ms    main thread
//
// — 160-250 ms of unresponsive screen, twice per tree in the DBH→Height
// chain, ~100 times a day. (An older comment here claimed "the draw itself
// takes a few ms". It was wrong by one to two orders of magnitude, and that
// wrong number is why this path went three rounds unexamined.)
//
// Two changes, both below:
//   · the render is HALVED in scale — a quarter of the pixels, so a quarter
//     of both the draw and the encode (see `renderScale`);
//   · the encode and the write leave the main thread entirely. Only
//     `drawHierarchy` has to be there, because UIKit will not draw a view
//     hierarchy off the main thread.
// What is left on the main thread is the ~10-20 ms render.
//
// THE HANDOFF. `captureWindow` hands the filename back the moment the
// picture exists in memory, and the bytes land afterwards, so an Accept
// tapped during the write still attaches this frame. Everything that can
// happen in that window is handled: a Retake or a screen exit supersedes the
// queued write (`delete`), and a write that fails resolves `written` to
// false so the holder can drop a name whose file will never exist.

import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A frame that has been rendered and named, whose bytes may still be on
/// their way to disk.
public struct HeldMeasurePhoto: Sendable {
    /// The filename to store on the reading. Valid from the moment it is
    /// handed over — attaching it to a reading before `written` resolves is
    /// exactly what it is for.
    public let name: String
    /// Resolves TRUE once the JPEG is on disk under `name`, FALSE if the
    /// encode or the write failed, or if the frame was superseded before its
    /// bytes went down. Awaiting it does NOT block the main thread.
    public let written: Task<Bool, Never>
}

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

    /// Remove a stored photo — and, if its write has not finished yet,
    /// supersede that write so the bytes never land behind the deletion.
    ///
    /// Retake and the way off the scan screen both come through here while a
    /// capture may still be encoding, which is the one way this path can
    /// leave an orphan in the store.
    public static func delete(_ name: String) {
        if writes.supersede(name) {
            // A write for this name is queued or running. `stillWanted` will
            // normally make it skip the write outright, but if the bytes were
            // already going down when the retake landed, they must not be
            // deleted BEFORE they are written. The removal therefore goes on
            // the same serial queue, behind that write: whichever way the
            // race falls, the queue runs the write (or its skip) first and
            // the removal second, and nothing is left on disk.
            writeQueue.async { try? FileManager.default.removeItem(at: url(for: name)) }
            return
        }
        try? FileManager.default.removeItem(at: url(for: name))
    }

    #if canImport(UIKit)

    /// Render scale for the measurement photo, in pixels per point.
    ///
    /// 1.5, not the screen's native 3.0. On a 393x852 pt phone that is
    /// 590x1278 px (0.75 Mpx) instead of 1179x2556 px (3.0 Mpx): a QUARTER
    /// of the pixels, hence about a quarter of the draw, a quarter of the
    /// JPEG encode, and a ~6 MB transient instead of ~25 MB.
    ///
    /// The photo is EVIDENCE, not a print — a cruiser looking back at the
    /// log to confirm the bracket sat on the tree they think they measured,
    /// on a phone screen, usually as a thumbnail first. At 0.75 Mpx the stem,
    /// the fit chord, the crosshair and the AR markers are all still plainly
    /// legible at full-screen in the viewer (which decodes for a ~1600 px
    /// long edge anyway, so it was already throwing most of 3.0 Mpx away).
    /// Nothing measures this image, so its resolution enters no computation:
    /// the reading, its sigma and its capture mode are identical either way.
    private static let renderScale: CGFloat = 1.5

    /// Snapshot the key window (AR feed + overlay chrome) and save as JPEG.
    ///
    /// The RENDER runs here, on the main thread, because UIKit can only draw
    /// a view hierarchy on the main thread — about 10-20 ms at `renderScale`.
    /// The ENCODE and the WRITE, the expensive half, run on `writeQueue`.
    ///
    /// Returns the held photo — its `name` immediately usable — or nil when
    /// there is no window to photograph. See `HeldMeasurePhoto.written` for
    /// the outcome of the deferred half.
    @MainActor
    public static func captureWindow() -> HeldMeasurePhoto? {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow })
            .first
        else { return nil }
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = renderScale
        // The window is opaque, so an alpha channel would be a fifth of the
        // bitmap spent storing 0xFF, and JPEG discards it regardless.
        format.opaque = true
        // Standard range, not the device's extended/wide-gamut default: an
        // EDR-capable phone otherwise gets a float-component backing store
        // for a photograph that is about to become an 8-bit-per-channel
        // JPEG. Same picture, a fraction of the bytes touched.
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds, format: format)
        let image = renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        let name = "m-\(UUID().uuidString).jpg"
        let frame = RenderedFrame(image: image)
        writes.begin(name)
        let written = Task.detached(priority: .userInitiated) { () -> Bool in
            await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                // Serial, so two captures in quick succession cannot hold two
                // full bitmaps at once, and so `delete` can order a removal
                // behind a write that is already running.
                writeQueue.async {
                    cont.resume(returning: encodeAndWrite(frame.image, named: name))
                }
            }
        }
        return HeldMeasurePhoto(name: name, written: written)
    }

    /// The rendered frame on its way to the write queue. A `UIImage` from
    /// `UIGraphicsImageRenderer` is finished and immutable, and the queue
    /// only ever reads it (encodes it), so handing it across is safe — this
    /// box states that for the concurrency checker rather than leaving the
    /// capture to be argued about later.
    private struct RenderedFrame: @unchecked Sendable {
        let image: UIImage
    }

    /// The deferred half: JPEG-encode the rendered frame and put it on disk.
    /// Runs on `writeQueue`, never on the main thread. Returns whether the
    /// file is there afterwards — false is a real answer, and the caller
    /// must not go on claiming a photo it never got.
    private static func encodeAndWrite(_ image: UIImage, named name: String) -> Bool {
        defer { writes.finish(name) }
        guard let data = image.jpegData(compressionQuality: 0.8) else { return false }
        // Superseded while we were encoding — a Retake, or the cruiser
        // leaving the screen. The holder has already given this name up, so
        // writing now would leave a file no reading will ever claim and no
        // screen is left to delete.
        guard writes.stillWanted(name) else { return false }
        do {
            try data.write(to: url(for: name))
            return true
        } catch {
            return false
        }
    }
    #else
    @MainActor
    public static func captureWindow() -> HeldMeasurePhoto? { nil }
    #endif

    // MARK: - Deferred writes

    /// One serial queue for every byte this store puts on disk or takes off
    /// it, so ordering between a write and the deletion that supersedes it is
    /// the queue's job and not a lock's.
    private static let writeQueue = DispatchQueue(
        label: "com.hcjeong.forestix.measure-photo.write", qos: .userInitiated)

    private static let writes = WriteLedger()

    /// Which filenames have a write in flight, and which of those the app has
    /// since changed its mind about. Read and written from the main actor
    /// (capture, delete) and from `writeQueue`, so it carries its own lock.
    private final class WriteLedger: @unchecked Sendable {
        private let lock = NSLock()
        private var inFlight: Set<String> = []
        private var superseded: Set<String> = []

        func begin(_ name: String) {
            lock.lock(); defer { lock.unlock() }
            inFlight.insert(name)
        }

        /// Mark a queued write unwanted. Returns true if it was still in
        /// flight, which is the caller's signal that anything touching the
        /// file has to be ordered behind it.
        func supersede(_ name: String) -> Bool {
            lock.lock(); defer { lock.unlock() }
            guard inFlight.contains(name) else { return false }
            superseded.insert(name)
            return true
        }

        /// Checked on the write queue immediately before the bytes go down.
        func stillWanted(_ name: String) -> Bool {
            lock.lock(); defer { lock.unlock() }
            return !superseded.contains(name)
        }

        /// Drop the name from both sets — the ledger only ever holds the
        /// handful of captures currently in flight.
        func finish(_ name: String) {
            lock.lock(); defer { lock.unlock() }
            inFlight.remove(name)
            superseded.remove(name)
        }
    }
}
