import Foundation

/// Launches and supervises the bundled `omnibot-backend` Rust binary.
///
/// Responsibilities:
/// - Spawn the binary as a child of this process so `terminationHandler` clean-up is automatic
/// - Read stdout until the backend prints `OMNIBOT_BACKEND_PORT=<port>`,
///   and inject it into the process environment via `setenv` so Flutter (Dart) can read it
/// - Forward all subsequent stdout/stderr to ~/Library/Logs/OmnibotApp/backend-stdout.log
/// - On unexpected exit: if the backend lived >= 10 s, attempt one restart, otherwise surface
///   a dialog hint via a notification (left to the UI layer).
final class BackendSupervisor {
    static let shared = BackendSupervisor()

    private let host = "127.0.0.1"
    private let defaultPort: UInt16 = 58761
    private var process: Process?
    private(set) var port: UInt16?
    private var logFileHandle: FileHandle?
    private var lastStartedAt: Date?
    private let queue = DispatchQueue(label: "com.omnimind.omnibot.supervisor")

    private func dbg(_ message: String) {
        // Sandboxed NSLog seems to be filtered; write directly to the log file so we can grep.
        let line = "[supervisor] \(message)\n"
        let logsDir = (NSHomeDirectory() as NSString).appendingPathComponent("Library/Logs/OmnibotApp")
        try? FileManager.default.createDirectory(atPath: logsDir, withIntermediateDirectories: true)
        let path = (logsDir as NSString).appendingPathComponent("supervisor.log")
        if !FileManager.default.fileExists(atPath: path) {
            FileManager.default.createFile(atPath: path, contents: nil)
        }
        if let fh = try? FileHandle(forWritingTo: URL(fileURLWithPath: path)) {
            _ = try? fh.seekToEnd()
            try? fh.write(contentsOf: Data(line.utf8))
            try? fh.close()
        }
    }

    // Public entry. Returns the assigned port (after first-line stdout) or throws.
    @discardableResult
    func start() throws -> UInt16 {
        dbg("start() called")
        try queue.sync {
            try _startLocked()
        }
        dbg("start() returning port=\(port ?? 0)")
        return port ?? 0
    }

    func stop() {
        queue.sync { [weak self] in
            guard let self else { return }
            self.process?.terminationHandler = nil
            self.process?.terminate()
        }
        // Give it 3 s, then SIGKILL.
        DispatchQueue.global().asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.queue.sync {
                if let p = self?.process, p.isRunning {
                    kill(p.processIdentifier, SIGKILL)
                }
            }
        }
    }

    private func _startLocked() throws {
        let support = NSSearchPathForDirectoriesInDomains(.applicationSupportDirectory,
                                                          .userDomainMask, true).first ?? "/tmp"
        let dataDir = (support as NSString).appendingPathComponent("OmnibotApp")
        try? FileManager.default.createDirectory(atPath: dataDir,
                                                  withIntermediateDirectories: true)

        let logsDir = (NSHomeDirectory() as NSString).appendingPathComponent("Library/Logs/OmnibotApp")
        try? FileManager.default.createDirectory(atPath: logsDir,
                                                  withIntermediateDirectories: true)
        setenv("OMNIBOT_DATA_DIR", dataDir, 1)

        if let p = self.process, p.isRunning {
            if let existingPort = self.port, isBackendHealthy(existingPort) {
                return
            }
            p.terminationHandler = nil
            p.terminate()
        }

        if let existingPort = healthyExistingBackend(dataDir: dataDir, logsDir: logsDir) {
            adopt(port: existingPort, dataDir: dataDir, source: "existing healthy backend")
            return
        }

        let bundle = Bundle.main
        guard let resourcePath = bundle.resourcePath else {
            throw NSError(domain: "BackendSupervisor", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "missing resource path"])
        }
        let binaryPath = "\(resourcePath)/omnibot-backend"
        guard FileManager.default.fileExists(atPath: binaryPath) else {
            throw NSError(domain: "BackendSupervisor", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "omnibot-backend binary missing at \(binaryPath)"])
        }

        let logPath = (logsDir as NSString).appendingPathComponent("backend-stdout.log")
        if !FileManager.default.fileExists(atPath: logPath) {
            FileManager.default.createFile(atPath: logPath, contents: nil)
        }
        let logFh = try FileHandle(forWritingTo: URL(fileURLWithPath: logPath))
        _ = try? logFh.seekToEnd()
        self.logFileHandle = logFh

        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: binaryPath)
        proc.arguments = ["--data-dir", dataDir, "--bind", "\(host):\(defaultPort)"]
        let stdoutPipe = Pipe()
        let stderrPipe = Pipe()
        proc.standardOutput = stdoutPipe
        proc.standardError = stderrPipe
        var env = ProcessInfo.processInfo.environment
        env["OMNIBOT_DATA_DIR"] = dataDir
        env["OMNIBOT_LOG"] = env["OMNIBOT_LOG"] ?? "info"
        proc.environment = env

        // The backend writes its port to `<data_dir>/.backend.port`; we delete any stale
        // copy before launching so we know the file we see was written by *this* run.
        let portFilePath = (dataDir as NSString).appendingPathComponent(".backend.port")
        try? FileManager.default.removeItem(atPath: portFilePath)

        try proc.run()
        self.process = proc
        self.lastStartedAt = Date()
        dbg("spawned backend pid=\(proc.processIdentifier), bind=\(host):\(defaultPort), waiting for port...")

        let port: UInt16
        do {
            port = try readPort(from: stdoutPipe.fileHandleForReading, timeoutSeconds: 8)
        } catch {
            dbg("stdout port read failed: \(error); falling back to port file")
            do {
                port = try waitForPortFile(at: portFilePath, timeoutSeconds: 4)
            } catch {
                proc.terminate()
                throw error
            }
        }
        adopt(port: port, dataDir: dataDir, source: "spawned pid=\(proc.processIdentifier)")
        dbg("[BackendSupervisor] started pid=\(proc.processIdentifier) port=\(port)")

        // Continue forwarding the rest of stdout to the log file in the background.
        forwardRemainingOutput(stdoutPipe.fileHandleForReading)
        forwardRemainingOutput(stderrPipe.fileHandleForReading)

        proc.terminationHandler = { [weak self] p in
            self?.handleTermination(p)
        }
    }

    private func waitForPortFile(at path: String, timeoutSeconds: TimeInterval) throws -> UInt16 {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if let data = try? Data(contentsOf: URL(fileURLWithPath: path)),
               let s = String(data: data, encoding: .utf8),
               let p = UInt16(s.trimmingCharacters(in: .whitespacesAndNewlines)) {
                return p
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        throw NSError(domain: "BackendSupervisor", code: 5,
                      userInfo: [NSLocalizedDescriptionKey: "backend port file did not appear at \(path) within \(Int(timeoutSeconds))s"])
    }

    private func readPort(from handle: FileHandle, timeoutSeconds: TimeInterval) throws -> UInt16 {
        // Read until the backend emits `OMNIBOT_BACKEND_PORT=<port>`.
        // Tracing logs can appear before that line, so keep scanning instead of
        // requiring it to be the first stdout line.
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        var buffer = Data()
        while Date() < deadline {
            let chunk: Data
            do {
                chunk = try handle.read(upToCount: 256) ?? Data()
            } catch {
                throw error
            }
            if chunk.isEmpty {
                Thread.sleep(forTimeInterval: 0.02)
                continue
            }
            buffer.append(chunk)
            while let newlineRange = buffer.firstRange(of: Data([0x0A])) {
                let line = buffer.subdata(in: 0..<newlineRange.lowerBound)
                let tail = buffer.subdata(in: newlineRange.upperBound..<buffer.count)
                buffer = tail
                if let s = String(data: line, encoding: .utf8),
                   let eq = s.firstIndex(of: "="),
                   s.hasPrefix("OMNIBOT_BACKEND_PORT") {
                    let portStr = String(s[s.index(after: eq)...])
                    if let p = UInt16(portStr.trimmingCharacters(in: .whitespacesAndNewlines)) {
                        // Forward any bytes already read past the newline into the log.
                        if !tail.isEmpty { try? self.logFileHandle?.write(contentsOf: tail) }
                        return p
                    }
                }
                try? self.logFileHandle?.write(contentsOf: line)
                try? self.logFileHandle?.write(contentsOf: Data([0x0A]))
            }
        }
        throw NSError(domain: "BackendSupervisor", code: 4,
                      userInfo: [NSLocalizedDescriptionKey: "timed out waiting for backend port line"])
    }

    private func adopt(port: UInt16, dataDir: String, source: String) {
        self.port = port
        setenv("OMNIBOT_BACKEND_PORT", String(port), 1)
        setenv("OMNIBOT_DATA_DIR", dataDir, 1)
        let portFilePath = (dataDir as NSString).appendingPathComponent(".backend.port")
        try? "\(port)\n".write(toFile: portFilePath, atomically: true, encoding: .utf8)
        dbg("adopted backend port=\(port) source=\(source)")
    }

    private func healthyExistingBackend(dataDir: String, logsDir: String) -> UInt16? {
        for candidate in backendPortCandidates(dataDir: dataDir, logsDir: logsDir) {
            if isBackendHealthy(candidate) {
                dbg("healthy backend candidate port=\(candidate)")
                return candidate
            }
            dbg("ignoring unhealthy backend candidate port=\(candidate)")
        }
        return nil
    }

    private func backendPortCandidates(dataDir: String, logsDir: String) -> [UInt16] {
        var result: [UInt16] = []
        var seen = Set<UInt16>()

        func add(_ value: UInt16?) {
            guard let value, value > 0, seen.insert(value).inserted else { return }
            result.append(value)
        }

        add(UInt16(ProcessInfo.processInfo.environment["OMNIBOT_BACKEND_PORT"] ?? ""))
        let portFilePath = (dataDir as NSString).appendingPathComponent(".backend.port")
        add(readPortFile(portFilePath))
        add(defaultPort)

        let logPaths = [
            (logsDir as NSString).appendingPathComponent("backend-stdout.log"),
            (logsDir as NSString).appendingPathComponent("dart-dev-backend.log"),
            (logsDir as NSString).appendingPathComponent("supervisor.log"),
        ]
        for port in loggedPorts(paths: logPaths) {
            add(port)
        }
        return result
    }

    private func readPortFile(_ path: String) -> UInt16? {
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)),
              let s = String(data: data, encoding: .utf8) else {
            return nil
        }
        return UInt16(s.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private func loggedPorts(paths: [String]) -> [UInt16] {
        var ports: [UInt16] = []
        var seen = Set<UInt16>()
        for path in paths {
            guard let text = tailText(path, maxBytes: 512 * 1024) else { continue }
            for port in extractPorts(from: text).reversed() {
                if seen.insert(port).inserted {
                    ports.append(port)
                }
            }
        }
        return ports
    }

    private func tailText(_ path: String, maxBytes: UInt64) -> String? {
        guard FileManager.default.fileExists(atPath: path),
              let handle = try? FileHandle(forReadingFrom: URL(fileURLWithPath: path)) else {
            return nil
        }
        defer { try? handle.close() }
        do {
            let size = try handle.seekToEnd()
            let offset = size > maxBytes ? size - maxBytes : 0
            try handle.seek(toOffset: offset)
            let data = try handle.readToEnd() ?? Data()
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }

    private func extractPorts(from text: String) -> [UInt16] {
        let patterns = [
            #"OMNIBOT_BACKEND_PORT=([0-9]{2,5})"#,
            #""port"\s*:\s*([0-9]{2,5})"#,
            #"listening\s+port=([0-9]{2,5})"#,
        ]
        var ports: [UInt16] = []
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(text.startIndex..<text.endIndex, in: text)
            for match in regex.matches(in: text, range: range) {
                guard match.numberOfRanges > 1,
                      let valueRange = Range(match.range(at: 1), in: text),
                      let port = UInt16(text[valueRange]) else {
                    continue
                }
                ports.append(port)
            }
        }
        return ports
    }

    private func isBackendHealthy(_ port: UInt16) -> Bool {
        guard let url = URL(string: "http://\(host):\(port)/health") else { return false }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 0.7)
        request.httpMethod = "GET"
        let semaphore = DispatchSemaphore(value: 0)
        var body = Data()
        var statusCode = 0
        let task = URLSession.shared.dataTask(with: request) { _, response, _ in
            if let http = response as? HTTPURLResponse {
                statusCode = http.statusCode
            }
            semaphore.signal()
        }
        task.resume()
        if semaphore.wait(timeout: .now() + 0.9) == .timedOut {
            task.cancel()
            return false
        }
        if statusCode != 200 {
            return false
        }

        let bodyTask = URLSession.shared.dataTask(with: request) { data, response, _ in
            if let http = response as? HTTPURLResponse {
                statusCode = http.statusCode
            }
            body = data ?? Data()
            semaphore.signal()
        }
        bodyTask.resume()
        if semaphore.wait(timeout: .now() + 0.9) == .timedOut {
            bodyTask.cancel()
            return false
        }
        guard statusCode == 200,
              let payload = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let capabilities = payload["capabilities"] as? [String: Any],
              capabilities["agentBrowserSession"] as? Bool == true else {
            return false
        }
        return true
    }

    private func forwardRemainingOutput(_ handle: FileHandle) {
        handle.readabilityHandler = { [weak self] fh in
            let data = fh.availableData
            if data.isEmpty {
                handle.readabilityHandler = nil
                return
            }
            try? self?.logFileHandle?.write(contentsOf: data)
        }
    }

    private func handleTermination(_ p: Process) {
        dbg("[BackendSupervisor] backend exited code=\(p.terminationStatus)")
        let elapsed = self.lastStartedAt.map { Date().timeIntervalSince($0) } ?? 0
        guard elapsed > 10 else {
            dbg("[BackendSupervisor] crash within 10 s; not restarting.")
            return
        }
        // Restart once.
        do {
            try queue.sync { try _startLocked() }
            dbg("[BackendSupervisor] restarted backend after crash.")
        } catch {
            dbg("[BackendSupervisor] restart failed: \(error)")
        }
    }
}
