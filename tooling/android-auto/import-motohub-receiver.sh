#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  tooling/android-auto/import-motohub-receiver.sh /path/to/MOTO-HUB

Imports the video-only Android Auto receiver source into the ignored private
build-input directory. No certificate or private key is copied.
USAGE
}

if [[ $# -ne 1 ]]; then
  usage
  exit 2
fi

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_repo="$(cd "$1" && pwd)"
source_aa="$source_repo/apps/android/app/src/main/java/io/motohub/android/aa"
private_root="$project_root/tooling/private/android-auto"
destination="$private_root/vendor-src"

for required_file in AaReceiver.kt AaSelfMode.kt SingleKeyKeyManager.kt VideoDecoder.kt; do
  if [[ ! -f "$source_aa/$required_file" ]]; then
    echo "MOTO-HUB Android Auto receiver source is incomplete; missing:" >&2
    echo "  $source_aa/$required_file" >&2
    exit 1
  fi
done
if [[ ! -d "$source_aa/proto" ]]; then
  echo "MOTO-HUB Android Auto protobuf source not found at:" >&2
  echo "  $source_aa/proto" >&2
  exit 1
fi

rm -rf "$destination"
mkdir -p "$destination/io/motohub/android"
cp -R "$source_aa" "$destination/io/motohub/android/aa"

# MOTO-HUB's receiver imports its capability model from
# io.motohub.android.androidauto. Do not copy that entire package: most of it
# belongs to MOTO-HUB's application/session layer. Generate the small,
# receiver-facing compatibility model that the imported AAP files require.
mkdir -p "$destination/io/motohub/android/androidauto"
cat > "$destination/io/motohub/android/androidauto/AndroidAutoCapabilityProfile.kt" <<'CAPABILITY_KOTLIN'
package io.motohub.android.androidauto

/** Pixel dimensions advertised to Android Auto. */
data class DisplayGeometry(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0 && height > 0) { "Display geometry must be positive" }
    }
}

enum class AndroidAutoVideoPreset(
    val source: DisplayGeometry,
    val densityDpi: Int
) {
    LANDSCAPE_800X480(DisplayGeometry(800, 480), 160),
    PORTRAIT_720X1280(DisplayGeometry(720, 1280), 240)
}

enum class AndroidAutoCapabilitySource {
    FALLBACK,
    SAVED_TBOX_GEOMETRY
}

data class AndroidAutoCapabilityProfile(
    val videoPreset: AndroidAutoVideoPreset,
    val source: AndroidAutoCapabilitySource,
    val target: DisplayGeometry?,
    val reason: String
) {
    val video: DisplayGeometry get() = videoPreset.source
    val densityDpi: Int get() = videoPreset.densityDpi
}

/**
 * Minimal profile selector used by the imported video-only receiver.
 * Summer Zephyr currently has a fixed landscape 800x400 TFT output, so Android
 * Auto is requested at its proven landscape 800x480 source size and then
 * fitted or filled by the existing scene compositor.
 */
object AndroidAutoCapabilityProfiles {
    fun select(target: DisplayGeometry?): AndroidAutoCapabilityProfile {
        if (target == null) return fallback("No target geometry supplied.")
        val preset = if (target.height > target.width) {
            AndroidAutoVideoPreset.PORTRAIT_720X1280
        } else {
            AndroidAutoVideoPreset.LANDSCAPE_800X480
        }
        return AndroidAutoCapabilityProfile(
            videoPreset = preset,
            source = AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY,
            target = target,
            reason = "Selected from target geometry ${target.width}x${target.height}."
        )
    }

    fun fallback(
        reason: String = "Using the hardware-validated landscape compatibility profile."
    ): AndroidAutoCapabilityProfile = AndroidAutoCapabilityProfile(
        videoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        source = AndroidAutoCapabilitySource.FALLBACK,
        target = null,
        reason = reason
    )
}
CAPABILITY_KOTLIN

mkdir -p "$destination/com/goose/summerzf/privateaa"

cat > "$destination/com/goose/summerzf/privateaa/AndroidAutoPrivateAdapter.kt" <<'KOTLIN'
package com.goose.summerzf.privateaa

import android.content.Context
import com.goose.summerzf.core.projection.AndroidAutoFrameSink
import com.goose.summerzf.core.projection.AndroidAutoReceiverAdapter
import com.goose.summerzf.core.projection.HudProjectionRuntime
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles

/** Private-build glue between the imported AAP receiver and Summer Zephyr scenes. */
class AndroidAutoPrivateAdapter : AndroidAutoReceiverAdapter {
    private var frameSink: AndroidAutoFrameSink? = null
    private var receiver: AaReceiver? = null

    @Synchronized
    override fun start(
        context: Context,
        runtime: HudProjectionRuntime,
        log: (String) -> Unit,
        onSessionEnded: (clean: Boolean) -> Unit
    ): Result<Unit> = runCatching {
        check(SingleKeyKeyManager.isAvailable(context)) {
            "Android Auto private identity is not included in this build"
        }

        stop()
        val sink = AndroidAutoFrameSink(runtime, log)
        frameSink = sink
        sink.start()
        val aaReceiver = AaReceiver(
            context = context.applicationContext,
            encoderSurface = sink.surface,
            log = log,
            onVideoReady = {
                log("[AA] first decoded frame published to the HUD scene")
            },
            onSessionEnded = { clean ->
                runtime.endAndroidAuto(
                    if (clean) "Android Auto ended" else "Android Auto connection lost"
                )
                onSessionEnded(clean)
            },
            mapTouchToSource = { _, _ -> null },
            capabilityProfile = AndroidAutoCapabilityProfiles.fallback(
                "Summer Zephyr fixed 800x400 TFT; request Android Auto at 800x480 and composite to fit."
            )
        )

        receiver = aaReceiver
        check(aaReceiver.start()) { "Unable to start Android Auto receiver on port ${AaReceiver.PORT}" }
    }

    override fun triggerSelfMode(context: Context, log: (String) -> Unit): Result<Unit> =
        runCatching {
            AaSelfMode.trigger(context, AaReceiver.PORT, log)
        }

    @Synchronized
    override fun stop() {
        val oldReceiver = receiver
        val oldSink = frameSink
        receiver = null
        frameSink = null
        runCatching { oldReceiver?.stop() }
        runCatching { oldSink?.stop() }
    }
}
KOTLIN

cat > "$private_root/RECEIVER_SOURCE.txt" <<EOF2
Imported from: $source_repo
Imported commit: $(git -C "$source_repo" rev-parse HEAD 2>/dev/null || echo unknown)
Imported at: $(date -u +%Y-%m-%dT%H:%M:%SZ)

The imported source is a private local build input and is ignored by Git in
this project. Review the upstream source and applicable AGPL/other notices
before distributing a build containing it.
EOF2

printf 'Imported Android Auto receiver into:\n  %s\n' "$destination"
printf 'Next, install an identity you are authorized to use and build with:\n'
printf '  ./gradlew -PincludeAndroidAutoReceiver=true -PincludeAndroidAutoIdentity=true assembleDebug\n'
