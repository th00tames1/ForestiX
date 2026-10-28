import Foundation

/// Only recognises UUID-named complete-export ZIPs in the caller's dedicated
/// export cache. Never descends into directories or touches source evidence.
public enum FieldExportRetention {
    public static func prune(directory: URL, now: Date = Date(), maximumAge: TimeInterval = 3600,
                             keepNewest: Int = 2) throws {
        let fm = FileManager.default
        let root = try directory.resourceValues(forKeys: [.isDirectoryKey,.isSymbolicLinkKey])
        guard root.isDirectory == true,root.isSymbolicLink != true else { return }
        let keys: Set<URLResourceKey> = [.isRegularFileKey,.isSymbolicLinkKey,.contentModificationDateKey]
        let candidates = try fm.contentsOfDirectory(at: directory,includingPropertiesForKeys: Array(keys)).compactMap { url -> (URL,Date)? in
            let name = url.lastPathComponent,prefix = "forestix-field-study-"
            guard name.hasPrefix(prefix),name.hasSuffix(".zip"),
                  UUID(uuidString: String(name.dropFirst(prefix.count).dropLast(4))) != nil else { return nil }
            let values = try url.resourceValues(forKeys: keys)
            guard values.isRegularFile == true,values.isSymbolicLink != true,let modified = values.contentModificationDate else { return nil }
            return (url,modified)
        }.sorted { $0.1 == $1.1 ? $0.0.lastPathComponent < $1.0.lastPathComponent : $0.1 > $1.1 }
        for (url,modified) in candidates.dropFirst(max(0,keepNewest)) where now.timeIntervalSince(modified) > max(0,maximumAge) {
            try fm.removeItem(at: url)
        }
    }
}
