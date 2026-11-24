import Darwin
import Foundation

/// Deadlock-free `Process` exit waiter for sync and async tests.
///
/// Installs `process.terminationHandler` BEFORE `process.run()` and waits with a bounded timeout
/// instead of calling Foundation's `Process.waitUntilExit()` (which can hang indefinitely after
/// `terminate()` inside Swift async tests on macOS).
final class ProcessExitWaiter: @unchecked Sendable {
    private let process: Process
    private let lock = NSLock()
    private let semaphore = DispatchSemaphore(value: 0)
    private var exited = false
    private var exitStatus: Int32 = -1
    private var continuations: [CheckedContinuation<Bool, Never>] = []

    init(process: Process) {
        self.process = process
        process.terminationHandler = { [weak self] proc in
            self?.markExited(status: proc.terminationStatus)
        }
    }

    private func markExited(status: Int32) {
        lock.lock()
        guard !exited else {
            lock.unlock()
            return
        }
        exited = true
        exitStatus = status
        let waiting = continuations
        continuations.removeAll()
        lock.unlock()

        semaphore.signal()
        for cont in waiting {
            cont.resume(returning: true)
        }
    }

    var terminationStatus: Int32 {
        lock.lock()
        defer { lock.unlock() }
        return exited ? exitStatus : process.terminationStatus
    }

    @discardableResult
    func wait(timeout: TimeInterval = 5.0) -> Bool {
        lock.lock()
        if exited {
            lock.unlock()
            return true
        }
        lock.unlock()

        let res = semaphore.wait(timeout: .now() + max(0.05, timeout))
        if res == .success {
            // Re-signal so repeated wait() calls return immediately.
            semaphore.signal()
            return true
        }
        lock.lock()
        let done = exited
        lock.unlock()
        return done
    }

    @discardableResult
    func waitAsync(timeout: TimeInterval = 5.0) async -> Bool {
        if lock.withLock({ exited }) {
            return true
        }

        return await withCheckedContinuation { cont in
            let alreadyDone: Bool = lock.withLock {
                if exited {
                    return true
                }
                continuations.append(cont)
                return false
            }

            if alreadyDone {
                cont.resume(returning: true)
                return
            }

            let timeoutNs = UInt64(max(0.05, timeout) * 1_000_000_000)
            DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + .nanoseconds(Int(timeoutNs))) { [weak self] in
                guard let self else { return }
                let pending: [CheckedContinuation<Bool, Never>] = self.lock.withLock {
                    if self.exited || self.continuations.isEmpty {
                        return []
                    }
                    let list = self.continuations
                    self.continuations.removeAll()
                    return list
                }
                for c in pending {
                    c.resume(returning: false)
                }
            }
        }
    }

    @discardableResult
    func terminateAndWaitAsync(timeout: TimeInterval = 3.0) async -> Bool {
        let pid = process.processIdentifier
        if pid > 1 {
            _ = Darwin.kill(pid, SIGTERM)
        }
        if await waitAsync(timeout: timeout) {
            return true
        }
        if pid > 1 {
            _ = Darwin.kill(pid, SIGKILL)
        }
        return await waitAsync(timeout: 1.5)
    }

    @discardableResult
    func terminateAndWaitSync(timeout: TimeInterval = 3.0) -> Bool {
        let pid = process.processIdentifier
        if pid > 1 {
            _ = Darwin.kill(pid, SIGTERM)
        }
        if wait(timeout: timeout) {
            return true
        }
        if pid > 1 {
            _ = Darwin.kill(pid, SIGKILL)
        }
        return wait(timeout: 1.5)
    }
}

/// Runs a short-lived helper command (`curl`, `openssl`, `bash`) with concurrent pipe draining
/// and a bounded exit timeout — never calling `Process.waitUntilExit()`.
enum TestProcessRunner {
    struct Output: Sendable {
        let exitCode: Int32
        let stdout: Data
        let stderr: Data
        let timedOut: Bool
    }

    static func run(
        executable: String,
        arguments: [String],
        captureStdout: Bool = true,
        captureStderr: Bool = false,
        timeout: TimeInterval = 15.0
    ) throws -> Output {
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: executable)
        proc.arguments = arguments

        let outPipe = captureStdout ? Pipe() : nil
        let errPipe = captureStderr ? Pipe() : nil
        proc.standardOutput = outPipe ?? FileHandle.nullDevice
        proc.standardError = errPipe ?? FileHandle.nullDevice

        let waiter = ProcessExitWaiter(process: proc)
        try proc.run()

        final class DataBox: @unchecked Sendable {
            var data = Data()
        }
        let outBox = DataBox()
        let errBox = DataBox()
        let group = DispatchGroup()

        if let outPipe {
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async {
                outBox.data = outPipe.fileHandleForReading.readDataToEndOfFile()
                group.leave()
            }
        }
        if let errPipe {
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async {
                errBox.data = errPipe.fileHandleForReading.readDataToEndOfFile()
                group.leave()
            }
        }

        let finished = waiter.wait(timeout: timeout)
        if !finished {
            let pid = proc.processIdentifier
            if pid > 1 {
                _ = Darwin.kill(pid, SIGKILL)
            }
            _ = waiter.wait(timeout: 1.5)
        }
        _ = group.wait(timeout: .now() + 2.0)

        return Output(
            exitCode: finished ? waiter.terminationStatus : -1,
            stdout: outBox.data,
            stderr: errBox.data,
            timedOut: !finished
        )
    }

    static func runCurl(_ args: [String], timeout: TimeInterval = 15.0) -> (Int, String) {
        guard let res = try? run(
            executable: "/usr/bin/curl",
            arguments: ["--noproxy", "*"] + args + ["-w", "\n%{http_code}"],
            captureStdout: true,
            captureStderr: false,
            timeout: timeout
        ) else {
            return (0, "")
        }
        let raw = String(decoding: res.stdout, as: UTF8.self)
        let lines = raw.components(separatedBy: "\n")
        let code = Int(lines.last ?? "") ?? 0
        let body = lines.dropLast().joined(separator: "\n")
        return (code, body)
    }
}
