//! Deleting user content goes to the Recycle Bin, so a mis-click can be undone from there.
use std::path::Path;

use anyhow::{bail, Result};

/// Move a file or folder to the Recycle Bin (silently, no Windows dialogs). Missing paths are fine.
#[cfg(windows)]
pub fn recycle(p: &Path) -> Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::UI::Shell::{SHFileOperationW, FOF_ALLOWUNDO, FOF_NOCONFIRMATION, FOF_NOERRORUI, FOF_SILENT, FO_DELETE, SHFILEOPSTRUCTW};
    if !p.exists() { return Ok(()); }
    let abs = std::path::absolute(p)?;
    // double-NUL terminated list of one path
    let mut from: Vec<u16> = abs.as_os_str().encode_wide().collect();
    from.extend([0, 0]);
    let mut op: SHFILEOPSTRUCTW = unsafe { std::mem::zeroed() };
    op.wFunc = FO_DELETE;
    op.pFrom = from.as_ptr();
    op.fFlags = (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI | FOF_SILENT) as u16;
    let r = unsafe { SHFileOperationW(&mut op) };
    if r != 0 || op.fAnyOperationsAborted != 0 { bail!("Windows couldn't move it to the Recycle Bin (code {r:#x}); is a file still open?"); }
    if p.exists() { bail!("it's still in use; close the game and try again"); }
    Ok(())
}

#[cfg(not(windows))]
pub fn recycle(p: &Path) -> Result<()> {
    if p.is_dir() { std::fs::remove_dir_all(p)?; } else if p.exists() { std::fs::remove_file(p)?; }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn recycles_a_file() {
        let d = std::env::temp_dir().join(format!("nc-trash-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let f = d.join("gone.txt");
        std::fs::write(&f, "x").unwrap();
        super::recycle(&f).unwrap();
        assert!(!f.exists());
        super::recycle(&f).unwrap();
        let _ = std::fs::remove_dir_all(&d);
    }
}
