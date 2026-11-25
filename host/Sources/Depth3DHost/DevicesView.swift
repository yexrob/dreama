import Depth3DHostCore
import SwiftUI

struct DevicesView: View {
    @ObservedObject var runtime: HostRuntime

    @State private var manualPIN: String = ""
    @State private var manualDeviceName: String = ""
    @State private var selectedPendingPairingId: String = ""
    @State private var isSubmittingPIN: Bool = false
    @State private var pinFeedbackMessage: String? = nil
    @State private var pinFeedbackIsError: Bool = false
    @State private var pinTask: Task<Void, Never>? = nil
    @State private var devicePendingRemoval: PairedDeviceRecord? = nil

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text(L10n.string("tab.devices"))
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundColor(HostTheme.text)
                    Spacer()
                    Button(L10n.string("action.refresh")) {
                        runtime.refreshPairedClientsFromEngine()
                        runtime.refreshPendingEnginePairings()
                    }
                    .buttonStyle(HostSecondaryButtonStyle())
                    .disabled(runtime.isRefreshingDevices)
                }

                if !runtime.network.isConnected {
                    OfflineBannerView(onOpenNetworkSettings: runtime.openNetworkSettings)
                }

                if let err = runtime.devicesError {
                    InlineErrorBanner(
                        title: L10n.string("devices.error.title"),
                        message: err,
                        actionTitle: L10n.string("action.retry"),
                        onAction: runtime.refreshPairedClientsFromEngine
                    )
                }

                // Active incoming pairing request (at most one at a time)
                if !runtime.pendingPairings.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(L10n.string("devices.pending.title"))
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(HostTheme.accent)
                        ForEach(runtime.pendingPairings) { prompt in
                            HostCard {
                                VStack(alignment: .leading, spacing: 10) {
                                    HStack {
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(L10n.format("pair.prompt.title", prompt.deviceName))
                                                .font(.system(size: 14, weight: .semibold))
                                                .foregroundColor(HostTheme.text)
                                            Text(L10n.format("pair.prompt.source_ip", prompt.clientAddress))
                                                .font(.system(size: 11.5).monospacedDigit())
                                                .foregroundColor(HostTheme.accent)
                                            Text(L10n.string("pair.prompt.subtitle"))
                                                .font(.system(size: 12))
                                                .foregroundColor(HostTheme.text2)
                                        }
                                        Spacer()
                                        Button(L10n.string("pair.prompt.reject")) {
                                            runtime.rejectPairing(promptId: prompt.id)
                                        }
                                        .buttonStyle(HostSecondaryButtonStyle())
                                        Button(L10n.string("pair.prompt.allow")) {
                                            // Default rememberAutoAllow=false to match safe confirmation rules.
                                            runtime.approvePairing(promptId: prompt.id, rememberAutoAllow: false)
                                        }
                                        .buttonStyle(HostPrimaryButtonStyle())
                                    }

                                    if prompt.identityChanged {
                                        Text(L10n.string("pair.prompt.identity_changed_warning"))
                                            .font(.system(size: 12, weight: .medium))
                                            .foregroundColor(HostTheme.danger)
                                    }
                                }
                            }
                        }
                    }
                }

                // Paired devices list
                pairedDevicesSection

                // Standard 4-digit PIN entry (for older headsets or compatible clients)
                manualPINSection
            }
            .padding(24)
        }
        .background(HostTheme.bg)
        .onAppear {
            runtime.refreshPairedClientsFromEngine()
            runtime.refreshPendingEnginePairings()
        }
        .alert(
            L10n.string("devices.remove.confirm_title"),
            isPresented: Binding(
                get: { devicePendingRemoval != nil },
                set: { if !$0 { devicePendingRemoval = nil } }
            ),
            presenting: devicePendingRemoval
        ) { device in
            Button(L10n.string("action.cancel"), role: .cancel) {}
            Button(L10n.string("devices.remove.confirm_button"), role: .destructive) {
                runtime.removePairedDevice(device)
            }
        } message: { device in
            Text(L10n.format("devices.remove.confirm_message", device.name))
        }
    }

    @ViewBuilder
    private var pairedDevicesSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L10n.string("devices.paired.title"))
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(HostTheme.text)

            let knownUUIDs = Set(runtime.pairedDevices.compactMap(\.engineUUID))
            let extraClients = runtime.engineClients.filter { !knownUUIDs.contains($0.uuid) }

            if runtime.isRefreshingDevices && runtime.pairedDevices.isEmpty && extraClients.isEmpty {
                LoadingCardView(message: L10n.string("devices.loading"))
            } else if runtime.pairedDevices.isEmpty && extraClients.isEmpty {
                EmptyStateCardView(
                    icon: "visionpro",
                    title: L10n.string("devices.empty.title"),
                    message: L10n.string("devices.empty.message")
                )
            } else {
                VStack(spacing: 8) {
                    ForEach(runtime.pairedDevices) { device in
                        HostCard {
                            HStack(spacing: 14) {
                                Image(systemName: "visionpro")
                                    .font(.system(size: 18))
                                    .foregroundColor(HostTheme.accent)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(device.name)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundColor(HostTheme.text)
                                    Text(L10n.format("devices.paired.date", formatDate(device.pairedAt)))
                                        .font(.system(size: 12))
                                        .foregroundColor(HostTheme.text2)
                                }
                                Spacer()
                                if let fp = device.certFingerprint, !fp.isEmpty {
                                    Toggle(
                                        L10n.string("devices.auto_allow"),
                                        isOn: Binding(
                                            get: { device.autoAllow },
                                            set: { runtime.setAutoAllow($0, forRecordId: device.id) }
                                        )
                                    )
                                    .toggleStyle(.switch)
                                    .tint(HostTheme.accent)
                                    .labelsHidden()
                                    Text(L10n.string("devices.auto_allow"))
                                        .font(.system(size: 12))
                                        .foregroundColor(HostTheme.text2)
                                } else {
                                    Text(L10n.string("devices.needs_reconnect_upgrade"))
                                        .font(.system(size: 12, weight: .medium))
                                        .foregroundColor(HostTheme.accent)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(
                                            Capsule().fill(HostTheme.accent.opacity(0.14))
                                        )
                                }

                                Button(L10n.string("action.remove")) {
                                    devicePendingRemoval = device
                                }
                                .buttonStyle(HostDangerButtonStyle())
                            }
                        }
                    }

                    ForEach(extraClients) { client in
                        HostCard {
                            HStack(spacing: 14) {
                                Image(systemName: "display")
                                    .font(.system(size: 18))
                                    .foregroundColor(HostTheme.text2)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(client.name)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundColor(HostTheme.text)
                                    Text(L10n.string("devices.pin_paired_label"))
                                        .font(.system(size: 12))
                                        .foregroundColor(HostTheme.text2)
                                }
                                Spacer()
                                Button(L10n.string("action.remove")) {
                                    runtime.removeEngineOnlyClient(client)
                                }
                                .buttonStyle(HostDangerButtonStyle())
                            }
                        }
                    }
                }
            }
        }
    }

    private var manualPINSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L10n.string("devices.manual_pin.title"))
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(HostTheme.text)

            HostCard {
                VStack(alignment: .leading, spacing: 12) {
                    Text(L10n.string("devices.manual_pin.desc"))
                        .font(.system(size: 12))
                        .foregroundColor(HostTheme.text2)

                    if runtime.pendingEnginePairings.count > 1 {
                        Picker(L10n.string("devices.manual_pin.select_device"), selection: $selectedPendingPairingId) {
                            Text(L10n.string("devices.manual_pin.select_device")).tag("")
                            ForEach(runtime.pendingEnginePairings) { pending in
                                let label = pending.name ?? L10n.string("device.default_name")
                                let source = pending.normalizedAddress ?? String(pending.id.prefix(8))
                                Text("\(label) (\(source))")
                                    .tag(pending.id)
                            }
                        }
                        .frame(maxWidth: 340)
                    }

                    HStack(spacing: 10) {
                        TextField(L10n.string("devices.manual_pin.pin_placeholder"), text: $manualPIN)
                            .textFieldStyle(.roundedBorder)
                            .font(.system(size: 14, weight: .medium).monospacedDigit())
                            .frame(width: 140)
                            .onChange(of: manualPIN) { _, newValue in
                                let filtered = String(newValue.filter(\.isNumber).prefix(4))
                                if filtered != newValue {
                                    manualPIN = filtered
                                }
                            }

                        TextField(L10n.string("devices.manual_pin.name_placeholder"), text: $manualDeviceName)
                            .textFieldStyle(.roundedBorder)
                            .frame(maxWidth: 220)

                        if isSubmittingPIN {
                            ProgressView()
                                .controlSize(.small)
                                .tint(HostTheme.accent)
                            Button(L10n.string("action.cancel")) {
                                pinTask?.cancel()
                                isSubmittingPIN = false
                            }
                            .buttonStyle(HostSecondaryButtonStyle())
                        } else {
                            Button(L10n.string("devices.manual_pin.submit")) {
                                submitManualPIN()
                            }
                            .buttonStyle(HostPrimaryButtonStyle())
                            .disabled(manualPIN.count != 4)
                        }
                    }

                    if let feedback = pinFeedbackMessage {
                        Text(feedback)
                            .font(.system(size: 12))
                            .foregroundColor(pinFeedbackIsError ? HostTheme.danger : HostTheme.accent)
                    }
                }
            }
        }
    }

    private func submitManualPIN() {
        pinFeedbackMessage = nil
        isSubmittingPIN = true
        let pinToSend = manualPIN
        let nameToSend = manualDeviceName
        let pairingIdToSend = selectedPendingPairingId.isEmpty ? nil : selectedPendingPairingId

        pinTask = Task {
            let result = await runtime.submitManualPIN(
                pin: pinToSend,
                deviceName: nameToSend,
                pairingId: pairingIdToSend
            )
            guard !Task.isCancelled else { return }
            isSubmittingPIN = false
            switch result {
            case .success:
                manualPIN = ""
                manualDeviceName = ""
                selectedPendingPairingId = ""
                pinFeedbackIsError = false
                pinFeedbackMessage = L10n.string("devices.manual_pin.success")
            case .failure(let err):
                pinFeedbackIsError = true
                switch err {
                case .invalidFormat:
                    pinFeedbackMessage = L10n.string("devices.manual_pin.error.format")
                case .engineNotReady:
                    pinFeedbackMessage = L10n.string("devices.manual_pin.error.not_ready")
                case .noPendingHeadset:
                    pinFeedbackMessage = L10n.string("devices.manual_pin.error.no_pending")
                case .multiplePendingHeadsets:
                    pinFeedbackMessage = L10n.string("devices.manual_pin.error.multiple_pending")
                case .pinRejected:
                    pinFeedbackMessage = L10n.string("devices.manual_pin.error.rejected")
                case .unexpected:
                    pinFeedbackMessage = L10n.string("devices.manual_pin.error.unexpected")
                }
            }
        }
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_Hans_CN")
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
