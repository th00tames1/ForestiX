import Foundation
#if canImport(CoreMotion) && os(iOS)
import CoreMotion
#endif

/// Developer evidence only; never supplies values to the live estimator.
/// Native listeners are absent when disabled and stopped on pause/finish.
public final class RawIMUCapture {
    private let buffer = RawIMUBuffer(platform: "ios",timeUnit: "seconds",
        sensorClock: "CoreMotion CMLogItem.timestamp: seconds since boot",
        hostClock: "ProcessInfo.systemUptime: seconds since boot",arTimeUnit: "seconds (ARFrame.timestamp)")
    #if canImport(CoreMotion) && os(iOS)
    private let manager = CMMotionManager()
    private let queue: OperationQueue = {
        let q = OperationQueue(); q.name = "Forestix.rawIMU"; q.maxConcurrentOperationCount = 1
        q.qualityOfService = .utility; return q
    }()
    #endif
    public init() {}
    public func resume() {
        #if canImport(CoreMotion) && os(iOS)
        if buffer.needsFreshArm {
            buffer.arm(streams: [
                .init(name: "accelerometer",source: "CMAccelerometerData.acceleration",columns: ["x","y","z"],units: ["g","g","g"],available: manager.isAccelerometerAvailable,requestedIntervalSeconds: 0.01),
                .init(name: "gyroscope",source: "CMGyroData.rotationRate",columns: ["x","y","z"],units: ["rad/s","rad/s","rad/s"],available: manager.isGyroAvailable,requestedIntervalSeconds: 0.01),
                .init(name: "attitude",source: "CMDeviceMotion",columns: ["qw","qx","qy","qz","gravity_x","gravity_y","gravity_z","user_acceleration_x","user_acceleration_y","user_acceleration_z","rotation_x","rotation_y","rotation_z"],units: ["1","1","1","1","g","g","g","g","g","g","rad/s","rad/s","rad/s"],available: manager.isDeviceMotionAvailable,requestedIntervalSeconds: 0.02,referenceFrame: "xArbitraryZVertical; CMAttitude quaternion as returned, wxyz; g=9.80665 m/s^2")])
        }
        buffer.resume(host: ProcessInfo.processInfo.systemUptime.description)
        manager.accelerometerUpdateInterval = 0.01; manager.gyroUpdateInterval = 0.01
        manager.deviceMotionUpdateInterval = 0.02
        if !manager.isAccelerometerActive { manager.startAccelerometerUpdates(to: queue) { [weak self] data,error in
            self?.error(error, stream: "accelerometer")
            if let d = data { self?.append("accelerometer",d.timestamp,[d.acceleration.x,d.acceleration.y,d.acceleration.z]) }
        } }
        if !manager.isGyroActive { manager.startGyroUpdates(to: queue) { [weak self] data,error in
            self?.error(error, stream: "gyroscope")
            if let d = data { self?.append("gyroscope",d.timestamp,[d.rotationRate.x,d.rotationRate.y,d.rotationRate.z]) }
        } }
        if !manager.isDeviceMotionActive { manager.startDeviceMotionUpdates(using: .xArbitraryZVertical,to: queue) { [weak self] data,error in
            self?.error(error, stream: "attitude")
            if let d = data {
                let q = d.attitude.quaternion, g = d.gravity, a = d.userAcceleration, r = d.rotationRate
                self?.append("attitude",d.timestamp,[q.w,q.x,q.y,q.z,g.x,g.y,g.z,a.x,a.y,a.z,r.x,r.y,r.z])
            }
        } }
        #else
        if buffer.needsFreshArm { buffer.arm(streams: [
            .init(name: "accelerometer",source: "unavailable on host",columns: ["x","y","z"],units: ["g","g","g"],available: false,requestedIntervalSeconds: 0.01),
            .init(name: "gyroscope",source: "unavailable on host",columns: ["x","y","z"],units: ["rad/s","rad/s","rad/s"],available: false,requestedIntervalSeconds: 0.01)]) }
        buffer.resume(host: ProcessInfo.processInfo.systemUptime.description)
        #endif
    }
    private func stop() {
        #if canImport(CoreMotion) && os(iOS)
        manager.stopAccelerometerUpdates(); manager.stopGyroUpdates(); manager.stopDeviceMotionUpdates()
        #endif
    }
    private func append(_ stream: String,_ timestamp: Double,_ values: [Double]) {
        let now = ProcessInfo.processInfo.systemUptime
        if !buffer.append(.init(stream: stream,timestamp: timestamp.description,received: now.description,values: values),hostSeconds: now) { stop() }
    }
    private func error(_ error: Error?,stream: String) {
        if let e = error as NSError? { mark("sensor_error",note: "\(stream): \(e.domain) \(e.code)") }
    }
    public func begin(id: String, arTimestamp: Double?, cameraPose: [Double]?) {
        let now = ProcessInfo.processInfo.systemUptime
        buffer.begin(id: id,hostSeconds: now,event: .init(event: "anchor",hostTimestamp: now.description,
            arTimestamp: arTimestamp?.description,cameraPose: cameraPose))
    }
    public func mark(_ event: String,arTimestamp: Double? = nil,cameraPose: [Double]? = nil,note: String? = nil) {
        buffer.mark(.init(event: event,hostTimestamp: ProcessInfo.processInfo.systemUptime.description,
            arTimestamp: arTimestamp?.description,cameraPose: cameraPose,note: note))
    }
    public func observe(_ observation: RawHeightObservation) { buffer.observe(observation) }
    public func pause() { buffer.pause(host: ProcessInfo.processInfo.systemUptime.description,reason: "screen inactive/background"); stop() }
    public func finish() -> RawInertialRecording? { stop(); return buffer.finish() }
    deinit { stop() }
}
