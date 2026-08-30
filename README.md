# Screen Recorder Mod
**Fabric 1.21.1 · MobileGlues renderer · Zalith Launcher / DroidBridge**

OBS-style screen recorder for Minecraft on Android. Records the **complete screen** — world, hotbar, health, hunger, armour, XP bar, item cooldowns, boss bars, everything — with zero encoding lag during gameplay.

---

## How it works

| Phase | What happens | CPU cost |
|---|---|---|
| **Recording** | Captures GL framebuffer at 460p, dumps raw frames to `.rawvid` file | Very low — just a GPU read + disk write |
| **Rendering** | You open the Gallery, hit "Render to MP4", FFmpeg encodes it | High — but done offline, not during play |

You play normally, record with no lag, then render later when you're done.

---

## What gets recorded (full OBS-style capture)

Because we read the actual rendered framebuffer:
- ✅ Game world, entities, particles
- ✅ **Hotbar** — all slots, selected item highlight
- ✅ **Item cooldown triangles** — pie-slice overlays on pearls, rods, etc.
- ✅ **Health bar** (normal + withered hearts)
- ✅ **Hunger bar**
- ✅ **Armour bar**
- ✅ **Oxygen bar** (when underwater)
- ✅ **XP bar + level number**
- ✅ **Boss bars** (Ender Dragon, Wither, etc.)
- ✅ **Status effect icons**
- ✅ Chat, crosshair, scoreboard, tab list
- ✅ Any HUD added by other mods

---

## Requirements

- Fabric Loader ≥ 0.15.11
- Fabric API for 1.21.1
- PojavLauncher FFmpeg plugin **or** Zalith Launcher's built-in FFmpeg (for rendering)
- MobileGlues renderer (Zalith / DroidBridge) — also works on desktop OpenGL

---

## Main menu buttons (bottom-left)

```
▶ Start Recording      — idle state
⏸ Pause Recording      — while recording
▶ Resume Recording     — while paused
⏹ Stop Recording       — while recording or paused
🎬 Recordings          — always (opens gallery)
⚙ Settings            — always
```

## Keybindings

| Key | Action |
|---|---|
| F9  | Start / Resume |
| F10 | Pause |
| F11 | Stop |

Rebindable in Options → Controls → Screen Recorder.

---

## Recording Gallery

Open with **🎬 Recordings** from the main menu.

- **[🎬 Render to MP4]** — encodes the raw recording to MP4 via your FFmpeg plugin
- **[▶ Play MP4]** — opens finished video in your system player / Android video app
- **[📁 Folder]** — opens the recordings folder
- **[🗑 Delete]** — deletes the `.rawvid` + MP4 (with confirm prompt)
- Green **`MP4 ✓`** badge appears once rendered
- Thumbnails are extracted from the first frame of each recording (no FFmpeg needed for `.rawvid` thumbnails)

---

## Output files

```
.minecraft/recordings/
  recording_2024-06-15_14-32-07.rawvid   ← raw frames (large, delete after render)
  recording_2024-06-15_14-32-07.mp4      ← rendered video (created by Gallery)
```

`.rawvid` files are large (raw uncompressed RGBA at 460p). Delete them after rendering to free space.

On Zalith / DroidBridge, `.minecraft` is in the launcher's app data directory.

---

## Building (GitHub Codespace)

```bash
unzip screenrecorder-mod.zip
cd screenrecorder-mod
./gradlew build
# Output: build/libs/screenrecorder-1.0.0.jar
```

Copy the JAR to your launcher's `mods/` folder alongside Fabric API.

---

## Project structure

```
src/main/java/com/screenrecorder/
  ScreenRecorderMod.java              Mod init, keybinding registration
  ScreenRecorderClient.java           Tick handler for keybindings
  mixin/
    GameRendererMixin.java            Hooks before swapBuffers — captures complete HUD
    InGameHudMixin.java               REC/PAUSED indicator + elapsed timer on HUD
    TitleScreenMixin.java             Main menu control buttons
  recording/
    RecordingManager.java             State machine, frame-rate cap, .rawvid output
    RecordingState.java               IDLE / RECORDING / PAUSED
  screen/
    RecordingGalleryScreen.java       Gallery — browse, render, play, delete
    RecordingSettingsScreen.java      FPS setting
    RecordingEntry.java               Metadata for one .rawvid file
    ThumbnailCache.java               Async thumbnail from first .rawvid frame or MP4
  util/
    FrameCapture.java                 glReadPixels + bilinear scale to 460p
    RawFrameWriter.java               Async disk IO thread for raw frame data
    PojavFFmpegRenderer.java          Pipes .rawvid → FFmpeg → MP4
    PojavFfmpegLocator.java           Finds FFmpeg binary (Zalith / Pojav / PATH)
```
