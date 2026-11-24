import Foundation
import FamilyControls
import ManagedSettings
import DeviceActivity

struct LockGroup: Codable {
    var id: String
    var name: String
    var domains: [String] = []
    var selection: FamilyActivitySelection = .init()
    var enabled = false
    var shield: [String: String] = [:]
}

struct LockSchedule: Codable {
    var id: String
    var group_id: String
    var start_hour: Int
    var start_minute: Int
    var end_hour: Int
    var end_minute: Int
    var weekdays: [Int]
    var time_zone: String
    var threshold_minutes: Int?
    var includes_past_activity: Bool = false
    var enabled = true
    var active = false
    var threshold_reached = false
    func isActive(at date: Date) -> Bool {
        guard let zone = TimeZone(identifier: time_zone) else { return false }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        let parts = calendar.dateComponents([.weekday, .hour, .minute], from: date)
        guard let day = parts.weekday, let hour = parts.hour, let minute = parts.minute else { return false }
        let current = hour * 60 + minute, start = start_hour * 60 + start_minute, end = end_hour * 60 + end_minute
        if end > start { return weekdays.contains(day) && current >= start && current < end }
        return weekdays.contains(day) && current >= start || weekdays.contains(day == 1 ? 7 : day - 1) && current < end
    }
}

struct LockEvent: Codable {
    var id: String = UUID().uuidString
    var timestamp_ms: Double = Date().timeIntervalSince1970 * 1000
    var kind: String
    var group_id: String?
    var schedule_id: String?
    var detail: String?
}

struct LockState: Codable {
    var groups: [LockGroup] = []
    var schedules: [LockSchedule] = []
    var events: [LockEvent] = []
    mutating func record(_ kind: String, group: String? = nil, schedule: String? = nil, detail: String? = nil) {
        events.append(LockEvent(kind: kind, group_id: group, schedule_id: schedule, detail: detail))
    }
    func activeGroups() -> [LockGroup] {
        let scheduled = Set(schedules.filter { $0.enabled && $0.isActive(at: Date()) && ($0.threshold_minutes == nil || $0.threshold_reached) }.map(\.group_id))
        return groups.filter { $0.enabled || scheduled.contains($0.id) }
    }
}


final class LockStore {
    let directory: URL
    private let document: URL
    init() throws {
        // The build publishes the logical App Group names as DESPIA_NX_APP_GROUPS in every bundle of the extension,
        // and as the legacy NXAppGroups key when the bundle does not declare that key itself.
        let names = (Bundle.main.object(forInfoDictionaryKey: "DESPIA_NX_APP_GROUPS") as? [String: String])
            ?? (Bundle.main.object(forInfoDictionaryKey: "NXAppGroups") as? [String: String])
        guard let name = names?["applock"],
              let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: name) else {
            throw LockFailure.unavailable("The applock App Group is not provisioned")
        }
        self.directory = directory
        document = directory.appendingPathComponent("despia-nx-applock.json")
    }
    func read() throws -> LockState {
        var state = LockState()
        var failure: Error?
        var coordination: NSError?
        NSFileCoordinator().coordinate(readingItemAt: document, options: [], error: &coordination) { url in
            do { if FileManager.default.fileExists(atPath: url.path) { state = try JSONDecoder().decode(LockState.self, from: Data(contentsOf: url)) } }
            catch { failure = error }
        }
        if let coordination { throw coordination }
        if let failure { throw failure }
        return state
    }
    @discardableResult func change<T>(_ operation: (inout LockState) throws -> T) throws -> T {
        var answer: T?
        var failure: Error?
        var coordination: NSError?
        NSFileCoordinator().coordinate(writingItemAt: document, options: .forMerging, error: &coordination) { url in
            do {
                var state = FileManager.default.fileExists(atPath: url.path) ? try JSONDecoder().decode(LockState.self, from: Data(contentsOf: url)) : LockState()
                answer = try operation(&state)
                try JSONEncoder().encode(state).write(to: url, options: .atomic)
            } catch { failure = error }
        }
        if let coordination { throw coordination }
        if let failure { throw failure }
        guard let answer else { throw LockFailure.unavailable("App Lock storage did not finish") }
        return answer
    }
    func reconcile() throws {
        var failure: Error?
        var coordination: NSError?
        NSFileCoordinator().coordinate(readingItemAt: document, options: [], error: &coordination) { url in
            do {
                let state = FileManager.default.fileExists(atPath: url.path) ? try JSONDecoder().decode(LockState.self, from: Data(contentsOf: url)) : LockState()
                try apply(state.activeGroups())
            } catch { failure = error }
        }
        if let coordination { throw coordination }
        if let failure { throw failure }
    }
    private func apply(_ groups: [LockGroup]) throws {
        try LockRules.checkDomainUnion(groups.map(\.domains))
        let store = ManagedSettingsStore(named: .init("despia.nx.applock"))
        let applications = groups.reduce(into: Set<ApplicationToken>()) { $0.formUnion($1.selection.applicationTokens) }
        let categories = groups.reduce(into: Set<ActivityCategoryToken>()) { $0.formUnion($1.selection.categoryTokens) }
        let websites = groups.reduce(into: Set<WebDomainToken>()) { $0.formUnion($1.selection.webDomainTokens) }
        let domains = Set(groups.flatMap(\.domains).map { WebDomain(domain: $0) })
        store.shield.applications = applications.isEmpty ? nil : applications
        store.shield.applicationCategories = categories.isEmpty ? nil : .specific(categories)
        store.shield.webDomainCategories = categories.isEmpty ? nil : .specific(categories)
        store.shield.webDomains = websites.isEmpty ? nil : websites
        store.webContent.blockedByFilter = domains.isEmpty ? nil : .specific(domains)
    }
    func style(application: ApplicationToken? = nil, category: ActivityCategoryToken? = nil, domain: WebDomainToken? = nil) throws -> [String: String] {
        let groups = try read().activeGroups()
        return groups.first(where: { group in
            if let application, group.selection.applicationTokens.contains(application) { return true }
            if let category, group.selection.categoryTokens.contains(category) { return true }
            if let domain, group.selection.webDomainTokens.contains(domain) { return true }
            return false
        })?.shield ?? groups.first?.shield ?? [:]
    }
    static func validateEffectiveDomains(_ groups: [LockGroup]) throws { try LockRules.checkDomainUnion(groups.map(\.domains)) }
    static func validate(_ schedule: LockSchedule) throws {
        _ = try LockRules.identifier(schedule.id); _ = try LockRules.identifier(schedule.group_id)
        guard (0...23).contains(schedule.start_hour), (0...23).contains(schedule.end_hour),
              (0...59).contains(schedule.start_minute), (0...59).contains(schedule.end_minute),
              !schedule.weekdays.isEmpty, schedule.weekdays.allSatisfy({ (1...7).contains($0) }), TimeZone(identifier: schedule.time_zone) != nil,
              schedule.start_hour != schedule.end_hour || schedule.start_minute != schedule.end_minute else { throw LockFailure.invalid("Invalid local-time schedule") }
        if let threshold = schedule.threshold_minutes, threshold < 1 { throw LockFailure.invalid("threshold_minutes must be positive") }
    }
    static func activityNames(_ id: String) -> [DeviceActivityName] { (1...7).map { DeviceActivityName("despia.nx.applock.\(id).\($0)") } }
    static func monitor(_ schedule: LockSchedule, selection: FamilyActivitySelection) throws {
        let center = DeviceActivityCenter()
        center.stopMonitoring(activityNames(schedule.id))
        guard schedule.enabled else { return }
        var installed: [DeviceActivityName] = []
        do {
            for day in Set(schedule.weekdays).sorted() {
                var start = DateComponents(); start.timeZone = TimeZone(identifier: schedule.time_zone); start.weekday = day; start.hour = schedule.start_hour; start.minute = schedule.start_minute
                var end = DateComponents(); end.timeZone = start.timeZone
                let overnight = schedule.end_hour * 60 + schedule.end_minute < schedule.start_hour * 60 + schedule.start_minute
                end.weekday = overnight ? day % 7 + 1 : day; end.hour = schedule.end_hour; end.minute = schedule.end_minute
                let name = DeviceActivityName("despia.nx.applock.\(schedule.id).\(day)")
                var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
                if let minutes = schedule.threshold_minutes {
                    let threshold = DateComponents(minute: minutes)
                    if #available(iOS 17.4, *) {
                        events[.init("threshold")] = DeviceActivityEvent(applications: selection.applicationTokens, categories: selection.categoryTokens, webDomains: selection.webDomainTokens, threshold: threshold, includesPastActivity: schedule.includes_past_activity)
                    } else {
                        guard !schedule.includes_past_activity else { throw LockFailure.unavailable("includes_past_activity requires iOS 17.4") }
                        events[.init("threshold")] = DeviceActivityEvent(applications: selection.applicationTokens, categories: selection.categoryTokens, webDomains: selection.webDomainTokens, threshold: threshold)
                    }
                }
                try center.startMonitoring(name, during: DeviceActivitySchedule(intervalStart: start, intervalEnd: end, repeats: true), events: events)
                installed.append(name)
            }
        } catch { center.stopMonitoring(installed); throw error }
    }
    static func scheduleID(_ activity: DeviceActivityName) -> String? {
        let prefix = "despia.nx.applock."
        guard activity.rawValue.hasPrefix(prefix), let dot = activity.rawValue.lastIndex(of: ".") else { return nil }
        return String(activity.rawValue[activity.rawValue.index(activity.rawValue.startIndex, offsetBy: prefix.count)..<dot])
    }
}
