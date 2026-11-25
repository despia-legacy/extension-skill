import Foundation
import CoreFoundation
import EventKit
import EventKitUI
import UIKit
import CoreLocation

@MainActor
final class CalendarExtension: NSObject, NativeExtension, @MainActor EKEventEditViewDelegate, @MainActor EKEventViewDelegate {
    private let store = EKEventStore()
    private var context: NativeExtContext!
    private var observer: NSObjectProtocol?
    private var requesting = false
    private var editor: EKEventEditViewController?
    private var viewer: EKEventViewController?
    required override init() { super.init() }

    func didLaunch(_ context: NativeExtContext) {
        self.context = context
        observer = NotificationCenter.default.addObserver(forName: .EKEventStoreChanged, object: store, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.context.emit("changed", ["changed": true]) }
        }
    }
    func willTerminate() { if let observer = observer { NotificationCenter.default.removeObserver(observer) } }

    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        do {
            let p = call.params
            switch call.route {
            case "capabilities":
                return .ok(["provider": "eventkit", "events": true, "calendars": true, "recurrence": true,
                            "alarms": true, "native_editor": true, "ios_reminders": true,
                            "android_table": false, "google_cloud_api": false])
            case "permissions":
                if p["request"] as? Bool == true {
                    guard !requesting else { return .error(code: "busy", message: "A calendar permission request is pending") }
                    let access = p["access"] as? String ?? "full"
                    let key: String
                    if #available(iOS 17, *) {
                        key = access == "reminders" ? "NSRemindersFullAccessUsageDescription" : access == "write_only" ? "NSCalendarsWriteOnlyAccessUsageDescription" : "NSCalendarsFullAccessUsageDescription"
                    } else { key = access == "reminders" ? "NSRemindersUsageDescription" : "NSCalendarsUsageDescription" }
                    guard let text = Bundle.main.object(forInfoDictionaryKey: key) as? String, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                        return .error(code: "not_included", message: "Missing build permission description: " + key)
                    }
                    requesting = true
                    Task { @MainActor in
                        defer { requesting = false }
                        do {
                            if #available(iOS 17, *) {
                                if access == "reminders" { _ = try await store.requestFullAccessToReminders() }
                                else if access == "write_only" { _ = try await store.requestWriteOnlyAccessToEvents() }
                                else { _ = try await store.requestFullAccessToEvents() }
                            } else { _ = try await store.requestAccess(to: access == "reminders" ? .reminder : .event) }
                            requesting = false
                            context.emit("permission", permissions())
                        } catch {
                            requesting = false
                            var result = permissions()
                            result["error"] = "permission_request_failed"
                            context.emit("permission", result)
                        }
                    }
                    return .ok(["pending": true, "events": permission(.event), "reminders": permission(.reminder)])
                }
                return .ok(permissions())
            case "present": return try present(p)
            case "android_table": return .error(code: "unsupported", message: "android_table is Android only")
            case "ios_sources":
                guard permission(.event) == "full" || permission(.reminder) == "full" else { throw CalendarFailure.permission }
                return .ok(["sources": store.sources.map { ["id": $0.sourceIdentifier, "title": $0.title, "type": $0.sourceType.rawValue] }])
            case "ios_reminders", "ios_reminder_save", "ios_reminder_delete": return try await reminders(call)
            default: break
            }
            let entity: EKEntityType = p["entity"] as? String == "reminders" ? .reminder : .event
            if ["calendars", "calendar_save", "calendar_delete"].contains(call.route) && entity == .reminder {
                guard permission(.reminder) == "full" else { throw CalendarFailure.permission }
            } else if call.route == "event_save" { try requireWrite() } else { try requireRead() }
            switch call.route {
            case "calendars": return .ok(["calendars": store.calendars(for: entity).map(calendar)])
            case "calendar_save":
                let data = try CalendarValues.object(p["data"])
                let item: EKCalendar
                if let id = data["id"] as? String { guard let found = store.calendar(withIdentifier: id) else { throw CalendarFailure.notFound }; item = found }
                else {
                    item = EKCalendar(for: entity, eventStore: store)
                    if let sourceID = data["source_id"] as? String { item.source = store.sources.first { $0.sourceIdentifier == sourceID } }
                    else { item.source = (entity == .event ? store.defaultCalendarForNewEvents : store.defaultCalendarForNewReminders())?.source ?? store.sources.first { $0.sourceType == .local } }
                    guard item.source != nil else { throw CalendarFailure.invalid("Choose a writable source_id") }
                }
                guard let title = data["title"] as? String, !title.isEmpty else { throw CalendarFailure.invalid("Calendar title is required") }
                item.title = title
                try store.saveCalendar(item, commit: true)
                return .ok(calendar(item))
            case "calendar_delete":
                guard let item = store.calendar(withIdentifier: p["id"] as? String ?? "") else { throw CalendarFailure.notFound }
                try store.removeCalendar(item, commit: true)
                return .ok(["deleted": true])
            case "events":
                let start = try CalendarValues.date(p["start_ms"]), end = try CalendarValues.date(p["end_ms"])
                guard end > start else { throw CalendarFailure.invalid("end_ms must be after start_ms") }
                var calendars: [EKCalendar]?
                if let id = p["calendar_id"] as? String { guard let found = store.calendar(withIdentifier: id) else { throw CalendarFailure.notFound }; calendars = [found] }
                let items = store.events(matching: store.predicateForEvents(withStart: start, end: end, calendars: calendars))
                    .sorted { $0.startDate == $1.startDate ? ($0.eventIdentifier ?? "") < ($1.eventIdentifier ?? "") : $0.startDate < $1.startDate }
                let offset = p["offset"] as? Int ?? 0, limit = p["limit"] as? Int ?? 25
                let page = Array(items.dropFirst(offset).prefix(limit))
                return .ok(["events": page.map(event), "next_offset": offset + page.count, "has_more": offset + page.count < items.count])
            case "event_get": return .ok(event(try findEvent(p["id"])))
            case "event_save":
                let data = try CalendarValues.object(p["data"])
                let item = try makeEvent(data, occurrence: p["occurrence_ms"])
                try store.save(item, span: p["span"] as? String == "future" ? .futureEvents : .thisEvent, commit: true)
                return .ok(["id": item.eventIdentifier ?? "", "saved": true])
            case "event_delete":
                let item = try findEvent(p["id"], occurrence: p["occurrence_ms"])
                try store.remove(item, span: p["span"] as? String == "future" ? .futureEvents : .thisEvent, commit: true)
                return .ok(["deleted": true])
            default: return .error(code: "not_registered", message: call.route)
            }
        } catch let failure as CalendarFailure {
            switch failure {
            case .notFound: return .error(code: "not_found", message: "Calendar item not found")
            case .permission: return .error(code: "permission_denied", message: "Calendar access is not granted")
            case .invalid(let message): return .error(code: "bad_request", message: message)
            }
        } catch { return .error(code: "calendar_error", message: error.localizedDescription) }
    }

    private func permission(_ type: EKEntityType) -> String {
        let status = EKEventStore.authorizationStatus(for: type)
        if #available(iOS 17, *) { if status == .fullAccess { return "full" }; if status == .writeOnly { return "write_only" } }
        switch status { case .authorized: return "full"; case .denied: return "denied"; case .restricted: return "restricted"; default: return "not_determined" }
    }
    private func permissions() -> [String: Any] { ["events": permission(.event), "reminders": permission(.reminder), "pending": requesting] }
    private func requireRead() throws { guard permission(.event) == "full" else { throw CalendarFailure.permission } }
    private func requireWrite() throws { guard ["full", "write_only"].contains(permission(.event)) else { throw CalendarFailure.permission } }
    /// The event, or with `occurrence` (epoch ms) that occurrence of a recurring event. Without it EventKit returns the
    /// first occurrence, so "this" and "future" act from the series start.
    private func findEvent(_ raw: Any?, occurrence: Any? = nil) throws -> EKEvent {
        guard let id = raw as? String, let item = store.event(withIdentifier: id) else { throw CalendarFailure.notFound }
        guard occurrence != nil else { return item }
        let start = try CalendarValues.date(occurrence)
        let matches = store.events(matching: store.predicateForEvents(withStart: start, end: start.addingTimeInterval(1), calendars: [item.calendar]))
        guard let found = matches.first(where: { $0.eventIdentifier == id && $0.startDate == start }) else { throw CalendarFailure.notFound }
        return found
    }
    private func calendar(_ item: EKCalendar) -> [String: Any] {
        ["id": item.calendarIdentifier, "title": item.title, "writable": item.allowsContentModifications,
         "source_id": item.source.sourceIdentifier, "type": item.type.rawValue]
    }
    private func event(_ item: EKEvent) -> [String: Any] {
        var out: [String: Any] = ["id": item.eventIdentifier ?? "", "calendar_id": item.calendar.calendarIdentifier,
            "title": item.title ?? "", "start_ms": item.startDate.timeIntervalSince1970 * 1000,
            "end_ms": item.endDate.timeIntervalSince1970 * 1000, "all_day": item.isAllDay,
            "timezone": item.timeZone?.identifier ?? "", "location": item.location ?? "", "notes": item.notes ?? "",
            "url": item.url?.absoluteString ?? "", "alarms": (item.alarms ?? []).map(CalendarValues.alarm),
            "ios": ["availability": item.availability.rawValue, "status": item.status.rawValue,
                    "detached": item.isDetached, "external_id": item.calendarItemExternalIdentifier ?? "",
                    "attendees": (item.attendees ?? []).map { ["name": $0.name ?? "", "url": $0.url.absoluteString,
                        "status": $0.participantStatus.rawValue, "role": $0.participantRole.rawValue] }]]
        out["recurrence"] = (item.recurrenceRules ?? []).map { rule -> [String: Any] in
            var value: [String: Any] = ["frequency": rule.frequency.rawValue, "interval": rule.interval,
                "days_of_week": (rule.daysOfTheWeek ?? []).map { ["day": $0.dayOfTheWeek.rawValue, "week": $0.weekNumber] },
                "days_of_month": rule.daysOfTheMonth ?? [], "months_of_year": rule.monthsOfTheYear ?? [],
                "weeks_of_year": rule.weeksOfTheYear ?? [], "days_of_year": rule.daysOfTheYear ?? [], "set_positions": rule.setPositions ?? []]
            if let end = rule.recurrenceEnd { value["end_count"] = end.occurrenceCount; if let date = end.endDate { value["end_ms"] = date.timeIntervalSince1970 * 1000 } }
            return value
        }
        return out
    }
    private func makeEvent(_ data: [String: Any], occurrence: Any? = nil) throws -> EKEvent {
        let item = try data["id"] == nil ? EKEvent(eventStore: store) : findEvent(data["id"], occurrence: occurrence)
        if let id = data["calendar_id"] as? String { guard let cal = store.calendar(withIdentifier: id) else { throw CalendarFailure.notFound }; item.calendar = cal }
        if item.calendar == nil { item.calendar = store.defaultCalendarForNewEvents }
        guard let cal = item.calendar, cal.allowsContentModifications else { throw CalendarFailure.invalid("Choose a writable calendar") }
        if let title = data["title"] as? String { item.title = title }
        if data["start_ms"] != nil { item.startDate = try CalendarValues.date(data["start_ms"]) }
        if data["end_ms"] != nil { item.endDate = try CalendarValues.date(data["end_ms"]) }
        guard item.startDate != nil, item.endDate != nil, item.endDate > item.startDate else { throw CalendarFailure.invalid("Valid start_ms and end_ms are required") }
        if let value = data["all_day"] as? Bool { item.isAllDay = value }
        if let value = data["timezone"] as? String { guard let zone = TimeZone(identifier: value) else { throw CalendarFailure.invalid("Unknown timezone") }; item.timeZone = zone }
        if let value = data["location"] as? String { item.location = value }
        if let value = data["notes"] as? String { item.notes = value }
        if let value = data["url"] as? String { item.url = value.isEmpty ? nil : URL(string: value) }
        if let values = data["alarms"] as? [[String: Any]] { item.alarms = try values.map(CalendarValues.makeAlarm) }
        if let rules = data["recurrence"] as? [[String: Any]] { item.recurrenceRules = try rules.map(recurrence) }
        if let ios = data["ios"] as? [String: Any] {
            if let raw = ios["availability"] as? Int, let value = EKEventAvailability(rawValue: raw) { item.availability = value }
            if let raw = ios["structured_location"] as? [String: Any] {
                let location = EKStructuredLocation(title: raw["title"] as? String ?? "")
                location.geoLocation = CLLocation(latitude: try CalendarValues.number(raw["latitude"]), longitude: try CalendarValues.number(raw["longitude"]))
                location.radius = (raw["radius"] as? NSNumber)?.doubleValue ?? 0
                item.structuredLocation = location
            }
        }
        return item
    }
    private func recurrence(_ data: [String: Any]) throws -> EKRecurrenceRule {
        guard let raw = data["frequency"] as? Int, let frequency = EKRecurrenceFrequency(rawValue: raw),
              let interval = data["interval"] as? Int, interval > 0 else { throw CalendarFailure.invalid("Recurrence needs frequency0..3 and positive interval") }
        let end: EKRecurrenceEnd?
        if let count = data["end_count"] as? Int, count > 0 { end = EKRecurrenceEnd(occurrenceCount: count) }
        else if data["end_ms"] != nil { end = EKRecurrenceEnd(end: try CalendarValues.date(data["end_ms"])) }
        else { end = nil }
        let days = try (data["days_of_week"] as? [[String: Int]] ?? []).map { value -> EKRecurrenceDayOfWeek in
            guard let raw = value["day"], let day = EKWeekday(rawValue: raw) else { throw CalendarFailure.invalid("Weekday must be1..7") }
            return EKRecurrenceDayOfWeek(day, weekNumber: value["week"] ?? 0)
        }
        return EKRecurrenceRule(recurrenceWith: frequency, interval: interval, daysOfTheWeek: days.isEmpty ? nil : days,
            daysOfTheMonth: data["days_of_month"] as? [NSNumber], monthsOfTheYear: data["months_of_year"] as? [NSNumber],
            weeksOfTheYear: data["weeks_of_year"] as? [NSNumber], daysOfTheYear: data["days_of_year"] as? [NSNumber],
            setPositions: data["set_positions"] as? [NSNumber], end: end)
    }
    private func reminders(_ call: NativeExtCall) async throws -> NativeExtResult {
        guard permission(.reminder) == "full" else { throw CalendarFailure.permission }
        if call.route == "ios_reminders" {
            var calendars: [EKCalendar]?
            if let id = call.params["calendar_id"] as? String { guard let cal = store.calendar(withIdentifier: id) else { throw CalendarFailure.notFound }; calendars = [cal] }
            let predicate = store.predicateForReminders(in: calendars)
            let items: [EKReminder] = await withCheckedContinuation { continuation in store.fetchReminders(matching: predicate) { continuation.resume(returning: $0 ?? []) } }
            guard call.isActive else { return .error(code: "timeout", message: "Reminder query expired") }
            let completed = call.params["completed"] as? Bool
            let filtered = items.filter { completed == nil || $0.isCompleted == completed }.sorted { $0.calendarItemIdentifier < $1.calendarItemIdentifier }
            let offset = call.params["offset"] as? Int ?? 0, limit = call.params["limit"] as? Int ?? 25
            let page = Array(filtered.dropFirst(offset).prefix(limit))
            return .ok(["reminders": page.map { item -> [String: Any] in
                var value: [String: Any] = ["id": item.calendarItemIdentifier, "title": item.title ?? "", "calendar_id": item.calendar.calendarIdentifier,
                    "completed": item.isCompleted, "priority": item.priority, "notes": item.notes ?? "", "url": item.url?.absoluteString ?? "", "location": item.location ?? "",
                    "alarms": (item.alarms ?? []).map(CalendarValues.alarm)]
                if let due = item.dueDateComponents, let date = Calendar.current.date(from: due) { value["due_ms"] = date.timeIntervalSince1970 * 1000 }
                if let start = item.startDateComponents, let date = Calendar.current.date(from: start) { value["start_ms"] = date.timeIntervalSince1970 * 1000 }
                if let date = item.completionDate { value["completed_ms"] = date.timeIntervalSince1970 * 1000 }
                return value
            }, "next_offset": offset + page.count, "has_more": offset + page.count < filtered.count])
        }
        if call.route == "ios_reminder_delete" {
            guard let id = call.params["id"] as? String, let item = store.calendarItem(withIdentifier: id) as? EKReminder else { throw CalendarFailure.notFound }
            try store.remove(item, commit: true); return .ok(["deleted": true])
        }
        let data = try CalendarValues.object(call.params["data"])
        let item: EKReminder
        if let id = data["id"] as? String { guard let found = store.calendarItem(withIdentifier: id) as? EKReminder else { throw CalendarFailure.notFound }; item = found }
        else { item = EKReminder(eventStore: store); item.calendar = store.defaultCalendarForNewReminders() }
        if let id = data["calendar_id"] as? String { guard let cal = store.calendar(withIdentifier: id) else { throw CalendarFailure.notFound }; item.calendar = cal }
        if let title = data["title"] as? String { item.title = title }
        if let value = data["completed"] as? Bool { item.isCompleted = value }
        if let value = data["priority"] as? Int { item.priority = value }
        if let value = data["notes"] as? String { item.notes = value }
        if let value = data["url"] as? String { item.url = value.isEmpty ? nil : URL(string: value) }
        if let value = data["location"] as? String { item.location = value }
        if let zone = data["timezone"] as? String { guard let value = TimeZone(identifier: zone) else { throw CalendarFailure.invalid("Unknown timezone") }; item.timeZone = value }
        if data["start_ms"] != nil { item.startDateComponents = Calendar.current.dateComponents([.year,.month,.day,.hour,.minute], from: try CalendarValues.date(data["start_ms"])) }
        if data["completed_ms"] != nil { item.completionDate = try CalendarValues.date(data["completed_ms"]) }
        if data["due_ms"] != nil { item.dueDateComponents = Calendar.current.dateComponents([.year,.month,.day,.hour,.minute], from: try CalendarValues.date(data["due_ms"])) }
        if let values = data["alarms"] as? [[String: Any]] { item.alarms = try values.map(CalendarValues.makeAlarm) }
        if let values = data["recurrence"] as? [[String: Any]] { item.recurrenceRules = try values.map(recurrence) }
        try store.save(item, commit: true); return .ok(["id": item.calendarItemIdentifier, "saved": true])
    }
    private func present(_ p: [String: Any]) throws -> NativeExtResult {
        guard editor == nil && viewer == nil else { return .error(code: "busy", message: "Calendar editor is already open") }
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return .error(code: "unavailable", message: "No foreground presenter") }
        guard root.presentedViewController == nil else { return .error(code: "busy", message: "Another modal is open") }
        if p["mode"] as? String == "view" {
            try requireRead()
            let controller = EKEventViewController()
            controller.event = try findEvent(p["id"])
            controller.delegate = self
            controller.allowsEditing = true
            viewer = controller
            root.present(UINavigationController(rootViewController: controller), animated: true)
            return .ok(["presented": true])
        }
        if #unavailable(iOS 17) { try requireWrite() }
        let controller = EKEventEditViewController()
        controller.eventStore = store
        if p["id"] != nil { try requireRead(); controller.event = try findEvent(p["id"]) }
        else {
            // iOS17's out-of-process editor can create an event without calendar access.
            let data = try CalendarValues.object(p["data"] ?? "{}")
            if permission(.event) == "full" || permission(.event) == "write_only" { controller.event = try makeEvent(data) }
            else {
                let event = EKEvent(eventStore: store); event.title = data["title"] as? String ?? ""
                if data["start_ms"] != nil { event.startDate = try CalendarValues.date(data["start_ms"]) }
                if data["end_ms"] != nil { event.endDate = try CalendarValues.date(data["end_ms"]) }
                controller.event = event
            }
        }
        controller.editViewDelegate = self; editor = controller
        root.present(controller, animated: true)
        return .ok(["presented": true])
    }
    func eventViewController(_ controller: EKEventViewController, didCompleteWith action: EKEventViewAction) {
        controller.dismiss(animated: true); viewer = nil
        context.emit("editor", ["action": "dismissed"])
    }
    func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
        controller.dismiss(animated: true); editor = nil
        context.emit("editor", ["action": action == .saved ? "saved" : action == .deleted ? "deleted" : "canceled"])
    }
}

enum CalendarFailure: Error { case permission, notFound, invalid(String) }
enum CalendarValues {
    static func object(_ raw: Any?) throws -> [String: Any] {
        guard let text = raw as? String, let bytes = text.data(using: .utf8),
              let value = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw CalendarFailure.invalid("data must be a JSON object") }
        return value
    }
    static func number(_ raw: Any?) throws -> Double {
        guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else { throw CalendarFailure.invalid("Expected a finite number") }
        return number.doubleValue
    }
    static func date(_ raw: Any?) throws -> Date { Date(timeIntervalSince1970: try number(raw) / 1000) }
    static func alarm(_ alarm: EKAlarm) -> [String: Any] {
        var answer: [String: Any] = ["offset_seconds": alarm.relativeOffset]
        if alarm.absoluteDate == nil && alarm.structuredLocation == nil {
            answer["minutes"] = -alarm.relativeOffset / 60
            return answer
        }
        var native: [String: Any] = ["proximity": alarm.proximity.rawValue]
        if let date = alarm.absoluteDate { native["absolute_ms"] = date.timeIntervalSince1970 * 1000 }
        if let location = alarm.structuredLocation {
            var fields: [String: Any] = ["title": location.title ?? "", "radius": location.radius]
            if let point = location.geoLocation {
                fields["latitude"] = point.coordinate.latitude
                fields["longitude"] = point.coordinate.longitude
            }
            native["structured_location"] = fields
        }
        answer["ios"] = native
        return answer
    }
    static func makeAlarm(_ fields: [String: Any]) throws -> EKAlarm {
        let native = fields["ios"] as? [String: Any] ?? [:]
        let alarm: EKAlarm
        if native["absolute_ms"] != nil { alarm = EKAlarm(absoluteDate: try date(native["absolute_ms"])) }
        else if fields["minutes"] != nil { alarm = EKAlarm(relativeOffset: -(try number(fields["minutes"])) * 60) }
        else if native["structured_location"] != nil { alarm = EKAlarm(relativeOffset: try number(fields["offset_seconds"] ?? 0)) }
        else { throw CalendarFailure.invalid("An alarm needs minutes or an iOS trigger") }
        if let fields = native["structured_location"] as? [String: Any] {
            let location = EKStructuredLocation(title: fields["title"] as? String ?? "")
            if fields["latitude"] != nil || fields["longitude"] != nil {
                location.geoLocation = CLLocation(latitude: try number(fields["latitude"]), longitude: try number(fields["longitude"]))
            }
            if fields["radius"] != nil { location.radius = try number(fields["radius"]) }
            alarm.structuredLocation = location
        }
        if let raw = native["proximity"] as? Int {
            guard let proximity = EKAlarmProximity(rawValue: raw) else { throw CalendarFailure.invalid("Invalid iOS alarm proximity") }
            alarm.proximity = proximity
        }
        return alarm
    }
}
