import Depth3DHostCore
import Foundation
import SwiftUI

/// Design tokens aligned with `design-opus/ux-spec.md`, adapted to macOS desktop proportions.
enum HostTheme {
    static let bg = Color(red: 0x0E / 255.0, green: 0x0F / 255.0, blue: 0x12 / 255.0)
    static let surface = Color(red: 0x17 / 255.0, green: 0x19 / 255.0, blue: 0x1D / 255.0)
    static let surfaceHi = Color(red: 0x20 / 255.0, green: 0x23 / 255.0, blue: 0x28 / 255.0)
    static let sheet = Color(red: 0x1A / 255.0, green: 0x1C / 255.0, blue: 0x20 / 255.0)
    static let fill = Color.white.opacity(0.10)
    static let fillHi = Color.white.opacity(0.16)
    static let selected = Color.white.opacity(0.18)

    static let text = Color(red: 0xEC / 255.0, green: 0xEC / 255.0, blue: 0xEE / 255.0)
    static let text2 = Color(red: 0xA6 / 255.0, green: 0xA9 / 255.0, blue: 0xB0 / 255.0)
    static let text3 = Color(red: 0x7D / 255.0, green: 0x81 / 255.0, blue: 0x8A / 255.0)

    /// Warm amber: reserved for active / enabled states, streaming indicators, and progress.
    static let accent = Color(red: 0xE3 / 255.0, green: 0xA8 / 255.0, blue: 0x57 / 255.0)
    /// Soft coral red: errors, missing permissions, offline, port conflict.
    static let danger = Color(red: 0xEE / 255.0, green: 0x85 / 255.0, blue: 0x77 / 255.0)

    static let cardRadius: CGFloat = 12
    static let buttonRadius: CGFloat = 9
}

/// String Catalog lookup helper working across both Xcode `.app` bundles and SwiftPM builds.
enum L10n {
    private static let resourceBundle: Bundle = {
        #if SWIFT_PACKAGE
        return Bundle.module
        #else
        return Bundle.main
        #endif
    }()

    static func string(_ key: String) -> String {
        HostStrings.string(key)
    }

    static func format(_ key: String, _ args: CVarArg...) -> String {
        let fmt = HostStrings.string(key)
        return String(format: fmt, locale: Locale(identifier: "zh_Hans_CN"), arguments: args)
    }

    static func loadThirdPartyNotices() -> String {
        if let url = resourceBundle.url(forResource: "THIRD_PARTY_NOTICES", withExtension: "md"),
           let text = try? String(contentsOf: url, encoding: .utf8)
        {
            return text
        }
        return string("about.licenses.fallback")
    }
}

/// High-contrast off-white primary button (at most one per page, per ux-spec §0.1).
struct HostPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(HostTheme.bg)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: HostTheme.buttonRadius, style: .continuous)
                    .fill(configuration.isPressed ? Color.white : HostTheme.text)
            )
            .opacity(isEnabled ? 1.0 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

struct HostSecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .medium))
            .foregroundColor(HostTheme.text)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: HostTheme.buttonRadius, style: .continuous)
                    .fill(configuration.isPressed ? HostTheme.fillHi : HostTheme.fill)
            )
            .opacity(isEnabled ? 1.0 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

struct HostDangerButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .medium))
            .foregroundColor(HostTheme.danger)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: HostTheme.buttonRadius, style: .continuous)
                    .fill(HostTheme.danger.opacity(configuration.isPressed ? 0.22 : 0.13))
            )
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

struct HostCard<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: HostTheme.cardRadius, style: .continuous)
                    .fill(HostTheme.surface)
            )
    }
}

struct OfflineBannerView: View {
    let onOpenNetworkSettings: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(HostTheme.danger)
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.string("state.offline.title"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(HostTheme.text)
                Text(L10n.string("state.offline.message"))
                    .font(.system(size: 12))
                    .foregroundColor(HostTheme.text2)
            }
            Spacer()
            Button(L10n.string("state.offline.action")) {
                onOpenNetworkSettings()
            }
            .buttonStyle(HostSecondaryButtonStyle())
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: HostTheme.cardRadius, style: .continuous)
                .fill(HostTheme.danger.opacity(0.14))
        )
    }
}

struct InlineErrorBanner: View {
    let title: String
    let message: String
    var actionTitle: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 15))
                .foregroundColor(HostTheme.danger)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(HostTheme.text)
                Text(message)
                    .font(.system(size: 12))
                    .foregroundColor(HostTheme.text2)
            }
            Spacer()
            if let actionTitle, let onAction {
                Button(actionTitle, action: onAction)
                    .buttonStyle(HostSecondaryButtonStyle())
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: HostTheme.cardRadius, style: .continuous)
                .fill(HostTheme.danger.opacity(0.14))
        )
    }
}

struct LoadingCardView: View {
    let message: String

    var body: some View {
        HostCard {
            HStack(spacing: 12) {
                ProgressView()
                    .controlSize(.small)
                    .tint(HostTheme.accent)
                Text(message)
                    .font(.system(size: 13))
                    .foregroundColor(HostTheme.text2)
            }
            .padding(.vertical, 8)
        }
    }
}

struct EmptyStateCardView: View {
    let icon: String
    let title: String
    let message: String
    var actionTitle: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        HostCard {
            VStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 26, weight: .light))
                    .foregroundColor(HostTheme.text2)
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(HostTheme.text)
                Text(message)
                    .font(.system(size: 12))
                    .foregroundColor(HostTheme.text2)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 380)
                if let actionTitle, let onAction {
                    Button(actionTitle, action: onAction)
                        .buttonStyle(HostPrimaryButtonStyle())
                        .padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 18)
        }
    }
}

/// Three offset layered frames matching `design-opus/ux-spec.md` §3.7.
struct DepthLayersEmblem: View {
    var size: CGFloat = 72

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.14, style: .continuous)
                .fill(HostTheme.surfaceHi)
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.14, style: .continuous)
                        .stroke(HostTheme.text3.opacity(0.5), lineWidth: 1.5)
                )
                .frame(width: size * 0.72, height: size * 0.50)
                .offset(x: -size * 0.13, y: -size * 0.12)

            RoundedRectangle(cornerRadius: size * 0.14, style: .continuous)
                .fill(HostTheme.surface)
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.14, style: .continuous)
                        .stroke(HostTheme.text2.opacity(0.55), lineWidth: 1.5)
                )
                .frame(width: size * 0.74, height: size * 0.52)

            RoundedRectangle(cornerRadius: size * 0.14, style: .continuous)
                .fill(HostTheme.bg)
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.14, style: .continuous)
                        .stroke(HostTheme.accent, lineWidth: 2)
                )
                .frame(width: size * 0.74, height: size * 0.52)
                .offset(x: size * 0.13, y: size * 0.12)
        }
        .frame(width: size, height: size)
    }
}
