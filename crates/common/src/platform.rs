//! Everything that differs by operating system, in one place. Nether ships on Windows only today, but macOS and Linux
//! are planned: code elsewhere calls these functions instead of writing `#[cfg(windows)]` blocks of its own, so a port
//! means filling in the other branches here rather than hunting through the codebase.
//!
//! Each function documents what it does on Windows and what it does elsewhere (usually nothing, which is safe).
use std::path::Path;
use std::process::{Child, Command};

/// Separator between entries of a Java class path (`-cp`).
pub const CLASSPATH_SEP: &str = if cfg!(windows) { ";" } else { ":" };

/// Suffix of native executables (`.exe` on Windows).
pub const EXE_SUFFIX: &str = if cfg!(windows) { ".exe" } else { "" };

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
#[cfg(windows)]
const ABOVE_NORMAL_PRIORITY_CLASS: u32 = 0x0000_8000;

/// A helper process (installer steps, Horizon tools) that must not flash a console window.
/// Windows: CREATE_NO_WINDOW. Elsewhere: nothing to do.
pub fn no_window(cmd: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(not(windows))]
    let _ = cmd;
}

/// The game's own process: no console window and, when `boost`, above-normal priority so background programs don't
/// take its CPU time. Windows: creation flags. Elsewhere: nothing yet.
pub fn game_process(cmd: &mut Command, boost: bool) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(if boost { CREATE_NO_WINDOW | ABOVE_NORMAL_PRIORITY_CLASS } else { CREATE_NO_WINDOW });
    }
    #[cfg(not(windows))]
    let _ = (cmd, boost);
}

/// Windows "Graphics performance preference" = High performance for this Java, unless the user already chose one for
/// it (Settings > System > Display > Graphics). On a laptop with integrated + dedicated graphics the game otherwise
/// often lands on the integrated GPU. Per user and per program, the same value Windows' own settings page writes.
/// Elsewhere: nothing.
pub fn prefer_fast_gpu(exe: &Path) {
    #[cfg(windows)]
    {
        use windows_sys::Win32::System::Registry::{RegCloseKey, RegCreateKeyExW, RegQueryValueExW, RegSetValueExW, HKEY, HKEY_CURRENT_USER, KEY_READ, KEY_WRITE, REG_SZ};
        fn w(s: &str) -> Vec<u16> { s.encode_utf16().chain(Some(0)).collect() }
        let name = w(&exe.to_string_lossy());
        let mut key: HKEY = std::ptr::null_mut();
        unsafe {
            if RegCreateKeyExW(HKEY_CURRENT_USER, w(r"Software\Microsoft\DirectX\UserGpuPreferences").as_ptr(), 0, std::ptr::null(), 0, KEY_READ | KEY_WRITE, std::ptr::null(), &mut key, std::ptr::null_mut()) != 0 { return; }
            let exists = RegQueryValueExW(key, name.as_ptr(), std::ptr::null(), std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut()) == 0;
            if !exists {
                let v = w("GpuPreference=2;");
                RegSetValueExW(key, name.as_ptr(), 0, REG_SZ, v.as_ptr() as *const u8, (v.len() * 2) as u32);
            }
            RegCloseKey(key);
        }
    }
    #[cfg(not(windows))]
    let _ = exe;
}

/// Opts the game out of Windows power throttling (EcoQoS: efficiency cores, lower clocks), which Windows 11 applies to
/// processes it thinks are in the background. Microsoft: StateMask 0 with the execution-speed bit in ControlMask = always
/// high QoS ("SetProcessInformation", ProcessPowerThrottling). Ignored where unsupported. Elsewhere: nothing.
pub fn full_speed(child: &Child) {
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        use windows_sys::Win32::System::Threading::{ProcessPowerThrottling, SetProcessInformation, PROCESS_POWER_THROTTLING_CURRENT_VERSION, PROCESS_POWER_THROTTLING_EXECUTION_SPEED, PROCESS_POWER_THROTTLING_STATE};
        let st = PROCESS_POWER_THROTTLING_STATE { Version: PROCESS_POWER_THROTTLING_CURRENT_VERSION, ControlMask: PROCESS_POWER_THROTTLING_EXECUTION_SPEED, StateMask: 0 };
        unsafe { SetProcessInformation(child.as_raw_handle() as _, ProcessPowerThrottling, &st as *const _ as *const _, std::mem::size_of::<PROCESS_POWER_THROTTLING_STATE>() as u32); }
    }
    #[cfg(not(windows))]
    let _ = child;
}

/// Total physical memory in MB (0 when unknown). Windows: GlobalMemoryStatusEx. Elsewhere: 0 for now.
pub fn system_ram_mb() -> u64 {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
        let mut m: MEMORYSTATUSEX = std::mem::zeroed();
        m.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
        if GlobalMemoryStatusEx(&mut m) != 0 { return m.ullTotalPhys / (1024 * 1024); }
    }
    0
}
