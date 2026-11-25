import Foundation

@MainActor
enum AlarmOpenJournal {
    static let notification = Notification.Name("DESPIA_NX_ALARMS_OPENED")
    private static func file() throws -> URL {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("nativeext/alarms", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("opened.json")
    }
    static func read() throws -> [[String: Any]] {
        let path = try file()
        if !FileManager.default.fileExists(atPath: path.path) { return [] }
        guard let events = try JSONSerialization.jsonObject(with: Data(contentsOf: path)) as? [[String: Any]] else { throw CocoaError(.fileReadCorruptFile) }
        return events
    }
    static func append(_ id: String) throws {
        guard UUID(uuidString: id) != nil else { throw NSError(domain: "DespiaAlarms", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid alarm id"]) }
        var events = try read()
        events.append(["id": UUID().uuidString.lowercased(), "type": "opened", "alarm_id": id.lowercased(), "at_ms": Int64(Date().timeIntervalSince1970 * 1000), "details": ["source": "native_intent"]])
        try JSONSerialization.data(withJSONObject: events, options: [.sortedKeys]).write(to: file(), options: .atomic)
        NotificationCenter.default.post(name: notification, object: nil)
    }
    static func acknowledge(_ ids: Set<String>) throws {
        let remaining = try read().filter { !ids.contains($0["id"] as? String ?? "") }
        try JSONSerialization.data(withJSONObject: remaining, options: [.sortedKeys]).write(to: file(), options: .atomic)
    }
}
