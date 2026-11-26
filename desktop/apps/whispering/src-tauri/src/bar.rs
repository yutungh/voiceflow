//! VoiceFlow A2: the floating recording bar (plan v2, phase A2) and the
//! window that opens after a voice feedback recording.
//!
//! The bar is a second webview, created hidden at startup so showing it is
//! instant. It is transparent, undecorated, always on top, kept out of the
//! taskbar and never focusable:
//!
//! - Windows: tao gives a non-focusable window `WS_EX_NOACTIVATE`. It is shown
//!   and moved with raw `SetWindowPos(..., SWP_NOACTIVATE | SWP_SHOWWINDOW)`
//!   (tauri's `show()` uses `SW_SHOW`, which activates, after the first show).
//! - macOS: the window is turned into a non-activating `NSPanel` subclass
//!   (a spike; see `macos` below), shown with `orderFrontRegardless`.
//!
//! One size, one place per dictation (0.9.5): the page always asks for the
//! same fixed size (`src/lib/bar/geometry.ts`). The window is placed ONCE per
//! dictation, at the first [`bar_present`] after [`bar_target`], scaled by
//! that monitor's DPI. Until the next dictation it never moves or resizes.
//! Before 0.9.5 the page measured its content for every state and the window
//! was resized and re-centred each time, which made it jump.
//!
//! 0.9.12, where (Settings > Recording bar): "Show on" picks the monitor when
//! a dictation starts ([`BarMonitor`]: the main monitor by default, else the
//! one under the mouse or the one holding the app being typed in), and
//! "Position" the edge and side ([`BarPosition`]: bottom or top). The bar
//! stays on that monitor until it hides; only a display change moves it: the
//! same monitor with new geometry (rescaled, resolution, taskbar moved) is
//! placed again on itself, and a monitor that is gone hands the bar to the
//! main monitor. A watcher notices that while the bar shows. On Windows the
//! monitors are read natively ([`platform::screens`]): an auto-hidden
//! taskbar's revealed size is kept clear too.
//!
//! The window is taller than the bar, with room above it for the "More
//! styles" list (0.9.8: while a dictation records, the bar is a card with
//! style chips and an expression slider; it grows and shrinks upward inside
//! the same window). On Windows a window region ([`bar_hit_rects`]) keeps
//! only the drawn parts, so the empty room is not there: clicks pass to the
//! app below.
//!
//! The bar's state comes from the dictation engine in the main webview
//! (`dictation://state`); the microphone level comes from here
//! (`audio://level`, ~25 Hz, read from `recorder::level`).
//!
//! Focus: a deliberate click on the bar must not leave the target app
//! unfocused. While the bar is visible a watcher samples the foreground app
//! every 50 ms. The page reports pointer-down ([`bar_pointer_down`]), which
//! also keeps the sample from just before the press (a slider drag can
//! outlast the short history), and, after the click or drag is done, asks
//! [`bar_restore_focus`]: if VoiceFlow is now in front but an external app
//! was in front just before the press, that app is brought back.

use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

use crate::recorder::level;

/// Window labels (also named in `capabilities/bar.json`).
pub const BAR_WINDOW: &str = "bar";
pub const FEEDBACK_WINDOW: &str = "feedback";

/// Events sent from here.
pub const LEVEL_EVENT: &str = "audio://level";
pub const FEEDBACK_RELOAD_EVENT: &str = "feedback://reload";

const LEVEL_PERIOD: Duration = Duration::from_millis(40);
const FOREGROUND_PERIOD: Duration = Duration::from_millis(50);
/// Foreground samples at least this much older than the pointer-down report
/// were taken before the click (the click reaches us a few ms after the
/// mouse button went down).
const CLICK_LATENCY: Duration = Duration::from_millis(40);
const FOREGROUND_HISTORY: usize = 20;
/// How often a shown bar checks that its monitor is unchanged (0.9.12).
const DISPLAY_PERIOD: Duration = Duration::from_millis(500);

/// Logical px between the window and the work area's edge. The page keeps a
/// 16 px transparent margin for the shadow, so the pill itself sits 24 px
/// above the taskbar or Dock.
pub const EDGE_GAP: f64 = 8.0;
/// The page's fixed size (`WINDOW_WIDTH` x `WINDOW_HEIGHT` in geometry.ts),
/// used until it reports it.
const INITIAL_SIZE: (f64, f64) = (512.0, 392.0);

static BAR_VISIBLE: AtomicBool = AtomicBool::new(false);

/// Where the bar sits (Settings > Recording bar > Position and Alignment).
/// 0.9.12: the top edge too; the page mirrors its layout (the fixed row on
/// top, the card growing down).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum BarPosition {
    #[default]
    BottomCenter,
    BottomLeft,
    BottomRight,
    TopCenter,
    TopLeft,
    TopRight,
}

impl BarPosition {
    pub fn is_top(self) -> bool {
        matches!(self, Self::TopCenter | Self::TopLeft | Self::TopRight)
    }
}

/// Which monitor a dictation's bar goes on (Settings > Recording bar > Show on).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum BarMonitor {
    /// The main (primary) monitor.
    #[default]
    Primary,
    /// The monitor the mouse is on when the dictation starts.
    Mouse,
    /// The monitor of the app being typed in (else the mouse's, else the main one).
    Target,
}

/// A monitor's work area in physical pixels, and its scale factor.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WorkArea {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    pub scale: f64,
}

/// One monitor: a stable id (its device name on Windows), its work area and
/// whether it is the main one.
#[derive(Clone, Debug, PartialEq)]
pub struct Screen {
    pub id: String,
    pub area: WorkArea,
    pub primary: bool,
}

/// The monitor for a new dictation. `target`, `cursor`: the ids of the
/// monitors holding the target window and the mouse, when known. Falls back
/// to the main monitor, then to any monitor.
pub fn choose_screen<'a>(
    screens: &'a [Screen],
    mode: BarMonitor,
    target: Option<&str>,
    cursor: Option<&str>,
) -> Option<&'a Screen> {
    let by_id = |id: Option<&str>| id.and_then(|id| screens.iter().find(|s| s.id == id));
    let primary = || screens.iter().find(|s| s.primary).or_else(|| screens.first());
    match mode {
        BarMonitor::Primary => primary(),
        BarMonitor::Mouse => by_id(cursor).or_else(primary),
        BarMonitor::Target => by_id(target).or_else(|| by_id(cursor)).or_else(primary),
    }
}

/// The monitor a shown bar belongs on now: the one it was placed on (with
/// its current geometry), or the main monitor once that one is gone.
pub fn relocate<'a>(screens: &'a [Screen], locked: &str) -> Option<&'a Screen> {
    screens
        .iter()
        .find(|s| s.id == locked)
        .or_else(|| screens.iter().find(|s| s.primary))
        .or_else(|| screens.first())
}

/// A rectangle as (left, top, right, bottom), physical px.
pub type Edges = (i32, i32, i32, i32);

/// The work area with an auto-hidden taskbar's revealed size kept clear.
/// `insets` (left, top, right, bottom) are the thickness of an auto-hide bar
/// on each edge of `monitor`; one only counts where the work area reaches
/// that edge (a taskbar that reserves space already moved it). Never less
/// than half the work area on either axis.
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
pub fn clear_of_autohide(work: Edges, monitor: Edges, insets: Edges) -> Edges {
    let (mut l, mut t, mut r, mut b) = work;
    let w = (r - l).max(0);
    let h = (b - t).max(0);
    let cap = |v: i32, span: i32| v.clamp(0, span / 4);
    if l <= monitor.0 {
        l += cap(insets.0, w);
    }
    if t <= monitor.1 {
        t += cap(insets.1, h);
    }
    if r >= monitor.2 {
        r -= cap(insets.2, w);
    }
    if b >= monitor.3 {
        b -= cap(insets.3, h);
    }
    (l, t, r.max(l + 1), b.max(t + 1))
}

/// A window rectangle in physical pixels.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WindowRect {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

/// Where the bar goes: `logical` is the page's size in CSS pixels. 0.9.12:
/// the top edge mirrors the bottom; on a work area too small for the window
/// and its gap, the window is kept inside it (the gap goes first).
pub fn place(area: WorkArea, logical: (f64, f64), position: BarPosition) -> WindowRect {
    let scale = if area.scale.is_finite() && area.scale > 0.0 {
        area.scale
    } else {
        1.0
    };
    let w = ((logical.0.max(1.0)) * scale).round() as i64;
    let h = ((logical.1.max(1.0)) * scale).round() as i64;
    let w = w.min(i64::from(area.width)).max(1);
    let h = h.min(i64::from(area.height)).max(1);
    let gap = (EDGE_GAP * scale).round() as i64;
    let left = i64::from(area.x);
    let right = left + i64::from(area.width);
    let top = i64::from(area.y);
    let bottom = top + i64::from(area.height);
    let x = match position {
        BarPosition::BottomCenter | BarPosition::TopCenter => left + (i64::from(area.width) - w) / 2,
        BarPosition::BottomLeft | BarPosition::TopLeft => left + gap,
        BarPosition::BottomRight | BarPosition::TopRight => right - gap - w,
    };
    let y = if position.is_top() { top + gap } else { bottom - gap - h };
    let x = x.clamp(left, (right - w).max(left));
    let y = y.clamp(top, (bottom - h).max(top));
    WindowRect {
        x: x as i32,
        y: y as i32,
        width: w as u32,
        height: h as u32,
    }
}

/// The bar's place for one dictation: computed on the first call, then the
/// same rectangle every time, whatever size is asked for, until [`reset`]
/// (a new dictation) or a different work area (a display change).
///
/// [`reset`]: Placement::reset
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Placement {
    locked: Option<(WorkArea, WindowRect)>,
}

impl Placement {
    /// A new dictation: the next call places the bar again.
    pub fn reset(&mut self) {
        self.locked = None;
    }

    pub fn rect(&mut self, area: WorkArea, logical: (f64, f64), position: BarPosition) -> WindowRect {
        if let Some((locked_area, rect)) = self.locked {
            if locked_area == area {
                return rect;
            }
        }
        let rect = place(area, logical, position);
        self.locked = Some((area, rect));
        rect
    }

    /// The locked rectangle and its scale, if placed.
    pub fn current(&self) -> Option<(WindowRect, f64)> {
        self.locked.map(|(area, rect)| (rect, area.scale))
    }
}

/// A part of the bar window that exists (drawn and clickable), in CSS px
/// relative to the window's top-left corner.
#[derive(Clone, Copy, Debug, PartialEq, Deserialize, Serialize)]
pub struct HitRect {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

/// The window region for `hit`, in physical px (left, top, right, bottom),
/// clamped to the window. Empty: no region (the whole window).
pub fn region_rects(hit: &[HitRect], scale: f64, window: (u32, u32)) -> Vec<(i32, i32, i32, i32)> {
    let scale = if scale.is_finite() && scale > 0.0 { scale } else { 1.0 };
    let (w, h) = (f64::from(window.0), f64::from(window.1));
    hit.iter()
        .filter(|r| r.x.is_finite() && r.y.is_finite() && r.width.is_finite() && r.height.is_finite())
        .filter_map(|r| {
            let left = (r.x * scale).floor().clamp(0.0, w);
            let top = (r.y * scale).floor().clamp(0.0, h);
            let right = ((r.x + r.width) * scale).ceil().clamp(0.0, w);
            let bottom = ((r.y + r.height) * scale).ceil().clamp(0.0, h);
            (right > left && bottom > top)
                .then_some((left as i32, top as i32, right as i32, bottom as i32))
        })
        .collect()
}

/// An app outside VoiceFlow that can be brought back to the front.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct External {
    pub window_id: Option<String>,
    pub pid: Option<u32>,
}

/// One foreground sample: `None` means VoiceFlow itself was in front.
type Sample = (Instant, Option<External>);

/// What a bar click did to the foreground.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Displacement {
    /// VoiceFlow is not in front, or was already in front before the click.
    NotDisplaced,
    /// VoiceFlow is in front now and this app was in front before the click.
    Displaced(External),
    /// VoiceFlow is in front and nothing says what was before it.
    Unknown,
}

/// The history with the sample kept at pointer-down added (oldest first), so
/// a drag that outlasts the history still knows what was in front before it.
pub fn with_pre_click(history: &[Sample], pre_click: Option<&Sample>) -> Vec<Sample> {
    let mut all: Vec<Sample> = history.to_vec();
    if let Some(sample) = pre_click {
        if !all.iter().any(|(at, _)| at == &sample.0) {
            all.push(sample.clone());
            all.sort_by_key(|(at, _)| *at);
        }
    }
    all
}

/// Decides whether a click on the bar took the front from another app.
/// `now_is_self`: VoiceFlow is in front now. `history`: oldest first.
/// `fallback`: the dictation's target, used when no sample predates the click.
pub fn displacement(
    history: &[Sample],
    click_at: Instant,
    now_is_self: bool,
    fallback: Option<&External>,
) -> Displacement {
    if !now_is_self {
        return Displacement::NotDisplaced;
    }
    let before = click_at.checked_sub(CLICK_LATENCY);
    let last_before = history
        .iter()
        .rev()
        .find(|(at, _)| before.is_some_and(|b| *at <= b));
    match last_before {
        Some((_, Some(external))) => Displacement::Displaced(external.clone()),
        Some((_, None)) => Displacement::NotDisplaced,
        None => match fallback {
            Some(external) => Displacement::Displaced(external.clone()),
            None => Displacement::Unknown,
        },
    }
}

#[derive(Default)]
struct Inner {
    /// The monitor this dictation's bar is on (chosen at [`bar_target`]).
    screen: Option<Screen>,
    /// "Show on", from the last [`bar_target`].
    monitor: BarMonitor,
    size: Option<(f64, f64)>,
    placement: Placement,
    hit: Vec<HitRect>,
    /// The region last set on the window (physical px), to skip repeats.
    region: Option<Vec<(i32, i32, i32, i32)>>,
    position: BarPosition,
    target: Option<External>,
    history: VecDeque<Sample>,
    click_at: Option<Instant>,
    /// The last sample from before the press, kept at pointer-down.
    pre_click: Option<Sample>,
    feedback_payload: Option<serde_json::Value>,
}

/// Managed state for the bar and the feedback window.
#[derive(Default)]
pub struct BarState {
    inner: Mutex<Inner>,
}

impl BarState {
    fn with<T>(&self, f: impl FnOnce(&mut Inner) -> T) -> T {
        let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        f(&mut guard)
    }
}

/// Creates the hidden bar window and starts the level pump and the
/// foreground watcher. Called once from `setup`.
pub fn create(app: &AppHandle) -> tauri::Result<()> {
    let builder = WebviewWindowBuilder::new(app, BAR_WINDOW, WebviewUrl::App("bar".into()));
    // VoiceFlow: the same WebView2 switches as every window (webview_args.rs).
    let window = crate::webview_args::keep_awake(app, builder)
        .title("VoiceLnq recording bar")
        .inner_size(INITIAL_SIZE.0, INITIAL_SIZE.1)
        .visible(false)
        .transparent(true)
        .decorations(false)
        .shadow(false)
        .resizable(false)
        .maximizable(false)
        .minimizable(false)
        .skip_taskbar(true)
        .always_on_top(true)
        .visible_on_all_workspaces(true)
        .focusable(false)
        .focused(false)
        .build()?;
    platform::prepare(&window);

    let pump = app.clone();
    std::thread::Builder::new()
        .name("voiceflow-bar-level".into())
        .spawn(move || loop {
            std::thread::sleep(LEVEL_PERIOD);
            if let Some(rms) = level::take() {
                let _ = pump.emit_to(BAR_WINDOW, LEVEL_EVENT, level::to_display(rms));
            }
        })
        .map(|_| ())
        .unwrap_or_else(|e| log::warn!("[bar] level pump not started: {e}"));

    let watcher = app.clone();
    std::thread::Builder::new()
        .name("voiceflow-bar-foreground".into())
        .spawn(move || loop {
            std::thread::sleep(FOREGROUND_PERIOD);
            if !BAR_VISIBLE.load(Ordering::Relaxed) {
                continue;
            }
            // `None`: nothing in front (switching); not a useful sample.
            let Some(sample) = platform::foreground() else { continue };
            watcher.state::<BarState>().with(|inner| {
                inner.history.push_back((Instant::now(), sample));
                while inner.history.len() > FOREGROUND_HISTORY {
                    inner.history.pop_front();
                }
            });
        })
        .map(|_| ())
        .unwrap_or_else(|e| log::warn!("[bar] foreground watcher not started: {e}"));

    // 0.9.12: a display change while the bar shows (monitor unplugged,
    // rescaled, taskbar moved) places it again at once, on the main thread
    // like every other show and hide, so it can never undo a hide.
    let displays = app.clone();
    std::thread::Builder::new()
        .name("voiceflow-bar-displays".into())
        .spawn(move || loop {
            std::thread::sleep(DISPLAY_PERIOD);
            if !BAR_VISIBLE.load(Ordering::Relaxed) {
                continue;
            }
            let app = displays.clone();
            let _ = displays.run_on_main_thread(move || {
                if !BAR_VISIBLE.load(Ordering::Relaxed) || !display_changed(&app) {
                    return;
                }
                log::info!("[bar] the display changed while the bar showed; placed again");
                let state = app.state::<BarState>();
                if let Err(e) = apply(&app, &state, false) {
                    log::warn!("[bar] could not place the bar again: {e}");
                }
            });
        })
        .map(|_| ())
        .unwrap_or_else(|e| log::warn!("[bar] display watcher not started: {e}"));
    Ok(())
}

/// True when the shown bar's monitor is gone or has new geometry.
fn display_changed(app: &AppHandle) -> bool {
    let Some(locked) = app.state::<BarState>().with(|i| i.screen.clone()) else {
        return false;
    };
    let Some(all) = platform::screens(app) else { return false };
    relocate(&all, &locked.id).is_none_or(|now| now != &locked)
}

/// The monitor for a new dictation, by the "Show on" setting.
fn screen_for(app: &AppHandle, mode: BarMonitor, window_id: Option<&str>) -> Option<Screen> {
    let all = platform::screens(app)?;
    let target = match mode {
        BarMonitor::Target => window_id.and_then(platform::screen_of_window),
        _ => None,
    };
    let cursor = match mode {
        BarMonitor::Primary => None,
        _ => platform::screen_at_cursor(app),
    };
    choose_screen(&all, mode, target.as_deref(), cursor.as_deref()).cloned()
}

fn bar_window(app: &AppHandle) -> Option<WebviewWindow> {
    app.get_webview_window(BAR_WINDOW)
}

/// Places (and, when visible, moves) the bar from the stored size and area.
fn apply(app: &AppHandle, state: &BarState, show: bool) -> Result<(), String> {
    let window = bar_window(app).ok_or("the recording bar window is missing")?;
    let (screen, size, position, mode, target_window) = state.with(|i| {
        (
            i.screen.clone(),
            i.size,
            i.position,
            i.monitor,
            i.target.as_ref().and_then(|t| t.window_id.clone()),
        )
    });
    // The dictation's monitor as it is now: a display change since it was
    // chosen (resolution, scaling, taskbar) places the bar again on that
    // same monitor; once that monitor is gone, on the main one.
    let screen = match screen {
        Some(locked) => match platform::screens(app) {
            Some(all) => relocate(&all, &locked.id).cloned().unwrap_or(locked),
            None => locked,
        },
        None => screen_for(app, mode, target_window.as_deref()).ok_or("no monitor found")?,
    };
    let area = screen.area;
    state.with(|i| i.screen = Some(screen));
    // Placed once per dictation; the same rectangle until the next one (or
    // a display change: another work area places it again).
    let rect = state.with(|i| i.placement.rect(area, size.unwrap_or(INITIAL_SIZE), position));
    apply_region(&window, state);
    if show || BAR_VISIBLE.load(Ordering::Relaxed) {
        platform::show_at(&window, rect);
        BAR_VISIBLE.store(true, Ordering::Relaxed);
    }
    Ok(())
}

/// Sets the window region from the page's hit rectangles (Windows), when it
/// changed. Set before the window shows, so the empty room above the pill is
/// never there.
fn apply_region(window: &WebviewWindow, state: &BarState) {
    let wanted = state.with(|i| {
        let (rect, scale) = i.placement.current()?;
        let region = region_rects(&i.hit, scale, (rect.width, rect.height));
        if i.region.as_ref() == Some(&region) {
            return None;
        }
        i.region = Some(region.clone());
        Some(region)
    });
    if let Some(region) = wanted {
        if !platform::set_region(window, &region) {
            // Not set: forget it, so the next present or change tries again.
            state.with(|i| i.region = None);
        }
    }
}

/// The dictation's target, at record start: what a displaced click restores
/// when no sample says otherwise. 0.9.12: the bar's monitor is chosen here,
/// by `monitor` ("Show on"), and kept until the next dictation.
/// `window_id`/`pid` are omitted when the target is VoiceFlow itself.
#[tauri::command]
pub fn bar_target(
    app: AppHandle,
    state: tauri::State<'_, BarState>,
    window_id: Option<String>,
    pid: Option<u32>,
    position: Option<BarPosition>,
    monitor: Option<BarMonitor>,
) {
    let mode = monitor.unwrap_or_default();
    let screen = screen_for(&app, mode, window_id.as_deref());
    state.with(|i| {
        i.monitor = mode;
        // None: chosen again at the first present.
        i.screen = screen;
        // A new dictation: placed again at its first present.
        i.placement.reset();
        if let Some(p) = position {
            i.position = p;
        }
        i.target = if window_id.is_some() || pid.is_some() {
            Some(External { window_id, pid })
        } else {
            None
        };
        i.history.clear();
    });
    if BAR_VISIBLE.load(Ordering::Relaxed) {
        let _ = apply(&app, &state, false);
    }
}

/// Shows the bar, without taking focus. `width` x `height` (CSS px) is the
/// page's fixed size; it is used only when the dictation's place is
/// computed, never to resize the bar mid-dictation. `hit`: the drawn parts
/// (the window region on Windows).
#[tauri::command]
pub fn bar_present(
    app: AppHandle,
    state: tauri::State<'_, BarState>,
    width: f64,
    height: f64,
    hit: Option<Vec<HitRect>>,
) -> Result<(), String> {
    if !(width.is_finite() && height.is_finite()) {
        return Err("bad bar size".into());
    }
    state.with(|i| {
        i.size = Some((width.clamp(40.0, 1600.0), height.clamp(24.0, 800.0)));
        if let Some(hit) = hit {
            i.hit = hit;
        }
    });
    apply(&app, &state, true)
}

/// The drawn parts changed (the popover or hint opened or closed): only the
/// window region changes; the window keeps its size and place.
#[tauri::command]
pub fn bar_hit_rects(app: AppHandle, state: tauri::State<'_, BarState>, hit: Vec<HitRect>) {
    state.with(|i| i.hit = hit);
    if let Some(window) = bar_window(&app) {
        apply_region(&window, &state);
    }
}

/// Hides the bar.
#[tauri::command]
pub fn bar_hide(app: AppHandle, state: tauri::State<'_, BarState>) {
    BAR_VISIBLE.store(false, Ordering::Relaxed);
    state.with(|i| {
        i.history.clear();
        i.click_at = None;
        i.pre_click = None;
    });
    if let Some(window) = bar_window(&app) {
        platform::hide(&window);
    }
}

/// The page saw a pointer-down on a control.
#[tauri::command]
pub fn bar_pointer_down(state: tauri::State<'_, BarState>) {
    let now = Instant::now();
    state.with(|i| {
        i.click_at = Some(now);
        let before = now.checked_sub(CLICK_LATENCY);
        i.pre_click = i
            .history
            .iter()
            .rev()
            .find(|(at, _)| before.is_some_and(|b| *at <= b))
            .cloned();
    });
}

/// After a deliberate click: brings back the app the click displaced.
/// Returns "not-displaced", "restored", "failed" or "unknown".
#[tauri::command]
pub async fn bar_restore_focus(app: AppHandle) -> String {
    let state = app.state::<BarState>();
    let (history, click_at, target) = state.with(|i| {
        let pre_click = i.pre_click.take();
        (
            with_pre_click(&i.history.iter().cloned().collect::<Vec<_>>(), pre_click.as_ref()),
            i.click_at.take(),
            i.target.clone(),
        )
    });
    let result = tokio::task::spawn_blocking(move || {
        let now_is_self = matches!(platform::foreground(), Some(None));
        match displacement(
            &history,
            click_at.unwrap_or_else(Instant::now),
            now_is_self,
            target.as_ref(),
        ) {
            Displacement::NotDisplaced => "not-displaced",
            Displacement::Unknown => "unknown",
            Displacement::Displaced(external) => {
                if crate::context::focus_window(external.window_id.as_deref(), external.pid) {
                    "restored"
                } else {
                    "failed"
                }
            }
        }
    })
    .await
    .unwrap_or("failed");
    if result != "not-displaced" {
        log::info!("[bar] focus after a click: {result}");
    }
    result.to_string()
}

/// Opens (or refreshes) the feedback window with `payload`, which the page
/// collects with [`feedback_payload_take`]. The window takes focus: the
/// person asked for it. Async: creating a window from a sync command can
/// deadlock on Windows.
#[tauri::command]
pub async fn feedback_window_open(
    app: AppHandle,
    payload: serde_json::Value,
) -> Result<(), String> {
    app.state::<BarState>()
        .with(|i| i.feedback_payload = Some(payload));
    if let Some(window) = app.get_webview_window(FEEDBACK_WINDOW) {
        let _ = window.emit_to(FEEDBACK_WINDOW, FEEDBACK_RELOAD_EVENT, ());
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
        return Ok(());
    }
    let builder =
        WebviewWindowBuilder::new(&app, FEEDBACK_WINDOW, WebviewUrl::App("feedback-capture".into()));
    crate::webview_args::keep_awake(&app, builder)
        .title("Feedback on a dictation")
        .inner_size(560.0, 720.0)
        .min_inner_size(420.0, 460.0)
        .center()
        .always_on_top(true)
        .focused(true)
        .build()
        .map(|_| ())
        .map_err(|e| format!("could not open the feedback window: {e}"))
}

/// VoiceFlow: the transform-clipboard window, built here so it gets the same
/// WebView2 switches as every other window (a webview created from the page
/// would get wry's defaults and be refused by the shared WebView2
/// environment). Async: creating a window from a sync command can deadlock
/// on Windows. The page shows, hides and focuses it afterwards.
#[tauri::command]
pub async fn transform_window_create(app: AppHandle) -> Result<(), String> {
    const LABEL: &str = "transform-clipboard";
    if app.get_webview_window(LABEL).is_some() {
        return Ok(());
    }
    let builder = WebviewWindowBuilder::new(&app, LABEL, WebviewUrl::App("transform-clipboard".into()));
    crate::webview_args::keep_awake(&app, builder)
        .title("Transform Clipboard")
        .inner_size(700.0, 600.0)
        .center()
        .always_on_top(true)
        .decorations(true)
        .resizable(true)
        .focused(true)
        .visible(true)
        .build()
        .map(|_| ())
        .map_err(|e| format!("could not open the transform window: {e}"))
}

/// The payload for the feedback window (taken once).
#[tauri::command]
pub fn feedback_payload_take(state: tauri::State<'_, BarState>) -> Option<serde_json::Value> {
    state.with(|i| i.feedback_payload.take())
}

#[cfg(target_os = "windows")]
mod platform {
    use super::{clear_of_autohide, Edges, External, Screen, WindowRect, WorkArea};
    use tauri::{AppHandle, WebviewWindow};
    use windows_sys::Win32::Foundation::{BOOL, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
    use windows_sys::Win32::Graphics::Gdi::{
        CombineRgn, CreateRectRgn, DeleteObject, EnumDisplayMonitors, GetMonitorInfoW,
        MonitorFromPoint, MonitorFromWindow, SetWindowRgn, HDC, HMONITOR, HRGN, MONITORINFO,
        MONITORINFOEXW, MONITOR_DEFAULTTONEAREST, RGN_ERROR, RGN_OR,
    };
    use windows_sys::Win32::UI::HiDpi::{GetDpiForMonitor, MDT_EFFECTIVE_DPI};
    use windows_sys::Win32::UI::Shell::{
        DefSubclassProc, SHAppBarMessage, SetWindowSubclass, ABE_BOTTOM, ABE_LEFT, ABE_RIGHT,
        ABE_TOP, ABM_GETAUTOHIDEBAREX, APPBARDATA,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        GetCursorPos, GetForegroundWindow, GetWindowLongW, GetWindowRect,
        GetWindowThreadProcessId, IsWindow, SetWindowLongW, SetWindowPos, ShowWindow,
        GWL_EXSTYLE, HWND_TOPMOST, MA_NOACTIVATE, MONITORINFOF_PRIMARY, SWP_NOACTIVATE,
        SWP_SHOWWINDOW, SW_HIDE, WM_MOUSEACTIVATE, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW,
    };

    /// "VFBA": this module's subclass id.
    const SUBCLASS_ID: usize = 0x5646_4241;

    /// The mouse never activates the bar: `WS_EX_NOACTIVATE` alone is not
    /// honoured on every path (Windows' "activate a window by hovering over
    /// it" setting ignores it); answering `WM_MOUSEACTIVATE` with
    /// `MA_NOACTIVATE` is. The click itself still reaches the page.
    unsafe extern "system" fn no_mouse_activate(
        hwnd: HWND,
        msg: u32,
        wparam: WPARAM,
        lparam: LPARAM,
        _id: usize,
        _data: usize,
    ) -> LRESULT {
        if msg == WM_MOUSEACTIVATE {
            return MA_NOACTIVATE as LRESULT;
        }
        DefSubclassProc(hwnd, msg, wparam, lparam)
    }

    /// Installs [`no_mouse_activate`] on `hwnd` (from the window's own thread).
    pub fn refuse_mouse_activation(hwnd: HWND) -> bool {
        unsafe { SetWindowSubclass(hwnd, Some(no_mouse_activate), SUBCLASS_ID, 0) != 0 }
    }

    /// Keeps the bar out of Alt+Tab (a tool window), makes sure it is
    /// non-activating even if the window was built without it, and that the
    /// mouse never activates it.
    pub fn prepare(window: &WebviewWindow) {
        let Some(hwnd) = raw(window) else { return };
        unsafe {
            let ex = GetWindowLongW(hwnd, GWL_EXSTYLE) as u32;
            SetWindowLongW(hwnd, GWL_EXSTYLE, (ex | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE) as i32);
        }
        if !refuse_mouse_activation(hwnd) {
            log::warn!("[bar] could not install the no-activate hook; clicks rely on the focus restore");
        }
    }

    fn raw(window: &WebviewWindow) -> Option<HWND> {
        window.hwnd().ok().map(|h| h.0 as HWND)
    }

    /// `Some(None)`: VoiceFlow is in front. `None`: nothing is.
    pub fn foreground() -> Option<Option<External>> {
        let hwnd = unsafe { GetForegroundWindow() };
        if hwnd.is_null() {
            return None;
        }
        let mut pid: u32 = 0;
        unsafe { GetWindowThreadProcessId(hwnd, &mut pid) };
        if pid == std::process::id() {
            return Some(None);
        }
        Some(Some(External {
            window_id: Some((hwnd as usize).to_string()),
            pid: Some(pid),
        }))
    }

    // ---- monitors (0.9.12) -------------------------------------------------
    //
    // Read here rather than through tao, whose monitor calls unwrap Win32
    // results that can fail while a monitor is being unplugged. Every call
    // is checked; a monitor that can't be read is left out.

    fn edges(r: &RECT) -> Edges {
        (r.left, r.top, r.right, r.bottom)
    }

    /// A monitor's device name ("\\.\DISPLAY1"), rectangles and main flag.
    fn monitor_info(monitor: HMONITOR) -> Option<(String, RECT, RECT, bool)> {
        if monitor.is_null() {
            return None;
        }
        let mut info: MONITORINFOEXW = unsafe { std::mem::zeroed() };
        info.monitorInfo.cbSize = std::mem::size_of::<MONITORINFOEXW>() as u32;
        let ok = unsafe { GetMonitorInfoW(monitor, &mut info as *mut MONITORINFOEXW as *mut MONITORINFO) };
        if ok == 0 {
            return None;
        }
        let len = info.szDevice.iter().position(|&c| c == 0).unwrap_or(info.szDevice.len());
        let name = String::from_utf16_lossy(&info.szDevice[..len]);
        let m = info.monitorInfo;
        Some((name, m.rcMonitor, m.rcWork, m.dwFlags & MONITORINFOF_PRIMARY != 0))
    }

    /// The monitor's scale (its effective DPI / 96); 1 when unknown.
    fn scale_of(monitor: HMONITOR) -> f64 {
        let (mut x, mut y) = (0u32, 0u32);
        let hr = unsafe { GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut x, &mut y) };
        if hr == 0 && x > 0 {
            f64::from(x) / 96.0
        } else {
            1.0
        }
    }

    /// The thickness of an auto-hidden taskbar on each edge of `monitor`
    /// (left, top, right, bottom): its full size, as when it slides out.
    fn autohide_insets(monitor: &RECT) -> Edges {
        let mut out = [0i32; 4];
        for (i, edge) in [ABE_LEFT, ABE_TOP, ABE_RIGHT, ABE_BOTTOM].into_iter().enumerate() {
            let mut data: APPBARDATA = unsafe { std::mem::zeroed() };
            data.cbSize = std::mem::size_of::<APPBARDATA>() as u32;
            data.uEdge = edge;
            data.rc = *monitor;
            let bar = unsafe { SHAppBarMessage(ABM_GETAUTOHIDEBAREX, &mut data) } as HWND;
            if bar.is_null() {
                continue;
            }
            let mut r = RECT { left: 0, top: 0, right: 0, bottom: 0 };
            if unsafe { GetWindowRect(bar, &mut r) } == 0 {
                continue;
            }
            out[i] = if edge == ABE_LEFT || edge == ABE_RIGHT { r.right - r.left } else { r.bottom - r.top };
        }
        (out[0], out[1], out[2], out[3])
    }

    fn screen_of(monitor: HMONITOR) -> Option<Screen> {
        let (id, rc_monitor, rc_work, primary) = monitor_info(monitor)?;
        let (l, t, r, b) = clear_of_autohide(edges(&rc_work), edges(&rc_monitor), autohide_insets(&rc_monitor));
        if r <= l || b <= t {
            return None;
        }
        Some(Screen {
            id,
            area: WorkArea {
                x: l,
                y: t,
                width: (r - l) as u32,
                height: (b - t) as u32,
                scale: scale_of(monitor),
            },
            primary,
        })
    }

    unsafe extern "system" fn collect(monitor: HMONITOR, _dc: HDC, _rect: *mut RECT, data: LPARAM) -> BOOL {
        let list = &mut *(data as *mut Vec<HMONITOR>);
        list.push(monitor);
        1
    }

    /// Every monitor now (physical px); `None` when none can be read.
    pub fn screens(_app: &AppHandle) -> Option<Vec<super::Screen>> {
        let mut handles: Vec<HMONITOR> = Vec::new();
        let ok = unsafe {
            EnumDisplayMonitors(
                std::ptr::null_mut(),
                std::ptr::null(),
                Some(collect),
                &mut handles as *mut Vec<HMONITOR> as LPARAM,
            )
        };
        if ok == 0 {
            return None;
        }
        let all: Vec<Screen> = handles.into_iter().filter_map(screen_of).collect();
        (!all.is_empty()).then_some(all)
    }

    /// The monitor holding most of a window (not its centre point: a window
    /// across two monitors, or minimised, still has one).
    pub fn screen_of_window(window_id: &str) -> Option<String> {
        let hwnd = window_id.parse::<usize>().ok()? as HWND;
        if hwnd.is_null() || unsafe { IsWindow(hwnd) } == 0 {
            return None;
        }
        let monitor = unsafe { MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) };
        monitor_info(monitor).map(|(id, ..)| id)
    }

    /// The monitor the mouse is on.
    pub fn screen_at_cursor(_app: &AppHandle) -> Option<String> {
        let mut pt = POINT { x: 0, y: 0 };
        if unsafe { GetCursorPos(&mut pt) } == 0 {
            return None;
        }
        let monitor = unsafe { MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST) };
        monitor_info(monitor).map(|(id, ..)| id)
    }

    fn place_once(hwnd: HWND, rect: WindowRect) -> bool {
        unsafe {
            SetWindowPos(
                hwnd,
                HWND_TOPMOST,
                rect.x,
                rect.y,
                rect.width as i32,
                rect.height as i32,
                SWP_NOACTIVATE | SWP_SHOWWINDOW,
            ) != 0
        }
    }

    fn is_at(hwnd: HWND, rect: WindowRect) -> bool {
        let mut r = RECT { left: 0, top: 0, right: 0, bottom: 0 };
        (unsafe { GetWindowRect(hwnd, &mut r) }) != 0
            && (r.left, r.top, r.right - r.left, r.bottom - r.top)
                == (rect.x, rect.y, rect.width as i32, rect.height as i32)
    }

    /// Moves, sizes and shows the bar in one call that never activates it.
    /// 0.9.12: moving onto a monitor with another scale makes Windows send
    /// the window a DPI change, and tao resizes it by the scale ratio while
    /// the call runs; the size asked for was already that monitor's, so it
    /// is set once more when the window did not end where it was put.
    pub fn show_at(window: &WebviewWindow, rect: WindowRect) {
        let Some(hwnd) = raw(window) else { return };
        if !place_once(hwnd, rect) {
            log::warn!("[bar] could not place the bar window");
            return;
        }
        if !is_at(hwnd, rect) && !(place_once(hwnd, rect) && is_at(hwnd, rect)) {
            log::warn!("[bar] the bar window did not keep its place after a DPI change");
        }
    }

    pub fn hide(window: &WebviewWindow) {
        if let Some(hwnd) = raw(window) {
            unsafe { ShowWindow(hwnd, SW_HIDE) };
        }
    }

    /// The union of `rects` (physical px, window-relative) as a region.
    /// Null for an empty list, or if a region can't be built.
    fn build_region(rects: &[(i32, i32, i32, i32)]) -> HRGN {
        let mut region: HRGN = std::ptr::null_mut();
        for &(l, t, r, b) in rects {
            let part = unsafe { CreateRectRgn(l, t, r, b) };
            if part.is_null() {
                continue;
            }
            if region.is_null() {
                region = part;
                continue;
            }
            let ok = unsafe { CombineRgn(region, region, part, RGN_OR) } != RGN_ERROR;
            unsafe { DeleteObject(part) };
            if !ok {
                unsafe { DeleteObject(region) };
                return std::ptr::null_mut();
            }
        }
        region
    }

    /// Limits the window (drawing and clicks) to `rects`; empty: the whole
    /// window. The system owns the region once it is set.
    pub fn set_region_hwnd(hwnd: HWND, rects: &[(i32, i32, i32, i32)]) -> bool {
        let region = build_region(rects);
        if !rects.is_empty() && region.is_null() {
            return false;
        }
        let set = unsafe { SetWindowRgn(hwnd, region, 1) } != 0;
        if !set && !region.is_null() {
            unsafe { DeleteObject(region) };
        }
        set
    }

    pub fn set_region(window: &WebviewWindow, rects: &[(i32, i32, i32, i32)]) -> bool {
        let Some(hwnd) = raw(window) else { return false };
        let set = set_region_hwnd(hwnd, rects);
        if !set {
            log::warn!("[bar] could not set the window region; the room above the pill may catch clicks");
        }
        set
    }
}

/// macOS spike: the bar becomes a non-activating NSPanel, so a click on it
/// does not activate VoiceFlow (which would take the front from the target
/// app). Compiles in CI; behaviour needs a real Mac (see LIVE-CHECK.md).
#[cfg(target_os = "macos")]
mod platform {
    use super::{External, WindowRect};
    pub use super::tauri_screens::{screen_at_cursor, screens};
    use objc2::ffi::object_setClass;
    use objc2::msg_send;
    use objc2::runtime::{AnyClass, AnyObject, Bool, ClassBuilder, Sel};
    use objc2::sel;
    use objc2_app_kit::NSWorkspace;
    use std::sync::OnceLock;
    use tauri::{PhysicalPosition, PhysicalSize, WebviewWindow};

    /// NSWindowStyleMaskNonactivatingPanel.
    const NONACTIVATING_PANEL: usize = 1 << 7;
    /// NSStatusWindowLevel: above normal and floating windows.
    const STATUS_WINDOW_LEVEL: isize = 25;
    /// canJoinAllSpaces | stationary | ignoresCycle | fullScreenAuxiliary.
    const COLLECTION_BEHAVIOR: usize = (1 << 0) | (1 << 4) | (1 << 6) | (1 << 8);

    extern "C" fn no(_this: &AnyObject, _sel: Sel) -> Bool {
        Bool::NO
    }

    fn panel_class() -> Option<&'static AnyClass> {
        static CLASS: OnceLock<Option<usize>> = OnceLock::new();
        let ptr = CLASS.get_or_init(|| {
            let superclass = AnyClass::get(c"NSPanel")?;
            let mut builder = ClassBuilder::new(c"VoiceFlowBarPanel", superclass)?;
            unsafe {
                builder.add_method(
                    sel!(canBecomeKeyWindow),
                    no as extern "C" fn(_, _) -> _,
                );
                builder.add_method(
                    sel!(canBecomeMainWindow),
                    no as extern "C" fn(_, _) -> _,
                );
            }
            Some(builder.register() as *const AnyClass as usize)
        });
        ptr.map(|p| unsafe { &*(p as *const AnyClass) })
    }

    fn ns_window(window: &WebviewWindow) -> Option<*mut AnyObject> {
        window
            .ns_window()
            .ok()
            .filter(|p| !p.is_null())
            .map(|p| p as *mut AnyObject)
    }

    /// Turns the tao window into the panel. Runs on the main thread.
    pub fn prepare(window: &WebviewWindow) {
        let w = window.clone();
        let _ = window.run_on_main_thread(move || unsafe {
            let Some(ns) = ns_window(&w) else { return };
            let Some(class) = panel_class() else {
                log::warn!("[bar] NSPanel class not available; the bar may activate VoiceFlow");
                return;
            };
            object_setClass(ns, class);
            let obj = &*ns;
            let style: usize = msg_send![obj, styleMask];
            let _: () = msg_send![obj, setStyleMask: style | NONACTIVATING_PANEL];
            let _: () = msg_send![obj, setFloatingPanel: Bool::YES];
            let _: () = msg_send![obj, setBecomesKeyOnlyIfNeeded: Bool::YES];
            let _: () = msg_send![obj, setHidesOnDeactivate: Bool::NO];
            let _: () = msg_send![obj, setLevel: STATUS_WINDOW_LEVEL];
            let _: () = msg_send![obj, setCollectionBehavior: COLLECTION_BEHAVIOR];
        });
    }

    pub fn foreground() -> Option<Option<External>> {
        let app = NSWorkspace::sharedWorkspace().frontmostApplication()?;
        let pid = app.processIdentifier();
        if pid <= 0 {
            return None;
        }
        if pid as u32 == std::process::id() {
            return Some(None);
        }
        Some(Some(External {
            window_id: None,
            pid: Some(pid as u32),
        }))
    }

    pub fn screen_of_window(_window_id: &str) -> Option<String> {
        None
    }

    pub fn show_at(window: &WebviewWindow, rect: WindowRect) {
        let _ = window.set_size(PhysicalSize::new(rect.width, rect.height));
        let _ = window.set_position(PhysicalPosition::new(rect.x, rect.y));
        let w = window.clone();
        let _ = window.run_on_main_thread(move || unsafe {
            if let Some(ns) = ns_window(&w) {
                let _: () = msg_send![&*ns, orderFrontRegardless];
            }
        });
    }

    pub fn hide(window: &WebviewWindow) {
        let w = window.clone();
        let _ = window.run_on_main_thread(move || unsafe {
            if let Some(ns) = ns_window(&w) {
                let nil: *const AnyObject = std::ptr::null();
                let _: () = msg_send![&*ns, orderOut: nil];
            }
        });
    }

    /// No window regions here: the fully transparent room above the pill is
    /// left to the window server (to confirm on a Mac, LIVE-CHECK.md A2).
    pub fn set_region(_window: &WebviewWindow, _rects: &[(i32, i32, i32, i32)]) -> bool {
        true
    }
}

#[cfg(not(any(target_os = "windows", target_os = "macos")))]
mod platform {
    use super::{External, WindowRect};
    pub use super::tauri_screens::{screen_at_cursor, screens};
    use tauri::{PhysicalPosition, PhysicalSize, WebviewWindow};

    pub fn prepare(_window: &WebviewWindow) {}
    pub fn foreground() -> Option<Option<External>> {
        None
    }
    pub fn screen_of_window(_window_id: &str) -> Option<String> {
        None
    }
    pub fn show_at(window: &WebviewWindow, rect: WindowRect) {
        let _ = window.set_size(PhysicalSize::new(rect.width, rect.height));
        let _ = window.set_position(PhysicalPosition::new(rect.x, rect.y));
        let _ = window.show();
    }
    pub fn hide(window: &WebviewWindow) {
        let _ = window.hide();
    }
    pub fn set_region(_window: &WebviewWindow, _rects: &[(i32, i32, i32, i32)]) -> bool {
        true
    }
}

/// Monitors through Tauri (macOS, Linux). A monitor's name is its model, so
/// two alike would share it: the id is the name and where it sits.
#[cfg(not(target_os = "windows"))]
mod tauri_screens {
    use super::{Screen, WorkArea};
    use tauri::AppHandle;

    fn id_of(monitor: &tauri::Monitor) -> String {
        let p = monitor.position();
        let s = monitor.size();
        let name = monitor.name().cloned().unwrap_or_default();
        format!("{name}@{},{} {}x{}", p.x, p.y, s.width, s.height)
    }

    pub fn screens(app: &AppHandle) -> Option<Vec<Screen>> {
        let primary = app.primary_monitor().ok().flatten().map(|m| id_of(&m));
        let all: Vec<Screen> = app
            .available_monitors()
            .ok()?
            .iter()
            .map(|m| {
                let area = m.work_area();
                let id = id_of(m);
                Screen {
                    primary: primary.as_deref() == Some(id.as_str()),
                    id,
                    area: WorkArea {
                        x: area.position.x,
                        y: area.position.y,
                        width: area.size.width,
                        height: area.size.height,
                        scale: m.scale_factor(),
                    },
                }
            })
            .collect();
        (!all.is_empty()).then_some(all)
    }

    pub fn screen_at_cursor(app: &AppHandle) -> Option<String> {
        let p = app.cursor_position().ok()?;
        app.monitor_from_point(p.x, p.y).ok().flatten().map(|m| id_of(&m))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FHD: WorkArea = WorkArea { x: 0, y: 0, width: 1920, height: 1040, scale: 1.0 };

    #[test]
    fn bottom_centre_sits_above_the_taskbar() {
        let r = place(FHD, (500.0, 100.0), BarPosition::BottomCenter);
        assert_eq!(r, WindowRect { x: 710, y: 1040 - 8 - 100, width: 500, height: 100 });
    }

    #[test]
    fn scale_applies_to_size_and_gap() {
        let area = WorkArea { x: 0, y: 0, width: 2880, height: 1704, scale: 1.5 };
        let r = place(area, (400.0, 80.0), BarPosition::BottomCenter);
        assert_eq!((r.width, r.height), (600, 120));
        assert_eq!(r.y, 1704 - 12 - 120);
        assert_eq!(r.x, (2880 - 600) / 2);
    }

    #[test]
    fn second_monitor_offsets_and_sides() {
        // A monitor to the left of the primary one, taskbar at the bottom.
        let area = WorkArea { x: -1920, y: 0, width: 1920, height: 1032, scale: 1.0 };
        let left = place(area, (300.0, 60.0), BarPosition::BottomLeft);
        assert_eq!((left.x, left.y), (-1920 + 8, 1032 - 8 - 60));
        let right = place(area, (300.0, 60.0), BarPosition::BottomRight);
        assert_eq!(right.x, -8 - 300);
    }

    #[test]
    fn oversized_content_is_clamped_to_the_work_area() {
        let small = WorkArea { x: 0, y: 0, width: 300, height: 200, scale: 1.0 };
        let r = place(small, (900.0, 400.0), BarPosition::BottomCenter);
        assert_eq!((r.width, r.height), (300, 200));
        let bad = WorkArea { scale: f64::NAN, ..FHD };
        assert_eq!(place(bad, (100.0, 50.0), BarPosition::BottomCenter).width, 100);
    }

    fn screen(id: &str, area: WorkArea, primary: bool) -> Screen {
        Screen { id: id.into(), area, primary }
    }

    /// A main 1080p monitor, a 1440p one at 125 % to its right and a 4K
    /// one at 150 % to its left (negative coordinates).
    fn desk() -> Vec<Screen> {
        vec![
            screen("\\\\.\\DISPLAY2", WorkArea { x: 1920, y: 0, width: 2560, height: 1392, scale: 1.25 }, false),
            screen("\\\\.\\DISPLAY1", FHD, true),
            screen("\\\\.\\DISPLAY3", WorkArea { x: -3840, y: -600, width: 3840, height: 2088, scale: 1.5 }, false),
        ]
    }

    #[test]
    fn show_on_picks_the_monitor_with_fallbacks() {
        let all = desk();
        let id = |s: Option<&Screen>| s.map(|s| s.id.clone());
        let d1 = Some("\\\\.\\DISPLAY1".to_string());
        let d3 = Some("\\\\.\\DISPLAY3".to_string());
        let d2 = Some("\\\\.\\DISPLAY2".to_string());
        // Main monitor: whatever the mouse or the app.
        assert_eq!(id(choose_screen(&all, BarMonitor::Primary, d2.as_deref(), d3.as_deref())), d1);
        // The mouse's monitor, else the main one.
        assert_eq!(id(choose_screen(&all, BarMonitor::Mouse, d2.as_deref(), d3.as_deref())), d3);
        assert_eq!(id(choose_screen(&all, BarMonitor::Mouse, None, None)), d1);
        assert_eq!(id(choose_screen(&all, BarMonitor::Mouse, None, Some("gone"))), d1);
        // The app's monitor, else the mouse's, else the main one.
        assert_eq!(id(choose_screen(&all, BarMonitor::Target, d2.as_deref(), d3.as_deref())), d2);
        assert_eq!(id(choose_screen(&all, BarMonitor::Target, None, d3.as_deref())), d3);
        assert_eq!(id(choose_screen(&all, BarMonitor::Target, Some("gone"), None)), d1);
        // No main flag (odd driver): the first monitor; none at all: none.
        let flat: Vec<Screen> = all.iter().cloned().map(|s| Screen { primary: false, ..s }).collect();
        assert_eq!(id(choose_screen(&flat, BarMonitor::Primary, None, None)), d2);
        assert!(choose_screen(&[], BarMonitor::Target, None, None).is_none());
    }

    #[test]
    fn a_shown_bar_stays_on_its_monitor_and_falls_back_to_the_main_one() {
        let all = desk();
        let d3 = "\\\\.\\DISPLAY3";
        // Unchanged: the same monitor, the same geometry.
        assert_eq!(relocate(&all, d3), all.iter().find(|s| s.id == d3));
        // Rescaled: the same monitor, with its new geometry.
        let mut rescaled = all.clone();
        rescaled[2].area = WorkArea { scale: 2.0, width: 3840, height: 2064, ..rescaled[2].area };
        assert_eq!(relocate(&rescaled, d3).map(|s| s.area.scale), Some(2.0));
        // Unplugged: the main monitor, never another secondary one.
        let unplugged: Vec<Screen> = all.iter().filter(|s| s.id != d3).cloned().collect();
        assert_eq!(relocate(&unplugged, d3).map(|s| s.primary), Some(true));
        // The whole desk changed and has no main flag: still somewhere.
        assert!(relocate(&[screen("x", FHD, false)], d3).is_some());
        assert!(relocate(&[], d3).is_none());
    }

    #[test]
    fn an_auto_hidden_taskbar_is_kept_clear_on_any_edge() {
        let monitor = (0, 0, 1920, 1080);
        // Auto-hidden at the bottom: the work area is the whole monitor.
        assert_eq!(clear_of_autohide(monitor, monitor, (0, 0, 0, 48)), (0, 0, 1920, 1032));
        // At the top, or on the left at 150 % (72 px).
        assert_eq!(clear_of_autohide(monitor, monitor, (0, 72, 0, 0)), (0, 72, 1920, 1080));
        assert_eq!(clear_of_autohide(monitor, monitor, (72, 0, 0, 0)), (72, 0, 1920, 1080));
        // A taskbar that reserves its space already moved that edge: no change.
        assert_eq!(clear_of_autohide((0, 0, 1920, 1032), monitor, (0, 0, 0, 48)), (0, 0, 1920, 1032));
        // A secondary monitor at negative coordinates.
        let left = (-2560, -200, 0, 1240);
        assert_eq!(clear_of_autohide(left, left, (0, 0, 0, 60)), (-2560, -200, 0, 1180));
        // A wild value never eats the screen.
        assert_eq!(clear_of_autohide(monitor, monitor, (0, 0, 0, 5000)), (0, 0, 1920, 1080 - 270));
    }

    #[test]
    fn top_mirrors_bottom_on_every_monitor_and_scale() {
        for s in desk() {
            let a = s.area;
            for (bottom, top) in [
                (BarPosition::BottomCenter, BarPosition::TopCenter),
                (BarPosition::BottomLeft, BarPosition::TopLeft),
                (BarPosition::BottomRight, BarPosition::TopRight),
            ] {
                let b = place(a, (512.0, 400.0), bottom);
                let t = place(a, (512.0, 400.0), top);
                let gap = (EDGE_GAP * a.scale).round() as i32;
                // Same size and side; the gap from its own edge is the same.
                assert_eq!((t.x, t.width, t.height), (b.x, b.width, b.height));
                assert_eq!(t.y, a.y + gap);
                assert_eq!(b.y + b.height as i32, a.y + a.height as i32 - gap);
                // Inside the work area, whatever its sign.
                for r in [b, t] {
                    assert!(r.x >= a.x && r.x + r.width as i32 <= a.x + a.width as i32);
                    assert!(r.y >= a.y && r.y + r.height as i32 <= a.y + a.height as i32);
                }
            }
        }
        // 150 %: the 512 x 400 window is 768 x 600 physical px.
        let r = place(desk()[2].area, (512.0, 400.0), BarPosition::TopCenter);
        assert_eq!((r.x, r.y, r.width, r.height), (-3840 + (3840 - 768) / 2, -600 + 12, 768, 600));
        assert!(BarPosition::TopRight.is_top() && !BarPosition::BottomCenter.is_top());
    }

    #[test]
    fn a_small_work_area_keeps_the_window_inside_it() {
        // 1280 x 720 at 175 %, taskbar at the top: the 400 px page is 700 px.
        let small = WorkArea { x: 0, y: 42, width: 1280, height: 678, scale: 1.75 };
        for p in [BarPosition::BottomCenter, BarPosition::TopRight, BarPosition::BottomLeft] {
            let r = place(small, (512.0, 400.0), p);
            assert_eq!(r.height, 678);
            assert_eq!(r.y, 42, "{p:?}: the gap goes, not the edge");
            assert!(r.x >= 0 && r.x + r.width as i32 <= 1280);
        }
    }

    #[test]
    fn show_on_and_position_parse_from_settings() {
        let m: BarMonitor = serde_json::from_str("\"target\"").unwrap();
        assert_eq!(m, BarMonitor::Target);
        assert_eq!(BarMonitor::default(), BarMonitor::Primary);
        let p: BarPosition = serde_json::from_str("\"top-center\"").unwrap();
        assert_eq!(p, BarPosition::TopCenter);
    }

    #[test]
    fn a_dictation_is_placed_once_whatever_size_is_asked_later() {
        let mut p = Placement::default();
        let first = p.rect(FHD, (352.0, 260.0), BarPosition::BottomCenter);
        // Later presents in the same dictation (other states, a stray size,
        // another setting) get the same rectangle.
        assert_eq!(p.rect(FHD, (700.0, 110.0), BarPosition::BottomCenter), first);
        assert_eq!(p.rect(FHD, (351.4, 259.6), BarPosition::BottomRight), first);
        // A display change places it again.
        let scaled = WorkArea { scale: 1.5, width: 2880, height: 1560, ..FHD };
        let moved = p.rect(scaled, (352.0, 260.0), BarPosition::BottomCenter);
        assert_eq!((moved.width, moved.height), (528, 390));
        // A new dictation places it again too.
        p.reset();
        let second = WorkArea { x: 1920, ..FHD };
        assert_eq!(
            p.rect(second, (352.0, 260.0), BarPosition::BottomCenter).x,
            1920 + (1920 - 352) / 2
        );
        assert_eq!(p.current().map(|(_, s)| s), Some(1.0));
    }

    #[test]
    fn hit_rects_scale_to_the_window_region() {
        let band = HitRect { x: 0.0, y: 184.0, width: 352.0, height: 76.0 };
        assert_eq!(region_rects(&[band], 1.0, (352, 260)), vec![(0, 184, 352, 260)]);
        assert_eq!(region_rects(&[band], 1.5, (528, 390)), vec![(0, 276, 528, 390)]);
        // 125 %: edges round outwards and stay inside the window.
        let pop = HitRect { x: 0.0, y: 3.3, width: 352.0, height: 180.9 };
        assert_eq!(region_rects(&[pop], 1.25, (440, 325)), vec![(0, 4, 440, 231)]);
        let wild = HitRect { x: -20.0, y: 250.0, width: 900.0, height: 90.0 };
        assert_eq!(region_rects(&[wild], 1.0, (352, 260)), vec![(0, 250, 352, 260)]);
        let empty = HitRect { x: 10.0, y: 10.0, width: 0.0, height: 5.0 };
        let nan = HitRect { x: f64::NAN, ..band };
        assert!(region_rects(&[empty, nan], 1.0, (352, 260)).is_empty());
    }

    /// Harness (Windows, run by hand): a window region leaves the room above
    /// the pill to the window below: hit-testing finds the window only inside
    /// its region. Off-screen, never shown on the desktop, no clicks.
    /// `cargo test --lib bar::tests::live_region_passes_clicks_through -- --ignored --nocapture`
    #[cfg(target_os = "windows")]
    #[test]
    #[ignore]
    fn live_region_passes_clicks_through() {
        use windows_sys::Win32::Foundation::POINT;
        use windows_sys::Win32::Graphics::Gdi::{CreateRectRgn, DeleteObject, GetWindowRgn};
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            CreateWindowExW, DestroyWindow, SetWindowPos, WindowFromPoint, HWND_TOPMOST,
            SWP_NOACTIVATE, SWP_SHOWWINDOW, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST,
            WS_POPUP,
        };
        let class: Vec<u16> = "STATIC\0".encode_utf16().collect();
        let (x0, y0) = (-30000, -30000);
        let hwnd = unsafe {
            CreateWindowExW(
                WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST,
                class.as_ptr(),
                std::ptr::null(),
                WS_POPUP,
                x0,
                y0,
                352,
                260,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null(),
            )
        };
        assert!(!hwnd.is_null());
        unsafe { SetWindowPos(hwnd, HWND_TOPMOST, x0, y0, 352, 260, SWP_NOACTIVATE | SWP_SHOWWINDOW) };
        let at = |x: i32, y: i32| unsafe { WindowFromPoint(POINT { x: x0 + x, y: y0 + y }) };
        let whole = at(176, 80) == hwnd;
        let band = HitRect { x: 0.0, y: 184.0, width: 352.0, height: 76.0 };
        assert!(super::platform::set_region_hwnd(hwnd, &region_rects(&[band], 1.0, (352, 260))));
        let copy = unsafe { CreateRectRgn(0, 0, 0, 0) };
        assert_ne!(unsafe { GetWindowRgn(hwnd, copy) }, 0, "no region on the window");
        unsafe { DeleteObject(copy) };
        if whole {
            // Off-screen points still hit-test here: the region must decide.
            assert_eq!(at(176, 220), hwnd, "the pill must catch clicks");
            assert_ne!(at(176, 80), hwnd, "the room above must let clicks through");
        } else {
            println!("(off-screen hit-testing unavailable; region set and read back only)");
        }
        // Clearing it restores the whole window.
        assert!(super::platform::set_region_hwnd(hwnd, &[]));
        let copy = unsafe { CreateRectRgn(0, 0, 0, 0) };
        assert_eq!(unsafe { GetWindowRgn(hwnd, copy) }, 0, "region not cleared");
        unsafe { DeleteObject(copy) };
        unsafe { DestroyWindow(hwnd) };
        println!("window region set, read back and cleared (hit-test checked: {whole})");
    }

    #[test]
    fn position_parses_from_settings() {
        let p: BarPosition = serde_json::from_str("\"bottom-right\"").unwrap();
        assert_eq!(p, BarPosition::BottomRight);
    }

    fn ext(id: &str) -> External {
        External { window_id: Some(id.into()), pid: Some(7) }
    }

    #[test]
    fn a_click_that_left_voiceflow_in_front_restores_the_app_before_it() {
        let t0 = Instant::now();
        let history = vec![
            (t0, Some(ext("1"))),
            (t0 + Duration::from_millis(50), Some(ext("2"))),
            // The click activated VoiceFlow; this sample came after it.
            (t0 + Duration::from_millis(100), None),
        ];
        let click = t0 + Duration::from_millis(120);
        assert_eq!(
            displacement(&history, click, true, None),
            Displacement::Displaced(ext("2"))
        );
    }

    #[test]
    fn no_restore_when_voiceflow_was_already_in_front_or_is_not_now() {
        let t0 = Instant::now();
        let history = vec![(t0, None)];
        let click = t0 + Duration::from_millis(200);
        assert_eq!(displacement(&history, click, true, Some(&ext("9"))), Displacement::NotDisplaced);
        let history = vec![(t0, Some(ext("1")))];
        assert_eq!(displacement(&history, click, false, None), Displacement::NotDisplaced);
    }

    #[test]
    fn without_earlier_samples_the_target_is_the_fallback() {
        let t0 = Instant::now();
        let click = t0 + Duration::from_millis(60);
        // Only a sample taken right at the click: too late to trust.
        let history = vec![(t0 + Duration::from_millis(50), None)];
        assert_eq!(
            displacement(&history, click, true, Some(&ext("5"))),
            Displacement::Displaced(ext("5"))
        );
        assert_eq!(displacement(&history, click, true, None), Displacement::Unknown);
    }

    #[test]
    fn a_long_drag_still_restores_the_app_that_was_in_front_at_the_press() {
        let t0 = Instant::now();
        // Kept at pointer-down: the user's app was in front before the press.
        let pre = (t0, Some(ext("mail")));
        // Two seconds of dragging later the short history holds only later
        // samples (VoiceFlow in front after the press).
        let history: Vec<Sample> = (1..=20)
            .map(|n| (t0 + Duration::from_millis(1000 + 50 * n), None))
            .collect();
        let click = t0 + Duration::from_millis(60);
        let merged = with_pre_click(&history, Some(&pre));
        assert_eq!(merged.len(), 21);
        assert!(merged.windows(2).all(|w| w[0].0 <= w[1].0), "oldest first");
        // Not the dictation's original target ("slack"): the app in front at the press.
        assert_eq!(
            displacement(&merged, click, true, Some(&ext("slack"))),
            Displacement::Displaced(ext("mail"))
        );
        // Already in the history: not added twice.
        assert_eq!(with_pre_click(&merged, Some(&pre)).len(), 21);
        assert_eq!(with_pre_click(&history, None).len(), 20);
    }

    /// Harness (Windows, run by hand): shows, moves, resizes and hides a
    /// window with the bar's styles and calls, off-screen, and checks that the
    /// foreground window never changes. It does not click or type.
    ///
    /// Limit: a test process has no right to take the foreground anyway, so
    /// this proves the calls are non-activating only together with the live
    /// check in docs/desktop/LIVE-CHECK.md (A2), where VoiceFlow has just
    /// received its hotkey and could take the front.
    /// `cargo test --lib bar::tests::live_bar_calls_keep_the_foreground -- --ignored --nocapture`
    #[cfg(target_os = "windows")]
    #[test]
    #[ignore]
    fn live_bar_calls_keep_the_foreground() {
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            CreateWindowExW, DestroyWindow, GetForegroundWindow, SetWindowPos, ShowWindow,
            HWND_TOPMOST, SWP_NOACTIVATE, SWP_SHOWWINDOW, SW_HIDE, WS_EX_NOACTIVATE,
            WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_POPUP,
        };
        let class: Vec<u16> = "STATIC\0".encode_utf16().collect();
        let before = unsafe { GetForegroundWindow() };
        let hwnd = unsafe {
            CreateWindowExW(
                WS_EX_NOACTIVATE | WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
                class.as_ptr(),
                std::ptr::null(),
                WS_POPUP,
                -32000,
                -32000,
                400,
                90,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null(),
            )
        };
        assert!(!hwnd.is_null(), "could not create the test window");
        // The bar's hook: the mouse never activates it.
        assert!(super::platform::refuse_mouse_activation(hwnd), "subclass not installed");
        let answer = unsafe {
            windows_sys::Win32::UI::WindowsAndMessaging::SendMessageW(
                hwnd,
                windows_sys::Win32::UI::WindowsAndMessaging::WM_MOUSEACTIVATE,
                hwnd as usize,
                0,
            )
        };
        assert_eq!(
            answer,
            windows_sys::Win32::UI::WindowsAndMessaging::MA_NOACTIVATE as isize,
            "WM_MOUSEACTIVATE must answer MA_NOACTIVATE"
        );
        let mut worst = Duration::ZERO;
        for i in 0..20 {
            let started = Instant::now();
            unsafe {
                SetWindowPos(
                    hwnd,
                    HWND_TOPMOST,
                    -32000 + i,
                    -32000,
                    400 + i,
                    90 + (i % 3) * 40,
                    SWP_NOACTIVATE | SWP_SHOWWINDOW,
                );
            }
            worst = worst.max(started.elapsed());
            assert_eq!(unsafe { GetForegroundWindow() }, before, "show/move {i} took the foreground");
            if i % 5 == 4 {
                unsafe { ShowWindow(hwnd, SW_HIDE) };
                assert_eq!(unsafe { GetForegroundWindow() }, before, "hide {i} changed the foreground");
            }
        }
        unsafe { DestroyWindow(hwnd) };
        println!("20 show/move/resize calls kept the foreground; slowest {worst:?}");
    }
}
