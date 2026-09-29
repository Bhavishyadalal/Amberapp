package com.amber.player

import android.webkit.JavascriptInterface
import android.widget.Toast

class AmberBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun openFile(path: String) {
        activity.runOnUiThread {
            val ok = FileHelper.openFile(activity, path)
            if (!ok) {
                Toast.makeText(activity, R.string.export_fail, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun shareFile(path: String) {
        activity.runOnUiThread {
            val ok = FileHelper.shareFile(activity, path)
            if (!ok) {
                Toast.makeText(activity, R.string.export_fail, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun exportFile(path: String) {
        activity.runOnUiThread {
            val ok = FileHelper.exportToPublic(activity, path)
            Toast.makeText(
                activity,
                if (ok) R.string.export_ok else R.string.export_fail,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @JavascriptInterface
    fun isAndroid(): Boolean = true

    @JavascriptInterface
    fun appVersion(): String = try {
        val p = activity.packageManager.getPackageInfo(activity.packageName, 0)
        p.versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }

    @JavascriptInterface
    fun playNativeTrack(url: String, title: String, channel: String, thumb: String, durMs: Long, startMs: Long) {
        activity.runOnUiThread {
            activity.playWithNativePlayer(url, title, channel, thumb, durMs, startMs)
        }
    }

    @JavascriptInterface
    fun pauseNativeAudio() {
        activity.runOnUiThread {
            activity.pauseNativeAudio()
        }
    }

    @JavascriptInterface
    fun resumeNativeAudio() {
        activity.runOnUiThread {
            activity.resumeNativeAudio()
        }
    }

    @JavascriptInterface
    fun seekNativeAudio(posMs: Long) {
        activity.runOnUiThread {
            activity.seekNativeAudio(posMs)
        }
    }

    @JavascriptInterface
    fun isNativeSupported(): Boolean = true
}
