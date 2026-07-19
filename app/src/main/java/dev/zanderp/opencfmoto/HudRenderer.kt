// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Renders one HUD bar's [HudCell]s to an opaque [Bitmap] that [AaCompositor] uploads to a GL texture
 * and draws into the bar. Kept apart from [HudModel] (which is pure/host-tested) — this is the
 * Android-graphics half. Fonts scale to the bar so it reads on both a thin side bar (800MT) and a
 * wide bottom band; the bar background is black so it blends seamlessly with the letterbox.
 */
object HudRenderer {
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA0A6")
        textAlign = Paint.Align.CENTER
    }

    fun render(bar: BarRect, cells: List<HudCell>): Bitmap {
        val bmp = Bitmap.createBitmap(bar.w.coerceAtLeast(1), bar.h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        if (cells.isEmpty()) return bmp
        if (bar.vertical) {
            val bandH = bar.h.toFloat() / cells.size
            cells.forEachIndexed { i, cell ->
                drawCell(c, bar.w / 2f, bandH * i + bandH / 2f, bar.w.toFloat(), bandH, cell)
            }
        } else {
            val bandW = bar.w.toFloat() / cells.size
            cells.forEachIndexed { i, cell ->
                drawCell(c, bandW * i + bandW / 2f, bar.h / 2f, bandW, bar.h.toFloat(), cell)
            }
        }
        return bmp
    }

    /** Draw one cell (big value over a small label), vertically centered at ([cx],[cy]). */
    private fun drawCell(c: Canvas, cx: Float, cy: Float, availW: Float, availH: Float, cell: HudCell) {
        valuePaint.color = if (cell.value == "HOT") Color.parseColor("#FF7043") else Color.WHITE
        val vSize = fitText(valuePaint, cell.value, availW * 0.92f, availH * (if (cell.big) 0.52f else 0.42f))
        val lSize = fitText(labelPaint, cell.label, availW * 0.92f, availH * 0.22f)
        valuePaint.textSize = vSize
        labelPaint.textSize = lSize
        val gap = lSize * 0.4f
        val totalH = vSize + gap + lSize
        val top = cy - totalH / 2f
        c.drawText(cell.value, cx, top + vSize, valuePaint)
        c.drawText(cell.label, cx, top + vSize + gap + lSize, labelPaint)
    }

    /** Largest text size ≤ [target] that keeps [text] within [maxWidth]. */
    private fun fitText(paint: Paint, text: String, maxWidth: Float, target: Float): Float {
        val size = target.coerceAtLeast(8f)
        paint.textSize = size
        val w = paint.measureText(text)
        return (if (w > maxWidth && w > 0) size * (maxWidth / w) else size).coerceAtLeast(8f)
    }
}
