import Foundation

@MainActor
final class AlarmLedger {
    private let file: URL
    private var alarms: [String: [String: Any]] = [:]
    private var receipts: [[String: Any]] = []
    init(directory: URL) throws {
        file = directory.appendingPathComponent("alarms.json")
        if FileManager.default.fileExists(atPath: file.path) {
            let stored = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
            guard let stored = stored, let alarms = stored["alarms"] as? [String: [String: Any]], let receipts = stored["events"] as? [[String: Any]] else { throw AlarmFailure.invalid("Invalid alarm storage") }
            self.alarms = alarms; self.receipts = receipts
        }
    }
    func get(_ id: String) throws -> [String: Any] { guard let alarm = alarms[id] else { throw AlarmFailure.missing }; return alarm }
    func list(group: String? = nil) -> [[String: Any]] { alarms.values.filter { group == nil || $0["group"] as? String == group }.sorted { ($0["id"] as? String ?? "") < ($1["id"] as? String ?? "") } }
    func put(_ alarm: [String: Any]) throws {
        guard let id = alarm["id"] as? String else { throw AlarmFailure.invalid("Missing alarm id") }
        let previous = alarms[id]; alarms[id] = alarm
        do { try persist() } catch { alarms[id] = previous; throw error }
    }
    func remove(_ id: String) throws {
        let previous = alarms.removeValue(forKey: id)
        do { try persist() } catch { alarms[id] = previous; throw error }
    }
    @discardableResult func event(_ type: String, id: String, details: [String: Any] = [:]) throws -> [String: Any] {
        let receipt: [String: Any] = ["id": UUID().uuidString.lowercased(), "type": type, "alarm_id": id, "at_ms": Int64(Date().timeIntervalSince1970 * 1000), "details": details]
        receipts.append(receipt)
        do { try persist() } catch { receipts.removeLast(); throw error }
        return receipt
    }
    func importEvent(_ receipt: [String: Any]) throws -> Bool {
        guard let id = receipt["id"] as? String else { throw AlarmFailure.invalid("Missing event id") }
        if receipts.contains(where: { $0["id"] as? String == id }) { return false }
        receipts.append(receipt)
        do { try persist() } catch { receipts.removeLast(); throw error }
        return true
    }
    func events() -> [[String: Any]] { receipts }
    func acknowledge(_ ids: [String]) throws -> Int {
        let previous = receipts, selected = Set(ids)
        receipts.removeAll { selected.contains($0["id"] as? String ?? "") }
        do { try persist() } catch { receipts = previous; throw error }
        return previous.count - receipts.count
    }
    private func persist() throws {
        try JSONSerialization.data(withJSONObject: ["alarms": alarms, "events": receipts], options: [.sortedKeys]).write(to: file, options: .atomic)
    }
}
