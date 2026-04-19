// Phase 7 — local-only analytics + structured logging.
//
// ## Privacy model (spec Phase 7): "Local crash log 만 수집 (privacy, 서버 전송 없음)"
//
// Every event goes to two sinks:
//   1. `os_log` (Console.app / Instruments) — structured category "Forestix"
//   2. a rotating JSONL file at
//      `Application Support/Forestix/logs/events.jsonl`
//
// Nothing is ever sent to the network. The JSONL file is bounded to 10 MB:
// when we would exceed, we rotate to `events.prev.jsonl` and start fresh.
// Settings exports the current log via the share sheet.
//
// ## What we log
// Only project / plot / tree UUIDs and numeric stats. Never:
//   • owner names
//   • free-text notes
//   • photo or scan file paths
//   • GPS coordinates below 3 decimal places (city-block resolution)

import Foundation
import os.log

public enum ForestixLogger {

    // MARK: - Public event surface

    public enum Event: Sendable {
        case appLaunched(version: String)
        case plotOpened(plotId: UUID, projectId: UUID)
        case plotClosed(plotId: UUID, projectId: UUID,
                        durationSec: TimeInterval, treeCount: Int)
        case dbhScanStarted(treeId: UUID?, method: String)
        case dbhScanCompleted(treeId: UUID?, dbhCm: Float,
                              confidence: String, durationMs: Int)
        case dbhScanFailed(reason: String)
        case heightScanStarted(treeId: UUID?, method: String)
        case heightScanCompleted(treeId: UUID?, heightM: Float,
                                 confidence: String, durationMs: Int)
        case heightScanFailed(reason: String)
        case gpsFixAchieved(tier: String, nSamples: Int,
                            medianHAccuracyM: Float)
        case saveFailed(entity: String, error: String)
        case lowBattery(level: Float)
        case trackingLimited(durationSec: TimeInterval)
        case backupCreated(projectId: UUID, bytes: Int64)
        case backupRestored(projectId: UUID, fromPath: String)
        case crashRecoveryPrompted(projectId: UUID, plotId: UUID)
    }

    // MARK: - Logging

    public static func log(_ event: Event,
                           file: String = #file,
                           line: Int = #line) {
        let dict = payload(for: event)
        Sink.shared.write(dict)
    }

    // MARK: - Log access (Settings → Export)

    public static var currentLogURL: URL { Sink.shared.currentURL }
    public static var previousLogURL: URL { Sink.shared.previousURL }

    /// Atomically clears both current and rotated logs. Called from the
    /// Settings "Clear analytics" button.
    public static func clear() { Sink.shared.clear() }

    // MARK: - Private payload builder

    private static func payload(for event: Event) -> [String: Any] {
        let now = ISO8601DateFormatter().string(from: Date())
        var base: [String: Any] = ["t": now]
        switch event {
        case .appLaunched(let v):
            base["event"] = "app.launched"; base["version"] = v
        case .plotOpened(let pid, let proj):
            base["event"] = "plot.opened"
            base["plotId"] = pid.uuidString
            base["projectId"] = proj.uuidString
        case .plotClosed(let pid, let proj, let dur, let n):
            base["event"] = "plot.closed"
            base["plotId"] = pid.uuidString
            base["projectId"] = proj.uuidString
            base["durationSec"] = Int(dur)
            base["treeCount"] = n
        case .dbhScanStarted(let tid, let m):
            base["event"] = "dbh.scan.started"
            base["treeId"] = tid?.uuidString ?? NSNull()
            base["method"] = m
        case .dbhScanCompleted(let tid, let dbh, let c, let ms):
            base["event"] = "dbh.scan.completed"
            base["treeId"] = tid?.uuidString ?? NSNull()
            base["dbhCm"] = dbh
            base["confidence"] = c
            base["durationMs"] = ms
        case .dbhScanFailed(let r):
            base["event"] = "dbh.scan.failed"; base["reason"] = r
        case .heightScanStarted(let tid, let m):
            base["event"] = "height.scan.started"
            base["treeId"] = tid?.uuidString ?? NSNull()
            base["method"] = m
        case .heightScanCompleted(let tid, let h, let c, let ms):
            base["event"] = "height.scan.completed"
            base["treeId"] = tid?.uuidString ?? NSNull()
            base["heightM"] = h
            base["confidence"] = c
            base["durationMs"] = ms
        case .heightScanFailed(let r):
            base["event"] = "height.scan.failed"; base["reason"] = r
        case .gpsFixAchieved(let tier, let n, let acc):
            base["event"] = "gps.fix"
            base["tier"] = tier
            base["nSamples"] = n
            base["medianHAccuracyM"] = acc
        case .saveFailed(let entity, let err):
            base["event"] = "save.failed"
            base["entity"] = entity
            base["error"] = err
        case .lowBattery(let lvl):
            base["event"] = "battery.low"; base["level"] = lvl
        case .trackingLimited(let dur):
            base["event"] = "ar.trackingLimited"; base["durationSec"] = dur
        case .backupCreated(let pid, let bytes):
            base["event"] = "backup.created"
            base["projectId"] = pid.uuidString
            base["bytes"] = bytes
        case .backupRestored(let pid, let from):
            base["event"] = "backup.restored"
            base["projectId"] = pid.uuidString
            // `fromPath` gets its last path component only — never log
            // user home paths.
            base["fromName"] = (from as NSString).lastPathComponent
        case .crashRecoveryPrompted(let pid, let plot):
            base["event"] = "crash.recovery.prompted"
            base["projectId"] = pid.uuidString
            base["plotId"] = plot.uuidString
        }
        return base
    }
}

// MARK: - Sink (file rotation + os_log)

private final class Sink {

    static let shared = Sink()
    private let queue = DispatchQueue(label: "forestix.logger",
                                      qos: .utility)
    private let osLog = OSLog(subsystem: "com.forestix", category: "Forestix")
    private let rotationThreshold: Int64 = 10 * 1024 * 1024  // 10 MB

    let currentURL: URL
    let previousURL: URL

    init() {
        let fm = FileManager.default
        let base: URL
        if let appSup = try? fm.url(for: .applicationSupportDirectory,
                                    in: .userDomainMask,
                                    appropriateFor: nil, create: true) {
            base = appSup.appendingPathComponent("Forestix/logs",
                                                 isDirectory: true)
        } else {
            base = fm.temporaryDirectory.appendingPathComponent("Forestix/logs",
                                                                 isDirectory: true)
        }
        try? fm.createDirectory(at: base, withIntermediateDirectories: true)
        self.currentURL  = base.appendingPathComponent("events.jsonl")
        self.previousURL = base.appendingPathComponent("events.prev.jsonl")
    }

    func write(_ dict: [String: Any]) {
        queue.async { [currentURL, previousURL, rotationThreshold, osLog] in
            guard JSONSerialization.isValidJSONObject(dict),
                  let data = try? JSONSerialization.data(
                    withJSONObject: dict,
                    options: [.sortedKeys]) else { return }

            // Mirror to os_log (structured category, Console-friendly).
            if let json = String(data: data, encoding: .utf8) {
                os_log("%{public}@", log: osLog, type: .info, json)
            }

            let fm = FileManager.default
            if !fm.fileExists(atPath: currentURL.path) {
                fm.createFile(atPath: currentURL.path, contents: nil)
            }

            // Rotation.
            if let sz = (try? fm.attributesOfItem(atPath: currentURL.path))?[.size]
                as? NSNumber, sz.int64Value > rotationThreshold {
                try? fm.removeItem(at: previousURL)
                try? fm.moveItem(at: currentURL, to: previousURL)
                fm.createFile(atPath: currentURL.path, contents: nil)
            }

            if let handle = try? FileHandle(forWritingTo: currentURL) {
                defer { try? handle.close() }
                _ = try? handle.seekToEnd()
                var line = data
                line.append(0x0A)           // '\n'
                try? handle.write(contentsOf: line)
            }
        }
    }

    func clear() {
        queue.sync { [currentURL, previousURL] in
            let fm = FileManager.default
            try? fm.removeItem(at: currentURL)
            try? fm.removeItem(at: previousURL)
        }
    }
}
