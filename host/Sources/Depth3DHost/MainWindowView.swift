import Depth3DHostCore
import SwiftUI

enum HostTab: String, CaseIterable, Identifiable {
    case status
    case devices
    case media
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .status: return L10n.string("tab.status")
        case .devices: return L10n.string("tab.devices")
        case .media: return L10n.string("tab.media")
        case .settings: return L10n.string("tab.settings")
        }
    }

    var icon: String {
        switch self {
        case .status: return "waveform.path.ecg"
        case .devices: return "visionpro"
        case .media: return "film.stack"
        case .settings: return "gearshape"
        }
    }
}

struct MainWindowView: View {
    @ObservedObject var runtime: HostRuntime
    @Binding var selectedTab: HostTab

    var body: some View {
        Group {
            if !runtime.settings.onboardingCompleted {
                OnboardingView(runtime: runtime)
            } else {
                HStack(spacing: 0) {
                    sidebar
                    Divider().overlay(HostTheme.fill)
                    detailContent
                }
                .background(HostTheme.bg)
            }
        }
        .frame(minWidth: 760, minHeight: 520)
        .preferredColorScheme(.dark)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                DepthLayersEmblem(size: 28)
                Text(L10n.string("app.name"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(HostTheme.text)
            }
            .padding(.horizontal, 12)
            .padding(.top, 16)
            .padding(.bottom, 14)

            ForEach(HostTab.allCases) { tab in
                Button {
                    selectedTab = tab
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: tab.icon)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(selectedTab == tab ? HostTheme.accent : HostTheme.text2)
                            .frame(width: 20)
                        Text(tab.title)
                            .font(.system(size: 13, weight: selectedTab == tab ? .semibold : .medium))
                            .foregroundColor(selectedTab == tab ? HostTheme.text : HostTheme.text2)
                        Spacer()
                        if tab == .status && needsAttentionBadge {
                            Circle()
                                .fill(HostTheme.danger)
                                .frame(width: 7, height: 7)
                        }
                        if tab == .devices && !runtime.pendingPairings.isEmpty {
                            Circle()
                                .fill(HostTheme.accent)
                                .frame(width: 7, height: 7)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(selectedTab == tab ? HostTheme.selected : Color.clear)
                    )
                }
                .buttonStyle(.plain)
            }

            Spacer()

            // Compact bottom status indicator
            HStack(spacing: 8) {
                Circle()
                    .fill(statusDotColor)
                    .frame(width: 8, height: 8)
                Text(sidebarStatusText)
                    .font(.system(size: 12))
                    .foregroundColor(HostTheme.text2)
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 16)
        }
        .padding(.horizontal, 10)
        .frame(width: 196)
        .background(HostTheme.surface)
    }

    @ViewBuilder
    private var detailContent: some View {
        switch selectedTab {
        case .status:
            StatusView(
                runtime: runtime,
                onNavigateToMedia: { selectedTab = .media },
                onNavigateToDevices: { selectedTab = .devices }
            )
        case .devices:
            DevicesView(runtime: runtime)
        case .media:
            MediaView(runtime: runtime)
        case .settings:
            SettingsView(runtime: runtime)
        }
    }

    private var needsAttentionBadge: Bool {
        switch runtime.overallStatus {
        case .needsPermissions, .portConflict, .error, .noNetwork:
            return true
        default:
            return false
        }
    }

    private var statusDotColor: Color {
        switch runtime.overallStatus {
        case .ready, .streaming: return HostTheme.accent
        case .paused, .loading: return HostTheme.text2
        case .noNetwork, .needsPermissions, .portConflict, .error: return HostTheme.danger
        }
    }

    private var sidebarStatusText: String {
        switch runtime.overallStatus {
        case .loading: return L10n.string("menubar.status.loading")
        case .ready: return L10n.string("menubar.status.ready")
        case .streaming: return L10n.string("menubar.status.streaming")
        case .paused: return L10n.string("menubar.status.paused")
        case .noNetwork: return L10n.string("menubar.status.no_network")
        case .needsPermissions: return L10n.string("menubar.status.needs_attention")
        case .portConflict: return L10n.string("menubar.status.needs_attention")
        case .error: return L10n.string("menubar.status.error")
        }
    }
}
