package io.evren.morsel

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Saves screenshots into the app's INTERNAL files dir and carries them to the
 * host through two channels:
 *
 * 1. DURABLE: a real shell copy to /data/local/tmp/morsel-screens via
 *    UiAutomation.executeShellCommand running the helper script
 *    scripts/copy-screenshot.sh (pushed there by ci-emulator.sh). The shell
 *    uid owns /data/local/tmp — unlike the app process, whose Linux UID
 *    cannot write there even under adopted shell permission identity (DAC
 *    checks use the effective uid, not the permission identity; that is why
 *    the earlier in-process copy attempt silently produced nothing) — and
 *    `run-as` inside the script reads the app-private file, so captures
 *    survive gradle's post-suite uninstall. The helper validates the copy at
 *    the exact nonzero byte count and lists verified copies in sizes.list
 *    for the host-side gate. logd has provably dropped identical chunk
 *    windows from EVERY reader under burst pressure (run 36988504221: both
 *    continuous streams lost the same chunks), so logcat alone cannot be
 *    the durability guarantee.
 *
 * 2. FALLBACK: the MORSEL_SHOT logcat mirror. Logcat entries are capped at
 *    ~4KB, so the base64 body travels in numbered chunks with BEGIN/END
 *    markers per screenshot, paced so logd cannot drop the tail of a burst.
 *    The mirror is best-effort; a failure never fails the test itself.
 */
object Screenshots {

    private const val TAG = "MORSEL_SHOT"
    private const val CHUNK_B64 = 1800

    fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "morsel-screens")
        captureInto(dir, name)
        copyViaShellChannel(context.packageName, dir, sanitize(name))
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

    /**
     * Copies one capture to /data/local/tmp through an actual shell script
     * and verifies it at the exact byte count. The helper
     * (scripts/copy-screenshot.sh, pushed by ci-emulator.sh) is executed as
     * `sh /data/local/tmp/morsel-copy-screenshot.sh PACKAGE SAFE EXPECTED`:
     * UiAutomationConnection on API 30 runs commands via Runtime.exec —
     * whitespace tokenization, NO shell parsing — so an inline `sh -c`
     * command would tokenize its quotes apart, and the pushed script file is
     * the only reliable way to run real shell logic (mkdir, run-as redirect,
     * exact-size validation). All arguments are whitespace-free by
     * construction (SAFE is sanitized to [A-Za-z0-9._-]). The helper prints
     * the verified size only on success; the comparison below gates the
     * sizes.list confirmation for the host gate. Never fails the test: the
     * host gate judges what arrived through any channel.
     */
    private fun copyViaShellChannel(packageName: String, dir: File, safe: String) {
        runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val expected = File(dir, "$safe.png").length()
            val output = shellCommand(
                automation,
                "sh /data/local/tmp/morsel-copy-screenshot.sh $packageName $safe $expected",
            )
            val copied = output.trim().toLongOrNull()
            if (copied != null && copied > 0 && copied == expected) {
                println("Screenshots: shell copy verified $safe.png ($copied bytes)")
            } else {
                println(
                    "Screenshots: shell copy failed for $safe.png: " +
                        "output='$output' expected=$expected",
                )
            }
        }.onFailure {
            println("Screenshots: shell copy of $safe failed: $it")
        }
    }

    /** Runs one command through UiAutomation's shell and returns its stdout. */
    private fun shellCommand(automation: UiAutomation, command: String): String = automation.executeShellCommand(command).use { pfd ->
        java.io.InputStreamReader(
            ParcelFileDescriptor.AutoCloseInputStream(pfd),
        ).useLines { lines -> lines.joinToString("\n") }
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
