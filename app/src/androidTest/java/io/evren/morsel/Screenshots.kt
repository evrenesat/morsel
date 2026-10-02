package io.evren.morsel

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Saves screenshots into the app's INTERNAL files dir (pullable with run-as
 * for as long as the app stays installed) AND mirrors every capture into the
 * logcat buffer under [TAG]: Gradle uninstalls the app when its connected run
 * ends — wiping internal storage — so the logcat stream (captured continuously
 * by scripts/ci-emulator.sh, immune to buffer rotation) plus the run-as pull
 * are the two channels that carry evidence out.
 *
 * Logcat entries are capped at ~4KB, so the base64 body travels in numbered
 * chunks with BEGIN/END markers per screenshot, paced so logd cannot drop the
 * tail of a burst. The mirror is best-effort; a failure never fails the test
 * itself. (/data/local/tmp copies under adopted shell identity were tried and
 * do NOT work: the permission identity changes permission checks, not the
 * app's Linux UID, and /data/local/tmp is not writable by app UIDs.)
 */
object Screenshots {

    private const val TAG = "MORSEL_SHOT"
    private const val CHUNK_B64 = 1800

    fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "morsel-screens")
        captureInto(dir, name)
        mirrorViaLogcat(dir, name)
    }

    private fun captureInto(dir: File, name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val shot: Bitmap = automation.takeScreenshot()
        dir.mkdirs()
        val safe = sanitize(name)
        val out = File(dir, "$safe.png")
        FileOutputStream(out).use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    private fun mirrorViaLogcat(dir: File, rawName: String) {
        runCatching {
            val safe = sanitize(rawName)
            val b64 = Base64.encodeToString(File(dir, "$safe.png").readBytes(), Base64.NO_WRAP)
            val chunks = (b64.length + CHUNK_B64 - 1) / CHUNK_B64
            Log.i(TAG, "BEGIN:$safe:$chunks")
            for (i in 0 until chunks) {
                val from = i * CHUNK_B64
                Log.i(TAG, "CHUNK:$safe:$i:" + b64.substring(from, minOf(from + CHUNK_B64, b64.length)))
                // A whole stream in one burst made logd drop its tail chunks
                // (run 36971344588 lost the last 5/2 chunks of two shots);
                // brief pauses let the buffer drain.
                if (i % 8 == 7) Thread.sleep(5)
            }
            Log.i(TAG, "END:$safe")
        }.onFailure {
            println("morsel-screens: logcat mirror of $rawName failed: $it")
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "-")
}

/**
 * Captures a device screenshot for every FAILED test, so visual evidence
 * exists regardless of where the assertion broke.
 */
class FailureScreenshotRule : org.junit.rules.TestWatcher() {

    override fun failed(e: Throwable?, description: org.junit.runner.Description) {
        runCatching {
            Screenshots.capture("FAILED-${description.testClass.simpleName}-${description.methodName}")
        }
    }
}
