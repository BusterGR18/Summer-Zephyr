# Projection scenes

## Modes

`HudSceneMode` separates the fixed 800x400 transport from the content source:

- `CUSTOM`: MapLibre/media theme composer.
- `APP_CAST`: Android 14 MediaProjection user-choice picker.
- `SCREEN_CAST`: MediaProjection restricted to the default display.
- `ANDROID_AUTO`: receiver integration boundary; currently unavailable.

## MediaProjection path

1. The Compose screen asks Android for capture consent.
2. The granted result is sent to the already-running `HudStreamService`.
3. The service promotes itself to the combined `connectedDevice` and
   `mediaProjection` foreground-service types.
4. `HudMediaProjectionCapture` creates an 800x400 `VirtualDisplay` backed by
   an `ImageReader`.
5. Capture is throttled to 30 fps and copied into two reusable bitmaps.
6. `HudProjectionSceneComposer` exposes the latest bitmap to the established
   Canvas renderer and H.264 encoder.

This deliberately reuses the tested MotoPlay transport instead of adding a
second encoder/session implementation.

## Android Auto boundary

MOTO-HUB's embedded Android Auto implementation is not a simple screen-capture
scene. It contains an Android Auto Projection receiver, TLS/head-unit identity,
protocol services, video decoding, OpenGL composition, touch input, and a
second encode path. Its public repository intentionally omits `aa_cert` and
`aa_identity_data`, and notes unresolved redistribution constraints around
part of the research source.

`AndroidAutoBridge` is the integration boundary for a future independently
reviewed receiver module. The user-facing scene reports the missing capability
rather than silently failing or claiming Android Auto support.
