//! Party voice: microphone -> Opus (pure Rust `opus1`, 48 kHz mono, 20 ms, 24 kb/s with in-band FEC) -> the party
//! link's datagrams; datagrams -> a small jitter buffer per speaker -> mixed -> speakers. CPAL owns the devices.
//! - The microphone is never opened while the mic is OFF (the default): capture starts only after the player picks
//!   push-to-talk or open mic, and stops when they switch it off.
//! - Nothing is written anywhere: frames live in memory for a few tens of ms.
//! - Device callbacks only copy samples into bounded queues; resampling, coding and mixing run on our own threads.
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::num::NonZeroU32;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SizedSample};
use opus1::{Application, Bitrate, Channels, InbandFec, OpusDecoder, OpusEncoder, SampleRate};

const RATE: usize = 48_000;
pub(crate) const FRAME: usize = 960; // 20 ms
/// keep at most this much captured / ready-to-play audio (bounded latency)
const MAX_QUEUE: usize = RATE / 2;
/// start a speaker once this many frames wait (60 ms), and never let more pile up
const START_FRAMES: usize = 3;
const MAX_FRAMES: usize = 8;

/// What the player chose (saved by app/party.rs).
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct Cfg {
    /// mic::OFF / PTT / OPEN (`nether_core::party::mic`)
    pub mode: u8,
    pub deaf: bool,
    /// device ids ("" = system default)
    pub input: String,
    pub output: String,
    /// open-mic gate, 0..1 (higher = needs a louder voice)
    pub gate: f32,
    /// mic gain and output volume, 0..2
    pub gain: f32,
    pub volume: f32,
}
impl Default for Cfg {
    fn default() -> Self { Cfg { mode: 0, deaf: false, input: String::new(), output: String::new(), gate: 0.35, gain: 1.0, volume: 1.0 } }
}

struct Speaker { dec: OpusDecoder, buf: BTreeMap<u16, Vec<u8>>, next: u16, playing: bool, last: Instant }

#[derive(Default)]
struct Shared {
    cfg: Cfg,
    ptt: bool,
    /// microphone samples at the device rate (mono)
    cap: VecDeque<f32>,
    cap_rate: usize,
    /// mixed output at the device rate (mono)
    out: VecDeque<f32>,
    out_rate: usize,
    /// last input level 0..1 and whether we are sending
    level: f32,
    sending: bool,
    /// sid -> speaker; per-sid volume and local mutes
    speakers: HashMap<u16, SpeakerSlot>,
    vol: HashMap<u16, f32>,
    muted: HashSet<u16>,
    /// mic test: hear yourself
    test: bool,
    err_in: String,
    err_out: String,
    dirty: bool,
    /// in a party with others (devices stay closed otherwise, unless testing the mic)
    active: bool,
    stats: Stats,
}

/// What happened to incoming voice since the start (for tests and a connection-quality hint).
#[derive(Clone, Copy, Debug, Default)]
pub struct Stats {
    /// frames received, played from a packet, rebuilt from the next packet's FEC, concealed (PLC)
    pub recv: u64, pub played: u64, pub fec: u64, pub plc: u64,
    /// arrived after their slot was played; dropped to keep latency bounded; times a speaker ran dry mid-stream
    pub late: u64, pub skipped: u64, pub dry: u64,
}

struct SpeakerSlot(Speaker);

/// The voice engine. Cheap to clone; threads stop with [`Voice::stop`].
#[derive(Clone)]
pub struct Voice { s: Arc<Mutex<Shared>>, run: Arc<AtomicBool>, broken: Arc<AtomicBool> }

fn lock(m: &Mutex<Shared>) -> std::sync::MutexGuard<'_, Shared> { m.lock().unwrap_or_else(|e| e.into_inner()) }

/// Input and output devices: (id, name).
pub fn devices() -> (Vec<(String, String)>, Vec<(String, String)>) {
    let host = cpal::default_host();
    let name = |d: &cpal::Device| d.description().map(|x| x.name().to_string()).unwrap_or_else(|_| "Audio device".into());
    let list = |it: Option<Vec<cpal::Device>>| it.unwrap_or_default().iter().filter_map(|d| d.id().ok().map(|id| (id.to_string(), name(d)))).collect::<Vec<_>>();
    (list(host.input_devices().ok().map(|i| i.collect())), list(host.output_devices().ok().map(|i| i.collect())))
}

fn pick(host: &cpal::Host, id: &str, input: bool) -> Option<cpal::Device> {
    let chosen = (!id.is_empty()).then(|| id.parse::<cpal::DeviceId>().ok()).flatten().and_then(|i| host.device_by_id(&i));
    chosen.or_else(|| if input { host.default_input_device() } else { host.default_output_device() })
}

/// Linear resampler (voice only needs this much).
struct Resample { step: f64, pos: f64, prev: f32 }
impl Resample {
    fn new(from: usize, to: usize) -> Self { Resample { step: from as f64 / to as f64, pos: 0.0, prev: 0.0 } }
    fn run(&mut self, input: &[f32], out: &mut Vec<f32>) {
        if (self.step - 1.0).abs() < 1e-9 { out.extend_from_slice(input); return; }
        // pos is the read position relative to input[0]; -1 = the previous block's last sample
        while self.pos < input.len() as f64 - 1.0 + 1e-9 {
            let i = self.pos.floor();
            let f = (self.pos - i) as f32;
            let a = if i < 0.0 { self.prev } else { input[i as usize] };
            let b = input[(i + 1.0).max(0.0) as usize];
            out.push(a + (b - a) * f);
            self.pos += self.step;
        }
        self.pos -= input.len() as f64;
        if let Some(l) = input.last() { self.prev = *l; }
    }
}

impl Voice {
    pub fn start(cfg: Cfg) -> Voice {
        let v = Voice { s: Arc::new(Mutex::new(Shared { cfg, dirty: true, ..Default::default() })), run: Arc::new(AtomicBool::new(true)), broken: Arc::new(AtomicBool::new(false)) };
        // tests drive `mix` themselves and never open this PC's sound devices
        if cfg!(test) { return v; }
        let v2 = v.clone();
        std::thread::Builder::new().name("nether-voice-dev".into()).spawn(move || v2.devices_loop()).ok();
        v
    }
    #[cfg_attr(not(test), allow(dead_code))] // for a connection-quality hint later; read by the network tests
    pub fn stats(&self) -> Stats { lock(&self.s).stats }

    pub fn cfg(&self) -> Cfg { lock(&self.s).cfg.clone() }
    pub fn set(&self, f: impl FnOnce(&mut Cfg)) {
        let mut s = lock(&self.s);
        let old = s.cfg.clone();
        f(&mut s.cfg);
        s.cfg.gate = s.cfg.gate.clamp(0.0, 1.0);
        s.cfg.gain = s.cfg.gain.clamp(0.0, 2.0);
        s.cfg.volume = s.cfg.volume.clamp(0.0, 2.0);
        s.cfg.mode = s.cfg.mode.min(2);
        if old.input != s.cfg.input || old.output != s.cfg.output || (old.mode == 0) != (s.cfg.mode == 0) { s.dirty = true; }
        if s.cfg.mode == 0 { s.ptt = false; s.test = false; }
    }
    pub fn ptt(&self, down: bool) { lock(&self.s).ptt = down; }
    /// Open the devices only while there is someone to talk to (or a mic test).
    pub fn set_active(&self, on: bool) { let mut s = lock(&self.s); if s.active != on { s.active = on; s.dirty = true; } }
    pub fn test(&self, on: bool) { let mut s = lock(&self.s); let t = on && s.cfg.mode != 0; if s.test != t { s.test = t; s.dirty = true; } }
    pub fn testing(&self) -> bool { lock(&self.s).test }
    pub fn member_volume(&self, sid: u16, v: f32) { lock(&self.s).vol.insert(sid, v.clamp(0.0, 2.0)); }
    pub fn member_mute(&self, sid: u16, on: bool) { let mut s = lock(&self.s); if on { s.muted.insert(sid); } else { s.muted.remove(&sid); } }
    /// (level 0..1, sending, input error, output error)
    pub fn meter(&self) -> (f32, bool, String, String) { let s = lock(&self.s); (s.level, s.sending, s.err_in.clone(), s.err_out.clone()) }
    /// sids heard in the last 300 ms
    pub fn talking(&self) -> Vec<u16> {
        let s = lock(&self.s);
        s.speakers.iter().filter(|(sid, sp)| sp.0.last.elapsed() < Duration::from_millis(300) && !s.muted.contains(sid)).map(|(k, _)| *k).collect()
    }
    /// Forget every speaker (left the party).
    pub fn reset(&self) { let mut s = lock(&self.s); s.speakers.clear(); s.out.clear(); }

    /// A voice frame from the party (sid of the speaker).
    pub fn push(&self, sid: u16, seq: u16, opus: &[u8]) {
        let mut s = lock(&self.s);
        if s.cfg.deaf || s.muted.contains(&sid) || opus.is_empty() { return; }
        if !s.speakers.contains_key(&sid) {
            let Ok(dec) = OpusDecoder::new(SampleRate::Hz48000, Channels::Mono) else { return };
            s.speakers.insert(sid, SpeakerSlot(Speaker { dec, buf: BTreeMap::new(), next: seq, playing: false, last: Instant::now() }));
        }
        s.stats.recv += 1;
        let Shared { speakers, stats, .. } = &mut *s;
        let Some(slot) = speakers.get_mut(&sid) else { return };
        let sp = &mut slot.0;
        // a long pause: start over at this frame
        if sp.last.elapsed() > Duration::from_millis(600) { sp.buf.clear(); sp.next = seq; sp.playing = false; }
        sp.last = Instant::now();
        let behind = seq.wrapping_sub(sp.next) > 0x8000;
        if behind && sp.playing { stats.late += 1; return; } // too late
        if behind { sp.next = seq; }
        sp.buf.insert(seq, opus.to_vec());
        while sp.buf.len() > MAX_FRAMES { // latency cap: skip ahead
            if let Some((&k, _)) = sp.buf.iter().next() { sp.buf.remove(&k); sp.next = k.wrapping_add(1); stats.skipped += 1; }
        }
    }

    // ---------------------------------------------------------------- threads

    /// Owns the CPAL streams: (re)opens them when the choice changes or a device fails; runs the coder and the mixer.
    fn devices_loop(&self) {
        let host = cpal::default_host();
        let mut input: Option<cpal::Stream> = None;
        let mut output: Option<cpal::Stream> = None;
        let mut retry = Instant::now();
        let enc = OpusEncoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip).ok();
        let mut enc = enc.map(|mut e| {
            e.set_bitrate(Bitrate::BitsPerSecond(NonZeroU32::new(24_000).unwrap()));
            e.set_inband_fec(InbandFec::On);
            let _ = e.set_packet_loss_perc(10);
            e
        });
        let mut up = Resample::new(RATE, RATE);
        let mut down = Resample::new(RATE, RATE);
        let mut pending48: Vec<f32> = Vec::with_capacity(FRAME * 4);
        let mut hang = 0u32; // open-mic hangover (frames)
        let mut seq: u16 = 0;
        let mut was_sending = false;
        while self.run.load(Ordering::SeqCst) {
            // (re)open devices
            let (dirty, cfg, live) = { let mut s = lock(&self.s); let d = s.dirty; s.dirty = false; (d, s.cfg.clone(), s.active || s.test) };
            if !live {
                if input.is_some() || output.is_some() { drop(input.take()); drop(output.take()); }
                { let mut s = lock(&self.s); s.level = 0.0; s.sending = false; s.cap.clear(); s.out.clear(); s.err_in.clear(); s.err_out.clear(); s.dirty = true; }
                std::thread::sleep(Duration::from_millis(150));
                continue;
            }
            let broken = self.broken.swap(false, Ordering::SeqCst);
            if dirty || (broken && retry.elapsed() > Duration::from_secs(2)) || (output.is_none() && retry.elapsed() > Duration::from_secs(5)) {
                retry = Instant::now();
                drop(input.take());
                drop(output.take());
                let (o, orate, oerr) = self.open_output(&host, &cfg.output);
                output = o;
                if orate > 0 { down = Resample::new(RATE, orate); }
                let (i, irate, ierr) = if cfg.mode != 0 { self.open_input(&host, &cfg.input) } else { (None, 0, String::new()) };
                input = i;
                if irate > 0 { up = Resample::new(irate, RATE); }
                let mut s = lock(&self.s);
                s.err_out = oerr;
                s.err_in = ierr;
                if orate > 0 { s.out_rate = orate; }
                if irate > 0 { s.cap_rate = irate; }
                s.cap.clear();
                pending48.clear();
            }
            // capture -> frames
            let raw: Vec<f32> = { let mut s = lock(&self.s); s.cap.drain(..).collect() };
            if !raw.is_empty() { up.run(&raw, &mut pending48); }
            while pending48.len() >= FRAME {
                let mut frame: Vec<f32> = pending48.drain(..FRAME).collect();
                let (cfg, ptt, test) = { let s = lock(&self.s); (s.cfg.clone(), s.ptt, s.test) };
                for x in &mut frame { *x = (*x * cfg.gain).clamp(-1.0, 1.0); }
                let rms = (frame.iter().map(|x| x * x).sum::<f32>() / FRAME as f32).sqrt();
                // perceptual level 0..1 (about -60..0 dBFS)
                let level = ((20.0 * rms.max(1e-6).log10() + 60.0) / 60.0).clamp(0.0, 1.0);
                let open = match cfg.mode {
                    1 => ptt,
                    2 => { if level > 0.25 + cfg.gate * 0.6 { hang = 15; } else { hang = hang.saturating_sub(1); } hang > 0 }
                    _ => false,
                };
                {
                    let mut s = lock(&self.s);
                    s.level = s.level * 0.6 + level * 0.4;
                    s.sending = open;
                    if test { // hear yourself (mic check)
                        let mut o = Vec::with_capacity(FRAME);
                        down.run(&frame, &mut o);
                        let room = MAX_QUEUE.saturating_sub(s.out.len());
                        s.out.extend(o.into_iter().take(room));
                    }
                }
                if open || was_sending { // one more frame after release ends the stream cleanly
                    if let Some(e) = enc.as_mut() {
                        let mut pkt = [0u8; 400];
                        if let Ok(n) = e.encode(&frame, FRAME, &mut pkt) { if n > 0 { SINK.with_frame(seq, &pkt[..n]); seq = seq.wrapping_add(1); } }
                    }
                }
                was_sending = open;
            }
            if input.is_none() { let mut s = lock(&self.s); s.level *= 0.8; s.sending = false; }
            // playout: keep about 60 ms queued at the device
            loop {
                let (need, orate) = { let s = lock(&self.s); (s.out.len() < s.out_rate.max(1) * 60 / 1000, s.out_rate) };
                if !need || orate == 0 || output.is_none() { break; }
                let mix = self.mix();
                let mut o = Vec::with_capacity(FRAME);
                down.run(&mix, &mut o);
                let mut s = lock(&self.s);
                let room = MAX_QUEUE.saturating_sub(s.out.len());
                s.out.extend(o.into_iter().take(room));
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        drop(input);
        drop(output);
    }

    /// 20 ms of every speaker mixed (silence when nobody talks).
    pub(crate) fn mix(&self) -> Vec<f32> {
        let mut out = vec![0f32; FRAME];
        let mut s = lock(&self.s);
        let master = if s.cfg.deaf { 0.0 } else { s.cfg.volume };
        let vols: HashMap<u16, f32> = s.vol.clone();
        let mut pcm = vec![0f32; FRAME];
        s.speakers.retain(|_, sp| sp.0.last.elapsed() < Duration::from_secs(3));
        let Shared { speakers, stats, .. } = &mut *s;
        for (sid, slot) in speakers.iter_mut() {
            let sp = &mut slot.0;
            if !sp.playing { if sp.buf.len() >= START_FRAMES { sp.playing = true; } else { continue; } }
            let got = if let Some(pkt) = sp.buf.remove(&sp.next) {
                stats.played += 1;
                sp.dec.decode(&pkt, FRAME, &mut pcm).ok()
            } else if sp.buf.is_empty() {
                // ran dry: wait for a new burst (counted when it was mid-speech, not at the end of a sentence)
                if sp.last.elapsed() < Duration::from_millis(100) { stats.dry += 1; }
                sp.playing = false;
                None
            } else if let Some(nxt) = sp.buf.get(&sp.next.wrapping_add(1)).cloned() {
                stats.fec += 1;
                sp.dec.decode_fec(&nxt, FRAME, &mut pcm).ok() // lost one: rebuild it from the next packet's FEC
            } else {
                stats.plc += 1;
                sp.dec.decode(&[], FRAME, &mut pcm).ok() // conceal
            };
            if sp.playing || got.is_some() { sp.next = sp.next.wrapping_add(1); }
            if got.is_some() {
                let g = master * vols.get(sid).copied().unwrap_or(1.0);
                for (o, x) in out.iter_mut().zip(&pcm) { *o += x * g; }
            }
        }
        for o in &mut out { *o = o.clamp(-1.0, 1.0); }
        out
    }

    fn open_input(&self, host: &cpal::Host, id: &str) -> (Option<cpal::Stream>, usize, String) {
        let Some(dev) = pick(host, id, true) else { return (None, 0, "No microphone found".into()) };
        let Ok(cfg) = dev.default_input_config() else { return (None, 0, "The microphone isn't available".into()) };
        let (rate, ch) = (cfg.sample_rate() as usize, cfg.channels() as usize);
        let fmt = cfg.sample_format();
        let sc: cpal::StreamConfig = cfg.into();
        let r = match fmt {
            cpal::SampleFormat::F32 => self.input_stream::<f32>(&dev, sc, ch),
            cpal::SampleFormat::I16 => self.input_stream::<i16>(&dev, sc, ch),
            cpal::SampleFormat::U16 => self.input_stream::<u16>(&dev, sc, ch),
            cpal::SampleFormat::I32 => self.input_stream::<i32>(&dev, sc, ch),
            _ => Err("unsupported microphone format".to_string()),
        };
        match r {
            Ok(st) => if st.play().is_ok() { (Some(st), rate, String::new()) } else { (None, 0, "Couldn't start the microphone".into()) },
            Err(e) => (None, 0, if e.to_lowercase().contains("denied") { "Windows blocked the microphone (Privacy > Microphone)".into() } else { "Couldn't open the microphone".into() }),
        }
    }

    fn input_stream<T: SizedSample + Send + 'static>(&self, dev: &cpal::Device, sc: cpal::StreamConfig, ch: usize) -> Result<cpal::Stream, String> where f32: FromSample<T> {
        let s = self.s.clone();
        let broken = self.broken.clone();
        let ch = ch.max(1);
        dev.build_input_stream::<T, _, _>(sc, move |data: &[T], _| {
            // the audio thread: copy, never wait
            let Ok(mut g) = s.try_lock() else { return };
            for f in data.chunks(ch) {
                let v = f.iter().map(|x| x.to_sample::<f32>()).sum::<f32>() / ch as f32;
                if g.cap.len() < MAX_QUEUE { g.cap.push_back(v); }
            }
        }, move |_| broken.store(true, Ordering::SeqCst), None).map_err(|e| e.to_string())
    }

    fn open_output(&self, host: &cpal::Host, id: &str) -> (Option<cpal::Stream>, usize, String) {
        let Some(dev) = pick(host, id, false) else { return (None, 0, "No speakers or headphones found".into()) };
        let Ok(cfg) = dev.default_output_config() else { return (None, 0, "The speakers aren't available".into()) };
        let (rate, ch) = (cfg.sample_rate() as usize, cfg.channels() as usize);
        let fmt = cfg.sample_format();
        let sc: cpal::StreamConfig = cfg.into();
        let r = match fmt {
            cpal::SampleFormat::F32 => self.output_stream::<f32>(&dev, sc, ch),
            cpal::SampleFormat::I16 => self.output_stream::<i16>(&dev, sc, ch),
            cpal::SampleFormat::U16 => self.output_stream::<u16>(&dev, sc, ch),
            cpal::SampleFormat::I32 => self.output_stream::<i32>(&dev, sc, ch),
            _ => Err("unsupported output format".to_string()),
        };
        match r {
            Ok(st) => if st.play().is_ok() { (Some(st), rate, String::new()) } else { (None, 0, "Couldn't start the speakers".into()) },
            Err(_) => (None, 0, "Couldn't open the speakers".into()),
        }
    }

    fn output_stream<T: SizedSample + FromSample<f32> + Send + 'static>(&self, dev: &cpal::Device, sc: cpal::StreamConfig, ch: usize) -> Result<cpal::Stream, String> {
        let s = self.s.clone();
        let broken = self.broken.clone();
        let ch = ch.max(1);
        dev.build_output_stream::<T, _, _>(sc, move |data: &mut [T], _| {
            let Ok(mut g) = s.try_lock() else { return }; // silence this block rather than wait
            for f in data.chunks_mut(ch) {
                let v = g.out.pop_front().unwrap_or(0.0);
                let t = T::from_sample(v);
                for x in f { *x = t; }
            }
        }, move |_| broken.store(true, Ordering::SeqCst), None).map_err(|e| e.to_string())
    }
}

/// Where encoded frames go (set by app/party.rs: the party link).
pub struct Sink { f: Mutex<Option<Box<dyn Fn(u16, &[u8]) + Send>>> }
impl Sink {
    pub fn set(&self, f: Option<Box<dyn Fn(u16, &[u8]) + Send>>) { *self.f.lock().unwrap_or_else(|e| e.into_inner()) = f; }
    fn with_frame(&self, seq: u16, pkt: &[u8]) { if let Some(f) = self.f.lock().unwrap_or_else(|e| e.into_inner()).as_ref() { f(seq, pkt); } }
}
pub static SINK: Sink = Sink { f: Mutex::new(None) };

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resampler_keeps_duration() {
        let mut r = Resample::new(44_100, 48_000);
        let mut out = vec![];
        for _ in 0..100 { r.run(&[0.5f32; 441], &mut out); }
        assert!((out.len() as i64 - 48_000).abs() < 4, "{}", out.len());
        assert!(out.iter().all(|x| (*x - 0.5).abs() < 1e-4 || out.len() < 10));
    }

    #[test]
    fn opus_round_trip_and_jitter() {
        let mut e = OpusEncoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip).unwrap();
        e.set_bitrate(Bitrate::BitsPerSecond(NonZeroU32::new(24_000).unwrap()));
        let v = Voice { s: Arc::new(Mutex::new(Shared { cfg: Cfg { volume: 1.0, ..Default::default() }, ..Default::default() })), run: Arc::new(AtomicBool::new(false)), broken: Arc::new(AtomicBool::new(false)) };
        let tone: Vec<f32> = (0..FRAME).map(|i| (i as f32 * 0.05).sin() * 0.3).collect();
        // frames arrive out of order; one is lost
        let mut pkts = vec![];
        for _ in 0..6 { let mut b = [0u8; 400]; let n = e.encode(&tone, FRAME, &mut b).unwrap(); pkts.push(b[..n].to_vec()); }
        for &i in &[0usize, 2, 1, 4, 5] { v.push(7, i as u16, &pkts[i]); }
        let mut energy = 0.0;
        for _ in 0..6 { energy += v.mix().iter().map(|x| x * x).sum::<f32>(); }
        assert!(energy > 1.0, "no audio came out: {energy}");
        assert_eq!(v.talking(), vec![7]);
        v.member_mute(7, true);
        assert!(v.talking().is_empty());
    }
}
