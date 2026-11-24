import Depth3DHostCore
import SwiftUI

struct OnboardingView: View {
    @ObservedObject var runtime: HostRuntime
    @State private var step: Int = 0

    var body: some View {
        VStack(spacing: 0) {
            // Step indicator
            HStack(spacing: 8) {
                ForEach(0..<3, id: \.self) { idx in
                    Capsule()
                        .fill(idx <= step ? HostTheme.accent : HostTheme.fill)
                        .frame(width: idx == step ? 28 : 12, height: 5)
                        .animation(.easeOut(duration: 0.2), value: step)
                }
            }
            .padding(.top, 24)

            if !runtime.network.isConnected {
                OfflineBannerView(onOpenNetworkSettings: runtime.openNetworkSettings)
                    .padding(.horizontal, 36)
                    .padding(.top, 16)
            }

            Spacer()

            Group {
                switch step {
                case 0:
                    welcomeStep
                case 1:
                    permissionsStep
                default:
                    readyStep
                }
            }
            .padding(.horizontal, 40)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(HostTheme.bg)
        .onChange(of: runtime.permissions) { _, newValue in
            // Auto-advance as soon as all three permissions are granted.
            if step == 1 && newValue.allGranted {
                withAnimation(.easeOut(duration: 0.2)) {
                    step = 2
                }
            }
        }
    }

    private var welcomeStep: some View {
        VStack(spacing: 18) {
            DepthLayersEmblem(size: 84)
            Text(L10n.string("onboarding.welcome.title"))
                .font(.system(size: 26, weight: .semibold))
                .foregroundColor(HostTheme.text)
            Text(L10n.string("onboarding.welcome.subtitle"))
                .font(.system(size: 14))
                .foregroundColor(HostTheme.text2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 440)
            Button(L10n.string("onboarding.welcome.start")) {
                withAnimation(.easeOut(duration: 0.2)) {
                    step = runtime.permissions.allGranted ? 2 : 1
                }
            }
            .buttonStyle(HostPrimaryButtonStyle())
            .padding(.top, 8)
        }
    }

    private var permissionsStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text(L10n.string("onboarding.permissions.title"))
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(HostTheme.text)
                Text(L10n.string("onboarding.permissions.subtitle"))
                    .font(.system(size: 13))
                    .foregroundColor(HostTheme.text2)
            }

            VStack(spacing: 10) {
                PermissionRowCard(
                    title: L10n.string("perm.screen.title"),
                    description: L10n.string("perm.screen.desc"),
                    state: runtime.permissions.screenRecording,
                    actionTitle: L10n.string("perm.open_settings"),
                    onAction: runtime.requestScreenRecordingPermission
                )
                PermissionRowCard(
                    title: L10n.string("perm.accessibility.title"),
                    description: L10n.string("perm.accessibility.desc"),
                    state: runtime.permissions.accessibility,
                    actionTitle: L10n.string("perm.open_settings"),
                    onAction: runtime.requestAccessibilityPermission
                )
                PermissionRowCard(
                    title: L10n.string("perm.local_network.title"),
                    description: L10n.string("perm.local_network.desc"),
                    state: runtime.permissions.localNetwork,
                    actionTitle: L10n.string("perm.open_settings"),
                    onAction: runtime.openLocalNetworkSettings
                )
            }

            HStack {
                Button(L10n.string("onboarding.permissions.skip")) {
                    withAnimation(.easeOut(duration: 0.2)) {
                        step = 2
                    }
                }
                .buttonStyle(HostSecondaryButtonStyle())

                Spacer()

                if runtime.permissions.allGranted {
                    Button(L10n.string("onboarding.next")) {
                        withAnimation(.easeOut(duration: 0.2)) {
                            step = 2
                        }
                    }
                    .buttonStyle(HostPrimaryButtonStyle())
                } else {
                    Text(L10n.string("onboarding.permissions.auto_advance_hint"))
                        .font(.system(size: 12))
                        .foregroundColor(HostTheme.text2)
                }
            }
            .padding(.top, 4)
        }
        .frame(maxWidth: 560)
    }

    private var readyStep: some View {
        VStack(spacing: 18) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 52))
                .foregroundColor(HostTheme.accent)
            Text(L10n.string("onboarding.ready.title"))
                .font(.system(size: 24, weight: .semibold))
                .foregroundColor(HostTheme.text)
            Text(L10n.format("onboarding.ready.subtitle", runtime.settings.computerName))
                .font(.system(size: 14))
                .foregroundColor(HostTheme.text2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 440)
            Button(L10n.string("onboarding.ready.finish")) {
                runtime.completeOnboarding()
            }
            .buttonStyle(HostPrimaryButtonStyle())
            .padding(.top, 8)
        }
    }
}

struct PermissionRowCard: View {
    let title: String
    let description: String
    let state: PermissionState
    let actionTitle: String
    let onAction: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            statusIcon
                .frame(width: 24, height: 24)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(HostTheme.text)
                    statusBadge
                }
                Text(description)
                    .font(.system(size: 12))
                    .foregroundColor(HostTheme.text2)
            }
            Spacer()
            if state != .granted {
                Button(actionTitle, action: onAction)
                    .buttonStyle(HostSecondaryButtonStyle())
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: HostTheme.cardRadius, style: .continuous)
                .fill(HostTheme.surface)
        )
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch state {
        case .granted:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 18))
                .foregroundColor(HostTheme.accent)
        case .checking:
            ProgressView()
                .controlSize(.small)
                .tint(HostTheme.text2)
        case .denied:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 18))
                .foregroundColor(HostTheme.danger)
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        let text: String
        let color: Color
        switch state {
        case .granted:
            text = L10n.string("perm.status.granted")
            color = HostTheme.accent
        case .checking:
            text = L10n.string("perm.status.checking")
            color = HostTheme.text2
        case .denied:
            text = L10n.string("perm.status.denied")
            color = HostTheme.danger
        }
        return Text(text)
            .font(.system(size: 11, weight: .medium))
            .foregroundColor(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Capsule().fill(color.opacity(0.14)))
    }
}
