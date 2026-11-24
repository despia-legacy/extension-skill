import Foundation
import CoreFoundation

enum AlarmFailure: LocalizedError {
    case invalid(String)
    case permission
    case unsupported(String)
    case state(String)
    case missing
    var errorDescription: String? {
        switch self {
        case .invalid(let text), .unsupported(let text), .state(let text): return text
        case .permission: return "Authorize the requested alarm delivery mode first"
        case .missing: return "Alarm not found"
        }
    }
    var code: String {
        switch self {
        case .invalid: return "invalid_alarm"
        case .unsupported: return "unsupported"
        case .permission: return "permission_required"
        case .missing: return "not_found"
        case .state(let text): return text == "challenge_incomplete" ? text : "invalid_state"
        }
    }
}

enum AlarmValues {
    static func object(_ source: String) throws -> [String: Any] {
        guard let bytes = source.data(using: .utf8), let alarm = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw AlarmFailure.invalid("Expected a JSON object") }
        return alarm
    }
    static func number(_ object: [String: Any], _ key: String) throws -> Double {
        guard let number = object[key] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else { throw AlarmFailure.invalid(key + " must be a finite number") }
        return number.doubleValue
    }
    static func parse(_ source: String, now: Date = Date()) throws -> [String: Any] {
        var alarm = try object(source)
        let id: UUID
        if let supplied = alarm["id"] {
            guard let text = supplied as? String, let uuid = UUID(uuidString: text) else { throw AlarmFailure.invalid("id must be a UUID") }
            id = uuid
        } else { id = UUID() }
        guard let schedule = alarm["schedule"] as? [String: Any] else { throw AlarmFailure.invalid("schedule is required") }
        guard ["at_ms", "countdown_seconds", "hour"].filter({ schedule[$0] != nil }).count == 1 else { throw AlarmFailure.invalid("Choose at_ms, countdown_seconds or local hour and minute") }
        if schedule["weekdays"] != nil && schedule["hour"] == nil { throw AlarmFailure.invalid("weekdays belongs to a local hour/minute schedule") }
        let next = try next(schedule, now: now)
        guard next > now else { throw AlarmFailure.invalid("The alarm must be in the future") }
        if var challenge = alarm["challenge"] as? [String: Any] {
            guard let kind = challenge["kind"] as? String, !kind.isEmpty else { throw AlarmFailure.invalid("challenge.kind is required") }
            let target = try number(challenge, "target")
            let progress = challenge["progress"] == nil ? 0 : try number(challenge, "progress")
            _ = try checkedProgress(progress, target: target)
            challenge["progress"] = progress; alarm["challenge"] = challenge
        } else if alarm["challenge"] != nil { throw AlarmFailure.invalid("challenge must be an object") }
        if alarm["snooze_seconds"] != nil, try number(alarm, "snooze_seconds") <= 0 { throw AlarmFailure.invalid("snooze_seconds must be positive") }
        if let delivery = alarm["delivery"] as? String, !["system", "notification"].contains(delivery) { throw AlarmFailure.invalid("delivery must be system or notification") }
        for flag in ["enabled", "vibrate", "full_screen", "loop"] where alarm[flag] != nil {
            guard let supplied = alarm[flag] as? NSNumber, CFGetTypeID(supplied) == CFBooleanGetTypeID() else { throw AlarmFailure.invalid(flag + " must be a boolean") }
        }
        for field in ["label", "body", "sound", "group", "delivery", "color", "stop_label", "snooze_label", "open_label", "emergency_label"] where alarm[field] != nil {
            guard alarm[field] is String else { throw AlarmFailure.invalid(field + " must be a string") }
        }
        alarm["id"] = id.uuidString.lowercased(); alarm["label"] = alarm["label"] as? String ?? "Alarm"
        alarm["enabled"] = alarm["enabled"] as? Bool ?? true
        alarm["state"] = alarm["enabled"] as? Bool == true ? "scheduled" : "disabled"
        alarm["next_at_ms"] = Int64(next.timeIntervalSince1970 * 1000)
        return alarm
    }
    static func next(_ schedule: [String: Any], now: Date) throws -> Date {
        if schedule["at_ms"] != nil {
            let millis = try number(schedule, "at_ms")
            guard millis.rounded() == millis, millis >= 0, millis <= 9_007_199_254_740_991 else { throw AlarmFailure.invalid("at_ms must be an integer timestamp") }
            return Date(timeIntervalSince1970: millis / 1000)
        }
        if schedule["countdown_seconds"] != nil {
            let seconds = try number(schedule, "countdown_seconds")
            guard seconds > 0, seconds <= (9_007_199_254_740_991 - now.timeIntervalSince1970 * 1000) / 1000 else { throw AlarmFailure.invalid("countdown_seconds is out of range") }
            return now.addingTimeInterval(seconds)
        }
        let hour = try number(schedule, "hour"), minute = try number(schedule, "minute")
        guard hour.rounded() == hour, minute.rounded() == minute, (0...23).contains(hour), (0...59).contains(minute) else { throw AlarmFailure.invalid("Invalid local time") }
        if let supplied = schedule["timezone"], !(supplied is String) { throw AlarmFailure.invalid("timezone must be a string") }
        let weekdays = try days(schedule)
        var calendar = Calendar(identifier: .gregorian)
        if let zone = schedule["timezone"] as? String {
            guard let timezone = TimeZone(identifier: zone) else { throw AlarmFailure.invalid("Unknown timezone") }
            calendar.timeZone = timezone
        }
        var matches: [Date] = []
        for iso in weekdays.isEmpty ? Array(1...7) : weekdays {
            let weekday = iso % 7 + 1
            let components = DateComponents(hour: Int(hour), minute: Int(minute), second: 0, weekday: weekday)
            if let date = calendar.nextDate(after: now, matching: components, matchingPolicy: .nextTimePreservingSmallerComponents, repeatedTimePolicy: .first, direction: .forward) { matches.append(date) }
        }
        guard let earliest = matches.min() else { throw AlarmFailure.invalid("No next occurrence") }
        return earliest
    }
    static func days(_ schedule: [String: Any]) throws -> [Int] {
        guard let supplied = schedule["weekdays"] else { return [] }
        guard let raw = supplied as? [Any] else { throw AlarmFailure.invalid("weekdays must be an array") }
        return try raw.map { raw in
            guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.rounded() == number.doubleValue, (1...7).contains(number.intValue) else { throw AlarmFailure.invalid("weekdays uses ISO Monday=1 through Sunday=7") }
            return number.intValue
        }
    }
    static func progressText(_ text: String) throws -> Double {
        let pattern = "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$"
        guard text.range(of: pattern, options: .regularExpression) != nil, let progress = Double(text), progress.isFinite else { throw AlarmFailure.invalid("progress must be finite decimal numeric text") }
        return progress
    }
    static func checkedProgress(_ progress: Double, target: Double) throws -> Double {
        guard target.isFinite, target > 0, progress.isFinite, progress >= 0, progress <= target else { throw AlarmFailure.invalid("progress must be between zero and target") }
        return progress
    }
    static func nativeHandles(_ alarm: [String: Any]) -> [[String: String]] {
        if let handles = alarm["native_handles"] as? [[String: String]] { return handles }
        guard alarm["enabled"] as? Bool == true, let id = alarm["native_id"] as? String ?? alarm["id"] as? String else { return [] }
        return [["id": id, "delivery": alarm["delivery"] as? String ?? "system"]]
    }
    static func complete(_ alarm: [String: Any]) throws -> Bool {
        guard let challenge = alarm["challenge"] as? [String: Any] else { return true }
        return try number(challenge, "progress") >= number(challenge, "target")
    }
}
