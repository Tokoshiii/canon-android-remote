package dev.canonremote.app.dev

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * Test image of the developer mode's simulated live view: a slowly moving colour gradient with
 * « Mode développeur », the frame number and [caption], as a JPEG (like the camera's frames).
 * Called off the main thread.
 */
object DevTestImage {
    private const val WIDTH = 960
    private const val HEIGHT = 640

    fun render(index: Int, caption: String): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (index * 6) % 360
        val start = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.85f))
        val end = Color.HSVToColor(floatArrayOf(((hue + 140) % 360).toFloat(), 0.65f, 0.35f))
        val shift = (index % 20) * (WIDTH / 20f)
        val background = Paint().apply {
            shader = LinearGradient(shift, 0f, shift + WIDTH, HEIGHT.toFloat(), start, end, Shader.TileMode.MIRROR)
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), background)

        // Rule-of-thirds grid, as on the camera's screen.
        val grid = Paint().apply {
            color = Color.argb(90, 255, 255, 255)
            strokeWidth = 2f
        }
        for (i in 1..2) {
            canvas.drawLine(WIDTH * i / 3f, 0f, WIDTH * i / 3f, HEIGHT.toFloat(), grid)
            canvas.drawLine(0f, HEIGHT * i / 3f, WIDTH.toFloat(), HEIGHT * i / 3f, grid)
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 0f, 2f, Color.argb(160, 0, 0, 0))
        }
        text.textSize = 56f
        canvas.drawText("Mode développeur", WIDTH / 2f, HEIGHT / 2f - 30f, text)
        text.textSize = 34f
        canvas.drawText("Image de test n° $index", WIDTH / 2f, HEIGHT / 2f + 30f, text)
        text.textSize = 30f
        canvas.drawText(caption, WIDTH / 2f, HEIGHT - 48f, text)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
