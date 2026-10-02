package io.evren.morsel

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Saves screenshots into the app's INTERNAL files dir so CI can pull them with
 * `adb exec-out run-as io.evren.morsel tar ...` (the external Android/data dir
 * is not reliably readable by adb on newer APIs).
 *
 * Every capture is ALSO mirrored to /data/local/tmp/morsel-screens via the
 * shell-level UiAutomation: Gradle uninstalls the app when the connected run
 * ends, wiping internal storage — the mirror survives and lets the CI script
 * collect evidence from the main suite, not just the later adb-driven phases.
 */
object Screenshots {

    private const val MIRROR_DIR = "/data/local/tmp/morsel-screens"

    fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "morsel-screens")
        captureInto(dir, name)
        mirror(name)
    }

    private fun captureInto(dir: File, name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val shot: Bitmap = automation.takeScreenshot()
        dir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val out = File(dir, "$safe.png")
        FileOutputStream(out).use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    /** Best-effort duplication; a failed mirror never fails the test itself. */
    private fun mirror(name: String) {
        runCatching {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            shell("mkdir -p $MIRROR_DIR")
            shell(
                "run-as io.evren.morsel cat " +
                    "/data/user/0/io.evren.morsel/files/morsel-screens/$safe.png" +
                    " > $MIRROR_DIR/$safe.png",
            )
        }.onFailure {
            println("morsel-screens: mirror of $name failed: $it")
        }
    }

    /** Runs one shell command to completion (drains its stdout). */
    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes() }
    }
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
