import Cocoa
import FlutterMacOS

@main
class AppDelegate: FlutterAppDelegate {
  override func applicationWillFinishLaunching(_ notification: Notification) {
    // MainFlutterWindow.awakeFromNib already kicked off the supervisor; we only
    // start here as a redundant fallback if the window has not been instantiated yet.
    if BackendSupervisor.shared.port == nil {
      do {
        let p = try BackendSupervisor.shared.start()
        _writeSupervisorLog("AppDelegate: backend started port=\(p)")
      } catch {
        _writeSupervisorLog("AppDelegate: backend start FAILED: \(error)")
      }
    } else {
      _writeSupervisorLog("AppDelegate: backend already running on port \(BackendSupervisor.shared.port!)")
    }
    super.applicationWillFinishLaunching(notification)
  }

  private func _writeSupervisorLog(_ message: String) {
    let path = (NSHomeDirectory() as NSString).appendingPathComponent("Library/Logs/OmnibotApp/supervisor.log")
    let line = "[delegate] \(message)\n"
    try? FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    if !FileManager.default.fileExists(atPath: path) { FileManager.default.createFile(atPath: path, contents: nil) }
    if let fh = try? FileHandle(forWritingTo: URL(fileURLWithPath: path)) {
      try? fh.seekToEnd()
      try? fh.write(contentsOf: Data(line.utf8))
      try? fh.close()
    }
  }

  override func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
    BackendSupervisor.shared.stop()
    return .terminateNow
  }

  override func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
    return true
  }

  override func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
    return true
  }
}
