import Depth3DHostCore
import SwiftUI

struct StatusView: View {
    @ObservedObject var runtime: HostRuntime
    let onNavigateToMedia: () -> Void
    let onNavigateToDevices: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(L10n.string("tab.status"))
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(HostTheme.text)

                if !runtime.isInitialized {
                    LoadingCardView(message: L10n.string("status.loading"))
                } else {
                    if !runtime.network.isConnected {
                        OfflineBannerView(onOpenNetworkSettings: runtime.openNetworkSettings)
                    }

                    heroStatusCard

                    actionItemsSection

                    overviewGrid
                }
            }
            .padding(24)
        }
        .background(HostTheme.bg)
    }

    private var heroStatusCard: some View {
        HostCard {
            HStack(spacing: 16) {
                statusSymbol
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(statusColor.opacity(0.15)))

                VStack(alignment: .leading, spacing: 4) {
                    Text(heroTitle)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(HostTheme.text)
                    Text(heroSubtitle)
                        .font(.system(size: 13))
                        .foregroundColor(HostTheme.text2)
                }

                Spacer()

                heroAction
            }
        }
    }

    @ViewBuilder
    private var statusSymbol: some View {
        switch runtime.overallStatus {
        case .loading:
            ProgressView()
                .controlSize(.small)
                .tint(HostTheme.accent)
        case .ready:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 22))
                .foregroundColor(HostTheme.accent)
        case .streaming:
            Image(systemName: "play.circle.fill")
                .font(.system(size: 22))
                .foregroundColor(HostTheme.accent)
        case .paused:
            Image(systemName: "pause.circle.fill")
                .font(.system(size: 22))
                .foregroundColor(HostTheme.text2)
        case .noNetwork:
            Image(systemName: "wifi.slash")
                .font(.system(size: 20))
                .foregroundColor(HostTheme.danger)
        case .needsPermissions, .portConflict, .error:
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 20))
                .foregroundColor(HostTheme.danger)
        }
    }

    private var statusColor: Color {
        switch runtime.overallStatus {
        case .ready, .streaming, .loading: return HostTheme.accent
        case .paused: return HostTheme.text2
        case .noNetwork, .needsPermissions, .portConflict, .error: return HostTheme.danger
        }
    }

    private var heroTitle: String {
        switch runtime.overallStatus {
        case .loading:
            return L10n.string("status.hero.loading")
        case .ready:
            return L10n.string("status.hero.ready")
        case .streaming(let count):
            return L10n.format("status.hero.streaming", count)
        case .paused:
            return L10n.string("status.hero.paused")
        case .noNetwork:
            return L10n.string("status.hero.no_network")
        case .needsPermissions(let missing):
            return L10n.format("status.hero.needs_permissions", missing)
        case .portConflict:
            return L10n.string("status.hero.port_conflict")
        case .error:
            return L10n.string("status.hero.error")
        }
    }

    private var heroSubtitle: String {
        switch runtime.overallStatus {
        case .loading:
            return L10n.string("status.hero.loading.sub")
        case .ready:
            return L10n.format("status.hero.ready.sub", runtime.settings.computerName)
        case .streaming:
            return L10n.string("status.hero.streaming.sub")
        case .paused:
            return L10n.string("status.hero.paused.sub")
        case .noNetwork:
            return L10n.string("status.hero.no_network.sub")
        case .needsPermissions:
            return L10n.string("status.hero.needs_permissions.sub")
        case .portConflict:
            return L10n.string("status.hero.port_conflict.sub")
        case .error(let msg):
            return msg
        }
    }

    @ViewBuilder
    private var heroAction: some View {
        switch runtime.overallStatus {
        case .ready:
            Button(L10n.string("action.pause_streaming")) {
                runtime.togglePauseStreaming()
            }
            .buttonStyle(HostSecondaryButtonStyle())
        case .streaming:
            Button(L10n.string("action.disconnect_stream")) {
                runtime.disconnectActiveStream()
            }
            .buttonStyle(HostDangerButtonStyle())
        case .paused:
            Button(L10n.string("action.resume_streaming")) {
                runtime.togglePauseStreaming()
            }
            .buttonStyle(HostPrimaryButtonStyle())
        case .portConflict:
            Button(L10n.string("status.port_conflict.use_alternate")) {
                runtime.switchToAlternateEnginePorts()
            }
            .buttonStyle(HostPrimaryButtonStyle())
        case .error:
            Button(L10n.string("action.retry")) {
                runtime.retryEngine()
            }
            .buttonStyle(HostPrimaryButtonStyle())
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var actionItemsSection: some View {
        let hasBonjourError = runtime.permissions.bonjourPublishFailed && runtime.permissions.localNetwork != .denied
        let hasPermissionIssues = !runtime.permissions.allGranted
        let hasPortConflict: Bool = {
            if case .portConflict = runtime.engineState { return true }
            return false
        }()
        let hasWebDAVError = runtime.webDAVError != nil

        if hasPermissionIssues || hasBonjourError || hasPortConflict || hasWebDAVError {
            VStack(alignment: .leading, spacing: 10) {
                Text(L10n.string("status.needs_attention"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(HostTheme.text)

                if hasPortConflict {
                    InlineErrorBanner(
                        title: L10n.string("status.port_conflict.card_title"),
                        message: L10n.string("status.port_conflict.card_desc"),
                        actionTitle: L10n.string("action.retry"),
                        onAction: runtime.retryEngine
                    )
                }

                if hasBonjourError {
                    InlineErrorBanner(
                        title: L10n.string("status.bonjour_error.title"),
                        message: L10n.string("status.bonjour_error.desc"),
                        actionTitle: L10n.string("action.retry"),
                        onAction: runtime.retryBonjourPublish
                    )
                }

                if runtime.permissions.screenRecording != .granted {
                    PermissionRowCard(
                        title: L10n.string("perm.screen.title"),
                        description: L10n.string("perm.screen.desc"),
                        state: runtime.permissions.screenRecording,
                        actionTitle: L10n.string("perm.open_settings"),
                        onAction: runtime.requestScreenRecordingPermission
                    )
                }

                if runtime.permissions.accessibility != .granted {
                    PermissionRowCard(
                        title: L10n.string("perm.accessibility.title"),
                        description: L10n.string("perm.accessibility.desc"),
                        state: runtime.permissions.accessibility,
                        actionTitle: L10n.string("perm.open_settings"),
                        onAction: runtime.requestAccessibilityPermission
                    )
                }

                if runtime.permissions.localNetwork != .granted && !hasBonjourError {
                    PermissionRowCard(
                        title: L10n.string("perm.local_network.title"),
                        description: L10n.string("perm.local_network.desc"),
                        state: runtime.permissions.localNetwork,
                        actionTitle: L10n.string("perm.open_settings"),
                        onAction: runtime.openLocalNetworkSettings
                    )
                }

                if let err = runtime.webDAVError {
                    InlineErrorBanner(
                        title: L10n.string("media.error.title"),
                        message: err,
                        actionTitle: L10n.string("action.retry"),
                        onAction: { runtime.setMediaServiceEnabled(true) }
                    )
                }
            }
        }
    }

    private var overviewGrid: some View {
        HStack(spacing: 12) {
            overviewTile(
                icon: "display",
                title: L10n.string("status.tile.stream"),
                value: streamTileValue,
                subtitle: audioTileSubtitle,
                action: nil
            )
            overviewTile(
                icon: "film.stack",
                title: L10n.string("status.tile.media"),
                value: mediaTileValue,
                subtitle: mediaTileSubtitle,
                action: onNavigateToMedia
            )
            overviewTile(
                icon: "visionpro",
                title: L10n.string("status.tile.devices"),
                value: L10n.format("status.tile.devices.count", totalPairedCount),
                subtitle: runtime.network.localIPv4 ?? runtime.network.interfaceSummary,
                action: onNavigateToDevices
            )
        }
    }

    private func overviewTile(
        icon: String,
        title: String,
        value: String,
        subtitle: String,
        action: (() -> Void)?
    ) -> some View {
        let content = VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: icon)
                    .font(.system(size: 14))
                    .foregroundColor(HostTheme.accent)
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(HostTheme.text2)
                Spacer()
                if action != nil {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11))
                        .foregroundColor(HostTheme.text3)
                }
            }
            Text(value)
                .font(.system(size: 15, weight: .semibold).monospacedDigit())
                .foregroundColor(HostTheme.text)
            Text(subtitle)
                .font(.system(size: 12))
                .foregroundColor(HostTheme.text2)
                .lineLimit(1)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: HostTheme.cardRadius, style: .continuous)
                .fill(HostTheme.surface)
        )

        return Group {
            if let action {
                Button(action: action) { content }
                    .buttonStyle(.plain)
            } else {
                content
            }
        }
    }

    private var streamTileValue: String {
        switch runtime.engineState {
        case .ready: return L10n.string("status.stream.ready")
        case .streaming: return L10n.string("status.stream.active")
        case .paused: return L10n.string("status.stream.paused")
        case .starting: return L10n.string("status.stream.starting")
        default: return L10n.string("status.stream.unavailable")
        }
    }

    private var audioTileSubtitle: String {
        if runtime.engineLogState.systemAudioTapFailed {
            return L10n.string("status.audio.permission_hint")
        }
        return runtime.settings.muteMacWhileStreaming
            ? L10n.string("status.audio.tap_muted")
            : L10n.string("status.audio.tap_unmuted")
    }

    private var mediaTileValue: String {
        let count = runtime.settings.mediaFolders.count + runtime.settings.cloudRemotes.count
        if !runtime.settings.mediaServiceEnabled {
            return L10n.string("status.media.disabled")
        }
        return count == 0
            ? L10n.string("status.media.empty")
            : L10n.format("status.media.count", count)
    }

    private var mediaTileSubtitle: String {
        runtime.webDAVPort != nil
            ? L10n.string("status.media.running")
            : L10n.string("status.media.stopped")
    }

    private var totalPairedCount: Int {
        let knownUUIDs = Set(runtime.pairedDevices.compactMap(\.engineUUID))
        let extraEngine = runtime.engineClients.filter { !knownUUIDs.contains($0.uuid) }.count
        return runtime.pairedDevices.count + extraEngine
    }
}
