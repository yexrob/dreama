import AppKit
import Depth3DHostCore
import SwiftUI

struct MediaView: View {
    @ObservedObject var runtime: HostRuntime

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text(L10n.string("tab.media"))
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundColor(HostTheme.text)
                    Spacer()
                    Toggle(
                        L10n.string("media.enable_toggle"),
                        isOn: Binding(
                            get: { runtime.settings.mediaServiceEnabled },
                            set: { runtime.setMediaServiceEnabled($0) }
                        )
                    )
                    .toggleStyle(.switch)
                    .tint(HostTheme.accent)
                }

                if !runtime.network.isConnected {
                    OfflineBannerView(onOpenNetworkSettings: runtime.openNetworkSettings)
                }

                if let err = runtime.webDAVError {
                    InlineErrorBanner(
                        title: L10n.string("media.error.title"),
                        message: err,
                        actionTitle: L10n.string("action.retry"),
                        onAction: { runtime.setMediaServiceEnabled(true) }
                    )
                }

                HostCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(L10n.string("media.summary.title"))
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(HostTheme.text)
                            Text(
                                runtime.settings.mediaServiceEnabled
                                    ? L10n.string("media.summary.enabled")
                                    : L10n.string("media.summary.disabled")
                            )
                            .font(.system(size: 12))
                            .foregroundColor(HostTheme.text2)
                        }
                        Spacer()
                        if runtime.webDAVPort != nil {
                            Text(L10n.string("media.status.running"))
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(HostTheme.accent)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Capsule().fill(HostTheme.accent.opacity(0.14)))
                        }
                    }
                }

                localFoldersSection
                cloudDrivesSection
            }
            .padding(24)
        }
        .background(HostTheme.bg)
    }

    private var localFoldersSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L10n.string("media.folders.title"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(HostTheme.text)
                Spacer()
                Button(L10n.string("media.folders.add")) {
                    pickLocalFolder()
                }
                .buttonStyle(HostPrimaryButtonStyle())
            }

            if runtime.settings.mediaFolders.isEmpty {
                EmptyStateCardView(
                    icon: "folder",
                    title: L10n.string("media.folders.empty.title"),
                    message: L10n.string("media.folders.empty.message")
                )
            } else {
                VStack(spacing: 8) {
                    ForEach(runtime.settings.mediaFolders) { folder in
                        HostCard {
                            HStack(spacing: 12) {
                                Image(systemName: "folder.fill")
                                    .font(.system(size: 17))
                                    .foregroundColor(HostTheme.accent)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(folder.name)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundColor(HostTheme.text)
                                    Text(folder.path)
                                        .font(.system(size: 12))
                                        .foregroundColor(HostTheme.text2)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                }
                                Spacer()
                                Button(L10n.string("action.remove")) {
                                    runtime.removeMediaFolder(folder)
                                }
                                .buttonStyle(HostDangerButtonStyle())
                            }
                        }
                    }
                }
            }
        }
    }

    private var cloudDrivesSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L10n.string("media.cloud.title"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(HostTheme.text)
                Spacer()
                Button(L10n.string("media.cloud.refresh")) {
                    runtime.refreshCloudRemotes()
                }
                .buttonStyle(HostSecondaryButtonStyle())
                .disabled(runtime.isRefreshingCloudRemotes)
            }

            if runtime.isRefreshingCloudRemotes {
                LoadingCardView(message: L10n.string("media.cloud.loading"))
            } else if !runtime.rcloneAvailability.isInstalled {
                // Friendly setup guide when rclone is not installed (no raw error!)
                HostCard {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) {
                            Image(systemName: "cloud")
                                .font(.system(size: 16))
                                .foregroundColor(HostTheme.accent)
                            Text(L10n.string("media.cloud.guide.title"))
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(HostTheme.text)
                        }
                        Text(L10n.string("media.cloud.guide.desc"))
                            .font(.system(size: 12))
                            .foregroundColor(HostTheme.text2)
                        Text(L10n.string("media.cloud.guide.steps"))
                            .font(.system(size: 12))
                            .foregroundColor(HostTheme.text2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            } else {
                // Active cloud remotes
                if !runtime.settings.cloudRemotes.isEmpty {
                    VStack(spacing: 8) {
                        ForEach(runtime.settings.cloudRemotes) { remote in
                            HostCard {
                                HStack(spacing: 12) {
                                    Image(systemName: "cloud.fill")
                                        .font(.system(size: 17))
                                        .foregroundColor(HostTheme.accent)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(remote.displayName)
                                            .font(.system(size: 14, weight: .semibold))
                                            .foregroundColor(HostTheme.text)
                                        Text(remote.remoteSpec)
                                            .font(.system(size: 12))
                                            .foregroundColor(HostTheme.text2)
                                    }
                                    Spacer()
                                    Button(L10n.string("action.remove")) {
                                        runtime.removeCloudRemote(remote)
                                    }
                                    .buttonStyle(HostDangerButtonStyle())
                                }
                            }
                        }
                    }
                }

                // Discovered but not yet added remotes
                let addedSpecs = Set(runtime.settings.cloudRemotes.map(\.remoteSpec))
                let unadded = runtime.rcloneAvailability.availableRemotes.filter { !addedSpecs.contains($0) }

                if !unadded.isEmpty {
                    HostCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(L10n.string("media.cloud.available.title"))
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(HostTheme.text)
                            ForEach(unadded, id: \.self) { spec in
                                HStack {
                                    Text(spec.replacingOccurrences(of: ":", with: ""))
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundColor(HostTheme.text)
                                    Spacer()
                                    Button(L10n.string("media.cloud.add")) {
                                        runtime.addCloudRemote(remoteSpec: spec)
                                    }
                                    .buttonStyle(HostSecondaryButtonStyle())
                                }
                            }
                        }
                    }
                } else if runtime.settings.cloudRemotes.isEmpty {
                    EmptyStateCardView(
                        icon: "cloud",
                        title: L10n.string("media.cloud.no_remotes.title"),
                        message: L10n.string("media.cloud.no_remotes.message")
                    )
                }
            }
        }
    }

    private func pickLocalFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = L10n.string("media.folders.panel_prompt")
        if panel.runModal() == .OK, let url = panel.url {
            runtime.addMediaFolder(url: url)
        }
    }
}
