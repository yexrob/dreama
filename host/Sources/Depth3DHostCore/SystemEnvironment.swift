import AppKit
import CoreGraphics
import Darwin
import Foundation
import Network
import ServiceManagement

/// Information about a connected macOS display selectable for streaming.
public struct DisplayInfo: Identifiable, Equatable, Sendable {
    public let id: String // CGDirectDisplayID decimal string
    public let name: String
    public let width: Int
    public let height: Int
    public let isPrimary: Bool

    public init(id: String, name: String, width: Int, height: Int, isPrimary: Bool) {
        self.id = id
        self.name = name
        self.width = width
        self.height = height
        self.isPrimary = isPrimary
    }

    public var resolutionText: String {
        "\(width) × \(height)"
    }
}

public enum DisplayEnumerator {
    public static func currentDisplays() -> [DisplayInfo] {
        var displayCount: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &displayCount) == .success, displayCount > 0 else {
            return []
        }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(displayCount))
        guard CGGetActiveDisplayList(displayCount, &ids, &displayCount) == .success else {
            return []
        }

        let mainID = CGMainDisplayID()
        let screensByNumber: [CGDirectDisplayID: NSScreen] = {
            if Thread.isMainThread {
                return Dictionary(uniqueKeysWithValues: NSScreen.screens.compactMap { screen in
                    guard let num = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber else {
                        return nil
                    }
                    return (CGDirectDisplayID(num.uint32Value), screen)
                })
            }
            return [:]
        }()

        return ids.prefix(Int(displayCount)).enumerated().map { idx, cgID in
            let mode = CGDisplayCopyDisplayMode(cgID)
            let width = mode?.pixelWidth ?? CGDisplayPixelsWide(cgID)
            let height = mode?.pixelHeight ?? CGDisplayPixelsHigh(cgID)
            let screenName = screensByNumber[cgID]?.localizedName
            let fallbackName = CGDisplayIsBuiltin(cgID) != 0 ? "内建显示器" : "外接显示器 \(idx + 1)"
            return DisplayInfo(
                id: String(cgID),
                name: (screenName?.isEmpty == false ? screenName! : fallbackName),
                width: width,
                height: height,
                isPrimary: cgID == mainID
            )
        }
    }
}

/// Real-time LAN / Wi-Fi network monitor using `NWPathMonitor`.
public struct NetworkSnapshot: Equatable, Sendable {
    public let isConnected: Bool
    public let interfaceSummary: String
    public let localIPv4: String?

    public init(isConnected: Bool, interfaceSummary: String, localIPv4: String?) {
        self.isConnected = isConnected
        self.interfaceSummary = interfaceSummary
        self.localIPv4 = localIPv4
    }
}

public final class NetworkMonitor: @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "dev.depth3d.host.network", qos: .utility)
    private let lock = NSLock()
    private var snapshot = NetworkSnapshot(isConnected: true, interfaceSummary: "Wi-Fi", localIPv4: nil)
    private var onChange: (@Sendable (NetworkSnapshot) -> Void)?

    public init() {}

    deinit {
        stop()
    }

    public func currentSnapshot() -> NetworkSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return snapshot
    }

    public func start(onChange: @escaping @Sendable (NetworkSnapshot) -> Void) {
        lock.lock()
        self.onChange = onChange
        lock.unlock()

        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let ip = Self.primaryLocalIPv4()
            let connected = (path.status == .satisfied) && (ip != nil || path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet))
            let iface: String
            if path.usesInterfaceType(.wiredEthernet) {
                iface = "以太网"
            } else if path.usesInterfaceType(.wifi) {
                iface = "Wi-Fi"
            } else if connected {
                iface = "局域网"
            } else {
                iface = "未连接"
            }
            let next = NetworkSnapshot(isConnected: connected, interfaceSummary: iface, localIPv4: ip)
            self.lock.lock()
            let changed = (self.snapshot != next)
            self.snapshot = next
            let cb = self.onChange
            self.lock.unlock()
            if changed {
                HostLogger.info("Network state changed: connected=\(connected), iface=\(iface)", category: "network")
                cb?(next)
            }
        }
        monitor.start(queue: queue)
    }

    public func stop() {
        monitor.cancel()
    }

    public static func primaryLocalIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var fallback: String?
        var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = ptr {
            defer { ptr = current.pointee.ifa_next }
            let flags = Int32(current.pointee.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0,
                  let addr = current.pointee.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_INET)
            else {
                continue
            }

            let name = String(cString: current.pointee.ifa_name)
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                addr,
                socklen_t(addr.pointee.sa_len),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard result == 0 else { continue }
            let ip = String(cString: hostname)
            if ip.hasPrefix("169.254.") { continue }
            if name == "en0" || name == "en1" {
                return ip
            }
            if fallback == nil {
                fallback = ip
            }
        }
        return fallback
    }
}

/// Manages macOS 14+ Login Item registration via `SMAppService.mainApp`.
public enum LaunchAtLoginManager {
    public static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    public static func setEnabled(_ enabled: Bool) throws {
        if enabled {
            try SMAppService.mainApp.register()
        } else {
            try SMAppService.mainApp.unregister()
        }
    }
}
