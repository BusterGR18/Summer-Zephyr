package com.goose.summerzf.core.qr

import android.os.Parcelable
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.net.toUri
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import kotlinx.parcelize.Parcelize

sealed class QrContent : Parcelable {
    @Parcelize
    data class HudWifi(
        val ssid: String,
        val password: String?,
        val encryption: String?,
        val modelId: String?,
        val sn: String?,
        val mac: String?,
        val name: String?
    ): QrContent()

    @Parcelize
    object BadQr : QrContent()

    @Parcelize
    object Unknown : QrContent()
}

class QrAnalyzer (
    private val scanner: BarcodeScanner,
    private val onBarcodeScanned: (QrContent) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    private var isProcessing = false

    fun parseURLString(rawValue: String) : QrContent {
        val uri = runCatching { rawValue.toUri() }.getOrNull() ?: return QrContent.BadQr

        if (!uri.host.orEmpty().contains("carbit.com", ignoreCase = true)) {
            return QrContent.BadQr
        }

        val ssid = uri.getQueryParameter("ssid") ?: return QrContent.BadQr
        val pwd = uri.getQueryParameter("pwd")
        val encryption = uri.getQueryParameter("auth")
        val modelId = uri.getQueryParameter("modelid")
        val sn = uri.getQueryParameter("sn")
        val mac = uri.getQueryParameter("mac")
        val name = uri.getQueryParameter("name")

        return QrContent.HudWifi(
            ssid = ssid,
            password = pwd,
            encryption = encryption,
            modelId = modelId,
            sn = sn,
            mac = mac,
            name = name
        )
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        if (isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true

        val image = InputImage.fromMediaImage(
            mediaImage, imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(image).addOnSuccessListener { barcodes ->
            val result = barcodes.firstOrNull() {
                it.rawValue != null
            }
            val rawValue = result?.rawValue
            if (rawValue != null) {
                onBarcodeScanned(this.parseURLString(rawValue))
            }
        }.addOnFailureListener {
            onBarcodeScanned(QrContent.BadQr)
        }.addOnCompleteListener {
            imageProxy.close()
            isProcessing = false
        }
    }
}