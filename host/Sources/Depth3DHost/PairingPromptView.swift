import AppKit
import Combine
import Depth3DHostCore
import SwiftUI

/// Floating always-on-top confirmation window for incoming headset auto-pairing requests.
struct PairingPromptView: View {
    let prompt: PendingPairingPrompt
    let onApprove: (Bool) -> Void
    let onReject: () -> Void

    @State private var rememberAutoAllow: Bool = true
    @State private var remainingSeconds: Int = 60

    private let timer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                DepthLayersEmblem(size: 40)
                VStack(alignment: .leading, spacing: 3) {
                    Text(L10n.format("pair.prompt.title", prompt.deviceName))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(HostTheme.text)
                    Text(L10n.string("pair.prompt.subtitle"))
                        .font(.system(size: 12))
                        .foregroundColor(HostTheme.text2)
                }
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

                Button(L10n.string("pair.prompt.allow")) {
                    onApprove(rememberAutoAllow)
                }
                .buttonStyle(HostPrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 400)
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

/// Manages a standalone floating `NSPanel` for incoming pairing prompts so the user sees them
/// immediately even when the main window is closed.
@MainActor
final class PairingPanelController {
    private var panel: NSPanel?
    private var displayedPromptId: UUID?

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

        let rootView = PairingPromptView(
            prompt: next,
            onApprove: { remember in onApprove(next.id, remember) },
            onReject: { onReject(next.id) }
        )

        let hosting = NSHostingController(rootView: rootView)
        let newPanel = NSPanel(contentViewController: hosting)
        newPanel.title = L10n.string("pair.panel.window_title")
        newPanel.styleMask = [.titled, .closable, .nonactivatingPanel]
        newPanel.isFloatingPanel = true
        newPanel.level = .floating
        newPanel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        newPanel.isReleasedWhenClosed = false
        newPanel.backgroundColor = NSColor(HostTheme.sheet)
        newPanel.center()
        newPanel.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        self.panel = newPanel
    }

    func closePanel() {
        panel?.close()
        panel = nil
        displayedPromptId = nil
    }
}
