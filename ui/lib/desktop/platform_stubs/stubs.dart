/// Empty stub barrel — channels that need Dart-side noop implementations beyond the
/// default [BridgingBinaryMessenger] handling can register here.
///
/// Currently all stubs are handled inline by the messenger; this file exists for future
/// extension (e.g. when we need to short-circuit a stream channel with a fake event sequence).
library;
