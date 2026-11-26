//! Hosting network helpers: UPnP port mapping, LAN/public IP discovery, and plain server addresses for invites.
//! The public IP comes from the router (UPnP) or a free IP echo (ipify); whether friends can reach the server is
//! checked from outside by the free mcstatus.io status API (it pings the server like a Minecraft client would).
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use igd_next::{PortMappingProtocol, SearchOptions};

// ---- LAN / public address ----

/// This machine's LAN IPv4 address (the interface used for the default route), if any.
pub fn lan_ip() -> Option<Ipv4Addr> {
    // connect() on UDP only selects a route; nothing is sent.
    let s = UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("192.0.2.1:9").ok()?;
    match s.local_addr().ok()?.ip() {
        IpAddr::V4(ip) if !ip.is_unspecified() => Some(ip),
        _ => None,
    }
}

/// True for addresses reachable from the internet (not private, loopback, link-local, CGNAT or unspecified).
pub fn is_public_ipv4(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    !(ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified() || ip.is_broadcast()
        || (o[0] == 100 && (64..128).contains(&o[1])))
}

fn search() -> Result<igd_next::Gateway> {
    { let mut o = SearchOptions::default(); o.timeout = Some(Duration::from_secs(4)); igd_next::search_gateway(o) }
        .map_err(|e| anyhow!("no UPnP router answered ({e}); enable UPnP on the router or forward the port by hand"))
}

/// The router's external IPv4 address via UPnP (None when it is private, i.e. behind CGNAT or a second router).
pub fn public_ip() -> Result<Option<Ipv4Addr>> {
    let ip = search()?.get_external_ip().map_err(|e| anyhow!("router would not report its external IP: {e}"))?;
    Ok(match ip { IpAddr::V4(v) if is_public_ipv4(v) => Some(v), _ => None })
}

// ---- UPnP mapping ----

/// An open UPnP TCP port mapping. Removed on `close()` or when dropped.
pub struct Upnp {
    gateway: igd_next::Gateway,
    port: u16,
    external: Option<Ipv4Addr>,
    open: bool,
    /// set on close: ends the lease renewal thread
    stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

/// UPnP lease (seconds): a crash, kill or power cut leaves the forward for at most this long.
const LEASE: u32 = 3600;

impl Upnp {
    /// Ask the router to forward external TCP `port` to this machine's same port.
    pub fn open(port: u16, description: &str) -> Result<Upnp> {
        let lan = lan_ip().ok_or_else(|| anyhow!("no LAN address found"))?;
        let gateway = search()?;
        let local = SocketAddr::V4(SocketAddrV4::new(lan, port));
        gateway
            .add_port(PortMappingProtocol::TCP, port, local, LEASE, description)
            .map_err(|e| anyhow!("router refused the port mapping: {e}"))?;
        let external = match gateway.get_external_ip() { Ok(IpAddr::V4(v)) if is_public_ipv4(v) => Some(v), _ => None };
        // renew while the server runs (every 20 minutes); the thread ends when the mapping is closed
        let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        {
            let (gw, stop, description) = (gateway.clone(), stop.clone(), description.to_string());
            std::thread::spawn(move || {
                let mut waited = 0u32;
                while !stop.load(std::sync::atomic::Ordering::Relaxed) {
                    std::thread::sleep(Duration::from_secs(5));
                    waited += 5;
                    if waited >= LEASE / 3 {
                        waited = 0;
                        let _ = gw.add_port(PortMappingProtocol::TCP, port, local, LEASE, &description);
                    }
                }
            });
        }
        Ok(Upnp { gateway, port, external, open: true, stop })
    }

    /// The forwarded port.
    pub fn port(&self) -> u16 { self.port }

    /// The router's public address, when it has a routable one.
    pub fn external_ip(&self) -> Option<Ipv4Addr> { self.external }

    /// Remove the mapping now (idempotent).
    pub fn close(&mut self) {
        self.stop.store(true, std::sync::atomic::Ordering::Relaxed);
        if self.open {
            let _ = self.gateway.remove_port(PortMappingProtocol::TCP, self.port);
            self.open = false;
        }
    }
}

impl Drop for Upnp {
    fn drop(&mut self) { self.close(); }
}

// ---- invite codes ----

/// Where to join: an IPv4 address or hostname plus port.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Invite {
    pub host: String,
    pub port: u16,
}

impl Invite {
    /// `host:port` as typed into Minecraft's Direct Connect (port omitted when 25565).
    pub fn address(&self) -> String {
        if self.port == 25565 { self.host.clone() } else { format!("{}:{}", self.host, self.port) }
    }
}

const ALPHABET: &[u8; 32] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O, 0, 1
const VERSION: u8 = 1;

fn checksum(data: &[u8]) -> u8 {
    // CRC-8 (poly 0x07)
    let mut crc = 0u8;
    for &b in data {
        crc ^= b;
        for _ in 0..8 { crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x07 } else { crc << 1 }; }
    }
    crc
}

fn valid_host(h: &str) -> bool {
    !h.is_empty() && h.len() <= 253 && h.split('.').all(|l| {
        !l.is_empty() && l.len() <= 63 && !l.starts_with('-') && !l.ends_with('-') && l.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
    })
}

/// Encode an invite as a compact, case-insensitive, URL-safe code (A-Z 2-9) with a checksum.
pub fn encode(inv: &Invite) -> Result<String> {
    let mut b = vec![VERSION << 4];
    if inv.port == 0 { bail!("port must be 1-65535"); }
    match inv.host.parse::<Ipv4Addr>() {
        Ok(ip) => { b.extend_from_slice(&ip.octets()); }
        Err(_) => {
            if !valid_host(&inv.host) { bail!("not a valid host name: {}", inv.host); }
            b[0] |= 1; // kind: hostname
            b.push(inv.host.len() as u8);
            b.extend_from_slice(inv.host.to_ascii_lowercase().as_bytes());
        }
    }
    b.extend_from_slice(&inv.port.to_be_bytes());
    b.push(checksum(&b));
    // base32 with the custom alphabet
    let mut out = String::new();
    let (mut acc, mut bits) = (0u32, 0u32);
    for byte in b {
        acc = (acc << 8) | byte as u32;
        bits += 8;
        while bits >= 5 { bits -= 5; out.push(ALPHABET[((acc >> bits) & 31) as usize] as char); }
    }
    if bits > 0 { out.push(ALPHABET[((acc << (5 - bits)) & 31) as usize] as char); }
    Ok(out)
}

/// Decode and verify an invite code (case-insensitive; spaces and dashes ignored).
pub fn decode(code: &str) -> Result<Invite> {
    let (mut acc, mut bits) = (0u32, 0u32);
    let mut b = Vec::new();
    for ch in code.chars().filter(|c| !c.is_whitespace() && *c != '-') {
        let up = ch.to_ascii_uppercase() as u32;
        let v = if up < 128 { ALPHABET.iter().position(|&a| a as u32 == up) } else { None }
            .ok_or_else(|| anyhow!("invalid character '{ch}' in invite code"))? as u32;
        acc = (acc << 5) | v;
        bits += 5;
        if bits >= 8 { bits -= 8; b.push(((acc >> bits) & 0xff) as u8); }
    }
    if b.len() < 6 { bail!("invite code is too short"); }
    let (body, sum) = b.split_at(b.len() - 1);
    if checksum(body) != sum[0] { bail!("invite code is mistyped (checksum failed)"); }
    if body[0] >> 4 != VERSION { bail!("invite code is from a newer version"); }
    let (host, rest) = match body[0] & 0x0f {
        0 => {
            if body.len() != 1 + 4 + 2 { bail!("invite code is malformed"); }
            (Ipv4Addr::new(body[1], body[2], body[3], body[4]).to_string(), &body[5..])
        }
        1 => {
            let n = *body.get(1).ok_or_else(|| anyhow!("invite code is malformed"))? as usize;
            if body.len() != 2 + n + 2 { bail!("invite code is malformed"); }
            let h = std::str::from_utf8(&body[2..2 + n]).context("invite code is malformed")?;
            if !valid_host(h) { bail!("invite code is malformed"); }
            (h.to_string(), &body[2 + n..])
        }
        _ => bail!("invite code is malformed"),
    };
    let port = u16::from_be_bytes([rest[0], rest[1]]);
    if port == 0 { bail!("invite code is malformed"); }
    Ok(Invite { host, port })
}

/// Code split into dash-separated groups of 5 for reading aloud.
pub fn pretty(code: &str) -> String {
    code.as_bytes().chunks(5).map(|c| std::str::from_utf8(c).unwrap_or("")).collect::<Vec<_>>().join("-")
}

/// Parse `host[:port]` (port defaults to 25565).
pub fn parse_address(s: &str) -> Result<Invite> {
    let s = s.trim().trim_end_matches('/');
    let (host, port) = match s.rsplit_once(':') {
        Some((h, p)) => (h, p.parse::<u16>().ok().filter(|p| *p != 0).ok_or_else(|| anyhow!("bad port in {s}"))?),
        None => (s, 25565),
    };
    if host.parse::<Ipv4Addr>().is_err() && !valid_host(host) { bail!("not a valid address: {s}"); }
    Ok(Invite { host: host.to_ascii_lowercase(), port })
}

// ---- reachability from the internet ----

/// This network's public IPv4 address as the internet sees it (free echo service).
pub fn echo_ip(c: &reqwest::blocking::Client) -> Result<Ipv4Addr> {
    let t = c.get("https://api.ipify.org").timeout(Duration::from_secs(8)).send()?.error_for_status()?.text()?;
    t.trim().parse().map_err(|_| anyhow!("unexpected answer from the IP lookup: {}", t.trim()))
}

/// Ask an outside service to ping `host:port` like a Minecraft client: Ok(true) when friends can reach it.
pub fn reachable(c: &reqwest::blocking::Client, host: &str, port: u16) -> Result<bool> {
    let v: serde_json::Value = c.get(format!("https://api.mcstatus.io/v2/status/java/{host}:{port}?query=false&timeout=5"))
        .timeout(Duration::from_secs(15)).send()?.error_for_status()?.json()?;
    v["online"].as_bool().ok_or_else(|| anyhow!("the outside check gave no answer"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn inv(h: &str, p: u16) -> Invite { Invite { host: h.into(), port: p } }

    #[test]
    fn roundtrip_ipv4_and_host() {
        for i in [inv("203.0.113.9", 25565), inv("1.2.3.4", 1), inv("255.255.255.255", 65535), inv("play.example.com", 25570), inv("a", 80)] {
            let c = encode(&i).unwrap();
            assert!(c.bytes().all(|b| ALPHABET.contains(&b)), "{c}");
            assert_eq!(decode(&c).unwrap(), i);
            assert_eq!(decode(&c.to_lowercase()).unwrap(), i);
            assert_eq!(decode(&pretty(&c)).unwrap(), i);
        }
    }

    #[test]
    fn ipv4_code_is_compact() {
        assert!(encode(&inv("203.0.113.9", 25565)).unwrap().len() <= 16);
    }

    #[test]
    fn hostnames_are_lowercased() {
        assert_eq!(decode(&encode(&inv("Play.Example.COM", 25565)).unwrap()).unwrap().host, "play.example.com");
    }

    #[test]
    fn rejects_bad_input() {
        assert!(encode(&inv("bad host", 25565)).is_err());
        assert!(encode(&inv("example.com", 0)).is_err());
        assert!(decode("").is_err());
        assert!(decode("ABC").is_err());
        assert!(decode("0000000000000").is_err()); // chars outside the alphabet
    }

    #[test]
    fn checksum_catches_typos() {
        let c = encode(&inv("203.0.113.9", 25565)).unwrap();
        let mut caught = 0;
        let mut total = 0;
        for i in 0..c.len() - 1 {
            let mut b = c.clone().into_bytes();
            b[i] = if b[i] == b'A' { b'B' } else { b'A' };
            total += 1;
            if decode(std::str::from_utf8(&b).unwrap()).is_err() { caught += 1; }
        }
        assert_eq!(caught, total);
    }

    #[test]
    fn address_forms() {
        assert_eq!(parse_address("Ab-Cd.gl.joinmc.link:25570").unwrap(), inv("ab-cd.gl.joinmc.link", 25570));
        assert!(parse_address("bad host").is_err());
        assert_eq!(inv("203.0.113.9", 25565).address(), "203.0.113.9");
        assert_eq!(inv("h.net", 1234).address(), "h.net:1234");
    }

    #[test]
    fn public_ip_classes() {
        for s in ["10.0.0.1", "192.168.1.5", "172.16.0.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0"] {
            assert!(!is_public_ipv4(s.parse().unwrap()), "{s}");
        }
        assert!(is_public_ipv4("8.8.8.8".parse().unwrap()));
        assert!(is_public_ipv4("100.63.0.1".parse().unwrap()));
    }
}

// ---- server list ping (the multiplayer screen's status query) ----

/// What a server reports on the multiplayer screen.
#[derive(Clone, Debug, PartialEq)]
pub struct ServerStatus {
    pub online: u32,
    pub max: u32,
    /// Message of the day with formatting codes removed.
    pub motd: String,
    pub version: String,
    pub ping_ms: u32,
}

fn write_varint(out: &mut Vec<u8>, mut v: u32) {
    loop {
        if v & !0x7f == 0 { out.push(v as u8); return; }
        out.push((v as u8 & 0x7f) | 0x80);
        v >>= 7;
    }
}

fn read_varint(r: &mut impl std::io::Read) -> Result<u32> {
    let (mut v, mut shift) = (0u32, 0);
    loop {
        let mut b = [0u8];
        r.read_exact(&mut b)?;
        v |= ((b[0] & 0x7f) as u32) << shift;
        if b[0] & 0x80 == 0 { return Ok(v); }
        shift += 7;
        if shift > 28 { bail!("bad varint"); }
    }
}

/// Flatten a chat component (string or {"text", "extra"}) to plain text without § codes.
pub fn plain_motd(v: &serde_json::Value) -> String {
    fn walk(v: &serde_json::Value, out: &mut String) {
        match v {
            serde_json::Value::String(s) => out.push_str(s),
            serde_json::Value::Array(a) => a.iter().for_each(|x| walk(x, out)),
            serde_json::Value::Object(o) => {
                if let Some(t) = o.get("text") { walk(t, out); }
                if let Some(e) = o.get("extra") { walk(e, out); }
            }
            _ => {}
        }
    }
    let mut s = String::new();
    walk(v, &mut s);
    let mut out = String::new();
    let mut skip = false;
    for c in s.chars() {
        if skip { skip = false; continue; }
        if c == '§' { skip = true; continue; }
        out.push(c);
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Ask a server for its status ("host" or "host:port"; SRV records are not resolved). Times out after 3 s.
pub fn ping_server(addr: &str) -> Result<ServerStatus> {
    use std::io::{Read, Write};
    use std::net::{TcpStream, ToSocketAddrs};
    let (host, port) = match addr.rsplit_once(':') {
        Some((h, p)) if p.parse::<u16>().is_ok() && !h.contains(':') => (h.to_string(), p.parse::<u16>()?),
        _ => (addr.to_string(), 25565),
    };
    let sa = (host.as_str(), port).to_socket_addrs()?.next().ok_or_else(|| anyhow!("can't resolve {host}"))?;
    let t = Duration::from_secs(3);
    let start = std::time::Instant::now();
    let mut s = TcpStream::connect_timeout(&sa, t)?;
    let ping_ms = start.elapsed().as_millis() as u32;
    s.set_read_timeout(Some(t))?;
    s.set_write_timeout(Some(t))?;
    // handshake (protocol -1 = "any", next state 1 = status), then the status request
    let mut body = vec![0x00];
    write_varint(&mut body, u32::MAX);
    write_varint(&mut body, host.len() as u32);
    body.extend_from_slice(host.as_bytes());
    body.extend_from_slice(&port.to_be_bytes());
    body.push(0x01);
    let mut pkt = vec![];
    write_varint(&mut pkt, body.len() as u32);
    pkt.extend_from_slice(&body);
    pkt.extend_from_slice(&[0x01, 0x00]);
    s.write_all(&pkt)?;
    let len = read_varint(&mut s)? as usize;
    if len > 1 << 20 { bail!("status too large"); }
    let mut buf = vec![0u8; len];
    s.read_exact(&mut buf)?;
    let mut r = &buf[..];
    if read_varint(&mut r)? != 0 { bail!("unexpected packet"); }
    let n = read_varint(&mut r)? as usize;
    let json: serde_json::Value = serde_json::from_slice(r.get(..n).ok_or_else(|| anyhow!("short status"))?)?;
    Ok(ServerStatus {
        online: json["players"]["online"].as_u64().unwrap_or(0) as u32,
        max: json["players"]["max"].as_u64().unwrap_or(0) as u32,
        motd: plain_motd(&json["description"]),
        version: json["version"]["name"].as_str().unwrap_or_default().to_string(),
        ping_ms,
    })
}

#[cfg(test)]
mod slp_tests {
    use super::*;

    #[test]
    fn varint_and_motd() {
        let mut v = vec![];
        write_varint(&mut v, 300);
        assert_eq!(v, [0xac, 0x02]);
        assert_eq!(read_varint(&mut &v[..]).unwrap(), 300);
        let mut m = vec![];
        write_varint(&mut m, u32::MAX);
        assert_eq!(m, [0xff, 0xff, 0xff, 0xff, 0x0f]);
        let j = serde_json::json!({"text": "§aHello ", "extra": [{"text": "§lWorld"}, "!"]});
        assert_eq!(plain_motd(&j), "Hello World!");
        assert_eq!(plain_motd(&serde_json::json!("A  Minecraft\nServer")), "A Minecraft Server");
    }
}

#[cfg(test)]
mod live_tests {
    use super::*;

    /// Manual: `cargo test -p nether-core --release net_live -- --ignored --nocapture` (maps TCP 25599 for a moment).
    #[test]
    #[ignore]
    fn net_live() {
        let c = crate::download::client();
        println!("echo ip: {:?}", echo_ip(&c));
        println!("upnp public ip: {:?}", public_ip());
        match Upnp::open(25599, "Nether Client test") {
            Ok(mut u) => { println!("upnp mapped 25599, external {:?}", u.external_ip()); u.close(); }
            Err(e) => println!("upnp: {e:#}"),
        }
        println!("reachable 203.0.113.7:25565 -> {:?}", reachable(&c, "203.0.113.7", 25565));
    }
}
