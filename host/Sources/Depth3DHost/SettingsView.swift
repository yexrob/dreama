import Depth3DHostCore
import SwiftUI

struct SettingsView: View {
    @ObservedObject var runtime: HostRuntime
    @State private var nameDraft: String = ""
    @State private var showingLicenses: Bool = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(L10n.string("tab.settings"))
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(HostTheme.text)

                if !runtime.network.isConnected {
                    OfflineBannerView(onOpenNetworkSettings: runtime.openNetworkSettings)
                }

                if let loginErr = runtime.launchAtLoginError {
                    InlineErrorBanner(
                        title: L10n.string("settings.login_error.title"),
                        message: loginErr
                    )
                }

                // General section
                HostCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text(L10n.string("settings.section.general"))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(HostTheme.text2)

                        settingRow(
                            title: L10n.string("settings.launch_at_login.title"),
                            subtitle: L10n.string("settings.launch_at_login.desc")
                        ) {
                            Toggle(
                                "",
                                isOn: Binding(
                                    get: { runtime.settings.launchAtLogin },
                                    set: { runtime.setLaunchAtLogin($0) }
                                )
                            )
                            .toggleStyle(.switch)
                            .tint(HostTheme.accent)
                            .labelsHidden()
                        }

                        Divider().overlay(HostTheme.fill)

                        settingRow(
                            title: L10n.string("settings.computer_name.title"),
                            subtitle: L10n.string("settings.computer_name.desc")
                        ) {
                            HStack(spacing: 8) {
                                TextField("", text: $nameDraft)
                                    .textFieldStyle(.roundedBorder)
                                    .frame(width: 190)
                                    .onSubmit {
                                        runtime.setComputerName(nameDraft)
                                    }
                                if nameDraft.trimmingCharacters(in: .whitespacesAndNewlines) != runtime.settings.computerName {
                                    Button(L10n.string("action.save")) {
                                        runtime.setComputerName(nameDraft)
                                    }
                                    .buttonStyle(HostSecondaryButtonStyle())
                                }
                            }
                        }
                    }
                }

                // Streaming section
                HostCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text(L10n.string("settings.section.streaming"))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(HostTheme.text2)

                        settingRow(
                            title: L10n.string("settings.mute_mac.title"),
                            subtitle: L10n.string("settings.mute_mac.desc")
                        ) {
                            Toggle(
                                "",
                                isOn: Binding(
                                    get: { runtime.settings.muteMacWhileStreaming },
                                    set: { runtime.setMuteMacWhileStreaming($0) }
                                )
                            )
                            .toggleStyle(.switch)
                            .tint(HostTheme.accent)
                            .labelsHidden()
                        }

                        Divider().overlay(HostTheme.fill)

                        settingRow(
                            title: L10n.string("settings.display.title"),
                            subtitle: L10n.string("settings.display.desc")
                        ) {
                            Picker(
                                "",
                                selection: Binding<String>(
                                    get: { runtime.settings.selectedDisplayID ?? "" },
                                    set: { runtime.setSelectedDisplay($0.isEmpty ? nil : $0) }
                                )
                            ) {
                                Text(L10n.string("settings.display.auto")).tag("")
                                ForEach(runtime.displays) { display in
                                    Text("\(display.name) (\(display.resolutionText))").tag(display.id)
                                }
                            }
                            .labelsHidden()
                            .frame(width: 240)
                        }

                        Divider().overlay(HostTheme.fill)

                        settingRow(
                            title: L10n.string("settings.quality.title"),
                            subtitle: L10n.string("settings.quality.desc")
                        ) {
                            Picker(
                                "",
                                selection: Binding(
                                    get: { runtime.settings.qualityPreset },
                                    set: { runtime.setQualityPreset($0) }
                                )
                            ) {
                                Text(L10n.string("settings.quality.smooth")).tag(StreamQualityPreset.smooth)
                                Text(L10n.string("settings.quality.balanced")).tag(StreamQualityPreset.balanced)
                                Text(L10n.string("settings.quality.high")).tag(StreamQualityPreset.high)
                            }
                            .pickerStyle(.segmented)
                            .labelsHidden()
                            .frame(width: 220)
                        }

                        if runtime.overrides.engineBasePort == nil {
                            Divider().overlay(HostTheme.fill)

                            settingRow(
                                title: L10n.string("settings.port_mode.title"),
                                subtitle: L10n.string("settings.port_mode.desc")
                            ) {
                                Picker(
                                    "",
                                    selection: Binding(
                                        get: { runtime.settings.useAlternateEnginePorts },
                                        set: { useAlt in
                                            if useAlt {
                                                runtime.switchToAlternateEnginePorts()
                                            } else {
                                                runtime.switchToDefaultEnginePorts()
                                            }
                                        }
                                    )
                                ) {
                                    Text(L10n.string("settings.port_mode.default")).tag(false)
                                    Text(L10n.string("settings.port_mode.alternate")).tag(true)
                                }
                                .pickerStyle(.segmented)
                                .labelsHidden()
                                .frame(width: 190)
                            }
                        }
                    }
                }

                // About & Licenses section
                HostCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text(L10n.string("settings.section.about"))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(HostTheme.text2)

                        settingRow(
                            title: L10n.string("about.version.title"),
                            subtitle: L10n.string("about.license_summary")
                        ) {
                            Text(appVersionString)
                                .font(.system(size: 13).monospacedDigit())
                                .foregroundColor(HostTheme.text2)
                        }

                        Divider().overlay(HostTheme.fill)

                        HStack(spacing: 10) {
                            Button(L10n.string("about.licenses.button")) {
                                showingLicenses = true
                            }
                            .buttonStyle(HostSecondaryButtonStyle())

                            Button(L10n.string("settings.rerun_onboarding")) {
                                runtime.resetOnboarding()
                            }
                            .buttonStyle(HostSecondaryButtonStyle())

                            Spacer()
                        }
                    }
                }
            }
            .padding(24)
        }
        .background(HostTheme.bg)
        .onAppear {
            nameDraft = runtime.settings.computerName
            runtime.refreshDisplays()
        }
        .sheet(isPresented: $showingLicenses) {
            OpenSourceLicensesSheet()
        }
    }

    private func settingRow<Control: View>(
        title: String,
        subtitle: String,
        @ViewBuilder control: () -> Control
    ) -> some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(HostTheme.text)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(HostTheme.text2)
            }
            Spacer()
            control()
        }
    }

    private var appVersionString: String {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        return "v\(short)"
    }
}

struct OpenSourceLicensesSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(L10n.string("about.licenses.title"))
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(HostTheme.text)
                Spacer()
                Button(L10n.string("action.close")) {
                    dismiss()
                }
                .buttonStyle(HostSecondaryButtonStyle())
            }

            ScrollView {
                Text(L10n.loadThirdPartyNotices())
                    .font(.system(size: 12.5))
                    .foregroundColor(HostTheme.text)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
            }
            .background(
                RoundedRectangle(cornerRadius: HostTheme.cardRadius, style: .continuous)
                    .fill(HostTheme.surface)
            )
        }
        .padding(20)
        .frame(width: 560, height: 420)
        .background(HostTheme.sheet)
        .preferredColorScheme(.dark)
    }
}
