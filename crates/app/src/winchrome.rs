//! Native window chrome. Main window: no title bar and no border
//! (the app draws its own top bar + window buttons) while keeping native resize edges, snap and shadow.
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
use windows_sys::Win32::Graphics::Gdi::ScreenToClient;
use windows_sys::Win32::Graphics::Dwm::DwmSetWindowAttribute;
use windows_sys::Win32::UI::HiDpi::GetDpiForWindow;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::ReleaseCapture;
use windows_sys::Win32::UI::Shell::{DefSubclassProc, DragAcceptFiles, DragFinish, DragQueryFileW, SetWindowSubclass, HDROP};
use windows_sys::Win32::UI::WindowsAndMessaging::*;

/// What the window procedure reports back (run on the UI thread through the event loop).
pub struct Handlers {
    /// The window was maximized (true) or restored (false) by any means (button, snap).
    pub maxed: fn(bool),
    /// Files dropped onto the window.
    pub dropped: fn(Vec<std::path::PathBuf>),
}
static DRAGGING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static HANDLERS: std::sync::OnceLock<Handlers> = std::sync::OnceLock::new();

pub fn set_handlers(h: Handlers) { let _ = HANDLERS.set(h); }

unsafe fn dropped_files(drop: HDROP) -> Vec<std::path::PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    let n = DragQueryFileW(drop, u32::MAX, std::ptr::null_mut(), 0);
    let mut out = vec![];
    for i in 0..n {
        let len = DragQueryFileW(drop, i, std::ptr::null_mut(), 0) as usize;
        let mut buf = vec![0u16; len + 1];
        DragQueryFileW(drop, i, buf.as_mut_ptr(), buf.len() as u32);
        out.push(std::path::PathBuf::from(std::ffi::OsString::from_wide(&buf[..len])));
    }
    DragFinish(drop);
    out
}

fn hwnd_of(ui: &slint::Window) -> Option<HWND> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    match ui.window_handle().window_handle().ok()?.as_raw() {
        RawWindowHandle::Win32(w) => Some(w.hwnd.get() as HWND),
        _ => None,
    }
}

unsafe extern "system" fn chrome_proc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM, _id: usize, _data: usize) -> LRESULT {
    match msg {
        // client area = whole window (no caption, no frame); maximized windows are inset by the frame they overhang
        WM_NCCALCSIZE if wp != 0 => {
            if IsZoomed(hwnd) != 0 {
                let p = &mut *(lp as *mut NCCALCSIZE_PARAMS);
                let pad = GetSystemMetrics(SM_CXPADDEDBORDER);
                let (fx, fy) = (GetSystemMetrics(SM_CXSIZEFRAME) + pad, GetSystemMetrics(SM_CYSIZEFRAME) + pad);
                let r = &mut p.rgrc[0];
                r.left += fx; r.right -= fx; r.top += fy; r.bottom -= fy;
            }
            0
        }
        // resize edges inside the client area
        WM_NCHITTEST => {
            if IsZoomed(hwnd) == 0 {
                let (x, y) = ((lp & 0xffff) as i16 as i32, ((lp >> 16) & 0xffff) as i16 as i32);
                let mut r: RECT = std::mem::zeroed();
                GetWindowRect(hwnd, &mut r);
                let b = (6 * GetDpiForWindow(hwnd).max(96) / 96) as i32;
                let (l, rt, t, bt) = (x < r.left + b, x >= r.right - b, y < r.top + b, y >= r.bottom - b);
                let hit = match (l, rt, t, bt) {
                    (true, _, true, _) => HTTOPLEFT, (_, true, true, _) => HTTOPRIGHT,
                    (true, _, _, true) => HTBOTTOMLEFT, (_, true, _, true) => HTBOTTOMRIGHT,
                    (true, ..) => HTLEFT, (_, true, ..) => HTRIGHT, (_, _, true, _) => HTTOP, (.., true) => HTBOTTOM,
                    _ => HTCLIENT,
                };
                return hit as LRESULT;
            }
            HTCLIENT as LRESULT
        }
        // no double-click-to-maximize on the top bar (the max button and snap still work)
        WM_NCLBUTTONDBLCLK => 0,
        WM_SIZE => {
            if let Some(h) = HANDLERS.get() {
                let (f, m) = (h.maxed, wp as u32 == SIZE_MAXIMIZED);
                if wp as u32 != SIZE_MINIMIZED { let _ = slint::invoke_from_event_loop(move || f(m)); }
            }
            DefSubclassProc(hwnd, msg, wp, lp)
        }
        // The native move/size loop swallows the mouse-up that ends a top-bar drag, so Slint keeps the drag area as its
        // pressed pointer grabber and the buttons stay dead until some later click clears it. Replay the release
        // (through winit, so its capture bookkeeping stays right) and refresh the pointer position at the real cursor.
        WM_EXITSIZEMOVE => {
            let mut pt = POINT { x: 0, y: 0 };
            GetCursorPos(&mut pt);
            ScreenToClient(hwnd, &mut pt);
            let lp = ((pt.y as isize & 0xffff) << 16) | (pt.x as isize & 0xffff);
            if DRAGGING.swap(false, std::sync::atomic::Ordering::Relaxed) { PostMessageW(hwnd, WM_LBUTTONUP, 0, lp); }
            PostMessageW(hwnd, WM_MOUSEMOVE, 0, lp);
            DefSubclassProc(hwnd, msg, wp, lp as LPARAM)
        }
        WM_DROPFILES => {
            let files = dropped_files(wp as HDROP);
            if let Some(h) = HANDLERS.get() { let f = h.dropped; let _ = slint::invoke_from_event_loop(move || f(files)); }
            0
        }
        _ => DefSubclassProc(hwnd, msg, wp, lp),
    }
}

/// Main window: drop the title bar and the Windows 11 border. Call after `show()`.
pub fn borderless(ui: &slint::Window) -> bool {
    let Some(h) = hwnd_of(ui) else { return false };
    let none: u32 = 0xFFFF_FFFE; // DWMWA_COLOR_NONE
    let dark = 1i32;
    unsafe {
        DwmSetWindowAttribute(h as _, 20, &dark as *const _ as _, 4);
        DwmSetWindowAttribute(h as _, 34, &none as *const _ as _, 4); // DWMWA_BORDER_COLOR
        SetWindowSubclass(h, Some(chrome_proc), 1, 0);
        // files dropped from Explorer arrive as WM_DROPFILES (the OLE drop target winit registers is never used by Slint)
        windows_sys::Win32::System::Ole::RevokeDragDrop(h);
        DragAcceptFiles(h, 1);
        SetWindowPos(h, std::ptr::null_mut(), 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
        // Dropping the frame makes the client area as big as the window, but Windows sends no WM_SIZE for a frame-only change:
        // winit/Slint would keep the old, smaller size and draw/hit-test a stretched surface (clicks land beside the buttons
        // until the window is resized or maximised). Tell them the real client size.
        let mut r: RECT = std::mem::zeroed();
        GetClientRect(h, &mut r);
        let kind = if IsZoomed(h) != 0 { SIZE_MAXIMIZED } else { SIZE_RESTORED };
        SendMessageW(h, WM_SIZE, kind as usize, (((r.bottom - r.top) as isize) << 16) | ((r.right - r.left) as isize & 0xffff));
    }
    true
}

/// Start a native window move from the app's top bar (snap layouts and Aero snap included).
pub fn drag(ui: &slint::Window) {
    let Some(h) = hwnd_of(ui) else { return };
    unsafe {
        ReleaseCapture();
        DRAGGING.store(true, std::sync::atomic::Ordering::Relaxed);
        PostMessageW(h, WM_NCLBUTTONDOWN, HTCAPTION as usize, 0);
    }
}

pub fn toggle_max(ui: &slint::Window) {
    let Some(h) = hwnd_of(ui) else { return };
    unsafe { ShowWindow(h, if IsZoomed(h) != 0 { SW_RESTORE } else { SW_MAXIMIZE }); }
}

pub fn minimize(ui: &slint::Window) {
    if let Some(h) = hwnd_of(ui) { unsafe { ShowWindow(h, SW_MINIMIZE); } }
}

/// Bring the window to the front (after the tray, a second launch, an invite link).
pub fn to_front(ui: &slint::Window) {
    if let Some(h) = hwnd_of(ui) {
        unsafe {
            if IsIconic(h) != 0 { ShowWindow(h, SW_RESTORE); }
            SetForegroundWindow(h);
        }
    }
}

pub fn is_max(ui: &slint::Window) -> bool {
    hwnd_of(ui).is_some_and(|h| unsafe { IsZoomed(h) != 0 })
}
