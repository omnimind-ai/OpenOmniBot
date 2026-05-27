use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;

use anyhow::Context;
use omnibot_backend::{BackendConfig, run};

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let mut config = BackendConfig::default_loopback();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--data-dir" => {
                if let Some(p) = args.next() {
                    config.data_dir = Some(PathBuf::from(p));
                }
            }
            "--bind" => {
                if let Some(addr) = args.next() {
                    config.bind = parse_bind(&addr).context("parse --bind")?;
                }
            }
            "--help" | "-h" => {
                eprintln!("Usage: omnibot-backend [--data-dir <path>] [--bind 127.0.0.1:0]");
                return Ok(());
            }
            _ => {
                eprintln!("ignoring unknown arg: {arg}");
            }
        }
    }
    run(config).await
}

fn parse_bind(s: &str) -> anyhow::Result<SocketAddr> {
    if let Ok(addr) = s.parse::<SocketAddr>() {
        return Ok(addr);
    }
    // Allow host:port without IPv6 brackets.
    let mut parts = s.rsplitn(2, ':');
    let port: u16 = parts.next().context("missing port")?.parse()?;
    let host = parts.next().unwrap_or("127.0.0.1");
    let ip: IpAddr = host
        .parse()
        .unwrap_or_else(|_| IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));
    Ok(SocketAddr::new(ip, port))
}
