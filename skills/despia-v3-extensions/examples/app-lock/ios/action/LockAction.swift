import Foundation
import ManagedSettings

final class LockAction: ShieldActionDelegate {
    override func handle(action: ShieldAction, for application: ApplicationToken, completionHandler: @escaping (ShieldActionResponse) -> Void) { handle(action, kind: "application", completion: completionHandler) }
    override func handle(action: ShieldAction, for category: ActivityCategoryToken, completionHandler: @escaping (ShieldActionResponse) -> Void) { handle(action, kind: "category", completion: completionHandler) }
    override func handle(action: ShieldAction, for webDomain: WebDomainToken, completionHandler: @escaping (ShieldActionResponse) -> Void) { handle(action, kind: "web_domain", completion: completionHandler) }
    private func handle(_ action: ShieldAction, kind: String, completion: (ShieldActionResponse) -> Void) {
        do { try LockStore().change { $0.record("shield_action", detail: "\(kind):\(action.rawValue)") } }
        catch { NSLog("[despia:applock] Shield action could not be stored: %@", error.localizedDescription) }
        if action == .secondaryButtonPressed {
            if #available(iOS 26.5, *) { completion(.openParentalControlsApp) } else { completion(.defer) }
        } else { completion(.close) }
    }
}
