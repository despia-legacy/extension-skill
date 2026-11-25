import ActivityKit
import AlarmKit
import AppIntents
import Foundation
import SwiftUI
import UIKit
import UserNotifications

@MainActor
final class AlarmsExtension: NativeExtension {
    private var context: NativeExtContext!
    private var ledger: AlarmLedger?
    private var storageError: String?
    private var alarmObserver: Task<Void, Never>?
    private var authorizationObserver: Task<Void, Never>?
    private var openObserver: NSObjectProtocol?
    private var isRequesting = false
    private var changingIds = Set<String>()
    required init() {}
    func didLaunch(_ context: NativeExtContext) {
        self.context = context
        do { ledger = try AlarmLedger(directory: context.dataDirectory) }
        catch { storageError = error.localizedDescription; context.log("Cannot open alarm storage: " + error.localizedDescription) }
        openObserver = NotificationCenter.default.addObserver(forName: AlarmOpenJournal.notification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.retainOpenEvents() }
        }
        retainOpenEvents()
        if #available(iOS 26.0, *) {
            alarmObserver = Task { [weak self] in
                for await alarms in AlarmManager.shared.alarmUpdates {
                    guard let self = self, !Task.isCancelled else { break }
                    self.observe(alarms)
                }
            }
            authorizationObserver = Task { [weak self] in
                for await _ in AlarmManager.shared.authorizationUpdates {
                    guard let self = self, !Task.isCancelled else { break }
                    self.context.emit("permission", await self.permissions())
                }
            }
        }
    }
    func willTerminate() {
        alarmObserver?.cancel(); authorizationObserver?.cancel()
        if let observer = openObserver { NotificationCenter.default.removeObserver(observer) }
    }
    func sceneDidChange(_ phase: NativeExtScenePhase) { if phase == .didBecomeActive { retainOpenEvents() } }
    private func retainOpenEvents() {
        guard let ledger = ledger else { return }
        do {
            let pending = try AlarmOpenJournal.read()
            guard !pending.isEmpty else { return }
            for receipt in pending {
                if try ledger.importEvent(receipt) { context.emit("changed", receipt) }
            }
            try AlarmOpenJournal.acknowledge(Set(pending.compactMap { $0["id"] as? String }))
        } catch { context.log("Cannot retain opened alarm: " + error.localizedDescription) }
    }
    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        do {
            guard let ledger = ledger else { return .error(code: "storage_error", message: storageError ?? "Alarm storage is unavailable") }
            retainOpenEvents()
            let parameters = call.params
            let id = parameters["id"] as? String ?? ""
            switch call.route {
            case "capabilities":
                let hasAlarmKit: Bool
                if #available(iOS 26.0, *) { hasAlarmKit = true } else { hasAlarmKit = false }
                return .ok(["platform": "ios", "provider": hasAlarmKit ? "alarmkit" : "usernotifications", "system_alarms": hasAlarmKit,
                    "countdown": hasAlarmKit, "weekly": true, "pause": hasAlarmKit, "resume": hasAlarmKit,
                    "challenge": "app_reported_progress", "physical_exercise_detection": false,
                    "notification_fallback": true, "timezone": "system_alarms_follow_device_local_time", "custom_sound": "bundle_named_sound"])
            case "permissions":
                if parameters["request"] as? Bool == true {
                    guard !isRequesting else { throw AlarmFailure.state("A permission request is pending") }
                    let delivery = parameters["delivery"] as? String ?? "system"
                    if delivery == "system" {
                        guard #available(iOS 26.0, *) else { throw AlarmFailure.unsupported("System alarms require iOS 26 or later. Notification reminders are an explicit alternative") }
                        guard let text = Bundle.main.object(forInfoDictionaryKey: "NSAlarmKitUsageDescription") as? String, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw AlarmFailure.invalid("The build needs a nonempty NSAlarmKitUsageDescription") }
                    }
                    isRequesting = true
                    Task { @MainActor in
                        defer { isRequesting = false }
                        do {
                            if delivery == "notification" { _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) }
                            else if #available(iOS 26.0, *) { _ = try await AlarmManager.shared.requestAuthorization() }
                            self.context.emit("permission", await self.permissions())
                        } catch {
                            self.context.emit("permission", ["error": "permission_request_failed", "message": error.localizedDescription])
                        }
                    }
                    var answer = await permissions(); answer["pending"] = true; return .ok(answer)
                }
                return .ok(await permissions())
            case "android_access": throw AlarmFailure.unsupported("android_access is Android only")
            case "save":
                var alarm = try AlarmValues.parse(parameters["alarm"] as? String ?? "")
                alarm["delivery"] = alarm["delivery"] as? String ?? "system"
                try await replace(alarm, active: { call.isActive })
                try emit("saved", id: alarm["id"] as! String)
                return .ok(["alarm": alarm])
            case "get":
                if #available(iOS 26.0, *), AlarmManager.shared.authorizationState == .authorized { observe(try AlarmManager.shared.alarms) }
                return .ok(["alarm": try ledger.get(id)])
            case "list":
                if #available(iOS 26.0, *), AlarmManager.shared.authorizationState == .authorized { observe(try AlarmManager.shared.alarms) }
                return .ok(["alarms": ledger.list(group: parameters["group"] as? String)])
            case "cancel":
                let alarm = try ledger.get(id); try cancelNative(alarm); try ledger.remove(id); try emit("cancelled", id: id)
                return .ok(["alarm": alarm])
            case "stop": return .ok(["alarm": try stop(id, emergency: parameters["emergency"] as? Bool == true)])
            case "snooze":
                var alarm = try ledger.get(id)
                if (alarm["delivery"] as? String ?? "system") == "system" {
                    guard #available(iOS 26.0, *), let nativeId = UUID(uuidString: alarm["native_id"] as? String ?? id) else { throw AlarmFailure.unsupported("System snooze requires AlarmKit") }
                    let configured = alarm["snooze_seconds"] as? Double ?? 300
                    if let seconds = parameters["seconds"] as? Int, Double(seconds) != configured { throw AlarmFailure.unsupported("AlarmKit snoozes for the snooze_seconds saved in the alarm definition. Android accepts a per-call seconds override") }
                    try AlarmManager.shared.countdown(id: nativeId)
                    alarm["state"] = "countdown"; alarm["next_at_ms"] = Int64(Date().addingTimeInterval(configured).timeIntervalSince1970 * 1000)
                    try ledger.put(alarm); try emit("snoozed", id: id, details: ["next_at_ms": alarm["next_at_ms"]!])
                    return .ok(["alarm": alarm])
                }
                let seconds = parameters["seconds"] as? Int ?? Int(alarm["snooze_seconds"] as? Double ?? 300)
                guard seconds > 0, Double(seconds) < (9_007_199_254_740_991 - Date().timeIntervalSince1970 * 1000) / 1000 else { throw AlarmFailure.invalid("seconds must be positive and in range") }
                alarm["next_at_ms"] = Int64(Date().addingTimeInterval(Double(seconds)).timeIntervalSince1970 * 1000)
                alarm["state"] = "scheduled"; alarm["enabled"] = true; alarm["snoozed"] = true
                try await replace(alarm, active: { call.isActive }, overrideDate: true)
                try emit("snoozed", id: id, details: ["next_at_ms": alarm["next_at_ms"]!])
                return .ok(["alarm": alarm])
            case "pause", "resume":
                var alarm = try ledger.get(id)
                guard (alarm["delivery"] as? String ?? "system") == "system", #available(iOS 26.0, *) else { throw AlarmFailure.unsupported("Native pause and resume require an AlarmKit countdown") }
                guard let uuid = UUID(uuidString: alarm["native_id"] as? String ?? id) else { throw AlarmFailure.invalid("Invalid alarm id") }
                if call.route == "pause" { try AlarmManager.shared.pause(id: uuid); alarm["state"] = "paused" }
                else { try AlarmManager.shared.resume(id: uuid); alarm["state"] = "countdown" }
                try ledger.put(alarm); try emit(call.route == "pause" ? "paused" : "resumed", id: id)
                return .ok(["alarm": alarm])
            case "challenge":
                var alarm = try ledger.get(id)
                guard var challenge = alarm["challenge"] as? [String: Any] else { throw AlarmFailure.invalid("This alarm has no challenge") }
                let progress = try AlarmValues.progressText(parameters["progress"] as? String ?? ""), target = try AlarmValues.number(challenge, "target")
                _ = try AlarmValues.checkedProgress(progress, target: target)
                challenge["progress"] = progress; alarm["challenge"] = challenge
                try ledger.put(alarm); try emit("challenge", id: id, details: ["progress": progress, "target": target])
                if progress >= target { alarm = try stop(id, emergency: false) }
                return .ok(["alarm": alarm])
            case "events": return .ok(["events": ledger.events()])
            case "events_ack":
                guard let bytes = (parameters["ids"] as? String)?.data(using: .utf8), let ids = try JSONSerialization.jsonObject(with: bytes) as? [String] else { throw AlarmFailure.invalid("ids must encode an array of event ids") }
                return .ok(["acknowledged": try ledger.acknowledge(ids)])
            default: return .error(code: "unknown_route", message: "Unknown alarm route")
            }
        } catch let error as AlarmFailure { return .error(code: error.code, message: error.localizedDescription) }
        catch { return .error(code: "alarm_error", message: error.localizedDescription) }
    }
    private func permissions() async -> [String: Any] {
        let notifications = await UNUserNotificationCenter.current().notificationSettings()
        var answer: [String: Any] = ["notifications": String(describing: notifications.authorizationStatus), "pending": isRequesting]
        if #available(iOS 26.0, *) { answer["system"] = String(describing: AlarmManager.shared.authorizationState) }
        else { answer["system"] = "unsupported" }
        return answer
    }
    private func emit(_ type: String, id: String, details: [String: Any] = [:]) throws {
        guard let ledger = ledger else { throw AlarmFailure.state("Alarm storage unavailable") }
        context.emit("changed", try ledger.event(type, id: id, details: details))
    }
    @available(iOS 26.0, *)
    private func observe(_ nativeAlarms: [Alarm]) {
        guard let ledger = ledger else { return }
        let byId = Dictionary(uniqueKeysWithValues: nativeAlarms.map { ($0.id.uuidString.lowercased(), $0) })
        for var alarm in ledger.list() where (alarm["delivery"] as? String ?? "system") == "system" && alarm["enabled"] as? Bool == true {
            let id = alarm["id"] as! String
            guard !changingIds.contains(id) else { continue }
            let nativeId = alarm["native_id"] as? String ?? id
            let state = byId[nativeId].map { String(describing: $0.state) } ?? "ended"
            let previousForecast = alarm["next_at_ms"] as? Int64
            if state == "scheduled", let schedule = alarm["schedule"] as? [String: Any], schedule["hour"] != nil {
                do { alarm["next_at_ms"] = Int64(try AlarmValues.next(schedule, now: Date()).timeIntervalSince1970 * 1000) }
                catch { context.log("Cannot forecast next local alarm: " + error.localizedDescription) }
            }
            guard state != alarm["state"] as? String || previousForecast != alarm["next_at_ms"] as? Int64 else { continue }
            alarm["state"] = state
            if state == "alerting", var challenge = alarm["challenge"] as? [String: Any] { challenge["progress"] = 0; alarm["challenge"] = challenge }
            if state == "ended" { alarm["enabled"] = false }
            do { try ledger.put(alarm); try emit("state", id: id, details: ["state": state]) }
            catch { context.log("Cannot retain alarm state: " + error.localizedDescription) }
        }
    }
    private func replace(_ supplied: [String: Any], active: () -> Bool, overrideDate: Bool = false) async throws {
        guard let ledger = ledger else { throw AlarmFailure.state("Alarm storage unavailable") }
        var alarm = supplied
        let id = alarm["id"] as! String
        guard !changingIds.contains(id) else { throw AlarmFailure.state("This alarm is being changed") }
        let previous = ledger.list().first { $0["id"] as? String == id }
        changingIds.insert(id); defer { changingIds.remove(id) }
        let nativeId = UUID().uuidString.lowercased()
        alarm["native_id"] = nativeId
        let pending = previous.map(AlarmValues.nativeHandles) ?? []
        let handle = ["id": nativeId, "delivery": alarm["delivery"] as? String ?? "system"]
        let handles = alarm["enabled"] as? Bool == true ? [handle] : []
        alarm["native_handles"] = pending + handles
        do {
            try await arm(alarm, active: active, overrideDate: overrideDate)
            guard active() else { throw AlarmFailure.state("The bridge call ended") }
            try ledger.put(alarm)
        } catch {
            let original = error
            var candidate = alarm; candidate["native_handles"] = handles
            do { try cancelNative(candidate) }
            catch { context.log("Cannot cancel failed replacement: " + error.localizedDescription) }
            throw original
        }
        if let previous = previous {
            do { try cancelNative(previous) }
            catch {
                try emit("cleanup_required", id: id, details: ["message": error.localizedDescription])
                throw AlarmFailure.state("The new alarm is scheduled, but the previous native schedule could not be removed. Read the stored alarm and cancel it before retrying")
            }
        }
        alarm["native_handles"] = handles
        try ledger.put(alarm)
    }
    private func cancelNative(_ alarm: [String: Any]) throws {
        let handles = AlarmValues.nativeHandles(alarm)
        let center = UNUserNotificationCenter.current()
        for handle in handles where handle["delivery"] == "notification" {
            let id = handle["id"]!
            center.removePendingNotificationRequests(withIdentifiers: notificationIds(id))
            center.removeDeliveredNotifications(withIdentifiers: notificationIds(id))
        }
        let system = handles.filter { $0["delivery"] == "system" }
        guard !system.isEmpty else { return }
        guard #available(iOS 26.0, *) else { throw AlarmFailure.unsupported("System alarm cleanup requires iOS 26") }
        let scheduled = Set(try AlarmManager.shared.alarms.map(\.id))
        for handle in system {
            if let uuid = UUID(uuidString: handle["id"]!), scheduled.contains(uuid) { try AlarmManager.shared.cancel(id: uuid) }
        }
    }
    private func stop(_ id: String, emergency: Bool) throws -> [String: Any] {
        guard let ledger = ledger else { throw AlarmFailure.state("Alarm storage unavailable") }
        var alarm = try ledger.get(id)
        guard try (emergency || AlarmValues.complete(alarm)) else { throw AlarmFailure.state("challenge_incomplete") }
        if alarm["enabled"] as? Bool != true {
            alarm["state"] = "stopped"; try ledger.put(alarm)
            try emit(emergency ? "emergency_stopped" : "stopped", id: id)
            return alarm
        }
        if (alarm["delivery"] as? String ?? "system") == "notification" {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: notificationIds(alarm["native_id"] as? String ?? id))
            let days = try AlarmValues.days(alarm["schedule"] as? [String: Any] ?? [:])
            if days.isEmpty { try cancelNative(alarm); alarm["enabled"] = false; alarm["state"] = "stopped" }
            else { alarm["state"] = "scheduled" }
        } else if #available(iOS 26.0, *), let uuid = UUID(uuidString: alarm["native_id"] as? String ?? id) {
            try AlarmManager.shared.stop(id: uuid)
            if try AlarmValues.days(alarm["schedule"] as? [String: Any] ?? [:]).isEmpty { alarm["enabled"] = false; alarm["state"] = "stopped" }
            else { alarm["state"] = "scheduled" }
        } else { throw AlarmFailure.unsupported("System alarms require iOS 26") }
        if alarm["state"] as? String == "scheduled", let schedule = alarm["schedule"] as? [String: Any], schedule["hour"] != nil {
            alarm["next_at_ms"] = Int64(try AlarmValues.next(schedule, now: Date()).timeIntervalSince1970 * 1000)
        }
        try ledger.put(alarm); try emit(emergency ? "emergency_stopped" : "stopped", id: id)
        return alarm
    }
    private func arm(_ alarm: [String: Any], active: () -> Bool, overrideDate: Bool = false) async throws {
        guard active() else { throw AlarmFailure.state("The bridge call ended") }
        if alarm["enabled"] as? Bool != true { return }
        if (alarm["delivery"] as? String ?? "system") == "notification" { try await armNotification(alarm, active: active, overrideDate: overrideDate); return }
        guard #available(iOS 26.0, *) else { throw AlarmFailure.unsupported("System alarms require iOS 26. Set delivery to notification for notification reminders") }
        guard AlarmManager.shared.authorizationState == .authorized else { throw AlarmFailure.permission }
        guard let uuid = UUID(uuidString: alarm["native_id"] as? String ?? alarm["id"] as! String), let schedule = alarm["schedule"] as? [String: Any] else { throw AlarmFailure.invalid("Invalid alarm definition") }
        if let timezone = schedule["timezone"] as? String, timezone != TimeZone.current.identifier { throw AlarmFailure.unsupported("AlarmKit relative schedules use the device's local time zone") }
        let label = LocalizedStringResource(stringLiteral: alarm["label"] as? String ?? "Alarm")
        let stop = AlarmButton(text: LocalizedStringResource(stringLiteral: alarm["stop_label"] as? String ?? "Stop"), textColor: .white, systemImageName: "stop.circle")
        let open = AlarmButton(text: LocalizedStringResource(stringLiteral: alarm["open_label"] as? String ?? "Open app"), textColor: .white, systemImageName: "arrow.up.forward.app")
        let snooze = AlarmButton(text: LocalizedStringResource(stringLiteral: alarm["snooze_label"] as? String ?? "Snooze"), textColor: .white, systemImageName: "zzz")
        let hasChallenge = alarm["challenge"] != nil
        let alert = AlarmPresentation.Alert(title: label, stopButton: stop, secondaryButton: hasChallenge ? open : snooze, secondaryButtonBehavior: hasChallenge ? .custom : .countdown)
        let duration = schedule["countdown_seconds"] as? Double
        let presentation = AlarmPresentation(alert: alert,
            countdown: AlarmPresentation.Countdown(title: label, pauseButton: AlarmButton(text: "Pause", textColor: .white, systemImageName: "pause")),
            paused: AlarmPresentation.Paused(title: label, resumeButton: AlarmButton(text: "Resume", textColor: .white, systemImageName: "play")))
        let attributes = AlarmAttributes(presentation: presentation, metadata: DespiaAlarmMetadata(alarmId: alarm["id"] as! String, subtitle: alarm["body"] as? String ?? ""), tintColor: try color(alarm["color"] as? String ?? "#3164D9"))
        let postAlert = (alarm["snooze_seconds"] as? Double) ?? 300
        var nativeSchedule: Alarm.Schedule?
        if overrideDate || schedule["at_ms"] != nil { nativeSchedule = .fixed(Date(timeIntervalSince1970: (alarm["next_at_ms"] as! NSNumber).doubleValue / 1000)) }
        else if schedule["hour"] != nil {
            let days = try AlarmValues.days(schedule)
            let mapping: [Locale.Weekday] = [.monday, .tuesday, .wednesday, .thursday, .friday, .saturday, .sunday]
            nativeSchedule = .relative(.init(time: .init(hour: (schedule["hour"] as! NSNumber).intValue, minute: (schedule["minute"] as! NSNumber).intValue), repeats: days.isEmpty ? .never : .weekly(days.map { mapping[$0 - 1] })))
        }
        let soundName = alarm["sound"] as? String ?? "default"
        let sound: AlertConfiguration.AlertSound = soundName == "default" ? .default : .named(soundName)
        let configuration = AlarmManager.AlarmConfiguration(countdownDuration: Alarm.CountdownDuration(preAlert: overrideDate ? nil : duration, postAlert: postAlert), schedule: nativeSchedule,
            attributes: attributes, secondaryIntent: hasChallenge ? OpenDespiaAlarmIntent(alarmId: alarm["id"] as! String) : nil, sound: sound)
        _ = try await AlarmManager.shared.schedule(id: uuid, configuration: configuration)
    }
    @available(iOS 26.0, *)
    private func color(_ hex: String) throws -> Color {
        let text = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard text.count == 6, let rgb = UInt32(text, radix: 16) else { throw AlarmFailure.invalid("color must be #RRGGBB") }
        return Color(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255)
    }
    private func notificationIds(_ id: String) -> [String] { ["nativeext.alarms." + id] + (1...7).map { "nativeext.alarms." + id + "." + String($0) } }
    private func armNotification(_ alarm: [String: Any], active: () -> Bool, overrideDate: Bool) async throws {
        let center = UNUserNotificationCenter.current(), settings = await UNUserNotificationCenter.current().notificationSettings()
        guard active() else { throw AlarmFailure.state("The bridge call ended") }
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional || settings.authorizationStatus == .ephemeral else { throw AlarmFailure.permission }
        let id = alarm["native_id"] as? String ?? alarm["id"] as! String, schedule = alarm["schedule"] as! [String: Any]
        let content = UNMutableNotificationContent(); content.title = alarm["label"] as? String ?? "Alarm"; content.body = alarm["body"] as? String ?? ""
        content.userInfo = ["nativeext": context.extensionId, "alarm_id": alarm["id"] as! String]
        let soundName = alarm["sound"] as? String ?? "default"
        content.sound = soundName == "default" ? .default : UNNotificationSound(named: UNNotificationSoundName(rawValue: soundName))
        let days = try AlarmValues.days(schedule)
        center.removePendingNotificationRequests(withIdentifiers: notificationIds(id))
        var requests: [UNNotificationRequest] = []
        if !days.isEmpty {
            for day in Set(days) {
                var components = DateComponents(); components.hour = (schedule["hour"] as! NSNumber).intValue; components.minute = (schedule["minute"] as! NSNumber).intValue
                components.weekday = day % 7 + 1
                if let zone = schedule["timezone"] as? String { components.timeZone = TimeZone(identifier: zone) }
                requests.append(UNNotificationRequest(identifier: "nativeext.alarms." + id + "." + String(day), content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: true)))
            }
        }
        if days.isEmpty || overrideDate {
            let interval = (alarm["next_at_ms"] as! NSNumber).doubleValue / 1000 - Date().timeIntervalSince1970
            guard interval > 0 else { throw AlarmFailure.invalid("The reminder must be in the future") }
            requests.append(UNNotificationRequest(identifier: "nativeext.alarms." + id, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)))
        }
        do {
            for request in requests {
                guard active() else { throw AlarmFailure.state("The bridge call ended") }
                try await center.add(request)
            }
        } catch { center.removePendingNotificationRequests(withIdentifiers: notificationIds(id)); throw error }
    }
    func willPresentNotification(_ notification: UNNotification) -> UNNotificationPresentationOptions? { [.banner, .list, .sound] }
    func didReceiveNotificationResponse(_ response: UNNotificationResponse, completion: @escaping () -> Void) {
        defer { completion() }
        let id = response.notification.request.content.userInfo["alarm_id"] as? String ?? ""
        do { try emit("notification_opened", id: id) }
        catch { context.log("Cannot retain notification action: " + error.localizedDescription) }
    }
}
