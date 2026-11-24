import Foundation
import DeviceActivity

final class LockMonitor: DeviceActivityMonitor {
    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        update(activity, kind: "interval_started") { $0.active = true; $0.threshold_reached = false }
    }
    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        update(activity, kind: "interval_ended") { $0.active = false; $0.threshold_reached = false }
    }
    override func eventDidReachThreshold(_ event: DeviceActivityEvent.Name, activity: DeviceActivityName) {
        super.eventDidReachThreshold(event, activity: activity)
        update(activity, kind: "threshold_reached") { $0.active = true; $0.threshold_reached = true }
    }
    private func update(_ activity: DeviceActivityName, kind: String, change: (inout LockSchedule) -> Void) {
        do {
            guard let identifier = LockStore.scheduleID(activity) else { return }
            let store = try LockStore()
            try store.change { state in
                guard let index = state.schedules.firstIndex(where: { $0.id == identifier && $0.enabled }) else { return }
                change(&state.schedules[index]); state.record(kind, group: state.schedules[index].group_id, schedule: identifier)
            }
            try store.reconcile()
        } catch {
            do { try LockStore().change { $0.record("enforcement_failed", schedule: LockStore.scheduleID(activity), detail: error.localizedDescription) } }
            catch { NSLog("[despia:applock] Monitor failure could not be stored: %@", error.localizedDescription) }
            NSLog("[despia:applock] Monitor operation failed: %@", error.localizedDescription)
        }
    }
}
