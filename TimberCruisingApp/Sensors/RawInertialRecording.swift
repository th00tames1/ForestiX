import Foundation

/// Optional evidence only. These samples never feed the live height estimator.
public struct RawIMUStream: Codable, Sendable {
    public var name: String
    public var source: String
    public var columns: [String]
    public var units: [String]
    public var available: Bool
    public var requestedIntervalSeconds: Double
    public var referenceFrame: String?
}

public struct RawIMUSample: Codable, Sendable {
    public var stream: String
    /// Native decimal representation: seconds on iOS, integer nanoseconds on
    /// Android. Strings preserve native timestamps without JSON integer loss.
    public var timestamp: String
    public var received: String
    public var values: [Double]
}

public struct RawIMUEvent: Codable, Sendable {
    public var event: String
    public var hostTimestamp: String
    public var arTimestamp: String?
    public var cameraPose: [Double]?
    public var note: String?
    public init(event: String, hostTimestamp: String, arTimestamp: String? = nil, cameraPose: [Double]? = nil, note: String? = nil) {
        self.event = event; self.hostTimestamp = hostTimestamp; self.arTimestamp = arTimestamp
        self.cameraPose = cameraPose; self.note = note
    }
}

public struct RawHeightObservation: Codable, Sendable {
    public var arTimestamp: String?
    public var observedHostTimestamp: String
    /// Camera and anchor from ONE native AR frame, not separate advancing reads.
    public var cameraPose: [Double]?
    public var anchorPose: [Double]?
    public var trackingState: String
    public var trackingReason: String?
    public var thermalState: String
    public var deliveredArTimestamp: String?
    public var deliveryHostTimestamp: String?
    public var nativeFrameIntervalSeconds: Double?
    public var anchorTrackingState: String? = nil
    public var poseConvention = "column-major 4x4 camera/anchor to AR world, metres; y up; camera looks along -z"
}

public struct RawInertialAttachment: Codable, Sendable {
    public var file: RawCapturedFile
    public var status: String
    public var sampleCount: Int
    public var observationCount: Int
}

public struct RawInertialRecording: Codable, Sendable {
    public var schema = 1
    public var measurementId: String
    public var platform: String
    public var sensorTimeUnit: String
    public var hostTimeUnit: String
    public var sensorClock: String
    public var hostClock: String
    public var arTimeUnit: String
    public var axes = "device-fixed: x right, y top, z out of screen; independent of display rotation"
    public var includesCameraInInertialSamples = false
    public var streams: [RawIMUStream]
    public var samples: [RawIMUSample]
    public var events: [RawIMUEvent]
    public var observations: [RawHeightObservation]
    public var droppedSamples: Int
    public var capped: Bool
    public var interruptions: Int
    public var maximumDurationSeconds: Double

    public struct StreamStatistics: Codable, Sendable {
        public var count: Int
        public var firstTimestamp: String?
        public var lastTimestamp: String?
        public var meanIntervalSeconds: Double?
        public var maximumGapSeconds: Double?
        public var nonIncreasingTimestamps: Int
    }
    public var streamStatistics: [String: StreamStatistics] {
        Dictionary(uniqueKeysWithValues: streams.map { stream in
            let rows = samples.filter { $0.stream == stream.name }
            let times = rows.compactMap { Double($0.timestamp) }
            let scale = sensorTimeUnit == "nanoseconds" ? 1e-9 : 1.0
            let intervals = zip(times.dropFirst(),times).map { ($0 - $1) * scale }
            let positive = intervals.filter { $0 > 0 }
            return (stream.name, StreamStatistics(count: rows.count,firstTimestamp: rows.first?.timestamp,
                lastTimestamp: rows.last?.timestamp,meanIntervalSeconds: positive.isEmpty ? nil : positive.reduce(0,+)/Double(positive.count),
                maximumGapSeconds: positive.max(),nonIncreasingTimestamps: intervals.filter { $0 <= 0 }.count))
        })
    }

    public var status: String {
        let required = ["accelerometer", "gyroscope"]
        if !required.allSatisfy({ name in streams.contains { $0.name == name && $0.available } }) { return "unavailable" }
        let stages = Set(events.map(\.event))
        if capped || droppedSamples > 0 || interruptions > 0 || events.contains(where: { $0.event == "sensor_error" }) ||
            !Set(["anchor", "base", "top"]).isSubset(of: stages) ||
            !required.allSatisfy({ name in samples.contains { $0.stream == name } }) { return "partial" }
        return "captured"
    }

    /// Compact sidecar, atomic write and checksum; the existing manifest stays
    /// small and old height bundles without this optional attachment still read.
    func write(id: String, directory: URL) throws -> RawInertialAttachment {
        guard measurementId == id else { throw RawCaptureError.writeFailed("IMU measurement identity mismatch") }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        // Flatten metadata directly into the encoder; do not expand a large
        // sample file into a second Foundation object graph just to add keys.
        struct Document: Encodable {
            let recording: RawInertialRecording
            enum Keys: String, CodingKey { case streamStatistics, status }
            func encode(to encoder: Encoder) throws {
                try recording.encode(to: encoder)
                var c = encoder.container(keyedBy: Keys.self)
                try c.encode(recording.streamStatistics,forKey: .streamStatistics)
                try c.encode(recording.status,forKey: .status)
            }
        }
        let data = try encoder.encode(Document(recording: self))
        let file = try RawCaptureEvidenceIO.write(data, name: "imu.json", directory: directory)
        _ = try file.read(in: directory)
        return .init(file: file, status: status, sampleCount: samples.count, observationCount: observations.count)
    }
}

/// Bounded, thread-safe foreground evidence buffer. Two seconds of pre-roll
/// help estimate stationary bias; no disk or UI work runs in sensor callbacks.
public final class RawIMUBuffer: @unchecked Sendable {
    private let lock = NSLock()
    private let platform: String
    private let timeUnit: String
    private let sensorClock: String
    private let hostClock: String
    private let arTimeUnit: String
    private let maximumSamples: Int
    private let maximumDuration: Double
    private var streams: [RawIMUStream] = []
    private var samples: [RawIMUSample] = []
    private var sampleHostTimes: [Double] = []
    private var events: [RawIMUEvent] = []
    private var observations: [RawHeightObservation] = []
    private var id: String?
    private var startHostSeconds: Double?
    private var active = false
    private var finished = false
    private var capped = false
    private var dropped = 0
    private var interruptions = 0

    public init(platform: String, timeUnit: String, sensorClock: String, hostClock: String,
                arTimeUnit: String, maximumSamples: Int = 40_000, maximumDuration: Double = 120) {
        self.platform = platform; self.timeUnit = timeUnit; self.sensorClock = sensorClock
        self.hostClock = hostClock; self.arTimeUnit = arTimeUnit
        self.maximumSamples = maximumSamples; self.maximumDuration = maximumDuration
    }

    public func arm(streams: [RawIMUStream]) {
        lock.lock(); defer { lock.unlock() }
        self.streams = streams; samples.removeAll(keepingCapacity: true); sampleHostTimes.removeAll(keepingCapacity: true)
        events.removeAll(keepingCapacity: true); observations.removeAll(keepingCapacity: true)
        id = nil; startHostSeconds = nil; active = true; finished = false; capped = false; dropped = 0; interruptions = 0
    }
    public var needsFreshArm: Bool { lock.lock(); defer { lock.unlock() }; return streams.isEmpty || finished }
    public func resume(host: String) {
        lock.lock(); defer { lock.unlock() }
        guard !finished, !capped else { return }
        if !active, id != nil { addEvent(.init(event: "resume", hostTimestamp: host, arTimestamp: nil, cameraPose: nil, note: nil)) }
        active = true
    }
    public func pause(host: String, reason: String) {
        lock.lock(); defer { lock.unlock() }
        if active, !finished, id != nil {
            interruptions += 1
            addEvent(.init(event: "pause", hostTimestamp: host, arTimestamp: nil, cameraPose: nil, note: reason))
        }
        active = false
    }
    public func begin(id: String, hostSeconds: Double, event: RawIMUEvent) {
        lock.lock(); defer { lock.unlock() }
        let kept = zip(samples, sampleHostTimes).filter { $0.1 >= hostSeconds - 2 }
        samples = kept.map(\.0); sampleHostTimes = kept.map(\.1)
        self.id = id; startHostSeconds = hostSeconds; events = [event]; observations = []
        finished = false; capped = false; dropped = 0; interruptions = 0
    }
    public func mark(_ event: RawIMUEvent) {
        lock.lock(); defer { lock.unlock() }; guard id != nil, !finished else { return }; addEvent(event)
    }
    private func addEvent(_ event: RawIMUEvent) {
        if events.count < 128 { events.append(event) } else { capped = true }
    }
    public func observe(_ observation: RawHeightObservation) {
        lock.lock(); defer { lock.unlock() }
        guard id != nil, active, !finished, !capped else { return }
        guard observations.count < 700 else { capped = true; dropped += 1; return }
        observations.append(observation)
    }
    /// False requests the native listener to stop after the bounded capture.
    @discardableResult public func append(_ sample: RawIMUSample, hostSeconds: Double) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard active, !finished, !capped else { return false }
        guard let spec = streams.first(where: { $0.name == sample.stream }), spec.available,
              spec.columns.count == sample.values.count, sample.values.allSatisfy(\.isFinite),
              Double(sample.timestamp)?.isFinite == true, Double(sample.received)?.isFinite == true,
              hostSeconds.isFinite else { dropped += 1; return true }
        if let start = startHostSeconds {
            // A callback can capture its host time just before begin() takes
            // the lock, then arrive just after it. That is valid pre-roll,
            // not a backwards-clock failure or a reason to cap the recording.
            if hostSeconds < start - 2 { dropped += 1; return true }
            if samples.count >= maximumSamples || hostSeconds - start > maximumDuration {
                capped = true; dropped += 1; active = false; return false
            }
        } else if samples.count >= 2_000 {
            samples.removeFirst(1_000); sampleHostTimes.removeFirst(1_000)
        }
        samples.append(sample); sampleHostTimes.append(hostSeconds)
        return true
    }
    public func finish() -> RawInertialRecording? {
        lock.lock(); defer { lock.unlock() }; guard let id, !finished else { return nil }
        active = false; finished = true
        return .init(measurementId: id, platform: platform, sensorTimeUnit: timeUnit, hostTimeUnit: timeUnit,
            sensorClock: sensorClock, hostClock: hostClock, arTimeUnit: arTimeUnit, streams: streams,
            samples: samples, events: events, observations: observations, droppedSamples: dropped,
            capped: capped, interruptions: interruptions, maximumDurationSeconds: maximumDuration)
    }
}
