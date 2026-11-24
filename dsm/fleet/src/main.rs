// SPDX-License-Identifier: MIT OR Apache-2.0

//! `openboxes-dsm-fleet`: the DSM storage set the OpenBoxes custody
//! integration test runs its two warehouse accounts against.
//!
//! The set is the SDK's own node harness, included from the DSM checkout
//! beside this one: each node is the storage node's own code on a Postgres
//! database of its own, on the server `DSM_TEST_DATABASE_URL` names, served
//! over TLS from a CA of the set's own, exactly as `dsm-app-host`'s
//! `real_connection` test runs it. Nothing here answers for a node.
//!
//! The process starts the set, writes the env config a `dsm-app-host` reads
//! to reach it (`dsm_env_config.toml` under `--out`, each member's CA in its
//! own entry), prints `READY <config path>` on one line, and serves until it
//! is told to stop (SIGTERM, SIGINT, or its stdin closing because the test
//! that started it is gone). Then it takes every node down.

#[path = "../../../../dsm-main/dsm_client/deterministic_state_machine/dsm_sdk/src/test_support/nodes.rs"]
pub mod nodes;

use std::io::Write;
use std::path::{Path, PathBuf};

const USAGE: &str = "openboxes-dsm-fleet --out DIR   (DSM_TEST_DATABASE_URL names the Postgres server)";

fn out_dir(mut args: impl Iterator<Item = String>) -> Result<PathBuf, String> {
    let mut out = None;
    while let Some(flag) = args.next() {
        let value = args
            .next()
            .ok_or_else(|| format!("{flag} needs a value\nusage: {USAGE}"))?;
        match flag.as_str() {
            "--out" => out = Some(PathBuf::from(value)),
            other => return Err(format!("unknown flag {other}\nusage: {USAGE}")),
        }
    }
    out.ok_or_else(|| format!("--out is required\nusage: {USAGE}"))
}

/// The host part of a node endpoint (`https://host:port`), as an IP address.
fn endpoint_ip(endpoint: &str) -> Result<std::net::IpAddr, String> {
    let rest = endpoint
        .strip_prefix("https://")
        .ok_or_else(|| format!("the node endpoint {endpoint} is not https"))?;
    let host = match rest.rsplit_once(':') {
        Some((host, _port)) => host,
        None => rest,
    };
    host.trim_matches(|c| c == '[' || c == ']')
        .parse()
        .map_err(|e| format!("the node endpoint {endpoint} is not at an IP address: {e}"))
}

/// The env config a host reads to reach `fleet`: every member, its endpoint,
/// incarnation and CA, written the way `dsm-app-host`'s `real_connection`
/// test writes it for the hosts it starts. A member is known only by a
/// certificate from its own operator's CA, so each entry names its CA itself
/// (`ca_cert`); here one CA issues them all, as one operator runs this set.
fn env_config(dir: &Path, fleet: &nodes::NodeSet) -> Result<PathBuf, String> {
    std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    let ca_pem = std::str::from_utf8(fleet.ca_pem())
        .map_err(|e| format!("the set's CA is not PEM text: {e}"))?;
    let mut loopback_members = 0usize;
    for endpoint in fleet.endpoints() {
        if endpoint_ip(&endpoint)?.is_loopback() {
            loopback_members += 1;
        }
    }
    // The config admits loopback endpoints exactly when the set serves at one.
    let allow_localhost = match loopback_members {
        0 => "false",
        _ => "true",
    };
    let mut cfg = format!(
        "allow_localhost = {allow_localhost}\n\
         bitcoin_network = \"signet\"\n"
    );
    for (member_id, endpoint, incarnation) in fleet.members() {
        cfg.push_str(&format!(
            "\n[[nodes]]\nname = \"{member_id}\"\nendpoint = \"{endpoint}\"\n\
             register_incarnation = \"{}\"\nca_cert = \"\"\"\n{ca_pem}\"\"\"\n",
            dsm::utils::text_id::encode_base32_crockford(&incarnation)
        ));
    }
    let path = dir.join("dsm_env_config.toml");
    std::fs::write(&path, cfg).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(path)
}

/// Resolves when the process is told to stop: a termination signal, or its
/// stdin closing (the test that started it is gone).
async fn told_to_stop() -> &'static str {
    use tokio::io::AsyncReadExt;
    use tokio::signal::unix::{signal, SignalKind};
    let mut terminate = match signal(SignalKind::terminate()) {
        Ok(stream) => stream,
        Err(e) => panic!("listening for SIGTERM: {e}"),
    };
    let mut interrupt = match signal(SignalKind::interrupt()) {
        Ok(stream) => stream,
        Err(e) => panic!("listening for SIGINT: {e}"),
    };
    let mut stdin = tokio::io::stdin();
    let mut sink = Vec::new();
    tokio::select! {
        _ = terminate.recv() => "SIGTERM",
        _ = interrupt.recv() => "SIGINT",
        _ = stdin.read_to_end(&mut sink) => "stdin closed",
    }
}

fn main() -> Result<(), String> {
    let out = out_dir(std::env::args().skip(1))?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("openboxes-dsm-fleet")
        .build()
        .map_err(|e| format!("the runtime: {e}"))?;
    runtime.block_on(async {
        let mut fleet = nodes::NodeSet::start().await;
        let config = env_config(&out, &fleet)?;
        let members: Vec<String> = fleet.members().into_iter().map(|(id, _, _)| id).collect();
        println!("READY {}", config.display());
        std::io::stdout()
            .flush()
            .map_err(|e| format!("stdout: {e}"))?;
        let why = told_to_stop().await;
        eprintln!("[fleet] stopping ({why}): taking {} nodes down", members.len());
        fleet.take_down(&members).await;
        Ok(())
    })
}
