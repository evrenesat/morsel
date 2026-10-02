package io.evren.morsel

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/** Saves screenshots next to the app's external files dir; CI pulls them as artifacts. */
object Screenshots {

    fun capture(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val shot: Bitmap = automation.takeScreenshot()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "morsel-screens",
        )
        dir.mkdirs()
        val out = File(dir, "$name.png")
        FileOutputStream(out).use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
}
