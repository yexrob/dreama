import Foundation

/// mDNS publication status for `_depth3d-host._tcp`.
public enum BonjourPublicationState: Equatable, Sendable {
    case stopped
    case publishing
    case published
    case permissionDenied
    case failed(Int)
}

/// Pure TXT record builder for `_depth3d-host._tcp` matching `docs/specs/host-protocol.md`.
public enum HostBonjourTXTRecord {
    /// Builds TXT entries.
    /// - `gsPort`: omitted when streaming engine is not ready (port conflict, paused, crashed).
    /// - `mediaPort`: omitted when media service is disabled or not running.
    /// - `mediaRevision`: incremented when WebDAV credentials rotate after device unpair so
    ///   still-paired headsets observing Bonjour TXT know to re-fetch `/depth3d/v1/media`.
    public static func makeDictionary(
        name: String,
        id: String,
        gsPort: UInt16?,
        mediaPort: UInt16?,
        mediaRevision: Int = 0
    ) -> [String: Data] {
        var dict: [String: Data] = [
            "v": Data("1".utf8),
            "name": Data(name.utf8),
            "id": Data(id.utf8),
            "pair": Data("auto".utf8)
        ]
        if let gsPort {
            dict["gs"] = Data(String(gsPort).utf8)
        }
        if let mediaPort {
            dict["media"] = Data(String(mediaPort).utf8)
            if mediaRevision > 0 {
                dict["mrev"] = Data(String(mediaRevision).utf8)
            }
        }
        return dict
    }

    /// Maps `NetService` error codes accurately: ONLY Local Network policy denial
    /// (`kDNSServiceErr_PolicyDenied` = -65570, `NSNetServicesErrorCode` -72008) reports
    /// `.permissionDenied`; other errors (collision, timeout, invalid args) report `.failed(code)`.
    public static func classifyPublishError(code: Int) -> BonjourPublicationState {
        if code == -65570 || code == -72008 {
            return .permissionDenied
        }
        return .failed(code)
    }
}

/// Advertises ONLY `_depth3d-host._tcp` through macOS's system mDNSResponder via `NetService`.
/// Does NOT broadcast `_webdav._tcp` LAN-wide; paired headsets obtain WebDAV credentials via
/// mTLS `/depth3d/v1/media`.
public final class HostBonjourAdvertiser: NSObject, NetServiceDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var hostService: NetService?
    private var stateHandler: (@Sendable (BonjourPublicationState) -> Void)?
    private var currentState: BonjourPublicationState = .stopped

    public var state: BonjourPublicationState {
        lock.lock()
        defer { lock.unlock() }
        return currentState
    }

    public func setStateHandler(_ handler: (@Sendable (BonjourPublicationState) -> Void)?) {
        lock.lock()
        self.stateHandler = handler
        let snapshot = currentState
        lock.unlock()
        handler?(snapshot)
    }

    public func publishHost(
        name: String,
        hostId: String,
        controlPort: UInt16,
        gsPort: UInt16?,
        mediaPort: UInt16?,
        mediaRevision: Int = 0
    ) {
        let work = { [weak self] in
            guard let self else { return }
            self.stopHostOnMain()
            let service = NetService(domain: "local.", type: "_depth3d-host._tcp.", name: name, port: Int32(controlPort))
            let txtDict = HostBonjourTXTRecord.makeDictionary(
                name: name,
                id: hostId,
                gsPort: gsPort,
                mediaPort: mediaPort,
                mediaRevision: mediaRevision
            )
            service.setTXTRecord(NetService.data(fromTXTRecord: txtDict))
            service.delegate = self
            self.lock.lock()
            self.hostService = service
            self.currentState = .publishing
            let cb = self.stateHandler
            self.lock.unlock()
            cb?(.publishing)
            service.publish()
            HostLogger.info(
                "Publishing mDNS _depth3d-host._tcp on port \(controlPort) (gs=\(gsPort.map(String.init) ?? "none"), media=\(mediaPort.map(String.init) ?? "none"))",
                category: "mdns"
            )
        }
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }

    public func updateHostTXT(
        name: String,
        hostId: String,
        gsPort: UInt16?,
        mediaPort: UInt16?,
        mediaRevision: Int = 0
    ) {
        let work = { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let service = self.hostService
            self.lock.unlock()
            guard let service else { return }
            let txtDict = HostBonjourTXTRecord.makeDictionary(
                name: name,
                id: hostId,
                gsPort: gsPort,
                mediaPort: mediaPort,
                mediaRevision: mediaRevision
            )
            service.setTXTRecord(NetService.data(fromTXTRecord: txtDict))
        }
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }

    public func stop() {
        let work = { [weak self] in
            guard let self else { return }
            self.stopHostOnMain()
            self.updateState(.stopped)
        }
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.sync(execute: work)
        }
    }

    private func stopHostOnMain() {
        lock.lock()
        let service = hostService
        hostService = nil
        lock.unlock()
        service?.delegate = nil
        service?.stop()
    }

    private func updateState(_ next: BonjourPublicationState) {
        lock.lock()
        currentState = next
        let cb = stateHandler
        lock.unlock()
        cb?(next)
    }

    // MARK: - NetServiceDelegate

    public func netServiceDidPublish(_ sender: NetService) {
        lock.lock()
        let isHost = (sender === hostService)
        lock.unlock()
        guard isHost else { return }
        HostLogger.info("mDNS _depth3d-host._tcp published as \(sender.name)", category: "mdns")
        updateState(.published)
    }

    public func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        lock.lock()
        let isHost = (sender === hostService)
        lock.unlock()
        guard isHost else { return }
        let code = errorDict[NetService.errorCode]?.intValue ?? -1
        let classified = HostBonjourTXTRecord.classifyPublishError(code: code)
        switch classified {
        case .permissionDenied:
            HostLogger.warning("mDNS publish blocked by Local Network permission (\(code))", category: "mdns")
        default:
            HostLogger.error("mDNS publish failed with non-permission error (\(code))", category: "mdns")
        }
        updateState(classified)
    }
}
