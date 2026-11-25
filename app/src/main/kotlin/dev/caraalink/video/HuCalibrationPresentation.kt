package dev.caraalink.video

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import dev.caraalink.R
import dev.caraalink.diagnostics.HuCalibration

internal class HuCalibrationPresentation(context: Context, display: Display, private val samples: () -> List<HuCalibration.Point>) : Presentation(context, display) {
    private var surface: View? = null
    fun refresh() { surface?.invalidate() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val targetView = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(17,20,25))
                val recorded = samples()
                val targets = HuCalibration.targets
                val radius = (minOf(width,height)*.028f).coerceAtLeast(9f)
                paint.strokeWidth = 3f
                targets.forEachIndexed { i,p ->
                    val x = (p.x*width).toFloat(); val y = (p.y*height).toFloat()
                    paint.color = if (i < recorded.size) Color.rgb(103,199,164) else if (i == recorded.size) Color.rgb(167,199,255) else Color.DKGRAY
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(x,y,radius,paint)
                    canvas.drawLine(x-radius*1.4f,y,x+radius*1.4f,y,paint)
                    canvas.drawLine(x,y-radius*1.4f,x,y+radius*1.4f,paint)
                }
                recorded.lastOrNull()?.let {
                    paint.color = Color.YELLOW; paint.style = Paint.Style.FILL
                    canvas.drawCircle((it.x*width).toFloat(),(it.y*height).toFloat(),radius*.32f,paint)
                }
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER; paint.textSize = (height*.04f).coerceAtLeast(14f)
                canvas.drawText(context.getString(R.string.hu_cal_screen, recorded.size, targets.size),width/2f,height*.07f,paint)
            }
        }
        surface = targetView
        setContentView(targetView)
    }
}
