package io.evren.morsel

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Saves screenshots into the app's INTERNAL files dir so CI can pull them with
 * `adb exec-out run-as io.evren.morsel tar ...` (the external Android/data dir
 * is not reliably readable by adb on newer APIs).
 */
object Screenshots {

    fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "morsel-screens")
        captureInto(dir, name)
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
