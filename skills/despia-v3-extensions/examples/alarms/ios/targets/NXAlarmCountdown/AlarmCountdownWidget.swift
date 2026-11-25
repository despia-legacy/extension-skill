import ActivityKit
import AlarmKit
import AppIntents
import SwiftUI
import WidgetKit

@available(iOSApplicationExtension 26.0, *)
struct AlarmCountdownWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AlarmAttributes<DespiaAlarmMetadata>.self) { context in
            VStack(alignment: .leading, spacing: 8) {
                Text(context.attributes.presentation.alert.title).font(.headline)
                Text(context.attributes.metadata?.subtitle ?? "")
                countdown(context.state)
                HStack {
                    if let id = context.attributes.metadata?.alarmId {
                        Button(intent: OpenDespiaAlarmIntent(alarmId: id)) {
                            Label("Open alarm", systemImage: "alarm")
                        }
                    }
                }
            }.padding().activityBackgroundTint(context.attributes.tintColor.opacity(0.15))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Image(systemName: "alarm") }
                DynamicIslandExpandedRegion(.trailing) { countdown(context.state) }
                DynamicIslandExpandedRegion(.bottom) { Text(context.attributes.presentation.alert.title) }
            } compactLeading: { Image(systemName: "alarm") }
              compactTrailing: { countdown(context.state) }
              minimal: { Image(systemName: "alarm") }
        }
    }
    @ViewBuilder private func countdown(_ state: AlarmPresentationState) -> some View {
        switch state.mode {
        case .countdown(let timer): Text(timerInterval: timer.startDate...timer.fireDate, countsDown: true).monospacedDigit()
        case .paused: Text("Paused")
        case .alert: Text("Alarm")
        @unknown default: Text("Alarm")
        }
    }
}

@main
@available(iOSApplicationExtension 26.0, *)
struct DespiaAlarmWidgetBundle: WidgetBundle {
    var body: some Widget { AlarmCountdownWidget() }
}
