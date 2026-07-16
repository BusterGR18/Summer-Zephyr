# Private Android Auto scene

The normal project build does not contain an Android Auto receiver or a
head-unit identity. Android Auto is enabled only when both inputs are supplied
explicitly at build time.

## Design

The private scene uses the existing Summer Zephyr transport and fixed 800×400
encoder:

1. A local Android Auto Projection receiver listens on `127.0.0.1:5288`.
2. Android Auto self-mode connects to that local receiver.
3. The receiver decodes Android Auto's 800×480 H.264 stream to a Surface.
4. `AndroidAutoFrameSink` samples the decoder Surface through an external OES
   texture and publishes frames to the existing scene renderer.
5. The existing Fit/Fill scene control maps the 800×480 image to 800×400.
6. Summer Zephyr sends the final encoded stream to the motorcycle TFT.

The first integration is deliberately video-only. Android Auto audio, phone
preview controls, TFT touch, and handlebar controls remain future modules.

## 1. Import the receiver source locally

Clone MOTO-HUB beside this project, or use an existing checkout:

```bash
git clone https://github.com/vincenzobpt/MOTO-HUB.git
```

Then, from this project:

```bash
./tooling/android-auto/import-motohub-receiver.sh /path/to/MOTO-HUB
```

The script copies the receiver into:

```text
tooling/private/android-auto/vendor-src/
```

That directory is ignored by Git and is compiled only with the private Gradle
property. Review the upstream source and its notices before distributing a
build containing it.

## 2. Install an authorized identity

Prepare an X.509 certificate and matching RSA private key that you are
permitted to use. Do not commit or publish the key.

```bash
./tooling/android-auto/install-identity.sh \
  /path/to/head-unit-cert.pem \
  /path/to/head-unit-private-key.pem
```

The normalized build inputs are written to:

```text
tooling/private/android-auto/aa_cert
tooling/private/android-auto/aa_identity_data
```

The project does not include or copy credentials from another repository.

## 3. Build the private APK

```bash
./gradlew \
  -PincludeAndroidAutoReceiver=true \
  -PincludeAndroidAutoIdentity=true \
  assemblePrivateAndroidAutoDebug
```

The APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A normal build remains identity-free:

```bash
./gradlew assembleDebug
```

## 4. Test while stationary

1. Connect the custom dashboard to the motorcycle.
2. Open **Projection scenes → Android Auto**.
3. The foreground service starts the local receiver.
4. The foreground Activity triggers Android Auto self-mode.
5. Wait for **Android Auto** to replace the waiting scene.
6. Test both Fit and Fill.
7. Stop Android Auto and confirm the custom dashboard returns.

Export the diagnostics log after every test. Useful stages begin with
`ANDROID_AUTO` or `[AA]`.

## Known limitations

- Android Auto identity acceptance is not guaranteed for arbitrary
  certificates. The build plumbing validates format and key matching, not
  Google's acceptance policy.
- The first frame path performs an EGL readback before the existing Canvas
  renderer. This is intentionally conservative for initial integration and can
  later be replaced by a direct surface-to-surface compositor.
- No Android Auto audio sink/source is connected to the app UI yet. The
  imported receiver advertises the minimum channels needed by its tested
  profile, but this project treats the scene as video-only.
- TFT touch and handlebar input are not forwarded yet.
- Configure and test only while parked; do not use this experimental build as
  the sole navigation source.
