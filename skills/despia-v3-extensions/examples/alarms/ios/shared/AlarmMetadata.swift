import AlarmKit
import AppIntents
import Foundation

@available(iOS 26.0, *)
struct DespiaAlarmMetadata: AlarmMetadata {
    var alarmId: String
    var subtitle: String
}

@available(iOS 26.0, *)
struct OpenDespiaAlarmIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Open alarm"
    static var openAppWhenRun: Bool = true
    @Parameter(title: "Alarm identifier") var alarmId: String
    init() {}
    init(alarmId: String) { self.alarmId = alarmId }
    func perform() async throws -> some IntentResult {
        try await AlarmOpenJournal.append(alarmId)
        return .result()
    }
}
