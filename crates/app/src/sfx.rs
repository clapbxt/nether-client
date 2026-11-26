//! Interface sounds: a small earcon set, synthesized (no sound files) and played on a background thread.
//! The same tones are made by the agent (`ov/Sfx.java` in the nether-agent repo) so the launcher and the in-game menus sound alike.
//! Research: vault `research/GUI sounds research.md`. Setting: `prefs.sounds` (0 off, 1 quiet, 2 normal).
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SizedSample};

/// What happened. Order = priority when two land together (a toggle's own click loses to the toggle).
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Sfx { Click = 0, Open = 1, On = 2, Off = 3, Error = 4, Launch = 5 }

impl Sfx {
    /// The Slint side's numbers (`Sfx.play(n)`).
    pub fn from_i32(n: i32) -> Option<Sfx> {
        Some(match n { 0 => Sfx::Click, 1 => Sfx::On, 2 => Sfx::Off, 3 => Sfx::Open, 4 => Sfx::Launch, 5 => Sfx::Error, _ => return None })
    }
}

/// One partial: starts at `at` s, glides f0 -> f1 Hz over `len` s, attack / decay time constants in s.
struct Tone { at: f32, f0: f32, f1: f32, len: f32, atk: f32, tau: f32, amp: f32, h2: f32, h3: f32, noise: f32 }

const fn t(at: f32, f0: f32, f1: f32, len: f32, atk: f32, tau: f32, amp: f32, h2: f32, h3: f32, noise: f32) -> Tone {
    Tone { at, f0, f1, len, atk, tau, amp, h2, h3, noise }
}

// a soft, short tick: most frequent, so the quietest and briefest
const CLICK: &[Tone] = &[t(0.0, 1650.0, 1500.0, 0.040, 0.0006, 0.009, 0.50, 0.10, 0.0, 0.22)];
const ON: &[Tone] = &[t(0.0, 1180.0, 1180.0, 0.050, 0.0008, 0.016, 0.40, 0.12, 0.0, 0.08), t(0.045, 1580.0, 1580.0, 0.070, 0.0008, 0.024, 0.40, 0.12, 0.0, 0.0)];
const OFF: &[Tone] = &[t(0.0, 1580.0, 1580.0, 0.050, 0.0008, 0.016, 0.36, 0.12, 0.0, 0.08), t(0.045, 1180.0, 1180.0, 0.070, 0.0008, 0.024, 0.36, 0.12, 0.0, 0.0)];
// a panel or dialog appears: a gentle upward glide
const OPEN: &[Tone] = &[t(0.0, 520.0, 860.0, 0.150, 0.012, 0.045, 0.30, 0.15, 0.0, 0.0)];
// the one "hero" sound: PLAY / LAUNCH, a soft fifth
const LAUNCH: &[Tone] = &[t(0.0, 880.0, 880.0, 0.420, 0.003, 0.130, 0.30, 0.18, 0.04, 0.0), t(0.065, 1318.5, 1318.5, 0.420, 0.003, 0.150, 0.28, 0.14, 0.03, 0.0)];
// something failed: low and falling, slightly hollow (odd harmonic) so it never sounds like success
const ERROR: &[Tone] = &[t(0.0, 440.0, 430.0, 0.110, 0.002, 0.050, 0.34, 0.0, 0.22, 0.0), t(0.090, 349.2, 340.0, 0.160, 0.002, 0.070, 0.34, 0.0, 0.22, 0.0)];

fn tones(k: Sfx) -> &'static [Tone] {
    match k { Sfx::Click => CLICK, Sfx::On => ON, Sfx::Off => OFF, Sfx::Open => OPEN, Sfx::Launch => LAUNCH, Sfx::Error => ERROR }
}

/// Mono samples at `rate`; `pitch` scales every frequency (tiny random spread so repeated clicks don't drill).
pub fn render(k: Sfx, rate: u32, pitch: f32) -> Vec<f32> {
    let rate_f = rate as f32;
    let ts = tones(k);
    let end = ts.iter().map(|t| t.at + t.len).fold(0.0f32, f32::max);
    let n = (end * rate_f) as usize + 1;
    let mut out = vec![0.0f32; n];
    let mut seed: u32 = 0x9E37_79B9;
    for t in ts {
        let start = (t.at * rate_f) as usize;
        let len = (t.len * rate_f) as usize;
        let mut phase = 0.0f32;
        for i in 0..len.min(n.saturating_sub(start)) {
            let s = i as f32 / rate_f;
            let f = (t.f0 + (t.f1 - t.f0) * (s / t.len)) * pitch;
            phase += std::f32::consts::TAU * f / rate_f;
            let env = (1.0 - (-s / t.atk).exp()) * (-s / t.tau).exp();
            // last 2 ms fade to zero: no click at the cut
            let tail = ((len - i) as f32 / (0.002 * rate_f)).min(1.0);
            let mut v = phase.sin() + t.h2 * (2.0 * phase).sin() + t.h3 * (3.0 * phase).sin();
            if t.noise > 0.0 && s < 0.004 {
                seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
                v += t.noise * ((seed as f32 / u32::MAX as f32) * 2.0 - 1.0) * (1.0 - s / 0.004);
            }
            out[start + i] += v * env * tail * t.amp;
        }
    }
    out
}

/// 0 off, 1 quiet, 2 normal (prefs.sounds).
static LEVEL: AtomicU32 = AtomicU32::new(1);

pub fn set_level(level: i32) { LEVEL.store(level.clamp(0, 2) as u32, Ordering::Relaxed); }

fn gain() -> f32 { match LEVEL.load(Ordering::Relaxed) { 0 => 0.0, 1 => 0.22, _ => 0.45 } }

static TX: OnceLock<Mutex<Sender<Sfx>>> = OnceLock::new();

/// Play `k` (never blocks; does nothing when sounds are off or there are no speakers).
pub fn play(k: Sfx) {
    if gain() == 0.0 { return; }
    let tx = TX.get_or_init(|| {
        let (tx, rx) = channel();
        let _ = std::thread::Builder::new().name("sfx".into()).spawn(move || run(rx));
        Mutex::new(tx)
    });
    let _ = tx.lock().unwrap_or_else(|e| e.into_inner()).send(k);
}

type Voices = Arc<Mutex<Vec<(Vec<f32>, usize)>>>;

/// The sound thread: opens the speakers on demand and closes them after a quiet minute (Windows counts an open
/// output as "audio playing", which would keep the PC from sleeping).
fn run(rx: Receiver<Sfx>) {
    let voices: Voices = Arc::new(Mutex::new(Vec::new()));
    let mut out: Option<(cpal::Stream, u32)> = None;
    let mut seed: u32 = 0x1234_5678;
    loop {
        let first = match rx.recv_timeout(Duration::from_secs(60)) {
            Ok(k) => k,
            Err(RecvTimeoutError::Timeout) => { out = None; continue; }
            Err(RecvTimeoutError::Disconnected) => return,
        };
        // a widget's click and its own sound arrive together: keep the most meaningful one
        let mut k = first;
        while let Ok(x) = rx.recv_timeout(Duration::from_millis(4)) { k = k.max(x); }
        if out.is_none() { out = open(&voices); }
        let Some((_, rate)) = &out else { continue };
        seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
        let pitch = if k == Sfx::Click { 0.97 + 0.06 * (seed as f32 / u32::MAX as f32) } else { 1.0 };
        let g = gain();
        let buf: Vec<f32> = render(k, *rate, pitch).into_iter().map(|v| v * g).collect();
        let mut v = voices.lock().unwrap_or_else(|e| e.into_inner());
        v.retain(|(b, p)| *p < b.len());
        if v.len() < 6 { v.push((buf, 0)); }
    }
}

fn open(voices: &Voices) -> Option<(cpal::Stream, u32)> {
    let dev = cpal::default_host().default_output_device()?;
    let cfg = dev.default_output_config().ok()?;
    let (rate, ch, fmt) = (cfg.sample_rate(), cfg.channels() as usize, cfg.sample_format());
    let sc: cpal::StreamConfig = cfg.into();
    let st = match fmt {
        cpal::SampleFormat::F32 => stream::<f32>(&dev, sc, ch, voices.clone()),
        cpal::SampleFormat::I16 => stream::<i16>(&dev, sc, ch, voices.clone()),
        cpal::SampleFormat::U16 => stream::<u16>(&dev, sc, ch, voices.clone()),
        cpal::SampleFormat::I32 => stream::<i32>(&dev, sc, ch, voices.clone()),
        _ => None,
    }?;
    st.play().ok()?;
    Some((st, rate))
}

fn stream<T: SizedSample + FromSample<f32> + Send + 'static>(dev: &cpal::Device, sc: cpal::StreamConfig, ch: usize, voices: Voices) -> Option<cpal::Stream> {
    let ch = ch.max(1);
    dev.build_output_stream::<T, _, _>(sc, move |data: &mut [T], _| {
        let Ok(mut v) = voices.try_lock() else { for x in data.iter_mut() { *x = T::from_sample(0.0); } return };
        for f in data.chunks_mut(ch) {
            let mut s = 0.0f32;
            for (b, p) in v.iter_mut() { if let Some(x) = b.get(*p) { s += *x; *p += 1; } }
            let t = T::from_sample(s.clamp(-1.0, 1.0));
            for x in f { *x = t; }
        }
    }, |_| {}, None).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sounds_are_short_quiet_and_end_silent() {
        for k in [Sfx::Click, Sfx::On, Sfx::Off, Sfx::Open, Sfx::Launch, Sfx::Error] {
            let b = render(k, 48_000, 1.0);
            let peak = b.iter().fold(0.0f32, |m, x| m.max(x.abs()));
            assert!(peak > 0.05 && peak <= 1.0, "{k:?} peak {peak}");
            assert!(b.len() <= 48_000 / 2, "{k:?} longer than half a second");
            assert!(b.last().unwrap().abs() < 0.01, "{k:?} ends with a click");
        }
        // the click is the shortest of all
        assert!(render(Sfx::Click, 48_000, 1.0).len() < render(Sfx::On, 48_000, 1.0).len());
    }
}
