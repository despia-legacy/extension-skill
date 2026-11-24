import Foundation
import UIKit
import SwiftUI
import FamilyControls
import DeviceActivity
import ManagedSettings

@MainActor
final class AppLockExtension: NativeExtension {
    private var context: NativeExtContext?
    private var authorizationPending = false
    private var picker: UIViewController?
    func didLaunch(_ context: NativeExtContext) { self.context = context }
    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        do {
            if call.route == "capabilities" {
                return .ok(["provider": "screen_time", "apps": true, "categories": true, "domains": true, "native_picker": true, "schedules": true, "usage_thresholds": true, "native_shield_style": true, "opaque_selections": true, "package_suspension": false, "family_controls_entitlement_required": true, "explicit_domain_limit": 50, "explicit_domains_disable_safari_private_browsing": true])
            }
            if call.route == "authorization" {
                if call.params["revoke"] as? Bool == true {
                    try releaseAll()
                    AuthorizationCenter.shared.revokeAuthorization { [weak self] answer in
                        Task { @MainActor in self?.emit("authorization", ["status": self?.authorizationStatus ?? "unknown", "revoked": (try? answer.get()) != nil]) }
                    }
                } else if call.params["request"] as? Bool == true && !authorizationPending {
                    authorizationPending = true
                    let member: FamilyControlsMember = call.params["member"] as? String == "child" ? .child : .individual
                    Task { @MainActor [weak self] in
                        guard let self else { return }
                        do {
                            try await AuthorizationCenter.shared.requestAuthorization(for: member)
                            self.authorizationPending = false
                            self.emit("authorization", ["status": self.authorizationStatus, "pending": false])
                        } catch {
                            self.authorizationPending = false
                            self.emit("authorization", ["status": self.authorizationStatus, "pending": false, "error": error.localizedDescription,
                                                        "error_code": Self.familyControlsCode(error)])
                        }
                    }
                }
                return .ok(["status": authorizationStatus, "pending": authorizationPending])
            }
            if call.route.hasPrefix("android_") { return .error(code: "platform_limit", message: "This operation uses Android APIs") }
            let store = try LockStore()
            switch call.route {
            case "groups": return .ok(["groups": try store.read().groups.map(publicGroup)])
            case "group_get": return .ok(["group": publicGroup(try findGroup(id(call.params), in: store.read()))])
            case "group_save":
                let body = try object(call.params["data"])
                let identifier = try LockRules.identifier(body["id"] as? String ?? UUID().uuidString)
                guard !identifier.isEmpty, let name = body["name"] as? String, !name.isEmpty else { throw LockFailure.invalid("id and name must not be empty") }
                var saved: LockGroup!
                try store.change { state in
                    var group = state.groups.first(where: { $0.id == identifier }) ?? LockGroup(id: identifier, name: name)
                    group.name = name
                    if let domains = body["domains"] as? [String] { group.domains = try Array(Set(domains.map(LockRules.domain))).sorted() }
                    if let style = body["shield"] as? [String: String] { group.shield = style }
                    if let index = state.groups.firstIndex(where: { $0.id == identifier }) { state.groups[index] = group }
                    else { state.groups.append(group) }
                    try LockStore.validateEffectiveDomains(state.activeGroups())
                    state.record("group_saved", group: identifier); saved = group
                }
                try store.reconcile(); emit("changed", ["kind": "group_saved", "group_id": identifier])
                return .ok(["group": publicGroup(saved)])
            case "group_delete":
                let identifier = try id(call.params)
                try store.change { state in
                    _ = try findGroup(identifier, in: state)
                    for schedule in state.schedules where schedule.group_id == identifier { DeviceActivityCenter().stopMonitoring(LockStore.activityNames(schedule.id)) }
                    state.schedules.removeAll { $0.group_id == identifier }; state.groups.removeAll { $0.id == identifier }
                    state.record("group_deleted", group: identifier)
                }
                try store.reconcile(); return .ok(["id": identifier, "deleted": true])
            case "select":
                guard authorizationStatus == "approved" else { return .error(code: "permission_required", message: "Request Screen Time authorization first") }
                guard picker == nil else { return .error(code: "busy", message: "The native picker is open") }
                let group = try findGroup(id(call.params), in: store.read())
                guard let presenter = presenter() else { return .error(code: "unavailable", message: "No foreground host view controller") }
                let view = SelectionView(selection: group.selection, title: group.name) { [weak self] selection in
                    guard let self else { return }
                    self.picker?.dismiss(animated: true); self.picker = nil
                    do {
                        if let selection {
                            try store.change { state in
                                guard let index = state.groups.firstIndex(where: { $0.id == group.id }) else { throw LockFailure.missing("Group was deleted") }
                                let previous = state.groups[index].selection
                                let schedules = state.schedules.filter { $0.group_id == group.id && $0.enabled }
                                do { for schedule in schedules { try LockStore.monitor(schedule, selection: selection) } }
                                catch { for schedule in schedules { try LockStore.monitor(schedule, selection: previous) }; throw error }
                                state.groups[index].selection = selection; state.record("selected", group: group.id)
                            }
                            try store.reconcile()
                        }
                        self.emit("selection", ["group_id": group.id, "cancelled": selection == nil])
                    } catch { self.emit("selection", ["group_id": group.id, "error": error.localizedDescription]) }
                }
                let controller = UIHostingController(rootView: view)
                controller.isModalInPresentation = true; picker = controller; presenter.present(controller, animated: true)
                return .ok(["group_id": group.id, "pending": true])
            case "apply", "release":
                if call.route == "apply" && authorizationStatus != "approved" { return .error(code: "permission_required", message: "Screen Time authorization is not approved") }
                let identifier = try id(call.params), enabled = call.route == "apply"
                try store.change { state in
                    guard let index = state.groups.firstIndex(where: { $0.id == identifier }) else { throw LockFailure.missing("Group not found") }
                    state.groups[index].enabled = enabled
                    try LockStore.validateEffectiveDomains(state.activeGroups())
                    state.record(enabled ? "applied" : "released", group: identifier)
                }
                try store.reconcile()
                return .ok(["group_id": identifier, "manual_enabled": enabled, "active": try store.read().activeGroups().contains(where: { $0.id == identifier })])
            case "release_all": try releaseAll(); return .ok(["released": true])
            case "schedules": return .ok(["schedules": try json(store.read().schedules)])
            case "schedule_save":
                guard authorizationStatus == "approved" else { return .error(code: "permission_required", message: "Screen Time authorization is not approved") }
                var body = try object(call.params["data"])
                if body["id"] == nil { body["id"] = UUID().uuidString }
                body["enabled"] = body["enabled"] ?? true; body["active"] = false; body["threshold_reached"] = false
                body["includes_past_activity"] = body["includes_past_activity"] ?? false
                body["weekdays"] = body["weekdays"] ?? Array(1...7); body["time_zone"] = body["time_zone"] ?? TimeZone.current.identifier
                let schedule = try JSONDecoder().decode(LockSchedule.self, from: JSONSerialization.data(withJSONObject: body))
                try LockStore.validate(schedule)
                try store.change { state in
                    let group = try findGroup(schedule.group_id, in: state)
                    let previous = state.schedules.first(where: { $0.id == schedule.id })
                    state.schedules.removeAll { $0.id == schedule.id }; state.schedules.append(schedule)
                    try LockStore.validateEffectiveDomains(state.activeGroups())
                    do { try LockStore.monitor(schedule, selection: group.selection) }
                    catch { if let previous { let previousGroup = try findGroup(previous.group_id, in: state); try LockStore.monitor(previous, selection: previousGroup.selection) }; throw error }
                    state.record("schedule_saved", group: group.id, schedule: schedule.id)
                }
                try store.reconcile(); return .ok(["schedule": try json(schedule)])
            case "schedule_delete":
                let identifier = try id(call.params)
                try store.change { state in
                    guard state.schedules.contains(where: { $0.id == identifier }) else { throw LockFailure.missing("Schedule not found") }
                    DeviceActivityCenter().stopMonitoring(LockStore.activityNames(identifier)); state.schedules.removeAll { $0.id == identifier }; state.record("schedule_deleted", schedule: identifier)
                }
                try store.reconcile(); return .ok(["id": identifier, "deleted": true])
            case "events":
                let events = try store.change { state -> [LockEvent] in
                    let events = state.events
                    if call.params["clear"] as? Bool == true { state.events = [] }
                    return events
                }
                return .ok(["events": try json(events)])
            default: return .error(code: "not_registered", message: call.route)
            }
        } catch let failure as LockFailure {
            let code: String
            switch failure { case .invalid: code = "bad_request"; case .missing: code = "not_found"; case .unavailable: code = "unavailable"; case .platform: code = "platform_limit" }
            return .error(code: code, message: failure.localizedDescription)
        } catch { return .error(code: "app_lock_error", message: error.localizedDescription) }
    }
    /// FamilyControlsError as a stable code. `unauthorized` in a signed build usually means a bundle ID lacks the
    /// Family Controls entitlement (Distribution approval is per bundle ID: the app and its three extensions).
    private static func familyControlsCode(_ error: Error) -> String {
        guard let failure = error as? FamilyControlsError else { return "app_lock_error" }
        switch failure {
        case .restricted: return "restricted"
        case .unavailable: return "unavailable"
        case .invalidAccountType: return "invalid_account_type"
        case .invalidArgument: return "invalid_argument"
        case .authorizationConflict: return "authorization_conflict"
        case .authorizationCanceled: return "authorization_canceled"
        case .networkError: return "network_error"
        case .authenticationMethodUnavailable: return "authentication_method_unavailable"
        case .unauthorized: return "unauthorized"
        @unknown default: return "app_lock_error"
        }
    }
    private var authorizationStatus: String {
        let status = AuthorizationCenter.shared.authorizationStatus
        if #available(iOS 26.4, *), status == .approvedWithDataAccess { return "approved" }
        switch status { case .approved: return "approved"; case .denied: return "denied"; case .notDetermined: return "not_determined"; @unknown default: return "unknown" }
    }
    private func releaseAll() throws {
        let store = try LockStore()
        try store.change { state in
            for index in state.groups.indices { state.groups[index].enabled = false }
            for index in state.schedules.indices { state.schedules[index].enabled = false; state.schedules[index].active = false; state.schedules[index].threshold_reached = false; DeviceActivityCenter().stopMonitoring(LockStore.activityNames(state.schedules[index].id)) }
            state.record("released_all")
        }
        try store.reconcile()
    }
    private func emit(_ event: String, _ payload: [String: Any]) { context?.emit(event, payload) }
    private func id(_ params: [String: Any]) throws -> String { guard let identifier = params["id"] as? String, !identifier.isEmpty else { throw LockFailure.invalid("id is required") }; return identifier }
    private func object(_ encoded: Any?) throws -> [String: Any] {
        guard let encoded = encoded as? String, let bytes = encoded.data(using: .utf8), let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw LockFailure.invalid("data must encode a JSON object") }; return object
    }
    private func findGroup(_ identifier: String, in state: LockState) throws -> LockGroup { guard let group = state.groups.first(where: { $0.id == identifier }) else { throw LockFailure.missing("Group not found") }; return group }
    private func json<T: Encodable>(_ content: T) throws -> Any { try JSONSerialization.jsonObject(with: JSONEncoder().encode(content)) }
    private func publicGroup(_ group: LockGroup) -> [String: Any] {
        ["id": group.id, "name": group.name, "domains": group.domains, "manual_enabled": group.enabled, "shield": group.shield,
         "ios": ["application_count": group.selection.applicationTokens.count, "category_count": group.selection.categoryTokens.count, "web_domain_count": group.selection.webDomainTokens.count]]
    }
    private func presenter() -> UIViewController? {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.filter { $0.activationState == .foregroundActive }.flatMap(\.windows)
        guard var controller = windows.first(where: \.isKeyWindow)?.rootViewController else { return nil }
        while let presented = controller.presentedViewController { controller = presented }
        return controller
    }
}

private struct SelectionView: View {
    @State var selection: FamilyActivitySelection
    let title: String
    let finish: (FamilyActivitySelection?) -> Void
    var body: some View {
        NavigationView {
            FamilyActivityPicker(selection: $selection)
                .navigationTitle(title)
                .toolbar {
                    SwiftUI.ToolbarItem(placement: .cancellationAction) { Button("Cancel") { finish(nil) } }
                    SwiftUI.ToolbarItem(placement: .confirmationAction) { Button("Save") { finish(selection) } }
                }
        }
    }
}
