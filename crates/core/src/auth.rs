//! Microsoft account sign-in: OAuth device code -> Xbox Live -> XSTS -> Minecraft Services.
//! The user signs in on microsoft.com themselves; we never see their password.
//!
//! Needs an Azure app registration (public client, "personal Microsoft accounts") that Mojang has
//! approved for the Minecraft API. Configure its id via `NETHER_MS_CLIENT_ID` or `config.json`.
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use anyhow::{anyhow, bail, Result};
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::paths::Paths;
use crate::Session;

const SCOPE: &str = "XboxLive.signin offline_access";
const DEVICE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const KEYRING_SERVICE: &str = "NetherClient";
const KEYRING_USER: &str = "microsoft-refresh-token";
const AUTH_TIMEOUT: Duration = Duration::from_secs(20);

/// Send, retrying connection failures and timeouts (flaky Wi-Fi, Xbox hiccups) a few times.
fn retry(f: impl Fn() -> reqwest::Result<reqwest::blocking::Response>) -> reqwest::Result<reqwest::blocking::Response> {
    let mut r = f();
    for i in 0..3u64 {
        match &r {
            Err(e) if e.is_connect() || e.is_timeout() => { std::thread::sleep(Duration::from_millis(300 * (i + 1))); r = f(); }
            _ => break,
        }
    }
    r
}

/// Does this Minecraft token's account hold a Java Edition license (incl. Game Pass)?
fn owns_java(c: &Client, mc_token: &str) -> Result<bool> {
    let v: Value = retry(|| c.get("https://api.minecraftservices.com/entitlements/mcstore").bearer_auth(mc_token).timeout(AUTH_TIMEOUT).send())?.error_for_status()?.json()?;
    Ok(v["items"].as_array().map(|a| a.iter().any(|i| matches!(i["name"].as_str(), Some("product_minecraft" | "game_minecraft")))).unwrap_or(false))
}

#[derive(Deserialize, Clone, Debug)]
pub struct DeviceCode {
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
}

#[derive(Clone, Debug)]
pub struct MsTokens {
    pub access_token: String,
    pub refresh_token: String,
}

#[derive(Clone, Debug)]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub skins: Vec<Value>,
    pub capes: Vec<Value>,
}

#[derive(Clone, Debug)]
pub struct McLogin {
    pub session: Session,
    pub profile: Profile,
    /// Seconds the Minecraft token stays valid.
    pub expires_in: u64,
}

/// Built-in Azure app (public client) id; fallback when neither env nor config.json sets one.
pub const DEFAULT_CLIENT_ID: &str = "1426ea90-d649-4d74-8148-1232735d4e38";

/// The Azure app (client) id: env var, then `config.json` {"ms_client_id": "..."}, then the built-in default.
/// If config.json lacks the field it is written (other fields preserved, existing values never overwritten).
pub fn client_id(paths: &Paths) -> Option<String> {
    if let Ok(v) = std::env::var("NETHER_MS_CLIENT_ID") {
        if !v.trim().is_empty() { return Some(v.trim().to_string()); }
    }
    let v = crate::store::config(paths);
    if let Some(id) = v.get("ms_client_id").and_then(|s| s.as_str()).map(str::trim).filter(|s| !s.is_empty()) {
        return Some(id.to_string());
    }
    let _ = crate::store::update_config(paths, |o| { o.entry("ms_client_id").or_insert_with(|| json!(DEFAULT_CLIENT_ID)); });
    Some(DEFAULT_CLIENT_ID.to_string())
}

pub fn start_device_flow(c: &Client, client_id: &str) -> Result<DeviceCode> {
    let r = c.post(DEVICE_URL).form(&[("client_id", client_id), ("scope", SCOPE)]).send()?;
    if !r.status().is_success() {
        bail!("Microsoft rejected the sign-in request: {}", r.text().unwrap_or_default());
    }
    Ok(r.json()?)
}

fn parse_tokens(v: &Value) -> Result<MsTokens> {
    Ok(MsTokens {
        access_token: v["access_token"].as_str().ok_or_else(|| anyhow!("no access_token"))?.into(),
        refresh_token: v["refresh_token"].as_str().ok_or_else(|| anyhow!("no refresh_token"))?.into(),
    })
}

/// Block until the user finishes signing in (or the code expires / `cancel` is set).
pub fn poll_device_flow(c: &Client, client_id: &str, dc: &DeviceCode, cancel: &AtomicBool) -> Result<MsTokens> {
    let mut interval = dc.interval.max(1);
    let deadline = std::time::Instant::now() + Duration::from_secs(dc.expires_in);
    loop {
        if cancel.load(Ordering::Relaxed) { bail!("cancelled"); }
        if std::time::Instant::now() > deadline { bail!("the sign-in code expired"); }
        std::thread::sleep(Duration::from_secs(interval));
        let r = c
            .post(TOKEN_URL)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", client_id),
                ("device_code", dc.device_code.as_str()),
            ])
            .send()?;
        let ok = r.status().is_success();
        let v: Value = r.json()?;
        if ok { return parse_tokens(&v); }
        match v["error"].as_str().unwrap_or("") {
            "authorization_pending" => {}
            "slow_down" => interval += 5,
            "authorization_declined" => bail!("sign-in was declined"),
            "expired_token" => bail!("the sign-in code expired"),
            e => bail!("sign-in failed: {e}"),
        }
    }
}

pub fn refresh(c: &Client, client_id: &str, refresh_token: &str) -> Result<MsTokens> {
    let r = c.post(TOKEN_URL).form(&[("grant_type", "refresh_token"), ("client_id", client_id), ("refresh_token", refresh_token), ("scope", SCOPE)]).send()?;
    let ok = r.status().is_success();
    let v: Value = r.json()?;
    if !ok { bail!("session expired, please sign in again ({})", v["error"].as_str().unwrap_or("unknown")); }
    parse_tokens(&v)
}

fn xerr(code: u64) -> &'static str {
    match code {
        2148916233 => "This Microsoft account has no Xbox profile. Sign in once at xbox.com to create one.",
        2148916235 => "Xbox Live is not available in your country.",
        2148916236 | 2148916237 => "This account needs adult verification on the Xbox page.",
        2148916238 => "This is a child account. Add it to a Microsoft family to play.",
        _ => "Xbox Live refused the sign-in. Sign in once at xbox.com, then try again.",
    }
}

/// Exchange a Microsoft access token for a Minecraft session + profile.
pub fn minecraft_login(c: &Client, ms_access: &str) -> Result<McLogin> {
    // Xbox Live user token
    let xbl: Value = retry(|| c
        .post("https://user.auth.xboxlive.com/user/authenticate")
        .header("Accept", "application/json")
        .timeout(AUTH_TIMEOUT)
        .json(&json!({
            "Properties": {"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": format!("d={ms_access}")},
            "RelyingParty": "http://auth.xboxlive.com", "TokenType": "JWT"
        }))
        .send())?
        .error_for_status()?
        .json()?;
    let xbl_token = xbl["Token"].as_str().ok_or_else(|| anyhow!("no Xbox token"))?.to_string();

    // XSTS token for the Minecraft services
    let r = retry(|| c
        .post("https://xsts.auth.xboxlive.com/xsts/authorize")
        .header("Accept", "application/json")
        .timeout(AUTH_TIMEOUT)
        .json(&json!({
            "Properties": {"SandboxId": "RETAIL", "UserTokens": [xbl_token]},
            "RelyingParty": "rp://api.minecraftservices.com/", "TokenType": "JWT"
        }))
        .send())?;
    let ok = r.status().is_success();
    let xsts: Value = r.json()?;
    if !ok { bail!("{}", xerr(xsts["XErr"].as_u64().unwrap_or(0))); }
    let xsts_token = xsts["Token"].as_str().ok_or_else(|| anyhow!("no XSTS token"))?;
    let uhs = xsts["DisplayClaims"]["xui"][0]["uhs"].as_str().ok_or_else(|| anyhow!("no user hash"))?;

    // Minecraft access token
    let r = retry(|| c
        .post("https://api.minecraftservices.com/authentication/login_with_xbox")
        .timeout(AUTH_TIMEOUT)
        .json(&json!({"identityToken": format!("XBL3.0 x={uhs};{xsts_token}")}))
        .send())?;
    match r.status().as_u16() {
        403 => bail!("Mojang has not approved this app registration for the Minecraft API yet (https://aka.ms/AppRegInfo)."),
        429 => bail!("Minecraft is rate-limiting sign-ins. Wait a minute and try again."),
        _ => {}
    }
    let mc: Value = r.error_for_status()?.json()?;
    let mc_token = mc["access_token"].as_str().ok_or_else(|| anyhow!("no Minecraft token"))?.to_string();
    let expires_in = mc["expires_in"].as_u64().unwrap_or(86400);
    let profile = fetch_profile(c, &mc_token)?;
    Ok(login_from(profile, mc_token, expires_in))
}

fn fetch_profile(c: &Client, mc_token: &str) -> Result<Profile> {
    let r = retry(|| c.get("https://api.minecraftservices.com/minecraft/profile").bearer_auth(mc_token).timeout(AUTH_TIMEOUT).send())?;
    match r.status().as_u16() {
        401 => bail!("the saved Minecraft token expired"),
        // 404 = no Java profile: either the game isn't owned, or it is (e.g. Game Pass) but no name was picked yet
        404 => {
            if owns_java(c, mc_token).unwrap_or(false) { bail!("This account owns Minecraft but has no profile yet. Pick a name at minecraft.net first."); }
            bail!("This Microsoft account does not own Minecraft: Java Edition.");
        }
        _ => {}
    }
    let p: Value = r.error_for_status()?.json()?;
    Ok(Profile {
        id: p["id"].as_str().unwrap_or_default().into(),
        name: p["name"].as_str().unwrap_or_default().into(),
        skins: p["skins"].as_array().cloned().unwrap_or_default(),
        capes: p["capes"].as_array().cloned().unwrap_or_default(),
    })
}

fn login_from(profile: Profile, token: String, expires_in: u64) -> McLogin {
    let session = Session { name: profile.name.clone(), uuid: profile.id.clone(), access_token: token, user_type: "msa".into() };
    McLogin { session, profile, expires_in }
}

// ---- accounts: several Microsoft accounts, one active ----
// Metadata lives in `<root>/accounts.json` (no secrets). Secrets live in Windows Credential Manager as UTF-8 blobs:
// `refresh:<uuid>` (Microsoft refresh token) and `mc:<uuid>` (the Minecraft token, cached for its ~24 h life).

const ACCOUNTS_FILE: &str = "accounts.json";
/// Re-authenticate when the cached Minecraft token has less than this left.
const MC_TOKEN_MARGIN: u64 = 30 * 60;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
pub struct Account {
    pub uuid: String,
    pub name: String,
    /// Unix seconds when the cached Minecraft token expires (0 = none cached).
    #[serde(default)]
    pub mc_expires: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
pub struct Accounts {
    #[serde(default)]
    pub active: String,
    #[serde(default)]
    pub list: Vec<Account>,
}

impl Accounts {
    pub fn active(&self) -> Option<&Account> { self.list.iter().find(|a| a.uuid == self.active).or(self.list.first()) }
}

fn now_secs() -> u64 { std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) }

/// Saved accounts for display (empty when missing or unreadable; never moves or writes anything).
pub fn accounts(paths: &Paths) -> Accounts {
    crate::store::peek_json(&paths.root.join(ACCOUNTS_FILE)).unwrap_or_default()
}

/// Saved accounts before a change: an unreadable (locked) file is an error so it is never saved over;
/// a damaged one is moved aside and the list starts empty.
fn accounts_for_update(paths: &Paths) -> Result<Accounts> {
    Ok(match crate::store::read_json::<Accounts>(&paths.root.join(ACCOUNTS_FILE))? {
        crate::store::Load::Ok(a) => a,
        _ => Accounts::default(),
    })
}

fn save_accounts(paths: &Paths, a: &Accounts) -> Result<()> {
    crate::store::write_json(&paths.root.join(ACCOUNTS_FILE), a)
}

fn secret_set(key: &str, v: &str) -> Result<()> {
    keyring::Entry::new(KEYRING_SERVICE, key)?.set_secret(v.as_bytes())?;
    Ok(())
}

fn secret_get(key: &str) -> Option<String> {
    keyring::Entry::new(KEYRING_SERVICE, key).ok()?.get_secret().ok().and_then(|b| String::from_utf8(b).ok())
}

fn secret_del(key: &str) {
    if let Ok(e) = keyring::Entry::new(KEYRING_SERVICE, key) { let _ = e.delete_credential(); }
}

/// Legacy single-account refresh token (before multi-account); migrated on the next restore.
pub fn load_refresh_token() -> Option<String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).ok()?.get_password().ok()
}

/// Delete the legacy single-account token.
fn drop_legacy_token() { secret_del(KEYRING_USER); }

/// True when there is anything to restore (a saved account or a legacy token).
pub fn has_saved_login(paths: &Paths) -> bool { !accounts(paths).list.is_empty() || load_refresh_token().is_some() }

/// Store a fresh sign-in: tokens to the credential store, the account to accounts.json, and make it active.
pub fn remember(paths: &Paths, login: &McLogin, refresh_token: &str) -> Result<()> {
    let uuid = login.profile.id.clone();
    secret_set(&format!("refresh:{uuid}"), refresh_token)?;
    let exp = if secret_set(&format!("mc:{uuid}"), &login.session.access_token).is_ok() { now_secs() + login.expires_in } else { 0 };
    let mut a = accounts_for_update(paths)?;
    a.list.retain(|x| x.uuid != uuid);
    a.list.push(Account { uuid: uuid.clone(), name: login.profile.name.clone(), mc_expires: exp });
    a.active = uuid;
    save_accounts(paths, &a)?;
    drop_legacy_token();
    let _ = record_ownership(paths, &login.profile);
    Ok(())
}

/// Make another saved account the active one.
pub fn set_active(paths: &Paths, uuid: &str) -> Result<()> {
    let mut a = accounts_for_update(paths)?;
    if !a.list.iter().any(|x| x.uuid == uuid) { bail!("account not found"); }
    a.active = uuid.into();
    save_accounts(paths, &a)
}

/// Forget an account and its tokens. The next saved account, if any, becomes active.
pub fn remove(paths: &Paths, uuid: &str) -> Result<()> {
    secret_del(&format!("refresh:{uuid}"));
    secret_del(&format!("mc:{uuid}"));
    let mut a = accounts_for_update(paths)?;
    a.list.retain(|x| x.uuid != uuid);
    if a.active == uuid { a.active = a.list.first().map(|x| x.uuid.clone()).unwrap_or_default(); }
    save_accounts(paths, &a)?;
    if a.list.is_empty() { drop_legacy_token(); clear_ownership(paths); }
    Ok(())
}

/// The result of restoring a saved account.
pub struct Restored {
    pub login: McLogin,
    /// The network was unreachable: the session is the cached one (multiplayer may refuse it) and has no skins.
    pub offline: bool,
}

/// True when a failure is temporary (no network, rate limit, a service hiccup) rather than "bad credentials":
/// the saved sign-in is kept instead of signing the player out.
pub fn is_network_error(e: &anyhow::Error) -> bool {
    e.chain().any(|c| c.downcast_ref::<reqwest::Error>().map(|r| r.is_connect() || r.is_timeout() || r.status().is_some_and(|s| s.as_u16() == 429 || s.is_server_error())).unwrap_or(false))
}

fn offline_restore(acc: &Account, token: &str) -> Restored {
    let profile = Profile { id: acc.uuid.clone(), name: acc.name.clone(), skins: vec![], capes: vec![] };
    Restored { login: login_from(profile, token.into(), 0), offline: true }
}

/// Sign the active account in again without the user: the cached Minecraft token while it is good, else a refresh.
/// With no network the cached identity comes back with `offline: true`, so singleplayer still works.
pub fn restore(c: &Client, paths: &Paths, client_id: &str) -> Result<Restored> {
    let a = accounts(paths);
    let Some(acc) = a.active().cloned() else {
        // migrate the legacy single-account token
        let rt = load_refresh_token().ok_or_else(|| anyhow!("not signed in"))?;
        let t = refresh(c, client_id, &rt)?;
        let login = minecraft_login(c, &t.access_token)?;
        remember(paths, &login, &t.refresh_token)?;
        return Ok(Restored { login, offline: false });
    };
    let cached = secret_get(&format!("mc:{}", acc.uuid));
    if let Some(tok) = cached.as_ref().filter(|_| acc.mc_expires > now_secs() + MC_TOKEN_MARGIN) {
        match fetch_profile(c, tok) {
            Ok(profile) => return Ok(Restored { login: login_from(profile, tok.clone(), acc.mc_expires.saturating_sub(now_secs())), offline: false }),
            Err(e) if is_network_error(&e) => return Ok(offline_restore(&acc, tok)),
            Err(_) => {}
        }
    }
    let rt = secret_get(&format!("refresh:{}", acc.uuid)).ok_or_else(|| anyhow!("Sign in again to use {}.", acc.name))?;
    match refresh(c, client_id, &rt).and_then(|t| minecraft_login(c, &t.access_token).map(|l| (l, t))) {
        Ok((login, t)) => {
            remember(paths, &login, &t.refresh_token)?;
            Ok(Restored { login, offline: false })
        }
        Err(e) if is_network_error(&e) => Ok(offline_restore(&acc, cached.as_deref().unwrap_or("0"))),
        Err(e) => Err(e),
    }
}

/// A session safe to launch with: renews the account's Minecraft token when it is about to expire
/// (a launcher left open for a day would otherwise start the game with a dead token).
pub fn fresh_session(c: &Client, paths: &Paths, client_id: &str, current: &Session) -> Result<Session> {
    let a = accounts(paths);
    match a.list.iter().find(|x| x.uuid == current.uuid) {
        Some(acc) if acc.mc_expires > now_secs() + MC_TOKEN_MARGIN => Ok(current.clone()),
        Some(acc) => {
            if a.active != acc.uuid { set_active(paths, &acc.uuid)?; }
            match restore(c, paths, client_id) {
                Ok(r) if !r.offline => Ok(r.login.session),
                Ok(_) => Ok(current.clone()),
                Err(e) if is_network_error(&e) => Ok(current.clone()),
                Err(e) => Err(e),
            }
        }
        None => Ok(current.clone()),
    }
}

// ---- launch gate: real login required, offline only as a debug switch or for known owners ----

const ACCOUNT_FILE: &str = "account.json";

/// Why launching is allowed or not.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LaunchGate {
    /// Signed in with a Microsoft/Minecraft account that owns the game.
    Allowed,
    /// Offline play via `NETHER_DEV_OFFLINE` in a build with the `dev` feature.
    AllowedDevOffline,
    /// Offline play: this machine has signed in before with an account that owns the game.
    AllowedOfflineOwner,
    /// Not allowed; the message is for the user.
    Denied(String),
}

impl LaunchGate {
    /// True for every Allowed variant.
    pub fn allowed(&self) -> bool { !matches!(self, LaunchGate::Denied(_)) }
}

/// The cached "this machine has signed in with an owning account" record (`<root>/account.json`; no tokens).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct OwnerRecord {
    pub owns_game: bool,
    pub name: String,
    pub uuid: String,
    /// Unix seconds of the last successful online verification.
    pub verified: u64,
}

/// Remember that `profile` signed in and owns the game (call after every successful `minecraft_login`).
pub fn record_ownership(paths: &Paths, profile: &Profile) -> Result<()> {
    let rec = OwnerRecord {
        owns_game: !profile.id.is_empty(),
        name: profile.name.clone(),
        uuid: profile.id.clone(),
        verified: std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0),
    };
    crate::store::write_json(&paths.root.join(ACCOUNT_FILE), &rec)
}

/// The cached ownership record, if any.
pub fn cached_ownership(paths: &Paths) -> Option<OwnerRecord> {
    serde_json::from_slice(&std::fs::read(paths.root.join(ACCOUNT_FILE)).ok()?).ok()
}

/// Forget the cached ownership record (offline play is refused again until the next online sign-in).
pub fn clear_ownership(paths: &Paths) { let _ = std::fs::remove_file(paths.root.join(ACCOUNT_FILE)); }

/// A session from a real Minecraft sign-in (not `Session::offline`).
pub fn is_real_login(s: &Session) -> bool {
    s.user_type == "msa" && !s.uuid.is_empty() && !s.name.is_empty() && s.access_token.len() > 1
}

/// Offline play needs an online ownership check of the same account within this many seconds.
pub const OFFLINE_GRACE: u64 = 30 * 86400;

/// Pure decision behind `launch_gate`. Offline play: the cached session must be the account that was verified
/// online as an owner, within `OFFLINE_GRACE` of `now`, and still have its sign-in in the credential store.
pub fn decide_gate(session: Option<&Session>, dev_offline_env: bool, dev_build: bool, owner: Option<&OwnerRecord>, now: u64, has_refresh: bool) -> LaunchGate {
    if session.map(is_real_login).unwrap_or(false) { return LaunchGate::Allowed; }
    if dev_offline_env && dev_build { return LaunchGate::AllowedDevOffline; }
    let same = |o: &OwnerRecord| session.is_some_and(|s| !s.uuid.is_empty() && s.uuid.replace('-', "").eq_ignore_ascii_case(&o.uuid.replace('-', "")));
    if let Some(o) = owner.filter(|o| o.owns_game && same(o) && has_refresh) {
        if now.saturating_sub(o.verified) <= OFFLINE_GRACE { return LaunchGate::AllowedOfflineOwner; }
        return LaunchGate::Denied("Connect to the internet once so Microsoft can confirm you still own Minecraft (offline play works for 30 days after that).".into());
    }
    LaunchGate::Denied("Sign in with the Microsoft account that owns Minecraft: Java Edition to play.".into())
}

/// May the game be launched? `session` is the current sign-in (None when signed out).
/// `NETHER_DEV_OFFLINE` only counts in builds with the `dev` cargo feature (never shipped).
pub fn launch_gate(paths: &Paths, session: Option<&Session>) -> LaunchGate {
    let dev = std::env::var_os("NETHER_DEV_OFFLINE").is_some();
    let has_refresh = session.is_some_and(|s| secret_get(&format!("refresh:{}", s.uuid)).is_some());
    decide_gate(session, dev, cfg!(feature = "dev"), cached_ownership(paths).as_ref(), now_secs(), has_refresh)
}

#[cfg(test)]
mod gate_tests {
    use super::*;

    fn real() -> Session { Session { name: "Steve".into(), uuid: "8667ba71b85a4004af54457a9734eed7".into(), access_token: "eyJ.tok".into(), user_type: "msa".into() } }
    fn owner(owns: bool) -> OwnerRecord { OwnerRecord { owns_game: owns, name: "Steve".into(), uuid: "8667ba71b85a4004af54457a9734eed7".into(), verified: 1000 } }
    fn cached() -> Session { Session { access_token: "0".into(), ..real() } }

    #[test]
    fn real_login_always_allowed() {
        assert_eq!(decide_gate(Some(&real()), false, false, None, 1000, false), LaunchGate::Allowed);
    }

    #[test]
    fn offline_session_is_not_a_login() {
        let off = Session::offline("Steve");
        assert!(!is_real_login(&off));
        assert!(!decide_gate(Some(&off), false, false, None, 1000, true).allowed());
        assert!(!decide_gate(None, false, true, None, 1000, true).allowed());
    }

    #[test]
    fn dev_switch_needs_dev_build() {
        assert_eq!(decide_gate(None, true, true, None, 1000, false), LaunchGate::AllowedDevOffline);
        assert!(!decide_gate(None, true, false, None, 1000, false).allowed());
    }

    #[test]
    fn known_owner_may_play_offline() {
        assert_eq!(decide_gate(Some(&cached()), false, false, Some(&owner(true)), 1000 + OFFLINE_GRACE, true), LaunchGate::AllowedOfflineOwner);
        assert!(!decide_gate(Some(&cached()), false, false, Some(&owner(false)), 1000, true).allowed());
        assert!(!decide_gate(None, false, false, Some(&owner(true)), 1000, true).allowed(), "needs the cached account");
        assert!(!decide_gate(Some(&cached()), false, false, Some(&owner(true)), 1000, false).allowed(), "needs its stored sign-in");
        assert!(!decide_gate(Some(&cached()), false, false, Some(&owner(true)), 1001 + OFFLINE_GRACE, true).allowed(), "verified too long ago");
        let other = Session { uuid: "0000ba71b85a4004af54457a9734eed7".into(), ..cached() };
        assert!(!decide_gate(Some(&other), false, false, Some(&owner(true)), 1000, true).allowed(), "another account");
    }

    #[test]
    fn ownership_cache_round_trip() {
        let p = Paths { root: std::env::temp_dir().join(format!("nc-gate-{}", std::process::id())) };
        assert!(cached_ownership(&p).is_none());
        assert!(!launch_gate(&p, None).allowed() || cfg!(feature = "dev"));
        let prof = Profile { id: "8667ba71b85a4004af54457a9734eed7".into(), name: "Steve".into(), skins: vec![], capes: vec![] };
        record_ownership(&p, &prof).unwrap();
        let r = cached_ownership(&p).unwrap();
        assert!(r.owns_game && r.name == "Steve");
        let text = std::fs::read_to_string(p.root.join(ACCOUNT_FILE)).unwrap();
        assert!(!text.contains("token"));
        assert!(!launch_gate(&p, None).allowed() || cfg!(feature = "dev"), "a record alone is not enough");
        clear_ownership(&p);
        assert!(cached_ownership(&p).is_none());
        let _ = std::fs::remove_dir_all(&p.root);
    }
}
