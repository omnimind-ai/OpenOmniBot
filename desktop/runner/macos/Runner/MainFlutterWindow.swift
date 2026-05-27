import Cocoa
import FlutterMacOS

class MainFlutterWindow: NSWindow {
  override func awakeFromNib() {
    // Storyboard loads us before applicationWillFinishLaunching, so kick off the
    // backend here if needed.
    if BackendSupervisor.shared.port == nil {
      do {
        _ = try BackendSupervisor.shared.start()
        _supervisorLogWindow("started backend from awakeFromNib")
      } catch {
        _supervisorLogWindow("backend start FAILED in awakeFromNib: \(error)")
      }
    }
    let project = FlutterDartProject()
    var args: [String] = []
    if let port = BackendSupervisor.shared.port {
      args.append("--backend-port=\(port)")
    }
    project.dartEntrypointArguments = args
    _supervisorLogWindow("MainFlutterWindow.awakeFromNib: dartEntrypointArguments=\(args)")

    let flutterViewController = FlutterViewController(project: project)
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)

    RegisterGeneratedPlugins(registry: flutterViewController)

    super.awakeFromNib()
  }
}

fileprivate func _supervisorLogWindow(_ message: String) {
  let path = (NSHomeDirectory() as NSString).appendingPathComponent("Library/Logs/OmnibotApp/supervisor.log")
  try? FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
  if !FileManager.default.fileExists(atPath: path) { FileManager.default.createFile(atPath: path, contents: nil) }
  if let fh = try? FileHandle(forWritingTo: URL(fileURLWithPath: path)) {
    try? fh.seekToEnd()
    try? fh.write(contentsOf: Data("[window] \(message)\n".utf8))
    try? fh.close()
  }
}
