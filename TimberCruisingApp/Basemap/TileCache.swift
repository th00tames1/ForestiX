// Spec §8 Basemap/TileCache. REQ-PRJ-006 pre-download basemap tiles.
//
// A content-addressed on-disk cache: each tile is stored at
//     <cacheRoot>/<providerId>/<z>/<x>/<y>.<ext>
// where `providerId` is a slug derived from the tile URL template so users
// can switch providers without the caches colliding. The cache is plain
// flat files — no database — because offline-first cruises routinely copy
// project folders between devices and a single file tree survives that.
//
// UIKit/MapKit-dependent glue (the MKTileOverlay subclass that reads from
// this cache) lives in TileCache+MapKit.swift in the UI target. Keeping
// the pure-IO layer in the Basemap library target means `swift test` on
// macOS exercises the cache logic directly.

import Foundation
import Geo
#if canImport(CryptoKit)
import CryptoKit
#endif

public final class TileCache: @unchecked Sendable {

    // MARK: - Identity

    public struct Key: Hashable, Sendable {
        public let z: Int
        public let x: Int
        public let y: Int
        public init(z: Int, x: Int, y: Int) {
            self.z = z; self.x = x; self.y = y
        }
    }

    public struct ProviderConfig: Sendable {
        public let urlTemplate: String       // e.g. https://tile.example/{z}/{x}/{y}.png
        public let fileExtension: String     // "png", "jpg"
        public let providerId: String        // slug used as subdirectory

        public init(urlTemplate: String, fileExtension: String, providerId: String) {
            self.urlTemplate = urlTemplate
            self.fileExtension = fileExtension
            self.providerId = providerId
        }

        /// Build a provider id from a URL template if the caller doesn't have
        /// a stable name for it (e.g. user-pasted templates in Settings).
        public static func providerId(forURLTemplate template: String) -> String {
            let lowered = template.lowercased()
            #if canImport(CryptoKit)
            let digest = SHA256.hash(data: Data(lowered.utf8))
            let hex = digest.compactMap { String(format: "%02x", $0) }.joined()
            return "tpl-" + String(hex.prefix(16))
            #else
            return "tpl-" + String(UInt64(bitPattern: Int64(lowered.hashValue)), radix: 36)
            #endif
        }
    }

    // MARK: - State

    public let rootURL: URL
    public let provider: ProviderConfig
    private let fm: FileManager

    public init(rootURL: URL, provider: ProviderConfig, fileManager: FileManager = .default) throws {
        self.rootURL = rootURL
        self.provider = provider
        self.fm = fileManager
        try fm.createDirectory(at: providerDirectory, withIntermediateDirectories: true)
    }

    public var providerDirectory: URL {
        rootURL.appendingPathComponent(provider.providerId, isDirectory: true)
    }

    public func fileURL(for key: Key) -> URL {
        providerDirectory
            .appendingPathComponent("\(key.z)", isDirectory: true)
            .appendingPathComponent("\(key.x)", isDirectory: true)
            .appendingPathComponent("\(key.y).\(provider.fileExtension)")
    }

    // MARK: - Reads / writes

    public func isCached(_ key: Key) -> Bool {
        fm.fileExists(atPath: fileURL(for: key).path)
    }

    public func data(for key: Key) -> Data? {
        guard isCached(key) else { return nil }
        return try? Data(contentsOf: fileURL(for: key))
    }

    public func store(_ data: Data, for key: Key) throws {
        let url = fileURL(for: key)
        try fm.createDirectory(at: url.deletingLastPathComponent(),
                               withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }

    public func remove(_ key: Key) throws {
        let url = fileURL(for: key)
        if fm.fileExists(atPath: url.path) {
            try fm.removeItem(at: url)
        }
    }

    // MARK: - URL building

    public func resolvedURL(for key: Key) -> URL? {
        let resolved = provider.urlTemplate
            .replacingOccurrences(of: "{z}", with: "\(key.z)")
            .replacingOccurrences(of: "{x}", with: "\(key.x)")
            .replacingOccurrences(of: "{y}", with: "\(key.y)")
        return URL(string: resolved)
    }

    // MARK: - Cache stats

    public struct Stats: Equatable, Sendable {
        public let fileCount: Int
        public let byteCount: Int64
    }

    public func stats() -> Stats {
        var files = 0
        var bytes: Int64 = 0
        if let enumerator = fm.enumerator(at: providerDirectory,
                                          includingPropertiesForKeys: [.fileSizeKey],
                                          options: [.skipsHiddenFiles]) {
            for case let url as URL in enumerator {
                let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                guard values?.isRegularFile == true else { continue }
                files += 1
                bytes += Int64(values?.fileSize ?? 0)
            }
        }
        return Stats(fileCount: files, byteCount: bytes)
    }

    public func clear() throws {
        if fm.fileExists(atPath: providerDirectory.path) {
            try fm.removeItem(at: providerDirectory)
        }
        try fm.createDirectory(at: providerDirectory, withIntermediateDirectories: true)
    }
}

// MARK: - Tile maths

/// Slippy-map tile conversions (OSM / XYZ scheme).
public enum TileMath {

    /// Convert a lat/lon to the tile that contains it at zoom `z`.
    public static func tile(for point: CoordinateConversions.LatLon, zoom z: Int) -> TileCache.Key {
        let n = Double(1 << z)
        let latRad = point.latitude * .pi / 180
        let x = Int(floor((point.longitude + 180) / 360 * n))
        let y = Int(floor((1 - log(tan(latRad) + 1 / cos(latRad)) / .pi) / 2 * n))
        let maxIdx = (1 << z) - 1
        return TileCache.Key(
            z: z,
            x: max(0, min(maxIdx, x)),
            y: max(0, min(maxIdx, y))
        )
    }

    /// North-west corner lat/lon of a tile.
    public static func northwestCorner(of key: TileCache.Key) -> CoordinateConversions.LatLon {
        let n = Double(1 << key.z)
        let lon = Double(key.x) / n * 360 - 180
        let latRad = atan(sinh(.pi * (1 - 2 * Double(key.y) / n)))
        return CoordinateConversions.LatLon(latitude: latRad * 180 / .pi, longitude: lon)
    }
}
