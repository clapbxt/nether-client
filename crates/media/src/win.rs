//! Windows backend: GSMTC events (session, media properties, playback info, timeline) + a 1 s re-read as fallback.
use std::io::{BufRead, Write};
use std::path::PathBuf;
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use windows::core::Interface;
use windows::Foundation::TypedEventHandler;
use windows::Media::Control::{
    CurrentSessionChangedEventArgs, GlobalSystemMediaTransportControlsSession as Session,
    GlobalSystemMediaTransportControlsSessionManager as Manager,
    GlobalSystemMediaTransportControlsSessionMediaProperties as Props,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status, MediaPropertiesChangedEventArgs,
    PlaybackInfoChangedEventArgs, SessionsChangedEventArgs, TimelinePropertiesChangedEventArgs,
};
use windows::Storage::Streams::{Buffer, DataReader, InputStreamOptions};

enum Msg {
    Changed,
    Cmd(String),
    Quit,
}

const NONE: &str = "{\"t\":\"none\"}";
/// 1601-01-01 -> 1970-01-01 in 100 ns ticks (WinRT DateTime -> Unix ms)
const EPOCH_TICKS: i64 = 116_444_736_000_000_000;

pub fn run() {
    let args: Vec<String> = std::env::args().collect();
    let (mut art_dir, mut parent) = (None, None);
    let mut i = 1;
    while i + 1 < args.len() {
        match args[i].as_str() {
            "--art-dir" => art_dir = Some(PathBuf::from(&args[i + 1])),
            "--parent" => parent = args[i + 1].parse::<u32>().ok(),
            _ => { i += 1; continue; }
        }
        i += 2;
    }
    let art_dir = art_dir
        .or_else(|| std::env::var_os("LOCALAPPDATA").map(|p| PathBuf::from(p).join("NetherClient").join("media")))
        .unwrap_or_else(|| std::env::temp_dir().join("nether-media"));
    let _ = std::fs::create_dir_all(&art_dir);
    if !emit("{\"t\":\"hello\",\"v\":1}") { return; }

    let (tx, rx) = mpsc::channel();
    {
        let tx = tx.clone();
        std::thread::spawn(move || {
            for line in std::io::stdin().lock().lines() {
                let Ok(l) = line else { break };
                let l = l.trim();
                if l == "quit" { break; }
                if !l.is_empty() && tx.send(Msg::Cmd(l.to_string())).is_err() { return; }
            }
            let _ = tx.send(Msg::Quit); // stdin closed: the game is gone
        });
    }
    if let Some(pid) = parent {
        let tx = tx.clone();
        std::thread::spawn(move || { if parent_gone(pid) { let _ = tx.send(Msg::Quit); } });
    }

    let mgr = match Manager::RequestAsync().and_then(|op| op.join()) {
        Ok(m) => m,
        Err(e) => {
            emit(&format!("{{\"t\":\"error\",\"msg\":{}}}", esc(&e.message())));
            while let Ok(m) = rx.recv() { if let Msg::Quit = m { break; } }
            std::process::exit(1);
        }
    };
    for which in 0..2 {
        let t = tx.clone();
        let _ = if which == 0 {
            mgr.CurrentSessionChanged(&TypedEventHandler::<Manager, CurrentSessionChangedEventArgs>::new(move |_, _| { let _ = t.send(Msg::Changed); Ok(()) }))
        } else {
            mgr.SessionsChanged(&TypedEventHandler::<Manager, SessionsChangedEventArgs>::new(move |_, _| { let _ = t.send(Msg::Changed); Ok(()) }))
        };
    }

    let mut app = App { mgr, tx, art_dir, hooked: None, prefer: Vec::new(), last: String::new(), art_key: String::new(), art_path: String::new(), art_tries: 0 };
    app.refresh();
    loop {
        match rx.recv_timeout(Duration::from_millis(1000)) {
            Ok(Msg::Quit) | Err(RecvTimeoutError::Disconnected) => break,
            Ok(Msg::Cmd(c)) => app.command(&c),
            Ok(Msg::Changed) | Err(RecvTimeoutError::Timeout) => {
                // events come in bursts (a track change fires all three): one read for the lot
                std::thread::sleep(Duration::from_millis(30));
                while let Ok(m) = rx.try_recv() {
                    match m {
                        Msg::Quit => std::process::exit(0),
                        Msg::Cmd(c) => app.command(&c),
                        Msg::Changed => {}
                    }
                }
                app.refresh();
            }
        }
    }
    std::process::exit(0);
}

struct Hooked {
    s: Session,
    tokens: [i64; 3],
}

struct App {
    mgr: Manager,
    tx: Sender<Msg>,
    art_dir: PathBuf,
    hooked: Option<Hooked>,
    /// lower-case AUMID fragments of the preferred source ("spotify"; "chrome,msedge,..."), empty = system choice
    prefer: Vec<String>,
    last: String,
    art_key: String,
    art_path: String,
    art_tries: u32,
}

impl App {
    /// Preferred source when it is playing, else the session Windows considers current, else any preferred one.
    fn pick(&self) -> Option<Session> {
        let cur = self.mgr.GetCurrentSession().ok();
        if self.prefer.is_empty() { return cur; }
        let mut fallback = None;
        if let Ok(all) = self.mgr.GetSessions() {
            for i in 0..all.Size().unwrap_or(0) {
                let Ok(s) = all.GetAt(i) else { continue };
                let id = s.SourceAppUserModelId().map(|h| h.to_string().to_lowercase()).unwrap_or_default();
                if !self.prefer.iter().any(|t| id.contains(t.as_str())) { continue; }
                let playing = s.GetPlaybackInfo().and_then(|p| p.PlaybackStatus()).map(|st| st == Status::Playing).unwrap_or(false);
                if playing { return Some(s); }
                if fallback.is_none() { fallback = Some(s); }
            }
        }
        cur.or(fallback)
    }

    fn hook(&mut self, s: &Session) {
        if self.hooked.as_ref().is_some_and(|h| h.s.as_raw() == s.as_raw()) { return; }
        self.unhook();
        let (a, b, c) = (self.tx.clone(), self.tx.clone(), self.tx.clone());
        let t0 = s.MediaPropertiesChanged(&TypedEventHandler::<Session, MediaPropertiesChangedEventArgs>::new(move |_, _| { let _ = a.send(Msg::Changed); Ok(()) })).unwrap_or(0);
        let t1 = s.PlaybackInfoChanged(&TypedEventHandler::<Session, PlaybackInfoChangedEventArgs>::new(move |_, _| { let _ = b.send(Msg::Changed); Ok(()) })).unwrap_or(0);
        let t2 = s.TimelinePropertiesChanged(&TypedEventHandler::<Session, TimelinePropertiesChangedEventArgs>::new(move |_, _| { let _ = c.send(Msg::Changed); Ok(()) })).unwrap_or(0);
        self.hooked = Some(Hooked { s: s.clone(), tokens: [t0, t1, t2] });
    }

    fn unhook(&mut self) {
        if let Some(h) = self.hooked.take() {
            let _ = h.s.RemoveMediaPropertiesChanged(h.tokens[0]);
            let _ = h.s.RemovePlaybackInfoChanged(h.tokens[1]);
            let _ = h.s.RemoveTimelinePropertiesChanged(h.tokens[2]);
        }
    }

    fn refresh(&mut self) {
        let s = self.pick();
        let line = match &s {
            Some(s) => { self.hook(s); self.state(s).unwrap_or_else(|| NONE.to_string()) }
            None => { self.unhook(); NONE.to_string() }
        };
        if line != self.last {
            if !emit(&line) { std::process::exit(0); }
            self.last = line;
        }
    }

    /// `{"t":"state","app","aumid","title","artist","album","status":"playing|paused|stopped","pos","dur","ts","rate","art",
    /// "canPlay","canNext","canPrev","canSeek"}`: pos (s) is the position at ts (Unix ms); the reader extrapolates while playing.
    fn state(&mut self, s: &Session) -> Option<String> {
        let aumid = s.SourceAppUserModelId().map(|h| h.to_string()).unwrap_or_default();
        let props = s.TryGetMediaPropertiesAsync().ok()?.join().ok()?;
        let title = props.Title().map(|h| h.to_string()).unwrap_or_default();
        let artist = props.Artist().map(|h| h.to_string()).unwrap_or_default();
        let album = props.AlbumTitle().map(|h| h.to_string()).unwrap_or_default();
        if title.trim().is_empty() { return None; }

        let info = s.GetPlaybackInfo().ok();
        let status = match info.as_ref().and_then(|i| i.PlaybackStatus().ok()) {
            Some(Status::Playing) => "playing",
            Some(Status::Paused) | Some(Status::Changing) => "paused",
            _ => "stopped",
        };
        let rate = info.as_ref().and_then(|i| i.PlaybackRate().ok()).and_then(|r| r.Value().ok()).filter(|r| *r > 0.0).unwrap_or(1.0);
        let ctl = info.as_ref().and_then(|i| i.Controls().ok());
        let flag = |f: &dyn Fn(&windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackControls) -> windows::core::Result<bool>| {
            ctl.as_ref().and_then(|c| f(c).ok()).unwrap_or(false)
        };
        let can_play = flag(&|c| c.IsPlayPauseToggleEnabled()) || flag(&|c| c.IsPlayEnabled()) || flag(&|c| c.IsPauseEnabled());
        let (can_next, can_prev, can_seek) = (flag(&|c| c.IsNextEnabled()), flag(&|c| c.IsPreviousEnabled()), flag(&|c| c.IsPlaybackPositionEnabled()));

        let (mut pos, mut dur, mut ts) = (0.0, 0.0, now_ms());
        if let Ok(t) = s.GetTimelineProperties() {
            let start = t.StartTime().map(|x| x.Duration).unwrap_or(0);
            let end = t.EndTime().map(|x| x.Duration).unwrap_or(0);
            let p = t.Position().map(|x| x.Duration).unwrap_or(0);
            dur = (end - start).max(0) as f64 / 1e7;
            pos = ((p - start).max(0) as f64 / 1e7).min(if dur > 0.0 { dur } else { f64::MAX });
            let upd = t.LastUpdatedTime().map(|d| d.UniversalTime).unwrap_or(0);
            if upd > EPOCH_TICKS { ts = (upd - EPOCH_TICKS) / 10_000; }
        }

        let key = format!("{aumid}\u{1}{title}\u{1}{artist}\u{1}{album}");
        if key != self.art_key {
            self.art_key = key;
            self.art_path.clear();
            self.art_tries = 0;
        }
        // players often publish the cover a moment after the title: retry on the next few reads
        if self.art_path.is_empty() && self.art_tries < 5 {
            self.art_tries += 1;
            if let Some(p) = self.read_art(&props) { self.art_path = p; }
        }

        Some(format!(
            "{{\"t\":\"state\",\"app\":{},\"aumid\":{},\"title\":{},\"artist\":{},\"album\":{},\"status\":\"{status}\",\"pos\":{pos:.3},\"dur\":{dur:.3},\"ts\":{ts},\"rate\":{rate},\"art\":{},\"canPlay\":{can_play},\"canNext\":{can_next},\"canPrev\":{can_prev},\"canSeek\":{can_seek}}}",
            esc(&app_name(&aumid)), esc(&aumid), esc(&title), esc(&artist), esc(&album), esc(&self.art_path)
        ))
    }

    /// Thumbnail bytes -> `<art dir>/art-<hash>.<png|jpg|bmp>`; older covers are removed after a minute.
    fn read_art(&self, props: &Props) -> Option<String> {
        let st = props.Thumbnail().ok()?.OpenReadAsync().ok()?.join().ok()?;
        let size = st.Size().ok()?;
        if size == 0 || size > 8 << 20 { return None; }
        let buf = Buffer::Create(size as u32).ok()?;
        let got = st.ReadAsync(&buf, size as u32, InputStreamOptions::None).ok()?.join().ok()?;
        let mut v = vec![0u8; got.Length().ok()? as usize];
        DataReader::FromBuffer(&got).ok()?.ReadBytes(&mut v).ok()?;
        let ext = if v.starts_with(&[0x89, b'P', b'N', b'G']) { "png" } else if v.starts_with(&[0xFF, 0xD8]) { "jpg" } else if v.starts_with(b"BM") { "bmp" } else { return None };
        let name = format!("art-{:016x}.{ext}", fnv(&v));
        let path = self.art_dir.join(&name);
        if !path.exists() {
            let tmp = self.art_dir.join(format!("{name}.tmp"));
            std::fs::write(&tmp, &v).ok()?;
            std::fs::rename(&tmp, &path).ok()?;
        }
        if let Ok(rd) = std::fs::read_dir(&self.art_dir) {
            for e in rd.flatten() {
                let f = e.file_name().to_string_lossy().into_owned();
                let old = e.metadata().ok().and_then(|m| m.modified().ok()).and_then(|t| t.elapsed().ok()).is_some_and(|d| d.as_secs() > 60);
                if f.starts_with("art-") && f != name && old { let _ = std::fs::remove_file(e.path()); }
            }
        }
        Some(path.to_string_lossy().into_owned())
    }

    fn command(&mut self, c: &str) {
        let (verb, arg) = c.split_once(' ').unwrap_or((c, ""));
        if verb == "prefer" {
            self.prefer = if arg == "auto" { Vec::new() } else { arg.split(',').map(|t| t.trim().to_lowercase()).filter(|t| !t.is_empty()).collect() };
            self.refresh();
            return;
        }
        let Some(s) = self.pick() else { return };
        // fire and forget: the player answers through the change events
        let _ = match verb {
            "play" => s.TryPlayAsync().map(drop),
            "pause" => s.TryPauseAsync().map(drop),
            "toggle" => s.TryTogglePlayPauseAsync().map(drop),
            "next" => s.TrySkipNextAsync().map(drop),
            "prev" => s.TrySkipPreviousAsync().map(drop),
            "seek" => match arg.trim().parse::<f64>() {
                Ok(sec) if sec.is_finite() && sec >= 0.0 => s.TryChangePlaybackPositionAsync((sec * 1e7) as i64).map(drop),
                _ => Ok(()),
            },
            _ => Ok(()),
        };
    }
}

/// Blocks until process pid ends (true), or returns false at once when it cannot be watched.
fn parent_gone(pid: u32) -> bool {
    use windows::Win32::System::Threading::{OpenProcess, WaitForSingleObject, INFINITE, PROCESS_SYNCHRONIZE};
    unsafe {
        match OpenProcess(PROCESS_SYNCHRONIZE, false, pid) {
            Ok(h) => { WaitForSingleObject(h, INFINITE); true }
            Err(_) => false,
        }
    }
}

fn emit(line: &str) -> bool {
    let mut o = std::io::stdout().lock();
    writeln!(o, "{line}").and_then(|_| o.flush()).is_ok()
}

fn now_ms() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }

fn fnv(b: &[u8]) -> u64 { b.iter().fold(0xcbf29ce484222325u64, |h, x| (h ^ *x as u64).wrapping_mul(0x100000001b3)) }

fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

/// Display name of a source from its AppUserModelId ("Spotify.exe", "MSEdge", "...!Microsoft.ZuneMusic").
fn app_name(id: &str) -> String {
    let l = id.to_lowercase();
    const KNOWN: [(&str, &str); 19] = [
        ("spotify", "Spotify"), ("msedge", "Edge"), ("chrome", "Chrome"), ("firefox", "Firefox"), ("opera", "Opera"),
        ("brave", "Brave"), ("vivaldi", "Vivaldi"), ("zunemusic", "Media Player"), ("applemusic", "Apple Music"),
        ("itunes", "iTunes"), ("vlc", "VLC"), ("tidal", "TIDAL"), ("deezer", "Deezer"), ("amazonmusic", "Amazon Music"),
        ("foobar", "foobar2000"), ("musicbee", "MusicBee"), ("aimp", "AIMP"), ("youtube", "YouTube Music"), ("discord", "Discord"),
    ];
    if let Some((_, n)) = KNOWN.iter().find(|(k, _)| l.contains(k)) { return n.to_string(); }
    let base = id.rsplit('!').next().unwrap_or(id);
    let base = base.rsplit(['\\', '/']).next().unwrap_or(base);
    let base = base.strip_suffix(".exe").or_else(|| base.strip_suffix(".EXE")).unwrap_or(base);
    base.to_string()
}
