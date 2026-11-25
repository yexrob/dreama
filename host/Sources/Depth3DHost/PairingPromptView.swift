import AppKit
import Combine
import Depth3DHostCore
import SwiftUI

/// Floating always-on-top confirmation window for incoming headset auto-pairing requests.
///
/// Safety design against accidental approval:
/// - "以后自动允许这台设备" is UNCHECKED by default
/// - No default action shortcut (pressing Return/Enter CANNOT approve)
/// - Displays the requester's IP address clearly
/// - Displays an explicit coral warning when a previously bound `deviceId` presents a changed/missing cert
/// - Closing the panel (`×` / `Cmd+W`) immediately rejects the pairing request
/// - Non-activating floating panel that NEVER steals keyboard focus from the user's active app
struct PairingPromptView: View {
    let prompt: PendingPairingPrompt
    let onApprove: (Bool) -> Void
    let onReject: () -> Void

    @State private var rememberAutoAllow: Bool = false
    @State private var remainingSeconds: Int = 60

    private let timer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                DepthLayersEmblem(size: 40)
                VStack(alignment: .leading, spacing: 3) {
                    Text(L10n.format("pair.prompt.title", prompt.deviceName))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(HostTheme.text)
                    Text(L10n.format("pair.prompt.source_ip", prompt.clientAddress))
                        .font(.system(size: 11.5).monospacedDigit())
                        .foregroundColor(HostTheme.accent)
                    Text(L10n.string("pair.prompt.subtitle"))
                        .font(.system(size: 12))
                        .foregroundColor(HostTheme.text2)
                }
            }

            if prompt.identityChanged {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.shield.fill")
                        .font(.system(size: 14))
                        .foregroundColor(HostTheme.danger)
                    Text(L10n.string("pair.prompt.identity_changed_warning"))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(HostTheme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(HostTheme.danger.opacity(0.14))
                )
            }

            Toggle(isOn: $rememberAutoAllow) {
                Text(L10n.string("pair.prompt.auto_allow"))
                    .font(.system(size: 13))
                    .foregroundColor(HostTheme.text)
            }
            .toggleStyle(.checkbox)
            .tint(HostTheme.accent)

            HStack {
                Text(L10n.format("pair.prompt.countdown", max(0, remainingSeconds)))
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundColor(HostTheme.text2)
                Spacer()
                Button(L10n.string("pair.prompt.reject")) {
                    onReject()
                }
                .buttonStyle(HostSecondaryButtonStyle())
                .keyboardShortcut(.cancelAction)

                // Intentionally NO `.keyboardShortcut(.defaultAction)`: pressing Return must never approve.
                Button(L10n.string("pair.prompt.allow")) {
                    onApprove(rememberAutoAllow)
                }
                .buttonStyle(HostPrimaryButtonStyle())
            }
        }
        .padding(20)
        .frame(width: 430)
        .background(HostTheme.sheet)
        .preferredColorScheme(.dark)
        .onAppear {
            updateCountdown()
        }
        .onReceive(timer) { _ in
            updateCountdown()
        }
    }

    private func updateCountdown() {
        remainingSeconds = max(0, Int(ceil(prompt.expiresAt.timeIntervalSinceNow)))
    }
}

/// Manages a standalone non-activating floating `NSPanel` for incoming pairing prompts so the user
/// sees them immediately even when the main window is closed, WITHOUT stealing keyboard focus.
/// Closing the panel via its window close button immediately triggers `onReject`.
@MainActor
final class PairingPanelController: NSObject, NSWindowDelegate {
    private var panel: NSPanel?
    private var displayedPromptId: UUID?
    private var activeRejectHandler: ((UUID) -> Void)?
    private var isClosingProgrammatically: Bool = false

    func sync(
        prompts: [PendingPairingPrompt],
        onApprove: @escaping (UUID, Bool) -> Void,
        onReject: @escaping (UUID) -> Void
    ) {
        guard let next = prompts.first else {
            closePanel()
            return
        }
        if displayedPromptId == next.id, panel?.isVisible == true {
            return
        }

        closePanel()
        displayedPromptId = next.id
        activeRejectHandler = onReject

        let rootView = PairingPromptView(
            prompt: next,
            onApprove: { [weak self] remember in
                self?.displayedPromptId = nil
                self?.activeRejectHandler = nil
                onApprove(next.id, remember)
            },
            onReject: { [weak self] in
                self?.displayedPromptId = nil
                self?.activeRejectHandler = nil
                onReject(next.id)
            }
        )

        let hosting = NSHostingController(rootView: rootView)
        let newPanel = NSPanel(contentViewController: hosting)
        newPanel.title = L10n.string("pair.panel.window_title")
        newPanel.styleMask = [.titled, .closable, .nonactivatingPanel]
        newPanel.isFloatingPanel = true
        newPanel.becomesKeyOnlyIfNeeded = true
        newPanel.hidesOnDeactivate = false
        newPanel.level = .floating
        newPanel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        newPanel.isReleasedWhenClosed = false
        newPanel.backgroundColor = NSColor(HostTheme.sheet)
        newPanel.delegate = self
        newPanel.center()
        // Never call `makeKeyAndOrderFront` or `NSApp.activate` so keyboard focus stays in the user's app.
        newPanel.orderFrontRegardless()

        self.panel = newPanel
    }

    func closePanel() {
        isClosingProgrammatically = true
        panel?.delegate = nil
        panel?.close()
        panel = nil
        displayedPromptId = nil
        activeRejectHandler = nil
        isClosingProgrammatically = false
    }

    func windowWillClose(_ notification: Notification) {
        guard !isClosingProgrammatically else { return }
        guard let promptId = displayedPromptId, let reject = activeRejectHandler else { return }
        displayedPromptId = nil
        activeRejectHandler = nil
        panel = nil
        reject(promptId)
    }
}
