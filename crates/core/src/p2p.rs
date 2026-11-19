//! Peer-to-peer world link for Invite to world (iroh: QUIC dialed by public key, hole punching, n0's free public
//! relays when a direct path can't be made; no accounts, no port forwarding). The same model e4mc's "Dialtone" mode
//! uses. The host's launcher accepts connections for its LAN-opened world and pipes each QUIC stream to
//! 127.0.0.1:<lan port>; a guest's launcher listens on 127.0.0.1:<random> and pipes every game connection to the host
//! over one QUIC connection, so the game simply joins `127.0.0.1:<port>`. A fresh key per shared world: an invite stops
//! working once the world closes.
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use anyhow::{anyhow, Result};
use iroh::endpoint::{presets, Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayUrl};
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Runtime;
use tokio::sync::Mutex;

const ALPN: &[u8] = b"nether/world/1";
/// Prefix of a P2P invite address: `iroh:<endpoint id hex>[@<home relay url>]` (the relay lets a guest dial at once,
/// without waiting for the address lookup to publish).
pub const SCHEME: &str = "iroh:";

fn rt() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| tokio::runtime::Builder::new_multi_thread().worker_threads(2).thread_name("nether-p2p").enable_all().build().expect("tokio runtime"))
}

/// `iroh:<64 lowercase hex>` + optional `@https://<relay host>/`.
pub fn is_ticket(a: &str) -> bool {
    let Some(rest) = a.strip_prefix(SCHEME) else { return false };
    let (id, relay) = rest.split_once('@').map_or((rest, None), |(i, r)| (i, Some(r)));
    a.len() <= 160 && id.len() == 64 && id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        && relay.is_none_or(|r| r.strip_prefix("https://").is_some_and(|h| !h.is_empty() && h.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'/')))
}

fn parse(ticket: &str) -> Result<EndpointAddr> {
    if !is_ticket(ticket) { return Err(anyhow!("not a Nether world invite")); }
    let rest = &ticket[SCHEME.len()..];
    let (id, relay) = rest.split_once('@').map_or((rest, None), |(i, r)| (i, Some(r)));
    let id: EndpointId = id.parse().map_err(|e| anyhow!("bad invite: {e}"))?;
    let mut addr = EndpointAddr::new(id);
    if let Some(u) = relay.and_then(|r| r.parse::<RelayUrl>().ok()) { addr = addr.with_relay_url(u); }
    Ok(addr)
}

/// Pipe one QUIC stream and one TCP connection both ways until either side ends.
async fn pipe(mut send: SendStream, mut recv: RecvStream, tcp: TcpStream) {
    let _ = tcp.set_nodelay(true);
    let (mut r, mut w) = tcp.into_split();
    let up = async { let _ = tokio::io::copy(&mut r, &mut send).await; let _ = send.finish(); };
    let down = async { let _ = tokio::io::copy(&mut recv, &mut w).await; };
    tokio::join!(up, down);
}

// ---------------- host ----------------

/// A host link made ahead of time (bound and known to its relay), so opening a world is instant. Its key is still
/// fresh for each world: it is used once and the next one is made after.
static SPARE: std::sync::Mutex<Option<Endpoint>> = std::sync::Mutex::new(None);
static WARMING: AtomicBool = AtomicBool::new(false);

fn spare() -> std::sync::MutexGuard<'static, Option<Endpoint>> { SPARE.lock().unwrap_or_else(|e| e.into_inner()) }

async fn bind_host() -> Result<Endpoint> {
    let ep = Endpoint::builder(presets::N0).alpns(vec![ALPN.to_vec()]).bind().await.map_err(|e| anyhow!("couldn't start the world link: {e}"))?;
    let _ = tokio::time::timeout(Duration::from_secs(15), ep.online()).await;
    Ok(ep)
}

/// Get a host link ready in the background (call while a game runs). Cheap to call often.
pub fn warm() {
    if spare().is_some() || WARMING.swap(true, Ordering::SeqCst) { return; }
    rt().spawn(async {
        if let Ok(ep) = bind_host().await { *spare() = Some(ep); }
        WARMING.store(false, Ordering::SeqCst);
    });
}

/// Drop the ready link (no game running). Cheap to call often.
pub fn cool() {
    if let Some(ep) = spare().take() { rt().spawn(async move { let _ = tokio::time::timeout(Duration::from_secs(3), ep.close()).await; }); }
}

/// A shared world: friends dial `ticket()`.
pub struct Host { ep: Endpoint, peers: Arc<AtomicUsize> }

impl Host {
    /// Accept friends for the world listening on 127.0.0.1:`port`. Instant with a `warm()` link, else waits (up to
    /// 15 s) until the relay knows us.
    pub fn start(port: u16) -> Result<Host> {
        let ready = spare().take();
        rt().block_on(async {
            let ep = match ready { Some(ep) => ep, None => bind_host().await? };
            let peers = Arc::new(AtomicUsize::new(0));
            let (e2, p2) = (ep.clone(), peers.clone());
            tokio::spawn(async move {
                while let Some(incoming) = e2.accept().await {
                    let p = p2.clone();
                    tokio::spawn(async move {
                        let Ok(conn) = incoming.await else { return };
                        p.fetch_add(1, Ordering::SeqCst);
                        while let Ok((send, recv)) = conn.accept_bi().await {
                            tokio::spawn(async move {
                                if let Ok(tcp) = TcpStream::connect(("127.0.0.1", port)).await { pipe(send, recv, tcp).await; }
                            });
                        }
                        p.fetch_sub(1, Ordering::SeqCst);
                    });
                }
            });
            Ok(Host { ep, peers })
        })
    }

    pub fn ticket(&self) -> String {
        match self.ep.addr().relay_urls().next() {
            Some(u) => format!("{SCHEME}{}@{}", self.ep.id(), u),
            None => format!("{SCHEME}{}", self.ep.id()),
        }
    }

    /// Friends' launchers connected right now.
    pub fn peers(&self) -> usize { self.peers.load(Ordering::SeqCst) }

    /// Close every link (the world itself stays open on LAN).
    pub fn close(self) { rt().block_on(async { let _ = tokio::time::timeout(Duration::from_secs(3), self.ep.close()).await; }); }
}

// ---------------- guest ----------------

/// A link to someone's world: the game joins `127.0.0.1:port()`.
pub struct Guest { ep: Endpoint, port: u16, task: tokio::task::JoinHandle<()> }

impl Guest {
    /// Listen locally for the game and connect to the host on first use (and again if the link drops).
    pub fn start(ticket: &str) -> Result<Guest> {
        let id = parse(ticket)?;
        rt().block_on(async {
            let ep = Endpoint::bind(presets::N0).await.map_err(|e| anyhow!("couldn't start the world link: {e}"))?;
            let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
            let port = listener.local_addr()?.port();
            let conn: Arc<Mutex<Option<Connection>>> = Arc::new(Mutex::new(None));
            let e2 = ep.clone();
            let task = tokio::spawn(async move {
                while let Ok((tcp, _)) = listener.accept().await {
                    let (ep, conn, id) = (e2.clone(), conn.clone(), id.clone());
                    tokio::spawn(async move {
                        let c = {
                            let mut g = conn.lock().await;
                            match g.as_ref().filter(|c| c.close_reason().is_none()) {
                                Some(c) => c.clone(),
                                // a first attempt can fail fast (the host's address not findable yet): retry within
                                // the game's own ~30 s connect timeout
                                None => {
                                    let end = tokio::time::Instant::now() + Duration::from_secs(25);
                                    let mut wait = Duration::from_millis(500);
                                    loop {
                                        let left = end.saturating_duration_since(tokio::time::Instant::now());
                                        let r = tokio::time::timeout(left.min(Duration::from_secs(15)), ep.connect(id.clone(), ALPN)).await;
                                        match r {
                                            Ok(Ok(c)) => { *g = Some(c.clone()); break c; }
                                            r => {
                                                eprintln!("[p2p] dial failed: {}", match r { Ok(Err(e)) => e.to_string(), _ => "timed out".into() });
                                                if tokio::time::Instant::now() + wait >= end { return; } // the game shows "connection refused / timed out"
                                                tokio::time::sleep(wait).await;
                                                wait = (wait * 2).min(Duration::from_secs(4));
                                            }
                                        }
                                    }
                                }
                            }
                        };
                        if let Ok((send, recv)) = c.open_bi().await { pipe(send, recv, tcp).await; }
                    });
                }
            });
            Ok(Guest { ep, port, task })
        })
    }

    pub fn port(&self) -> u16 { self.port }

    pub fn close(self) {
        self.task.abort();
        rt().block_on(async { let _ = tokio::time::timeout(Duration::from_secs(3), self.ep.close()).await; });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tickets() {
        assert!(is_ticket(&format!("iroh:{}", "ab".repeat(32))));
        assert!(is_ticket(&format!("iroh:{}@https://use1-1.relay.n0.iroh.link./", "ab".repeat(32))));
        assert!(!is_ticket(&format!("iroh:{}@http://x", "ab".repeat(32))));
        assert!(!is_ticket("iroh:abc") && !is_ticket("1.2.3.4:25565") && !is_ticket(&format!("iroh:{}", "AB".repeat(32))));
    }

    /// Real network: a warmed link opens a world at once.
    #[test]
    #[ignore]
    fn warm_is_instant() {
        warm();
        let t = std::time::Instant::now();
        while spare().is_none() && t.elapsed() < Duration::from_secs(20) { std::thread::sleep(Duration::from_millis(50)); }
        eprintln!("warm took {:?}", t.elapsed());
        let t = std::time::Instant::now();
        let host = Host::start(1).unwrap();
        eprintln!("start took {:?}, ticket {}", t.elapsed(), host.ticket());
        assert!(t.elapsed() < Duration::from_millis(300) && host.ticket().contains('@'));
        host.close();
    }

    /// Real network: a host and a guest in one process carry bytes to a local TCP echo server.
    #[test]
    #[ignore]
    fn link_end_to_end() {
        use std::io::{Read, Write};
        let echo = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = echo.local_addr().unwrap().port();
        std::thread::spawn(move || {
            for s in echo.incoming().flatten() {
                std::thread::spawn(move || { let mut s = s; let mut b = [0u8; 64]; while let Ok(n) = s.read(&mut b) { if n == 0 { break; } let _ = s.write_all(&b[..n]); } });
            }
        });
        let host = Host::start(port).unwrap();
        let guest = Guest::start(&host.ticket()).unwrap();
        let mut c = std::net::TcpStream::connect(("127.0.0.1", guest.port())).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(40))).unwrap();
        c.write_all(b"hello nether").unwrap();
        let mut b = [0u8; 12];
        c.read_exact(&mut b).unwrap();
        assert_eq!(&b, b"hello nether");
        assert_eq!(host.peers(), 1);
        guest.close();
        host.close();
    }
}
