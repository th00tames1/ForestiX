import Foundation
import Models

/// The UserDefaults/UI cache is not durable proof. Scan the original journal,
/// bounded by one line plus a 64 KiB chunk; never repair or rewrite it here.
enum FieldQuickResultProofReader {
    static func read(url: URL?,recordId: String) throws -> [FieldExportApplicationResult] {
        try read(url: url,recordIds: [recordId])
    }
    static func read(url: URL?,recordIds: [String]) throws -> [FieldExportApplicationResult] {
        guard let url,FileManager.default.fileExists(atPath: url.path) else { return [] }
        let wanted = Set(recordIds.map { $0.lowercased() })
        var result: [FieldExportApplicationResult] = []
        try scan(url: url) { entry in
            if wanted.contains(entry.id.uuidString.lowercased()) {
                guard let kind = FieldStudyKind(rawValue: entry.kind.rawValue) else { throw FieldStudyError.invalid("quick proof kind") }
                result.append(.init(id: entry.id.uuidString,store: "quick_history",kind: kind,value: entry.value,measurementId: entry.measurementId))
            }
        }
        return result
    }
    static func entries(url: URL) throws -> [QuickMeasureEntry] {
        var entries: [QuickMeasureEntry] = [],ids = Set<UUID>()
        try scan(url: url) { entry in
            guard ids.insert(entry.id).inserted else { throw FieldStudyError.corrupt("duplicate quick journal result UUID") }
            entries.append(entry)
        }
        return entries
    }
    private static func scan(url: URL,consumeEntry: (QuickMeasureEntry) throws -> Void) throws {
        let fm = FileManager.default,before = try fm.attributesOfItem(atPath: url.path)
        guard before[.type] as? FileAttributeType == .typeRegular else { throw FieldStudyError.invalid("quick journal file") }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var buffer = Data(),first = true
        func consume(_ data: Data) throws {
            guard !data.isEmpty else { return }
            if first,data.starts(with: Data("#v ".utf8)) {
                first = false
                guard let text = String(data: data,encoding: .utf8),let version = Int(text.dropFirst(3)),(1...8).contains(version) else { throw FieldStudyError.corrupt("quick journal version") }
                return
            }
            first = false
            let entry = try JSONDecoder().decode(QuickMeasureEntry.self,from: data)
            try consumeEntry(entry)
        }
        while let chunk = try handle.read(upToCount: 65536),!chunk.isEmpty {
            buffer.append(chunk)
            while let index = buffer.firstIndex(of: 10) {
                let line = Data(buffer[..<index]); guard line.count <= 4*1024*1024 else { throw FieldStudyError.corrupt("quick journal oversized line") }
                try consume(line); buffer.removeSubrange(...index)
            }
            guard buffer.count <= 4*1024*1024 else { throw FieldStudyError.corrupt("quick journal oversized line") }
        }
        if !buffer.isEmpty { try consume(buffer) }
        let after = try fm.attributesOfItem(atPath: url.path)
        for key in [FileAttributeKey.size,.systemFileNumber,.modificationDate] {
            guard (before[key] as? NSObject) == (after[key] as? NSObject) else { throw FieldStudyError.conflict("quick journal changed during recovery") }
        }
    }
}
