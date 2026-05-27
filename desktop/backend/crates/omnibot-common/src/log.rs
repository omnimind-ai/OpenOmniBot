use std::path::Path;

use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling;
use tracing_subscriber::{EnvFilter, Layer, layer::SubscriberExt, util::SubscriberInitExt};

pub fn init_logging(log_dir: Option<&Path>) -> Option<WorkerGuard> {
    let env_filter = EnvFilter::try_from_env("OMNIBOT_LOG")
        .or_else(|_| EnvFilter::try_new("info"))
        .unwrap();

    let stderr_layer = tracing_subscriber::fmt::layer()
        .with_writer(std::io::stderr)
        .with_target(false)
        .with_filter(env_filter);

    let mut guard = None;
    let file_layer = log_dir.map(|dir| {
        let _ = std::fs::create_dir_all(dir);
        let appender = rolling::daily(dir, "backend.log");
        let (non_blocking, g) = tracing_appender::non_blocking(appender);
        guard = Some(g);
        tracing_subscriber::fmt::layer()
            .with_writer(non_blocking)
            .with_ansi(false)
            .json()
    });

    let registry = tracing_subscriber::registry().with(stderr_layer);
    if let Some(file_layer) = file_layer {
        registry.with(file_layer).init();
    } else {
        registry.init();
    }
    guard
}
