import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'bridge_method_channel_registry.dart';
import 'bridge_websocket_client.dart';

const int _defaultBackendPort = 58761;
const String _loopbackHost = '127.0.0.1';

/// Resolves the backend port and prepares a [BridgeWebsocketClient].
///
/// The port is delivered via Flutter `dartEntrypointArguments`
/// (`--backend-port=<n>`) set by the native runner. We fall back to an
/// environment variable so tests / `flutter run --dart-entrypoint-args=...`
/// flows still work.
///
/// Does NOT touch any [WidgetsBinding] — call before
/// `DesktopWidgetsBinding.ensureInitialized()`.
Future<BridgeWebsocketClient?> installChannelBridge({
  List<String>? args,
}) async {
  if (_pendingWs != null) {
    return _pendingWs;
  }
  if (kIsWeb) {
    return null;
  }
  if (!(Platform.isMacOS || Platform.isWindows || Platform.isLinux)) {
    return null;
  }

  final candidates = <_PortCandidate>[];
  var sawBackendPortArg = false;
  if (args != null) {
    for (final a in args) {
      if (a.startsWith('--backend-port=')) {
        sawBackendPortArg = true;
        candidates.add(
          _PortCandidate(
            int.tryParse(a.substring('--backend-port='.length)),
            'dart entrypoint args',
          ),
        );
        break;
      }
    }
  }
  if (sawBackendPortArg &&
      (candidates.isEmpty ||
          candidates.first.port == null ||
          candidates.first.port! <= 0)) {
    debugPrint(
      'OmniBot bridge: explicit backend port is invalid (args=$args); channel bridge not installed.',
    );
    return null;
  }

  final envPort = int.tryParse(
    Platform.environment['OMNIBOT_BACKEND_PORT'] ?? '',
  );
  if (envPort != null) {
    candidates.add(_PortCandidate(envPort, 'OMNIBOT_BACKEND_PORT'));
  }
  candidates.addAll(await _readBackendPortFiles());
  candidates.add(_PortCandidate(_defaultBackendPort, 'default desktop port'));
  candidates.addAll(await _readBackendPortsFromLogs());

  var port = await _firstHealthyBackendPort(candidates);
  port ??= await _startDevBackendFromSource();
  if (port == null || port <= 0) {
    debugPrint(
      'OmniBot bridge: backend port missing (args=$args); channel bridge not installed.',
    );
    return null;
  }
  _pendingWs = BridgeWebsocketClient(port: port);
  installRegisteredBridgeMethodHandlers(_pendingWs!);
  debugPrint('OmniBot bridge: target backend port=$port');
  return _pendingWs;
}

BridgeWebsocketClient? _pendingWs;
Future<int?>? _devBackendStartFuture;
Process? _devBackendProcess;

/// Accessor used by `DesktopWidgetsBinding.createBinaryMessenger`.
BridgeWebsocketClient? get pendingBridgeClient => _pendingWs;

Future<int?> _firstHealthyBackendPort(
  Iterable<_PortCandidate> candidates,
) async {
  final seen = <int>{};
  for (final candidate in candidates) {
    final port = candidate.port;
    if (port == null || port <= 0 || !seen.add(port)) {
      continue;
    }
    if (await _isBackendHealthy(port)) {
      debugPrint(
        'OmniBot bridge: discovered healthy backend port=$port from ${candidate.source}',
      );
      await _persistBackendPort(port);
      return port;
    }
    debugPrint(
      'OmniBot bridge: ignoring unhealthy backend port=$port from ${candidate.source}',
    );
  }
  return null;
}

Future<List<_PortCandidate>> _readBackendPortFiles() async {
  final ports = <_PortCandidate>[];
  for (final path in _candidatePortFiles()) {
    try {
      final file = File(path);
      if (!await file.exists()) {
        continue;
      }
      final raw = (await file.readAsString()).trim();
      final port = int.tryParse(raw);
      if (port != null && port > 0) {
        ports.add(_PortCandidate(port, path));
      }
    } catch (_) {
      // Keep probing the next candidate; stale/missing port files are common in dev.
    }
  }
  return ports;
}

Future<List<_PortCandidate>> _readBackendPortsFromLogs() async {
  final candidates = <_PortCandidate>[];
  for (final path in _candidateLogFiles()) {
    final text = await _readLogTail(path);
    if (text == null || text.isEmpty) {
      continue;
    }
    for (final port in _extractBackendPorts(text).reversed) {
      candidates.add(_PortCandidate(port, path));
    }
  }
  return candidates;
}

Future<int?> _startDevBackendFromSource() {
  _devBackendStartFuture ??= _startDevBackendFromSourceLocked();
  return _devBackendStartFuture!;
}

Future<int?> _startDevBackendFromSourceLocked() async {
  final command = await _devBackendCommand();
  if (command == null) {
    return null;
  }

  final dataDir = _defaultDataDir();
  final logDir = _defaultLogDir();
  await Directory(dataDir).create(recursive: true);
  await Directory(logDir).create(recursive: true);
  final portFile = File('$dataDir/.backend.port');
  try {
    if (await portFile.exists()) {
      await portFile.delete();
    }
  } catch (_) {}

  final logFile = File('$logDir/dart-dev-backend.log');
  final sink = logFile.openWrite(mode: FileMode.append);
  sink.writeln(
    '[dart] starting dev backend command=${command.executable} '
    'args=${command.arguments.join(' ')} dataDir=$dataDir',
  );

  final stdoutPort = Completer<int>();
  try {
    _devBackendProcess = await Process.start(
      command.executable,
      command.arguments,
      workingDirectory: command.workingDirectory,
      environment: {
        'OMNIBOT_LOG': Platform.environment['OMNIBOT_LOG'] ?? 'info',
        'OMNIBOT_DATA_DIR': dataDir,
      },
    );
    _pipeProcessOutput(_devBackendProcess!, sink, stdoutPort);
    debugPrint(
      'OmniBot bridge: started source backend pid=${_devBackendProcess!.pid}',
    );
  } catch (e) {
    sink.writeln('[dart] failed to start dev backend: $e');
    await sink.close();
    debugPrint('OmniBot bridge: failed to start source backend: $e');
    return null;
  }

  final port = await _waitForBackendPort(
    portFile,
    _devBackendProcess!,
    stdoutPort.future,
  );
  if (port != null) {
    debugPrint('OmniBot bridge: source backend reported port=$port');
    await _persistBackendPort(port);
    return port;
  }
  return null;
}

Future<_DevBackendCommand?> _devBackendCommand() async {
  final executableName = Platform.isWindows
      ? 'omnibot-backend.exe'
      : 'omnibot-backend';
  final bundled = _findBundledBackendExecutable(executableName);
  if (bundled != null && await bundled.exists()) {
    return _DevBackendCommand(
      bundled.path,
      _backendArgs(),
      bundled.parent.path,
    );
  }

  final backendDir = _findSourceBackendDir();
  if (backendDir == null) {
    return null;
  }
  final debugBinary = File('${backendDir.path}/target/debug/$executableName');
  if (await debugBinary.exists()) {
    return _DevBackendCommand(
      debugBinary.path,
      _backendArgs(),
      backendDir.path,
    );
  }
  final releaseBinary = File(
    '${backendDir.path}/target/release/$executableName',
  );
  if (await releaseBinary.exists()) {
    return _DevBackendCommand(
      releaseBinary.path,
      _backendArgs(),
      backendDir.path,
    );
  }
  return _DevBackendCommand('cargo', [
    'run',
    '-p',
    'omnibot-backend',
    '--',
    ..._backendArgs(),
  ], backendDir.path);
}

List<String> _backendArgs() => [
  '--data-dir',
  _defaultDataDir(),
  '--bind',
  '$_loopbackHost:$_defaultBackendPort',
];

Future<int?> _waitForBackendPort(
  File portFile,
  Process process,
  Future<int> stdoutPortFuture,
) async {
  int? stdoutPort;
  stdoutPortFuture
      .then((port) {
        stdoutPort = port;
      })
      .catchError((_) {});
  final exitFuture = process.exitCode;
  final deadline = DateTime.now().add(const Duration(seconds: 90));
  while (DateTime.now().isBefore(deadline)) {
    if (stdoutPort != null && stdoutPort! > 0) {
      return stdoutPort;
    }
    if (await portFile.exists()) {
      final raw = (await portFile.readAsString()).trim();
      final port = int.tryParse(raw);
      if (port != null && port > 0) {
        return port;
      }
    }
    final exited = await Future.any<Object?>([
      exitFuture.then<Object?>((code) => code),
      Future<void>.delayed(const Duration(milliseconds: 100)),
    ]);
    if (exited is int) {
      debugPrint(
        'OmniBot bridge: source backend exited before writing port (code=$exited).',
      );
      return null;
    }
  }
  debugPrint(
    'OmniBot bridge: timed out waiting for source backend port file at ${portFile.path}.',
  );
  return null;
}

void _pipeProcessOutput(
  Process process,
  IOSink sink,
  Completer<int> stdoutPort,
) {
  void pipe(Stream<List<int>> stream, String prefix) {
    stream.transform(utf8.decoder).transform(const LineSplitter()).listen((
      line,
    ) {
      sink.writeln('[$prefix] $line');
      if (prefix == 'stdout' &&
          !stdoutPort.isCompleted &&
          line.startsWith('OMNIBOT_BACKEND_PORT=')) {
        final port = int.tryParse(
          line.substring('OMNIBOT_BACKEND_PORT='.length).trim(),
        );
        if (port != null && port > 0) {
          stdoutPort.complete(port);
        }
      }
    });
  }

  pipe(process.stdout, 'stdout');
  pipe(process.stderr, 'stderr');
  unawaited(
    process.exitCode.then((code) async {
      sink.writeln('[dart] dev backend exited code=$code');
      await sink.close();
    }),
  );
}

Directory? _findSourceBackendDir() {
  final seen = <String>{};
  final starts = <Directory>[Directory.current];
  try {
    if (Platform.script.scheme == 'file') {
      final scriptPath = Platform.script.toFilePath();
      if (scriptPath.isNotEmpty) {
        starts.add(File(scriptPath).parent);
      }
    }
  } catch (_) {
    // Flutter may expose a non-file script URI in some launch modes.
  }

  for (final start in starts) {
    Directory dir;
    try {
      dir = start.absolute;
    } catch (_) {
      continue;
    }
    while (seen.add(dir.path)) {
      final candidate = Directory('${dir.path}/desktop/backend');
      if (File('${candidate.path}/Cargo.toml').existsSync()) {
        return candidate;
      }
      final parent = dir.parent;
      if (parent.path == dir.path) {
        break;
      }
      dir = parent;
    }
  }
  return null;
}

File? _findBundledBackendExecutable(String executableName) {
  final executable = File(Platform.resolvedExecutable);
  final candidates = <File>[];
  try {
    final macosDir = executable.parent;
    if (macosDir.path.endsWith('/Contents/MacOS')) {
      candidates.add(File('${macosDir.parent.path}/Resources/$executableName'));
    }
    candidates.add(File('${macosDir.path}/../Resources/$executableName'));
  } catch (_) {}

  for (final candidate in candidates) {
    if (candidate.existsSync()) {
      return candidate.absolute;
    }
  }
  return null;
}

Future<bool> _isBackendHealthy(int port) async {
  final client = HttpClient()
    ..connectionTimeout = const Duration(milliseconds: 500);
  try {
    final request = await client
        .getUrl(Uri.parse('http://$_loopbackHost:$port/health'))
        .timeout(const Duration(milliseconds: 700));
    final response = await request.close().timeout(
      const Duration(milliseconds: 700),
    );
    final body = await response.transform(utf8.decoder).join().timeout(
      const Duration(milliseconds: 700),
    );
    if (response.statusCode != 200) {
      return false;
    }
    final payload = jsonDecode(body);
    if (payload is! Map) {
      return false;
    }
    final capabilities = payload['capabilities'];
    return capabilities is Map && capabilities['agentBrowserSession'] == true;
  } catch (_) {
    return false;
  } finally {
    client.close(force: true);
  }
}

Future<void> _persistBackendPort(int port) async {
  final dataDir = _defaultDataDir();
  try {
    await Directory(dataDir).create(recursive: true);
    await File('$dataDir/.backend.port').writeAsString('$port\n');
  } catch (_) {
    // Best effort only; health probing is the source of truth in dev.
  }
}

Iterable<String> _candidateLogFiles() sync* {
  final logDir = _defaultLogDir();
  yield '$logDir/backend-stdout.log';
  yield '$logDir/dart-dev-backend.log';
  yield '$logDir/supervisor.log';

  final home = Platform.environment['HOME'];
  if (Platform.isMacOS && home != null && home.isNotEmpty) {
    yield '$home/Library/Containers/com.omnimind.bot.omnibotDesktopRunner/Data/Library/Logs/OmnibotApp/backend-stdout.log';
    yield '$home/Library/Containers/com.omnimind.bot.omnibotDesktopRunner/Data/Library/Logs/OmnibotApp/dart-dev-backend.log';
    yield '$home/Library/Containers/com.omnimind.bot.omnibotDesktopRunner/Data/Library/Logs/OmnibotApp/supervisor.log';
  }
}

Future<String?> _readLogTail(String path) async {
  const maxBytes = 512 * 1024;
  try {
    final file = File(path);
    if (!await file.exists()) {
      return null;
    }
    final length = await file.length();
    final start = length > maxBytes ? length - maxBytes : 0;
    final bytes = await file
        .openRead(start)
        .fold<List<int>>(<int>[], (buffer, chunk) => buffer..addAll(chunk));
    return utf8.decode(bytes, allowMalformed: true);
  } catch (_) {
    return null;
  }
}

List<int> _extractBackendPorts(String text) {
  final ports = <int>[];
  final patterns = <RegExp>[
    RegExp(r'OMNIBOT_BACKEND_PORT=(\d{2,5})'),
    RegExp(r'"port"\s*:\s*(\d{2,5})'),
    RegExp(r'listening\s+port=(\d{2,5})'),
  ];
  for (final pattern in patterns) {
    for (final match in pattern.allMatches(text)) {
      final port = int.tryParse(match.group(1) ?? '');
      if (port != null && port > 0) {
        ports.add(port);
      }
    }
  }
  return ports;
}

Iterable<String> _candidatePortFiles() sync* {
  final dataDir = Platform.environment['OMNIBOT_DATA_DIR'];
  if (dataDir != null && dataDir.trim().isNotEmpty) {
    yield '${dataDir.trim()}/.backend.port';
  }

  final home = Platform.environment['HOME'];
  final appData = Platform.environment['APPDATA'];
  if (Platform.isMacOS && home != null && home.isNotEmpty) {
    yield '$home/Library/Application Support/OmnibotApp/.backend.port';
    yield '$home/Library/Application Support/com.Omnimind.OmnibotApp/.backend.port';
    yield '$home/Library/Containers/com.omnimind.bot.omnibotDesktopRunner/Data/Library/Application Support/OmnibotApp/.backend.port';
  } else if (Platform.isWindows && appData != null && appData.isNotEmpty) {
    yield '$appData\\OmnibotApp\\.backend.port';
    yield '$appData\\Omnimind\\OmnibotApp\\.backend.port';
  } else if (Platform.isLinux && home != null && home.isNotEmpty) {
    final xdgDataHome = Platform.environment['XDG_DATA_HOME'];
    if (xdgDataHome != null && xdgDataHome.isNotEmpty) {
      yield '$xdgDataHome/OmnibotApp/.backend.port';
    }
    yield '$home/.local/share/OmnibotApp/.backend.port';
  }
}

String _defaultDataDir() {
  final dataDir = Platform.environment['OMNIBOT_DATA_DIR'];
  if (dataDir != null && dataDir.trim().isNotEmpty) {
    return dataDir.trim();
  }
  final home = Platform.environment['HOME'];
  final appData = Platform.environment['APPDATA'];
  if (Platform.isMacOS && home != null && home.isNotEmpty) {
    return '$home/Library/Application Support/OmnibotApp';
  }
  if (Platform.isWindows && appData != null && appData.isNotEmpty) {
    return '$appData\\OmnibotApp';
  }
  if (Platform.isLinux && home != null && home.isNotEmpty) {
    final xdgDataHome = Platform.environment['XDG_DATA_HOME'];
    if (xdgDataHome != null && xdgDataHome.isNotEmpty) {
      return '$xdgDataHome/OmnibotApp';
    }
    return '$home/.local/share/OmnibotApp';
  }
  return '${Directory.systemTemp.path}/OmnibotApp';
}

String _defaultLogDir() {
  final home = Platform.environment['HOME'];
  final localAppData = Platform.environment['LOCALAPPDATA'];
  if (Platform.isMacOS && home != null && home.isNotEmpty) {
    return '$home/Library/Logs/OmnibotApp';
  }
  if (Platform.isWindows && localAppData != null && localAppData.isNotEmpty) {
    return '$localAppData\\OmnibotApp\\Logs';
  }
  if (Platform.isLinux && home != null && home.isNotEmpty) {
    final xdgStateHome = Platform.environment['XDG_STATE_HOME'];
    if (xdgStateHome != null && xdgStateHome.isNotEmpty) {
      return '$xdgStateHome/OmnibotApp/logs';
    }
    return '$home/.local/state/OmnibotApp/logs';
  }
  return '${Directory.systemTemp.path}/OmnibotApp/logs';
}

class _DevBackendCommand {
  const _DevBackendCommand(
    this.executable,
    this.arguments,
    this.workingDirectory,
  );

  final String executable;
  final List<String> arguments;
  final String workingDirectory;
}

class _PortCandidate {
  const _PortCandidate(this.port, this.source);

  final int? port;
  final String source;
}
