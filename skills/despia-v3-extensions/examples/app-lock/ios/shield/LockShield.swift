import Foundation
import UIKit
import ManagedSettings
import ManagedSettingsUI

final class LockShield: ShieldConfigurationDataSource {
    override func configuration(shielding application: Application) -> ShieldConfiguration { configuration(application: application.token) }
    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration { configuration(application: application.token, category: category.token) }
    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration { configuration(domain: webDomain.token) }
    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration { configuration(category: category.token, domain: webDomain.token) }
    private func configuration(application: ApplicationToken? = nil, category: ActivityCategoryToken? = nil, domain: WebDomainToken? = nil) -> ShieldConfiguration {
        let style: [String: String]
        do { style = try LockStore().style(application: application, category: category, domain: domain) }
        catch { NSLog("[despia:applock] Shield style unavailable: %@", error.localizedDescription); style = [:] }
        let secondaryTitle: String
        if #available(iOS 26.5, *) { secondaryTitle = "Open focus app" } else { secondaryTitle = "Keep focusing" }
        return ShieldConfiguration(backgroundBlurStyle: .systemMaterial, backgroundColor: color(style["background_color"], fallback: .systemBackground), icon: UIImage(systemName: style["symbol"] ?? "lock.fill"),
            title: .init(text: style["title"] ?? "Time to focus", color: color(style["text_color"], fallback: .label)),
            subtitle: .init(text: style["message"] ?? "This selection is blocked by your focus group.", color: .secondaryLabel),
            primaryButtonLabel: .init(text: style["button"] ?? "Close", color: .white), primaryButtonBackgroundColor: color(style["accent_color"], fallback: .systemBlue),
            secondaryButtonLabel: .init(text: style["secondary_button"] ?? secondaryTitle, color: .label))
    }
    private func color(_ text: String?, fallback: UIColor) -> UIColor {
        guard let text, text.count == 7, text.hasPrefix("#"), let rgb = UInt32(text.dropFirst(), radix: 16) else { return fallback }
        return UIColor(red: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255, blue: CGFloat(rgb & 255) / 255, alpha: 1)
    }
}
