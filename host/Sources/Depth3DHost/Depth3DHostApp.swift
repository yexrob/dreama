import AppKit
import Combine
import Darwin
import Depth3DHostCore
import SwiftUI

@MainActor
final class HostAppDelegate: NSObject, NSApplicationDelegate {
    let runtime = HostRuntime()
    private let pairingPanelController = PairingPanelController()
    private var cancellables = Set<AnyCancellable>()
    private var signalSources: [DispatchSourceSignal] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        runtime.start()

        runtime.$pendingPairings
            .receive(on: DispatchQueue.main)
            .sink { [weak self] prompts in
                guard let self else { return }
                self.pairingPanelController.sync(
                    prompts: prompts,
                    onApprove: { id, remember in
                        self.runtime.approvePairing(promptId: id, rememberAutoAllow: remember)
                    },
                    onReject: { id in
                        self.runtime.rejectPairing(promptId: id)
                    }
                )
            }
            .store(in: &cancellables)

        installSignalHandlers()
    }

    func applicationWillTerminate(_ notification: Notification) {
        pairingPanelController.closePanel()
        runtime.shutdown()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        // Keep running in the menu bar when the main window is closed.
        false
    }

    private func installSignalHandlers() {
        for sig in [SIGTERM, SIGINT] {
            signal(sig, SIG_IGN)
            let source = DispatchSource.makeSignalSource(signal: sig, queue: .main)
            source.setEventHandler { [weak self] in
                HostLogger.info("Received termination signal \(sig); shutting down cleanly", category: "app")
                self?.runtime.shutdown()
                NSApp.terminate(nil)
            }
            source.resume()
            signalSources.append(source)
        }
    }
}

@main
struct Depth3DHostApp: App {
    @NSApplicationDelegateAdaptor(HostAppDelegate.self) private var appDelegate
    @State private var selectedTab: HostTab = .status
    @Environment(\.openWindow) private var openWindow

    var body: some Scene {
        Window(L10n.string("app.name"), id: "main") {
            MainWindowView(runtime: appDelegate.runtime, selectedTab: $selectedTab)
                .onAppear {
                    NSApp.setActivationPolicy(.regular)
                    NSApp.activate(ignoringOtherApps: true)
                }
                .onDisappear {
                    // Return to menu bar accessory mode when main window is closed.
                    DispatchQueue.main.async {
                        let visibleMain = NSApp.windows.contains { $0.isVisible && $0.title == L10n.string("app.name") }
                        if !visibleMain {
                            NSApp.setActivationPolicy(.accessory)
                        }
                    }
                }
        }
        .defaultSize(width: 820, height: 560)
        .windowResizability(.contentMinSize)

        MenuBarExtra {
            MenuBarContentView(
                runtime: appDelegate.runtime,
                onOpenMainWindow: { tab in
                    selectedTab = tab
                    NSApp.setActivationPolicy(.regular)
                    openWindow(id: "main")
                    NSApp.activate(ignoringOtherApps: true)
                }
            )
        } label: {
            Image(systemName: menuBarSymbolName(for: appDelegate.runtime.overallStatus))
                .accessibilityLabel(L10n.string("app.name"))
        }
    }

    private func menuBarSymbolName(for status: HostOverallStatus) -> String {
        switch status {
        case .ready:
            return "square.stack.3d.up"
        case .streaming:
            return "square.stack.3d.up.fill"
        case .paused:
            return "pause.circle"
        case .loading:
            return "square.stack.3d.up"
        case .noNetwork, .needsPermissions, .portConflict, .error:
            return "exclamationmark.triangle"
        }
    }
}

struct MenuBarContentView: View {
    @ObservedObject var runtime: HostRuntime
    let onOpenMainWindow: (HostTab) -> Void

    var body: some View {
        // Status line
        Text(statusLine)

        Divider()

        // Connected / paired devices section
        Text(L10n.string("menubar.devices.header"))
        if case .streaming(let count) = runtime.overallStatus {
            Text(L10n.format("menubar.devices.streaming_count", count))
        } else if runtime.pairedDevices.isEmpty && runtime.engineClients.isEmpty {
            Text(L10n.string("menubar.devices.none"))
        } else {
            ForEach(runtime.pairedDevices.prefix(5)) { device in
                Text(device.name)
            }
            let knownUUIDs = Set(runtime.pairedDevices.compactMap(\.engineUUID))
            ForEach(runtime.engineClients.filter { !knownUUIDs.contains($0.uuid) }.prefix(3)) { client in
                Text(client.name)
            }
        }

        Divider()

        if case .streaming = runtime.overallStatus {
            Button(L10n.string("action.disconnect_stream")) {
                runtime.disconnectActiveStream()
            }
        }

        Button(
            runtime.engineState == .paused
                ? L10n.string("action.resume_streaming")
                : L10n.string("action.pause_streaming")
        ) {
            runtime.togglePauseStreaming()
        }

        Divider()

        Button(L10n.string("menubar.open_status")) {
            onOpenMainWindow(.status)
        }

        Button(L10n.string("menubar.open_settings")) {
            onOpenMainWindow(.settings)
        }

        Divider()

        Button(L10n.string("menubar.quit")) {
            runtime.shutdown()
            NSApp.terminate(nil)
        }
        .keyboardShortcut("q")
    }

    private var statusLine: String {
        switch runtime.overallStatus {
        case .loading:
            return L10n.string("menubar.status.loading")
        case .ready:
            return L10n.string("menubar.status.ready")
        case .streaming(let count):
            return L10n.format("status.hero.streaming", count)
        case .paused:
            return L10n.string("menubar.status.paused")
        case .noNetwork:
            return L10n.string("menubar.status.no_network")
        case .needsPermissions:
            return L10n.string("menubar.status.needs_attention")
        case .portConflict:
            return L10n.string("status.hero.port_conflict")
        case .error:
            return L10n.string("menubar.status.error")
        }
    }
}
