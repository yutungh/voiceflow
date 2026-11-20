//! VoiceFlow C1: the icons of the apps people dictate into, for Home and
//! History. The icon is read from the app (the .exe on Windows, the bundle
//! on macOS), converted to PNG and cached in the app data folder
//! (`app-icons/<hash>.png`), so each app is read once. Returned to the page
//! as a data URL. When no icon can be read, the page shows a lettered tile.

use base64::Engine;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::path::PathBuf;
use tauri::Manager;

/// The icon for an app as a `data:image/png;base64,...` URL, or None.
#[tauri::command]
pub async fn app_icon(
    app: tauri::AppHandle,
    exe_path: Option<String>,
    bundle_id: Option<String>,
) -> Result<Option<String>, String> {
    let key = match (&exe_path, &bundle_id) {
        (Some(path), _) if !path.is_empty() => path.to_lowercase(),
        (_, Some(id)) if !id.is_empty() => id.to_lowercase(),
        _ => return Ok(None),
    };
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("app-icons");
    let file = cache_file(&dir, &key);

    if let Ok(bytes) = std::fs::read(&file) {
        return Ok(Some(data_url(&bytes)));
    }

    let exe = exe_path.unwrap_or_default();
    let bundle = bundle_id.unwrap_or_default();
    let png = tauri::async_runtime::spawn_blocking(move || icon_png(&exe, &bundle))
        .await
        .map_err(|e| e.to_string())?;
    let Some(png) = png else { return Ok(None) };

    // A failed cache write only means the icon is read again next time.
    if std::fs::create_dir_all(&dir).is_ok() {
        let _ = std::fs::write(&file, &png);
    }
    Ok(Some(data_url(&png)))
}

fn cache_file(dir: &std::path::Path, key: &str) -> PathBuf {
    let mut hasher = DefaultHasher::new();
    key.hash(&mut hasher);
    dir.join(format!("{:016x}.png", hasher.finish()))
}

fn data_url(png: &[u8]) -> String {
    format!(
        "data:image/png;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(png)
    )
}

#[cfg(target_os = "windows")]
fn icon_png(exe_path: &str, _bundle_id: &str) -> Option<Vec<u8>> {
    use std::ptr::null_mut;
    use windows_sys::Win32::Graphics::Gdi::{
        DeleteObject, GetDC, GetDIBits, GetObjectW, ReleaseDC, BITMAP, BITMAPINFO,
        BITMAPINFOHEADER, BI_RGB, DIB_RGB_COLORS,
    };
    use windows_sys::Win32::UI::Shell::SHDefExtractIconW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{DestroyIcon, GetIconInfo, HICON, ICONINFO};

    const SIZE: u32 = 64;
    let path = std::path::Path::new(exe_path);
    if !path.is_file() {
        return None;
    }
    let wide: Vec<u16> = exe_path.encode_utf16().chain(std::iter::once(0)).collect();

    unsafe {
        let mut icon: HICON = null_mut();
        let hr = SHDefExtractIconW(wide.as_ptr(), 0, 0, &mut icon, null_mut(), SIZE);
        if hr != 0 || icon.is_null() {
            return None;
        }

        let mut info: ICONINFO = std::mem::zeroed();
        if GetIconInfo(icon, &mut info) == 0 {
            DestroyIcon(icon);
            return None;
        }
        let cleanup = |info: &ICONINFO, icon: HICON| {
            if !info.hbmColor.is_null() {
                DeleteObject(info.hbmColor);
            }
            if !info.hbmMask.is_null() {
                DeleteObject(info.hbmMask);
            }
            DestroyIcon(icon);
        };
        if info.hbmColor.is_null() {
            cleanup(&info, icon);
            return None;
        }

        let mut bitmap: BITMAP = std::mem::zeroed();
        if GetObjectW(
            info.hbmColor,
            std::mem::size_of::<BITMAP>() as i32,
            &mut bitmap as *mut BITMAP as *mut core::ffi::c_void,
        ) == 0
        {
            cleanup(&info, icon);
            return None;
        }
        let (width, height) = (bitmap.bmWidth, bitmap.bmHeight);
        if width <= 0 || height <= 0 || width > 512 || height > 512 {
            cleanup(&info, icon);
            return None;
        }

        let mut header: BITMAPINFO = std::mem::zeroed();
        header.bmiHeader = BITMAPINFOHEADER {
            biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
            biWidth: width,
            biHeight: -height, // top-down rows
            biPlanes: 1,
            biBitCount: 32,
            biCompression: BI_RGB,
            ..std::mem::zeroed()
        };
        let mut pixels = vec![0u8; (width * height * 4) as usize];
        let dc = GetDC(null_mut());
        let rows = GetDIBits(
            dc,
            info.hbmColor,
            0,
            height as u32,
            pixels.as_mut_ptr() as *mut core::ffi::c_void,
            &mut header,
            DIB_RGB_COLORS,
        );
        ReleaseDC(null_mut(), dc);
        cleanup(&info, icon);
        if rows == 0 {
            return None;
        }

        // BGRA to RGBA. Old icons carry no alpha: treat them as opaque.
        let has_alpha = pixels.as_chunks::<4>().0.iter().any(|p| p[3] != 0);
        for p in pixels.as_chunks_mut::<4>().0 {
            p.swap(0, 2);
            if !has_alpha {
                p[3] = 255;
            }
        }
        encode_png(width as u32, height as u32, &pixels)
    }
}

#[cfg(target_os = "macos")]
fn icon_png(exe_path: &str, bundle_id: &str) -> Option<Vec<u8>> {
    use objc2_app_kit::{NSBitmapImageFileType, NSBitmapImageRep, NSWorkspace};
    use objc2_foundation::{NSDictionary, NSString};

    let workspace = NSWorkspace::sharedWorkspace();
    // The bundle's own icon; the executable inside it has a generic one.
    let bundle_path = if bundle_id.is_empty() {
        None
    } else {
        workspace
            .URLForApplicationWithBundleIdentifier(&NSString::from_str(bundle_id))
            .and_then(|url| url.path())
            .map(|p| p.to_string())
    };
    let path = bundle_path.unwrap_or_else(|| exe_path.to_string());
    if path.is_empty() {
        return None;
    }
    let image = workspace.iconForFile(&NSString::from_str(&path));
    let tiff = image.TIFFRepresentation()?;
    let rep = NSBitmapImageRep::imageRepWithData(&tiff)?;
    let png = unsafe {
        rep.representationUsingType_properties(NSBitmapImageFileType::PNG, &NSDictionary::new())
    }?;
    Some(png.to_vec())
}

#[cfg(not(any(target_os = "windows", target_os = "macos")))]
fn icon_png(_exe_path: &str, _bundle_id: &str) -> Option<Vec<u8>> {
    None
}

#[cfg(target_os = "windows")]
fn encode_png(width: u32, height: u32, rgba: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut out, width, height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().ok()?;
        writer.write_image_data(rgba).ok()?;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cache_file_is_stable_and_case_insensitive_by_caller() {
        let dir = std::path::Path::new("icons");
        assert_eq!(cache_file(dir, "c:\\a\\b.exe"), cache_file(dir, "c:\\a\\b.exe"));
        assert_ne!(cache_file(dir, "c:\\a\\b.exe"), cache_file(dir, "c:\\a\\c.exe"));
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn reads_an_icon_from_a_windows_exe() {
        let png = icon_png(r"C:\Windows\System32\notepad.exe", "").expect("an icon");
        assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
        assert!(icon_png(r"C:\no\such\app.exe", "").is_none());
    }

    #[test]
    fn data_url_is_png_base64() {
        assert_eq!(data_url(&[1, 2, 3]), "data:image/png;base64,AQID");
    }
}
