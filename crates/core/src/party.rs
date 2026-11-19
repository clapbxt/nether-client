//! Party: the pre-game social group of the title screen (Fortnite-style lobby). Pure rules and wire types; no network,
//! UI, files or audio here (`party_net` carries the frames, the launcher's `app::party` drives both).
//!
//! Model: the party is a star around its **leader**. The leader's launcher holds the authoritative [`Party`]; members
//! hold the last [`Snapshot`] it sent. Every member is a Nether friend of whoever brought them in, never a stranger:
//! - a member INVITES a friend: the member tells the leader (`Admit`) and then the friend (`Invite`); the friend joins.
//! - a friend ASKS to join a member: the member forwards it to the leader (`Request`, shown as "friend of X"); only the
//!   leader approves. Any member that blocked the asker vetoes it (silently).
//! - a solo player is a party of one and its leader; leaving makes you a new solo party; the party ends when empty.
//! - the leader leaving hands the party to the earliest-joined member (also when the leader's link is lost for good:
//!   every member computes the same successor from the last snapshot).
//! Nothing is kept: chat lines are delivered once to connected members, voice is never stored, a party is forgotten
//! when it closes.
use std::collections::HashSet;

use serde::{Deserialize, Serialize};

/// Wire protocol version (frames carry it in `Hello`).
pub const PROTO: u32 = 1;
/// iroh ALPN of the party link (separate from the world link's `nether/world/1`).
pub const ALPN: &[u8] = b"nether/party/1";
/// Longest chat line (chars).
pub const MAX_TEXT: usize = 280;
/// How long an invite / approval lets someone join (ms).
pub const ALLOW_MS: u64 = 10 * 60_000;
/// How long a join request waits for the leader (ms).
pub const REQUEST_MS: u64 = 5 * 60_000;
/// A member whose link dropped keeps their spot this long (ms).
pub const GRACE_MS: u64 = 45_000;
/// Members wait this long for a lost leader before the successor takes over (ms).
pub const LEADER_GRACE_MS: u64 = 10_000;

pub fn valid_uuid(u: &str) -> bool { u.len() == 32 && u.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }
/// iroh endpoint id, as `EndpointId`'s Display writes it.
pub fn valid_peer(p: &str) -> bool { p.len() == 64 && p.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }
pub fn valid_id(p: &str) -> bool { p.len() == 32 && p.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }

/// A Minecraft name as shown (sanitised: 1..16 of `[A-Za-z0-9_]`).
pub fn clean_name(n: &str) -> String { n.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '_').take(16).collect() }

/// One chat line: no control chars, trimmed, at most [`MAX_TEXT`] chars. Empty = drop it.
pub fn clean_text(t: &str) -> String {
    let s: String = t.chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
    s.trim().chars().take(MAX_TEXT).collect()
}

fn short(s: &str, n: usize) -> String { s.chars().filter(|c| !c.is_control()).take(n).collect() }

/// What a member looks like in the lobby (shared by the member's own launcher; all optional).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Look {
    /// Nether cape id ("" = none)
    pub cape: String,
    /// cosmetic ids by slot (hat, headband, mask, back, wings, aura, pet), "" = empty
    pub cos: Vec<String>,
}
impl Look {
    pub fn clean(mut self) -> Look {
        let ok = |s: &String| s.len() <= 32 && s.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-');
        if !ok(&self.cape) { self.cape.clear(); }
        self.cos.truncate(7);
        for c in &mut self.cos { if !ok(c) { c.clear(); } }
        self
    }
}

/// Microphone state as others see it.
pub mod mic { pub const OFF: u8 = 0; pub const PTT: u8 = 1; pub const OPEN: u8 = 2; }

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Member {
    pub uuid: String,
    pub name: String,
    /// party endpoint id (the leader dials / accepts this key only)
    pub peer: String,
    /// join order (smaller = earlier; decides the next leader)
    pub seq: u64,
    /// voice stream id the leader stamps on this member's frames
    pub sid: u16,
    pub ready: bool,
    pub mic: u8,
    pub deaf: bool,
    /// link lost, waiting to come back
    pub away: bool,
    /// game version + loader label ("1.21.11 Fabric"), "" = launcher only
    pub game: String,
    /// "lobby" | "world" | "server" | "launcher" (where the member is; no addresses)
    pub at: String,
    pub look: Look,
}

/// A friend of a member asking to join (leader decides).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Request {
    pub uuid: String,
    pub name: String,
    pub peer: String,
    /// the member they are friends with ("" = the leader)
    pub via: String,
    pub via_name: String,
    pub at: u64,
}

/// Where the party plays.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Dest {
    /// a public server (`host:port`)
    Server { address: String, name: String },
    /// a member's singleplayer world through the P2P world link (`iroh:` ticket)
    World { ticket: String, name: String, host: String, ver: String, loader: String },
}

impl Dest {
    pub fn clean(self) -> Option<Dest> {
        match self {
            Dest::Server { address, name } => {
                let ok = address.len() <= 96 && address.rsplit_once(':').is_some_and(|(h, p)| !h.is_empty() && p.parse::<u16>().is_ok()
                    && h.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_'));
                ok.then(|| Dest::Server { address: address.to_ascii_lowercase(), name: short(&name, 48) })
            }
            Dest::World { ticket, name, host, ver, loader } => {
                let ok = ticket.starts_with("iroh:") && ticket.len() <= 160 && valid_uuid(&host);
                ok.then(|| Dest::World { ticket, name: short(&name, 48), host, ver: short(&ver, 32), loader: short(&loader, 16) })
            }
        }
    }
}

/// The party as every member sees it (the leader sends it after each change).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Snapshot {
    /// random 128-bit hex
    pub id: String,
    /// bumped on every change; an older snapshot never replaces a newer one
    pub epoch: u64,
    pub leader: String,
    pub members: Vec<Member>,
    /// join requests waiting for the leader (members see them so a blocker can veto)
    pub pending: Vec<Request>,
    pub dest: Option<Dest>,
    /// bumped when the leader presses PLAY (ready members go to `dest`)
    pub go: u64,
}

impl Snapshot {
    pub fn member(&self, uuid: &str) -> Option<&Member> { self.members.iter().find(|m| m.uuid == uuid) }
    pub fn size(&self) -> usize { self.members.len() }
    /// Who leads if `gone` (the leader) is lost: the earliest-joined other member.
    pub fn successor(&self, gone: &str) -> Option<&Member> { self.members.iter().filter(|m| m.uuid != gone).min_by_key(|m| m.seq) }
}

/// Messages between launchers (JSON frames on one reliable QUIC stream; voice goes in datagrams, see [`voice_frame`]).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Msg {
    /// first frame both ways: who I say I am (checked against the friend list / party roster for this key)
    Hello { v: u32, uuid: String, name: String },
    // ---- friend to friend
    /// you're invited to this party: dial `leader_peer` and send `Join`. `auto` = your request was approved.
    Invite { party: String, leader: String, leader_name: String, leader_peer: String, size: u32, auto: bool },
    /// let me join your party
    Ask,
    /// I just sent you a world invite through the friends service: fetch it now (it pops up at once, no waiting for
    /// the next check). Older launchers ignore it and still get the invite on their next check.
    WorldInvite,
    /// my friends state changed in a way you should see (we're friends now, I removed you, I asked to join): sync
    /// with the friends service now instead of at the next check. Older launchers ignore it.
    Sync,
    /// answer to `Ask` (`ok` = forwarded to the leader / approved; `why` for the asker's UI)
    AskAnswer { ok: bool, why: String },
    // ---- member to leader
    /// I invited my friend: let them in
    Admit { uuid: String, name: String, peer: String },
    /// my friend asks to join (you decide)
    Request { uuid: String, name: String, peer: String },
    Join { party: String },
    Leave,
    Kick { uuid: String },
    Promote { uuid: String },
    Approve { uuid: String },
    Decline { uuid: String },
    /// someone in `pending` is blocked by me: never let them in
    Veto { uuid: String },
    /// my state
    Me { ready: bool, mic: u8, deaf: bool, game: String, at: String, look: Look },
    /// members I blocked (pairwise: we no longer hear or read each other)
    Blocks { uuids: Vec<String> },
    Chat { text: String },
    SetDest { dest: Option<Dest> },
    Go,
    // ---- leader to member
    State { snap: Snapshot },
    Said { id: u64, from: String, name: String, text: String },
    /// your forwarded request was approved / declined (pass it to your friend)
    Approved { uuid: String },
    Declined { uuid: String },
    /// you were removed by the leader
    Kicked,
    Ping,
    Pong,
}

impl Msg {
    pub fn to_bytes(&self) -> Vec<u8> { serde_json::to_vec(self).unwrap_or_default() }
    pub fn parse(b: &[u8]) -> Option<Msg> { serde_json::from_slice(b).ok() }
}

/// Voice datagram: `[1][sid: u16 BE][seq: u16 BE][opus packet]`. Members send sid 0; the leader re-stamps the
/// sender's sid before passing it on.
pub const VOICE: u8 = 1;
pub fn voice_frame(sid: u16, seq: u16, opus: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(5 + opus.len());
    v.push(VOICE);
    v.extend_from_slice(&sid.to_be_bytes());
    v.extend_from_slice(&seq.to_be_bytes());
    v.extend_from_slice(opus);
    v
}
/// (sid, seq, opus) of a voice datagram.
pub fn parse_voice(d: &[u8]) -> Option<(u16, u16, &[u8])> {
    if d.len() < 6 || d.len() > 1200 || d[0] != VOICE { return None; }
    Some((u16::from_be_bytes([d[1], d[2]]), u16::from_be_bytes([d[3], d[4]]), &d[5..]))
}
/// The same frame with another sid.
pub fn restamp(d: &[u8], sid: u16) -> Vec<u8> {
    let mut v = d.to_vec();
    if v.len() >= 3 { v[1..3].copy_from_slice(&sid.to_be_bytes()); }
    v
}

// ---------------------------------------------------------------- the leader's authority

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Refusal { NotLeader, NotMember, NotAllowed, Blocked, Full, Stale }
impl std::fmt::Display for Refusal {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Refusal::NotLeader => "only the party leader can do that",
            Refusal::NotMember => "not in this party",
            Refusal::NotAllowed => "that invite has ended",
            Refusal::Blocked => "can't join this party",
            Refusal::Full => "too many requests right now",
            Refusal::Stale => "that party has changed",
        })
    }
}

#[derive(Clone, Debug)]
struct Allow { uuid: String, peer: String, until: u64 }

/// The authoritative party, held by the leader's launcher.
#[derive(Clone, Debug)]
pub struct Party {
    pub snap: Snapshot,
    allow: Vec<Allow>,
    /// pairs (sorted) that blocked each other: no chat/voice between them
    pairs: HashSet<(String, String)>,
    /// uuids someone in the party vetoed (never admitted while this party lives)
    vetoed: HashSet<String>,
    next_seq: u64,
    next_sid: u16,
    /// away since (uuid, ms)
    away: Vec<(String, u64)>,
}

fn pair(a: &str, b: &str) -> (String, String) { if a < b { (a.into(), b.into()) } else { (b.into(), a.into()) } }

impl Party {
    /// A new party of one: `me` leads.
    pub fn solo(id: String, mut me: Member) -> Party {
        me.seq = 0;
        me.sid = 1;
        me.away = false;
        let snap = Snapshot { id, epoch: 1, leader: me.uuid.clone(), members: vec![me], pending: vec![], dest: None, go: 0 };
        Party { snap, allow: vec![], pairs: HashSet::new(), vetoed: HashSet::new(), next_seq: 1, next_sid: 2, away: vec![] }
    }

    /// Take over a party from a snapshot (leader handover / lost leader): `me` becomes the leader.
    pub fn adopt(mut snap: Snapshot, me: &str, gone: Option<&str>) -> Party {
        if let Some(g) = gone { snap.members.retain(|m| m.uuid != g); }
        snap.leader = me.to_string();
        snap.epoch += 1;
        if let Some(g) = gone { snap.pending.retain(|r| r.via != g); }
        let next_seq = snap.members.iter().map(|m| m.seq).max().unwrap_or(0) + 1;
        let next_sid = snap.members.iter().map(|m| m.sid).max().unwrap_or(0).wrapping_add(1).max(1);
        // everyone must redial the new leader: they count as away until they do (except me)
        let now = 0;
        let away = snap.members.iter().filter(|m| m.uuid != me).map(|m| (m.uuid.clone(), now)).collect();
        for m in &mut snap.members { m.away = m.uuid != me; }
        Party { snap, allow: vec![], pairs: HashSet::new(), vetoed: HashSet::new(), next_seq, next_sid, away }
    }

    fn bump(&mut self) { self.snap.epoch += 1; }
    pub fn leader(&self) -> &str { &self.snap.leader }
    pub fn is_member(&self, uuid: &str) -> bool { self.snap.member(uuid).is_some() }

    /// A member invited their friend (or the leader did): let that friend in for a while.
    pub fn admit(&mut self, by: &str, uuid: &str, peer: &str, now: u64) -> Result<(), Refusal> {
        if !self.is_member(by) { return Err(Refusal::NotMember); }
        if !valid_uuid(uuid) || !valid_peer(peer) { return Err(Refusal::NotAllowed); }
        if self.vetoed.contains(uuid) { return Err(Refusal::Blocked); }
        self.allow.retain(|a| a.uuid != uuid);
        if self.allow.len() >= 64 { return Err(Refusal::Full); }
        self.allow.push(Allow { uuid: uuid.into(), peer: peer.into(), until: now + ALLOW_MS });
        // an invite answers a waiting request
        if self.snap.pending.iter().any(|r| r.uuid == uuid) { self.snap.pending.retain(|r| r.uuid != uuid); self.bump(); }
        Ok(())
    }

    /// A friend of `via` asks to join. Ok(true) = new request (the leader's UI shows it).
    pub fn request(&mut self, via: &str, via_name: &str, uuid: &str, name: &str, peer: &str, now: u64) -> Result<bool, Refusal> {
        if !self.is_member(via) { return Err(Refusal::NotMember); }
        if !valid_uuid(uuid) || !valid_peer(peer) { return Err(Refusal::NotAllowed); }
        if self.vetoed.contains(uuid) { return Err(Refusal::Blocked); }
        if self.is_member(uuid) { return Ok(false); }
        if let Some(r) = self.snap.pending.iter_mut().find(|r| r.uuid == uuid) { r.at = now; r.peer = peer.into(); return Ok(false); }
        if self.snap.pending.len() >= 20 { return Err(Refusal::Full); }
        let via_out = if via == self.snap.leader { String::new() } else { via.to_string() };
        self.snap.pending.push(Request { uuid: uuid.into(), name: clean_name(name), peer: peer.into(), via: via_out, via_name: clean_name(via_name), at: now });
        self.bump();
        Ok(true)
    }

    /// The leader lets a requester in: returns the request (so its `via` member can be told).
    pub fn approve(&mut self, by: &str, uuid: &str, now: u64) -> Result<Request, Refusal> {
        if by != self.snap.leader { return Err(Refusal::NotLeader); }
        let i = self.snap.pending.iter().position(|r| r.uuid == uuid).ok_or(Refusal::Stale)?;
        let r = self.snap.pending.remove(i);
        self.bump();
        if self.vetoed.contains(uuid) { return Err(Refusal::Blocked); }
        self.allow.retain(|a| a.uuid != uuid);
        self.allow.push(Allow { uuid: r.uuid.clone(), peer: r.peer.clone(), until: now + ALLOW_MS });
        Ok(r)
    }

    pub fn decline(&mut self, by: &str, uuid: &str) -> Result<Request, Refusal> {
        if by != self.snap.leader { return Err(Refusal::NotLeader); }
        let i = self.snap.pending.iter().position(|r| r.uuid == uuid).ok_or(Refusal::Stale)?;
        let r = self.snap.pending.remove(i);
        self.bump();
        Ok(r)
    }

    /// A member blocked a pending requester / someone about to be admitted: they never get in. Returns the dropped request.
    pub fn veto(&mut self, by: &str, uuid: &str) -> Option<Request> {
        if !self.is_member(by) || !valid_uuid(uuid) { return None; }
        self.vetoed.insert(uuid.to_string());
        self.allow.retain(|a| a.uuid != uuid);
        let i = self.snap.pending.iter().position(|r| r.uuid == uuid)?;
        self.bump();
        Some(self.snap.pending.remove(i))
    }

    /// Someone dialed the leader to join. Ok(true) = joined now, Ok(false) = a member coming back.
    pub fn join(&mut self, uuid: &str, name: &str, peer: &str, now: u64) -> Result<bool, Refusal> {
        if let Some(m) = self.snap.members.iter_mut().find(|m| m.uuid == uuid) {
            if m.peer != peer { return Err(Refusal::NotAllowed); } // a member's launcher restarted: they need a new invite
            m.away = false;
            self.away.retain(|(u, _)| u != uuid);
            self.bump();
            return Ok(false);
        }
        if self.vetoed.contains(uuid) { return Err(Refusal::Blocked); }
        let i = self.allow.iter().position(|a| a.uuid == uuid && a.peer == peer && a.until >= now).ok_or(Refusal::NotAllowed)?;
        self.allow.remove(i);
        self.snap.pending.retain(|r| r.uuid != uuid);
        let sid = self.next_sid;
        self.next_sid = self.next_sid.wrapping_add(1).max(1);
        self.snap.members.push(Member { uuid: uuid.into(), name: clean_name(name), peer: peer.into(), seq: self.next_seq, sid, ..Default::default() });
        self.next_seq += 1;
        self.bump();
        Ok(true)
    }

    /// Remove a member (leave / kick / timed out). Returns the new leader when the leader left; None = party empty or
    /// leader unchanged. When the last member leaves the snapshot is empty.
    pub fn remove(&mut self, uuid: &str) -> Option<String> {
        let before = self.snap.members.len();
        self.snap.members.retain(|m| m.uuid != uuid);
        if self.snap.members.len() == before { return None; }
        self.away.retain(|(u, _)| u != uuid);
        self.pairs.retain(|(a, b)| a != uuid && b != uuid);
        self.snap.pending.retain(|r| r.via != uuid);
        if matches!(&self.snap.dest, Some(Dest::World { host, .. }) if host == uuid) { self.snap.dest = None; }
        self.bump();
        if self.snap.leader == uuid {
            let next = self.snap.members.iter().min_by_key(|m| m.seq).map(|m| m.uuid.clone())?;
            self.snap.leader = next.clone();
            return Some(next);
        }
        None
    }

    pub fn kick(&mut self, by: &str, uuid: &str) -> Result<(), Refusal> {
        if by != self.snap.leader { return Err(Refusal::NotLeader); }
        if by == uuid || !self.is_member(uuid) { return Err(Refusal::NotMember); }
        self.remove(uuid);
        Ok(())
    }

    /// Hand leadership to a member (the old leader's launcher then hands the party over: see [`Party::adopt`]).
    pub fn promote(&mut self, by: &str, uuid: &str) -> Result<(), Refusal> {
        if by != self.snap.leader { return Err(Refusal::NotLeader); }
        if by == uuid || !self.snap.member(uuid).is_some_and(|m| !m.away) { return Err(Refusal::NotMember); }
        self.snap.leader = uuid.to_string();
        self.bump();
        Ok(())
    }

    /// A member's link dropped: keep their spot for [`GRACE_MS`].
    pub fn lost(&mut self, uuid: &str, now: u64) {
        if let Some(m) = self.snap.members.iter_mut().find(|m| m.uuid == uuid && !m.away) {
            m.away = true;
            m.ready = false;
            self.away.push((uuid.to_string(), now));
            self.bump();
        }
    }

    /// Drop expired invites/requests and members away too long. Returns the uuids that timed out.
    pub fn expire(&mut self, now: u64) -> Vec<String> {
        self.allow.retain(|a| a.until >= now);
        let n = self.snap.pending.len();
        self.snap.pending.retain(|r| r.at + REQUEST_MS >= now);
        if self.snap.pending.len() != n { self.bump(); }
        // adopted parties start their grace at the first expire call
        for (_, t) in &mut self.away { if *t == 0 { *t = now; } }
        let gone: Vec<String> = self.away.iter().filter(|(_, t)| now.saturating_sub(*t) > GRACE_MS).map(|(u, _)| u.clone()).collect();
        for u in &gone { self.remove(u); }
        gone
    }

    /// A member's own state.
    pub fn set_me(&mut self, uuid: &str, ready: bool, mic_state: u8, deaf: bool, game: &str, at: &str, look: Look) -> bool {
        let Some(m) = self.snap.members.iter_mut().find(|m| m.uuid == uuid) else { return false };
        let (game, at) = (short(game, 40), short(at, 12));
        let look = look.clean();
        let mic_state = mic_state.min(2);
        if m.ready == ready && m.mic == mic_state && m.deaf == deaf && m.game == game && m.at == at && m.look == look { return false; }
        m.ready = ready; m.mic = mic_state; m.deaf = deaf; m.game = game; m.at = at; m.look = look;
        self.bump();
        true
    }

    /// The members `uuid` blocked (only pairs inside this party matter).
    pub fn set_blocks(&mut self, uuid: &str, blocked: &[String]) {
        self.pairs.retain(|(a, b)| !((a == uuid || b == uuid) && !blocked.iter().any(|x| x == a || x == b)));
        for b in blocked { if b != uuid && self.is_member(b) { self.pairs.insert(pair(uuid, b)); } }
    }

    pub fn blocked_pair(&self, a: &str, b: &str) -> bool { self.pairs.contains(&pair(a, b)) }

    /// Who receives text from `from`: connected members other than the sender, minus blocked pairs.
    pub fn text_to(&self, from: &str) -> Vec<&Member> {
        self.snap.members.iter().filter(|m| m.uuid != from && !m.away && !self.blocked_pair(from, &m.uuid)).collect()
    }

    /// Who receives voice from `from`: as text, minus deafened members.
    pub fn voice_to(&self, from: &str) -> Vec<&Member> { self.text_to(from).into_iter().filter(|m| !m.deaf).collect() }

    pub fn set_dest(&mut self, by: &str, dest: Option<Dest>) -> Result<(), Refusal> {
        if by != self.snap.leader { return Err(Refusal::NotLeader); }
        let dest = dest.and_then(Dest::clean);
        if self.snap.dest != dest { self.snap.dest = dest; self.bump(); }
        Ok(())
    }

    /// PLAY: ready members go to the destination.
    pub fn go(&mut self, by: &str) -> Result<(), Refusal> {
        if by != self.snap.leader { return Err(Refusal::NotLeader); }
        if self.snap.dest.is_none() { return Err(Refusal::Stale); }
        self.snap.go += 1;
        for m in &mut self.snap.members { if m.uuid != by { m.ready = false; } }
        self.bump();
        Ok(())
    }

    /// The member with this endpoint key.
    pub fn by_peer(&self, peer: &str) -> Option<&Member> { self.snap.members.iter().find(|m| m.peer == peer) }
    pub fn by_sid(&self, sid: u16) -> Option<&Member> { self.snap.members.iter().find(|m| m.sid == sid) }
    /// An allowed (invited / approved) joiner's uuid for this key.
    pub fn allowed_peer(&self, peer: &str, now: u64) -> Option<&str> { self.allow.iter().find(|a| a.peer == peer && a.until >= now).map(|a| a.uuid.as_str()) }
}

/// A random 128-bit hex id.
pub fn new_id() -> String {
    use ring::rand::SecureRandom;
    let mut b = [0u8; 16];
    let _ = ring::rand::SystemRandom::new().fill(&mut b);
    hex::encode(b)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u(c: char) -> String { c.to_string().repeat(32) }
    fn p(c: char) -> String { c.to_string().repeat(64) }
    fn me(c: char) -> Member { Member { uuid: u(c), name: format!("P{c}"), peer: p(c), ..Default::default() } }

    #[test]
    fn solo_invite_join_leave() {
        let mut party = Party::solo(new_id(), me('a'));
        assert_eq!(party.leader(), u('a'));
        assert_eq!(party.snap.size(), 1);
        // a stranger can't join
        assert_eq!(party.join(&u('b'), "Pb", &p('b'), 0), Err(Refusal::NotAllowed));
        party.admit(&u('a'), &u('b'), &p('b'), 0).unwrap();
        // a different key with the same uuid can't use the invite
        assert_eq!(party.join(&u('b'), "Pb", &p('c'), 1), Err(Refusal::NotAllowed));
        assert_eq!(party.join(&u('b'), "Pb", &p('b'), 1), Ok(true));
        assert_eq!(party.snap.size(), 2);
        // the invite is used up
        party.remove(&u('b'));
        assert_eq!(party.join(&u('b'), "Pb", &p('b'), 2), Err(Refusal::NotAllowed));
        // invites expire
        party.admit(&u('a'), &u('c'), &p('c'), 0).unwrap();
        assert_eq!(party.join(&u('c'), "Pc", &p('c'), ALLOW_MS + 1), Err(Refusal::NotAllowed));
    }

    #[test]
    fn leader_leaves_earliest_takes_over() {
        let mut party = Party::solo(new_id(), me('a'));
        for c in ['b', 'c', 'd'] { party.admit(&u('a'), &u(c), &p(c), 0).unwrap(); party.join(&u(c), "x", &p(c), 0).unwrap(); }
        assert_eq!(party.remove(&u('a')), Some(u('b')));
        assert_eq!(party.leader(), u('b'));
        assert_eq!(party.remove(&u('c')), None);
        assert_eq!(party.remove(&u('b')), Some(u('d')));
        assert_eq!(party.remove(&u('d')), None);
        assert!(party.snap.members.is_empty());
    }

    #[test]
    fn requests_need_the_leader_and_vetoes_win() {
        let mut party = Party::solo(new_id(), me('a'));
        party.admit(&u('a'), &u('b'), &p('b'), 0).unwrap();
        party.join(&u('b'), "Pb", &p('b'), 0).unwrap();
        // c, a friend of b, asks; only the leader decides
        assert_eq!(party.request(&u('b'), "Pb", &u('c'), "Pc", &p('c'), 0), Ok(true));
        assert_eq!(party.snap.pending[0].via_name, "Pb");
        assert_eq!(party.approve(&u('b'), &u('c'), 0), Err(Refusal::NotLeader));
        let r = party.approve(&u('a'), &u('c'), 0).unwrap();
        assert_eq!(r.via, u('b'));
        assert_eq!(party.join(&u('c'), "Pc", &p('c'), 1), Ok(true));
        // d asks; b blocked d: vetoed, and stays out even if invited later
        party.request(&u('b'), "Pb", &u('d'), "Pd", &p('d'), 0).unwrap();
        assert!(party.veto(&u('b'), &u('d')).is_some());
        assert!(party.snap.pending.is_empty());
        assert_eq!(party.admit(&u('c'), &u('d'), &p('d'), 0), Err(Refusal::Blocked));
        // requests time out
        party.request(&u('b'), "Pb", &u('e'), "Pe", &p('e'), 0).unwrap();
        party.expire(REQUEST_MS + 1);
        assert!(party.snap.pending.is_empty());
    }

    #[test]
    fn pairwise_blocks_route_around() {
        let mut party = Party::solo(new_id(), me('a'));
        for c in ['b', 'c'] { party.admit(&u('a'), &u(c), &p(c), 0).unwrap(); party.join(&u(c), "x", &p(c), 0).unwrap(); }
        party.set_blocks(&u('a'), &[u('b')]);
        let to: Vec<&str> = party.text_to(&u('a')).iter().map(|m| m.uuid.as_str()).collect();
        assert_eq!(to, vec![u('c').as_str()]);
        let to: Vec<&str> = party.text_to(&u('b')).iter().map(|m| m.uuid.as_str()).collect();
        assert_eq!(to, vec![u('c').as_str()]);
        party.set_blocks(&u('a'), &[]);
        assert_eq!(party.text_to(&u('a')).len(), 2);
        // deafened members get no voice
        party.set_me(&u('c'), false, mic::PTT, true, "", "lobby", Look::default());
        assert_eq!(party.voice_to(&u('a')).len(), 1);
    }

    #[test]
    fn lost_members_come_back_or_time_out() {
        let mut party = Party::solo(new_id(), me('a'));
        party.admit(&u('a'), &u('b'), &p('b'), 0).unwrap();
        party.join(&u('b'), "Pb", &p('b'), 0).unwrap();
        party.lost(&u('b'), 1000);
        assert!(party.snap.member(&u('b')).unwrap().away);
        assert_eq!(party.join(&u('b'), "Pb", &p('b'), 2000), Ok(false));
        assert!(!party.snap.member(&u('b')).unwrap().away);
        party.lost(&u('b'), 3000);
        assert_eq!(party.expire(3000 + GRACE_MS + 1), vec![u('b')]);
        assert_eq!(party.snap.size(), 1);
    }

    #[test]
    fn adopt_and_successor_agree() {
        let mut party = Party::solo(new_id(), me('a'));
        for c in ['b', 'c'] { party.admit(&u('a'), &u(c), &p(c), 0).unwrap(); party.join(&u(c), "x", &p(c), 0).unwrap(); }
        let snap = party.snap.clone();
        let next = snap.successor(&u('a')).unwrap().uuid.clone();
        assert_eq!(next, u('b'));
        let mut np = Party::adopt(snap.clone(), &next, Some(&u('a')));
        assert_eq!(np.leader(), u('b'));
        assert!(np.snap.epoch > snap.epoch);
        assert_eq!(np.snap.size(), 2);
        // c redials the new leader with the same key
        assert_eq!(np.join(&u('c'), "x", &p('c'), 5), Ok(false));
        // only the leader sets the destination; PLAY needs one
        assert_eq!(np.go(&u('b')), Err(Refusal::Stale));
        np.set_dest(&u('b'), Some(Dest::Server { address: "Mc.Example.net:25565".into(), name: "Ex".into() })).unwrap();
        assert!(matches!(&np.snap.dest, Some(Dest::Server { address, .. }) if address == "mc.example.net:25565"));
        assert_eq!(np.set_dest(&u('c'), None), Err(Refusal::NotLeader));
        np.go(&u('b')).unwrap();
        assert_eq!(np.snap.go, 1);
    }

    #[test]
    fn wire_round_trip_and_voice_frames() {
        let m = Msg::Invite { party: new_id(), leader: u('a'), leader_name: "Pa".into(), leader_peer: p('a'), size: 2, auto: false };
        assert_eq!(Msg::parse(&m.to_bytes()), Some(m));
        assert_eq!(Msg::parse(br#"{"t":"ask"}"#), Some(Msg::Ask));
        assert!(Msg::parse(br#"{"t":"nope"}"#).is_none());
        let f = voice_frame(0, 7, &[1, 2, 3]);
        let g = restamp(&f, 300);
        assert_eq!(parse_voice(&g), Some((300, 7, &[1u8, 2, 3][..])));
        assert!(parse_voice(&[1, 0]).is_none());
        assert_eq!(clean_text("  hi\u{0007}there  "), "hi there");
        assert_eq!(clean_name("No tch!"), "Notch");
        assert!(Dest::Server { address: "bad host:1".into(), name: String::new() }.clean().is_none());
    }
}
