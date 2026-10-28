#if canImport(UIKit)
import UIKit
import Models

/// App-owned notifications remain observed while a full-screen measurement
/// covers the map. Navigation disappearance is never treated as inactivity.
final class FieldUIFlowApplicationLifecycle {
    private let owner = UUID().uuidString
    private var observers: [NSObjectProtocol] = []
    @MainActor init() {
        let registry = FieldUIFlowRegistry.shared,owner = self.owner
        registry.owner(owner,active: UIApplication.shared.applicationState == .active,now: FieldContinuousTime.seconds())
        for (name,active) in [(UIApplication.didBecomeActiveNotification,true),(UIApplication.willResignActiveNotification,false)] {
            observers.append(NotificationCenter.default.addObserver(forName: name,object: nil,queue: .main) { _ in
                registry.owner(owner,active: active,now: FieldContinuousTime.seconds())
            })
        }
    }
    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        FieldUIFlowRegistry.shared.owner(owner,active: false,now: FieldContinuousTime.seconds())
    }
}
#endif
