//! Party links (iroh QUIC, ALPN `nether/party/1`): one long-lived endpoint per launcher run, dialed by its endpoint
//! id (published to friends through presence). Each link is one reliable stream of length-prefixed JSON frames
//! ([`party::Msg`]) plus unreliable datagrams for voice. Who may connect is the caller's decision (`auth`: friends and
//! the party roster); this layer only moves bytes. Nothing is written to disk.
//!
//! Default is direct-first with n0's public relays as fallback (peers can see each other's public IP on a direct
//! path). Relay-only mode drops the endpoint's IP transports, so peers only ever see the relay; the relay operator still
//! sees connection metadata (never the encrypted payload).
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use anyhow::{anyhow, Result};
use iroh::endpoint::{presets, Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointId};
use tokio::runtime::Runtime;
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};

use crate::party::{Msg, ALPN};

const MAX_FRAME: usize = 64 * 1024;

/// Voice datagrams sent by this process (packets, bytes): the leader's forwarding load.
pub static VOICE_OUT: (AtomicU64, AtomicU64) = (AtomicU64::new(0), AtomicU64::new(0));

/// Dev / test only: a bad network for outgoing voice, `NC_NET_IMPAIR="loss=0.05,burst=0.3,delay=40,jitter=30"`
/// (loss and burst are 0..1: chance to lose a packet, and to lose the next one too after a loss; delay and jitter in ms).
/// Jitter delays each packet independently, so packets also arrive out of order, like on Wi-Fi.
#[cfg(any(test, feature = "dev"))]
pub mod impair {
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
    use std::sync::OnceLock;
    use std::time::Duration;

    pub struct Impair { pub loss: f64, pub burst: f64, pub delay: f64, pub jitter: f64, seed: AtomicU64, lost: AtomicBool }
    pub fn get() -> Option<&'static Impair> {
        static I: OnceLock<Option<Impair>> = OnceLock::new();
        I.get_or_init(|| {
            let v = std::env::var("NC_NET_IMPAIR").ok()?;
            let mut im = Impair { loss: 0.0, burst: 0.0, delay: 0.0, jitter: 0.0, seed: AtomicU64::new(0x9E37_79B9_7F4A_7C15), lost: AtomicBool::new(false) };
            for kv in v.split(',') {
                let Some((k, x)) = kv.split_once('=') else { continue };
                let x: f64 = x.trim().parse().unwrap_or(0.0);
                match k.trim() { "loss" => im.loss = x, "burst" => im.burst = x, "delay" => im.delay = x, "jitter" => im.jitter = x, _ => {} }
            }
            Some(im)
        }).as_ref()
    }
    impl Impair {
        fn rand(&self) -> f64 {
            let mut x = self.seed.load(Ordering::Relaxed);
            x ^= x << 13; x ^= x >> 7; x ^= x << 17;
            self.seed.store(x, Ordering::Relaxed);
            (x >> 11) as f64 / (1u64 << 53) as f64
        }
        /// None = lost, else how long to hold the packet.
        pub fn fate(&self) -> Option<Duration> {
            let p = if self.lost.load(Ordering::Relaxed) { self.burst.max(self.loss) } else { self.loss };
            let gone = self.rand() < p;
            self.lost.store(gone, Ordering::Relaxed);
            if gone { return None; }
            Some(Duration::from_secs_f64((self.delay + self.jitter * self.rand()).max(0.0) / 1000.0))
        }
    }
}

fn rt() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| tokio::runtime::Builder::new_multi_thread().worker_threads(2).thread_name("nether-party").enable_all().build().expect("tokio runtime"))
}

#[derive(Debug)]
pub enum Event {
    /// a link is up (`incoming` = they dialed us)
    Up { peer: String, incoming: bool },
    Frame { peer: String, msg: Msg },
    Voice { peer: String, data: Vec<u8> },
    /// the link is gone (closed, failed to dial, or refused)
    Down { peer: String },
}

struct Link { gen: u64, tx: UnboundedSender<Vec<u8>>, conn: Arc<Mutex<Option<Connection>>> }

type Auth = Box<dyn Fn(&str) -> bool + Send + Sync>;

struct Inner {
    ep: Endpoint,
    links: Mutex<HashMap<String, Link>>,
    events: Mutex<Sender<Event>>,
    auth: Auth,
    gen: AtomicU64,
    relay_only: bool,
}

#[derive(Clone)]
pub struct Net { inner: Arc<Inner> }

fn emit(i: &Inner, e: Event) { let _ = i.events.lock().unwrap_or_else(|e| e.into_inner()).send(e); }

impl Net {
    /// Start the endpoint. `auth(peer)` decides whether an incoming key may connect. Waits (up to 10 s) for the relay.
    pub fn start(relay_only: bool, auth: impl Fn(&str) -> bool + Send + Sync + 'static) -> Result<(Net, Receiver<Event>)> {
        let (etx, erx) = std::sync::mpsc::channel();
        let net = rt().block_on(async {
            let mut b = Endpoint::builder(presets::N0).alpns(vec![ALPN.to_vec()]);
            if relay_only { b = b.clear_ip_transports(); }
            let ep = b.bind().await.map_err(|e| anyhow!("couldn't start the party link: {e}"))?;
            let _ = tokio::time::timeout(Duration::from_secs(10), ep.online()).await;
            Ok::<_, anyhow::Error>(Net { inner: Arc::new(Inner { ep, links: Mutex::new(HashMap::new()), events: Mutex::new(etx), auth: Box::new(auth), gen: AtomicU64::new(1), relay_only }) })
        })?;
        let n2 = net.clone();
        rt().spawn(async move {
            while let Some(incoming) = n2.inner.ep.accept().await {
                let n3 = n2.clone();
                tokio::spawn(async move {
                    let Ok(conn) = incoming.await else { return };
                    let peer = conn.remote_id().to_string();
                    if !(n3.inner.auth)(&peer) { conn.close(1u32.into(), b"not allowed"); return; }
                    let Ok(Ok((send, recv))) = tokio::time::timeout(Duration::from_secs(10), conn.accept_bi()).await else { conn.close(2u32.into(), b"no stream"); return };
                    let (tx, rx) = unbounded_channel();
                    let gen = n3.inner.gen.fetch_add(1, Ordering::SeqCst);
                    let slot = Arc::new(Mutex::new(Some(conn.clone())));
                    n3.inner.links.lock().unwrap_or_else(|e| e.into_inner()).insert(peer.clone(), Link { gen, tx, conn: slot });
                    emit(&n3.inner, Event::Up { peer: peer.clone(), incoming: true });
                    n3.run(peer, gen, conn, send, recv, rx).await;
                });
            }
        });
        Ok((net, erx))
    }

    /// Our endpoint id (64 hex), what friends dial.
    pub fn id(&self) -> String { self.inner.ep.id().to_string() }
    pub fn relay_only(&self) -> bool { self.inner.relay_only }

    /// Send a frame, dialing the peer first if there is no link.
    pub fn send(&self, peer: &str, msg: &Msg) {
        let bytes = msg.to_bytes();
        if bytes.len() > MAX_FRAME { return; }
        let mut links = self.inner.links.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(l) = links.get(peer) { if l.tx.send(bytes.clone()).is_ok() { return; } }
        let Ok(id) = peer.parse::<EndpointId>() else { return };
        let (tx, rx) = unbounded_channel();
        let _ = tx.send(bytes);
        let gen = self.inner.gen.fetch_add(1, Ordering::SeqCst);
        let slot = Arc::new(Mutex::new(None));
        links.insert(peer.to_string(), Link { gen, tx, conn: slot.clone() });
        drop(links);
        let (n, peer) = (self.clone(), peer.to_string());
        rt().spawn(async move {
            // a fresh peer's relay address can take a few seconds to be findable, and a first attempt can fail fast:
            // keep trying (queued frames wait) for up to 30 s before reporting the link down
            let dial = async {
                let mut wait = Duration::from_millis(500);
                loop {
                    let one = async {
                        let conn = n.inner.ep.connect(id, ALPN).await.map_err(|e| anyhow!("{e}"))?;
                        let (send, recv) = conn.open_bi().await.map_err(|e| anyhow!("{e}"))?;
                        Ok::<_, anyhow::Error>((conn, send, recv))
                    };
                    match tokio::time::timeout(Duration::from_secs(15), one).await {
                        Ok(Ok(c)) => return c,
                        Ok(Err(e)) => { if cfg!(any(test, feature = "dev")) { eprintln!("[party] dial {}: {e:#}", &peer[..8.min(peer.len())]); } }
                        Err(_) => { if cfg!(any(test, feature = "dev")) { eprintln!("[party] dial {}: timed out", &peer[..8.min(peer.len())]); } }
                    }
                    if n.inner.links.lock().unwrap_or_else(|e| e.into_inner()).get(&peer).is_none_or(|l| l.gen != gen) { std::future::pending::<()>().await; }
                    tokio::time::sleep(wait).await;
                    wait = (wait * 2).min(Duration::from_secs(4));
                }
            };
            match tokio::time::timeout(Duration::from_secs(30), dial).await {
                Ok((conn, send, recv)) => {
                    *slot.lock().unwrap_or_else(|e| e.into_inner()) = Some(conn.clone());
                    emit(&n.inner, Event::Up { peer: peer.clone(), incoming: false });
                    n.run(peer, gen, conn, send, recv, rx).await;
                }
                _ => {
                    let mine = n.drop_if(&peer, gen);
                    if mine { emit(&n.inner, Event::Down { peer }); }
                }
            }
        });
    }

    /// A voice datagram to a connected peer (dropped when there is no link or the path is congested).
    pub fn voice(&self, peer: &str, data: &[u8]) {
        let conn = self.inner.links.lock().unwrap_or_else(|e| e.into_inner()).get(peer).and_then(|l| l.conn.lock().unwrap_or_else(|e| e.into_inner()).clone());
        if let Some(c) = conn {
            if c.max_datagram_size().is_some_and(|m| m >= data.len()) {
                VOICE_OUT.0.fetch_add(1, Ordering::Relaxed);
                VOICE_OUT.1.fetch_add(data.len() as u64, Ordering::Relaxed);
                let b = bytes::Bytes::copy_from_slice(data);
                #[cfg(any(test, feature = "dev"))]
                if let Some(im) = impair::get() {
                    match im.fate() {
                        None => {}
                        Some(d) if d.is_zero() => { let _ = c.send_datagram(b); }
                        Some(d) => { rt().spawn(async move { tokio::time::sleep(d).await; let _ = c.send_datagram(b); }); }
                    }
                    return;
                }
                let _ = c.send_datagram(b);
            }
        }
    }

    pub fn connected(&self, peer: &str) -> bool {
        self.inner.links.lock().unwrap_or_else(|e| e.into_inner()).get(peer).is_some_and(|l| l.conn.lock().unwrap_or_else(|e| e.into_inner()).as_ref().is_some_and(|c| c.close_reason().is_none()))
    }

    /// "direct" / "relay" for a connected peer's selected path.
    pub fn route(&self, peer: &str) -> Option<&'static str> {
        let conn = self.inner.links.lock().unwrap_or_else(|e| e.into_inner()).get(peer).and_then(|l| l.conn.lock().unwrap_or_else(|e| e.into_inner()).clone())?;
        let paths = conn.paths();
        let sel = paths.iter().find(|p| p.is_selected())?;
        Some(if sel.is_relay() { "relay" } else { "direct" })
    }

    /// Close one link.
    pub fn hang_up(&self, peer: &str) {
        let l = self.inner.links.lock().unwrap_or_else(|e| e.into_inner()).remove(peer);
        if let Some(c) = l.and_then(|l| l.conn.lock().unwrap_or_else(|e| e.into_inner()).take()) { c.close(0u32.into(), b"bye"); }
    }

    /// Close everything (launcher closing / signing out). Short, best effort.
    pub fn close(&self) {
        let links: Vec<Link> = self.inner.links.lock().unwrap_or_else(|e| e.into_inner()).drain().map(|(_, l)| l).collect();
        for l in links { if let Some(c) = l.conn.lock().unwrap_or_else(|e| e.into_inner()).take() { c.close(0u32.into(), b"bye"); } }
        let ep = self.inner.ep.clone();
        rt().block_on(async { let _ = tokio::time::timeout(Duration::from_secs(2), ep.close()).await; });
    }

    fn drop_if(&self, peer: &str, gen: u64) -> bool {
        let mut links = self.inner.links.lock().unwrap_or_else(|e| e.into_inner());
        if links.get(peer).is_some_and(|l| l.gen == gen) { links.remove(peer); return true; }
        false
    }

    /// Pump one connection: writer (queued frames), reader (frames), datagrams. Ends when the connection closes.
    async fn run(&self, peer: String, gen: u64, conn: Connection, mut send: SendStream, mut recv: RecvStream, mut rx: UnboundedReceiver<Vec<u8>>) {
        let writer = async {
            while let Some(b) = rx.recv().await {
                let len = (b.len() as u32).to_be_bytes();
                if send.write_all(&len).await.is_err() || send.write_all(&b).await.is_err() { break; }
            }
        };
        let reader = async {
            let mut len = [0u8; 4];
            loop {
                if recv.read_exact(&mut len).await.is_err() { break; }
                let n = u32::from_be_bytes(len) as usize;
                if n == 0 || n > MAX_FRAME { break; }
                let mut b = vec![0u8; n];
                if recv.read_exact(&mut b).await.is_err() { break; }
                if let Some(msg) = Msg::parse(&b) { emit(&self.inner, Event::Frame { peer: peer.clone(), msg }); }
            }
        };
        let datagrams = async {
            while let Ok(d) = conn.read_datagram().await {
                if d.len() <= 1200 { emit(&self.inner, Event::Voice { peer: peer.clone(), data: d.to_vec() }); }
            }
        };
        // the reader or the connection ending ends the link; a replaced link only loses its writer
        tokio::select! {
            _ = reader => {}
            _ = datagrams => {}
            _ = async { writer.await; std::future::pending::<()>().await } => {}
        }
        if self.drop_if(&peer, gen) {
            conn.close(0u32.into(), b"bye");
            emit(&self.inner, Event::Down { peer });
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real network: two endpoints in one process exchange a frame and a datagram.
    #[test]
    #[ignore]
    fn two_peers_talk() {
        let (a, ea) = Net::start(false, |_| true).unwrap();
        let (b, eb) = Net::start(false, |_| true).unwrap();
        a.send(&b.id(), &Msg::Hello { v: 1, uuid: "a".repeat(32), name: "A".into() });
        let deadline = std::time::Instant::now() + Duration::from_secs(40);
        let mut got = false;
        while std::time::Instant::now() < deadline && !got {
            if let Ok(Event::Frame { msg: Msg::Hello { name, .. }, peer }) = eb.recv_timeout(Duration::from_secs(1)) {
                assert_eq!(name, "A");
                assert_eq!(peer, a.id());
                got = true;
            }
        }
        assert!(got, "no frame");
        b.send(&a.id(), &Msg::Ping);
        let mut pong = false;
        while std::time::Instant::now() < deadline && !pong {
            if let Ok(Event::Frame { msg: Msg::Ping, .. }) = ea.recv_timeout(Duration::from_secs(1)) { pong = true; }
        }
        assert!(pong);
        a.voice(&b.id(), &crate::party::voice_frame(0, 1, &[9; 40]));
        let mut voice = false;
        while std::time::Instant::now() < deadline && !voice {
            if let Ok(Event::Voice { data, .. }) = eb.recv_timeout(Duration::from_secs(1)) { voice = data.len() == 45; }
        }
        assert!(voice);
        println!("route a->b: {:?}", a.route(&b.id()));
        a.close();
        b.close();
    }

    /// Privacy mode: an endpoint without IP transports only ever talks through the relay.
    #[test]
    #[ignore]
    fn relay_only_stays_on_the_relay() {
        let (a, _ea) = Net::start(true, |_| true).unwrap();
        let (b, eb) = Net::start(false, |_| true).unwrap();
        a.send(&b.id(), &Msg::Ping);
        let deadline = std::time::Instant::now() + Duration::from_secs(40);
        let mut got = false;
        while std::time::Instant::now() < deadline && !got { got = matches!(eb.recv_timeout(Duration::from_secs(1)), Ok(Event::Frame { msg: Msg::Ping, .. })); }
        assert!(got, "no frame through the relay");
        std::thread::sleep(Duration::from_secs(5)); // a direct path would have been found by now
        assert_eq!(a.route(&b.id()), Some("relay"));
        a.close();
        b.close();
    }
}
