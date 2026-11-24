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
    public static func makeDictionary(
        name: String,
        id: String,
        gsPort: UInt16,
        mediaPort: UInt16?
    ) -> [String: Data] {
        var dict: [String: Data] = [
            "v": Data("1".utf8),
            "name": Data(name.utf8),
            "id": Data(id.utf8),
            "pair": Data("auto".utf8),
            "gs": Data(String(gsPort).utf8)
        ]
        if let mediaPort {
            dict["media"] = Data(String(mediaPort).utf8)
        }
        return dict
    }
}

/// Advertises `_depth3d-host._tcp` (and optionally `_webdav._tcp` when media service is active)
/// through macOS's system mDNSResponder via `NetService`.
public final class HostBonjourAdvertiser: NSObject, NetServiceDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var hostService: NetService?
    private var webDAVService: NetService?
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
        gsPort: UInt16,
        mediaPort: UInt16?
    ) {
        let work = { [weak self] in
            guard let self else { return }
            self.stopHostOnMain()
            let service = NetService(domain: "local.", type: "_depth3d-host._tcp.", name: name, port: Int32(controlPort))
            let txtDict = HostBonjourTXTRecord.makeDictionary(
                name: name,
                id: hostId,
                gsPort: gsPort,
                mediaPort: mediaPort
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
            HostLogger.info("Publishing mDNS _depth3d-host._tcp on port \(controlPort) (gs=\(gsPort), media=\(mediaPort.map(String.init) ?? "none"))", category: "mdns")
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
        gsPort: UInt16,
        mediaPort: UInt16?
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
                mediaPort: mediaPort
            )
            service.setTXTRecord(NetService.data(fromTXTRecord: txtDict))
        }
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }

    public func publishWebDAV(computerName: String, port: UInt16?) {
        let work = { [weak self] in
            guard let self else { return }
            self.stopWebDAVOnMain()
            guard let port else { return }
            let service = NetService(domain: "local.", type: "_webdav._tcp.", name: "depth3d \(computerName)", port: Int32(port))
            service.setTXTRecord(NetService.data(fromTXTRecord: ["path": Data("/".utf8)]))
            self.lock.lock()
            self.webDAVService = service
            self.lock.unlock()
            service.publish()
            HostLogger.info("Publishing mDNS _webdav._tcp on port \(port)", category: "mdns")
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
            self.stopWebDAVOnMain()
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

    private func stopWebDAVOnMain() {
        lock.lock()
        let service = webDAVService
        webDAVService = nil
        lock.unlock()
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
        // -72008 / kDNSServiceErr_PolicyDenied (-65570) indicates Local Network permission denied.
        if code == -65570 || code == -72008 {
            HostLogger.warning("mDNS publish blocked by Local Network permission (\(code))", category: "mdns")
            updateState(.permissionDenied)
        } else {
            HostLogger.error("mDNS publish failed with code \(code)", category: "mdns")
            updateState(.failed(code))
        }
    }
}
