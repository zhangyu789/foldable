# DUO Fold Transition

Android demo of a **foldable hinge transition**: when you start folding the device, an [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) captures the inner display with `takeScreenshot()`, then plays a perspective + right-edge Gaussian blur animation as the cover wakes up.

Inspired by the capture / cover hand-off flow in projects like Fold8 Hinge Fade, with additional perspective stretch and blur tuned for a left-hinge model.

## Features

- Screenshot via Accessibility (`canTakeScreenshot`) — no MediaProjection consent dialog per session
- Overlay on the default display while folding; hand-off to the cover `Presentation` when it turns on
- Left-hinge perspective: left edge fixed, right edge foreshortens and stretches past the screen edge
- Right-side Gaussian blur that grows with fold progress
- Runtime tuning sliders: stretch, rotation factor, hinge factor
- On-device preview without folding (captures the settings screen)

## Requirements

| Item | Value |
|------|--------|
| minSdk | 30 (Accessibility `takeScreenshot`) |
| targetSdk | 36 |
| Device | Foldable with `Sensor.TYPE_HINGE_ANGLE` recommended (e.g. Pixel Fold) |
| Permissions | Draw over other apps + Accessibility service enabled |

## Setup

1. Open the project in Android Studio and sync Gradle.
2. Build and install a debug APK:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

3. Launch **DUO Fold Transition**.
4. Grant **Display over other apps**.
5. Open **Accessibility settings** and enable **DUO Fold Transition**.
6. Unfold the phone flat, then start folding — the service captures the inner screen and runs the transition.

Use **Preview effect** on the home screen to scrub 0°→90° without folding.

## How it works

```
Hinge flat (~180°)
    │
    ▼ angle drops below ~168°
takeScreenshot(DEFAULT_DISPLAY)   ← AccessibilityService
    │
    ▼
TYPE_APPLICATION_OVERLAY + FoldableScreenView
    ├── polyToPoly perspective (stretch + foreshorten)
    └── right-edge Gaussian blur
    │
    ▼ cover display turns on
Presentation on cover → short hold → fade out
    │
    ▼ unfold past ~172°
re-arm for the next fold
```

### Tunable parameters (`FoldMath`)

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `rightStretchMax` | 0.79 | Extra right extension as a fraction of screen width |
| `rotationFactor` | 0.75 | Visual angle = anim angle × factor |
| `hingeAngleFactor` | 0.70 | Anim angle = mapped hinge progress × 90° × factor |

## Project layout

```
app/src/main/java/com/duo/foldable/
  HingeCaptureService.kt      # Accessibility service, hinge, screenshot, overlays
  MainActivity.kt             # Setup UI, permissions, preview, sliders
  FoldableScreenView.kt       # Perspective transform + right blur
  FoldMath.kt                 # Pure geometry / tuning knobs
  GaussianBlur.kt             # Software right-edge blur helper
  FoldTransitionController.kt # Optional in-app dual-screen compositor
  ...
```

## Privacy

Screenshots are kept in memory for a single fold cycle, used only for the overlay animation, and are not written to disk or uploaded.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## License

Add your preferred license before publishing (e.g. MIT / Apache-2.0).
