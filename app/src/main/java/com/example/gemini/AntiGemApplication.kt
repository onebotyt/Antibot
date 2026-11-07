package com.example.gemini

import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.widget.Toast
import com.example.gemini.data.adb.AdbConnectManager
import com.example.gemini.data.updater.PluginUpdateCheckWorker
import com.example.gemini.data.updater.UpdateCheckWorker
import com.telemetrydeck.sdk.TelemetryDeck
import java.security.MessageDigest

class AntiGemApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.example.gemini.data.preferences.AuthPreferences(this)
        com.example.gemini.theme.ThemeManager.init(this)
        UpdateCheckWorker.schedule(this)
        PluginUpdateCheckWorker.schedule(this)
        // Reconnects adb when Wireless debugging turns back on (it gets a new port every time)
        AdbConnectManager.init(this)
        com.example.gemini.data.ssh.SshSessionFactory.init(this)
    }

    private fun isOfficialRelease(): Boolean = runCatching {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return false
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().any { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02X".format(it) } == RELEASE_CERT_SHA256
        }
    }.getOrDefault(false)

    private companion object {
        // SHA-256 of the release.jks certificate (alias "antigem"). Public, safe to commit.
        const val RELEASE_CERT_SHA256 = "CCF2C681063BA941641519E4EAC7F024E92880CCD06EED5070DAA9717380F315"
    }
}
