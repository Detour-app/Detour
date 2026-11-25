package com.jellemax.detour.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil

// Value tag on the trip-detail map (#836): a dark pill with the highlight
// row's coloured dot and its value, so the map says "87 km/h" where it used to
// show a bare dot. Sizes in dp, scaled by the screen density at draw time.
private const val TAG_FILL = "#E6212121"
private const val TAG_TEXT_DP = 12f
private const val TAG_DOT_DP = 8f
private const val TAG_PAD_H_DP = 8f
private const val TAG_GAP_DP = 6f
private const val TAG_HEIGHT_DP = 24f
// The focused tag (its row was tapped) gets a white ring, the same ring the
// dots used to carry, so it stands out from the others without resizing.
private const val TAG_RING_DP = 2f
// Tags stand this far above their point (#1066), so the route's start and end
// dots and the replay marker under it stay visible.
const val HIGHLIGHT_TAG_LIFT_DP = 6f
// Least gap between a tag on the route's outermost point and the map's edge.
private const val TAG_EDGE_GAP_DP = 8f

/** Style image id of [highlightId]'s tag, plain or [focused]. */
fun highlightTagImageId(highlightId: String, focused: Boolean): String =
    "mr-img-tag-$highlightId" + if (focused) "-focused" else ""

/** Width in dp of [text]'s tag image, ring included, the same for plain and focused. */
fun highlightTagWidthDp(text: String, density: Float): Float =
    TAG_PAD_H_DP + TAG_DOT_DP + TAG_GAP_DP + tagTextPaint(density).measureText(text) / density + TAG_PAD_H_DP +
        2 * TAG_RING_DP

/**
 * Padding, in dp, that fits the trip-detail route so no tag clips (#1066):
 * tags sit centred above their point, so a point on the left or right edge
 * needs half the [widestTagDp] beside it and one on the top edge a whole tag
 * above it. Never less than [marginDp] on any side; just [marginDp] with no tags.
 */
internal fun routeFitPaddingDp(widestTagDp: Float, marginDp: Float): RouteFitPadding {
    if (widestTagDp <= 0f) return RouteFitPadding(sideDp = marginDp, topDp = marginDp, bottomDp = marginDp)
    val tagHeightDp = TAG_HEIGHT_DP + 2 * TAG_RING_DP
    return RouteFitPadding(
        sideDp = maxOf(marginDp, widestTagDp / 2 + TAG_EDGE_GAP_DP),
        topDp = maxOf(marginDp, HIGHLIGHT_TAG_LIFT_DP + tagHeightDp + TAG_EDGE_GAP_DP),
        bottomDp = marginDp,
    )
}

/** What [routeFitPaddingDp] returns; left and right share [sideDp]. */
internal data class RouteFitPadding(val sideDp: Float, val topDp: Float, val bottomDp: Float)

private fun tagTextPaint(density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = TAG_TEXT_DP * density
    typeface = Typeface.DEFAULT_BOLD
}

/** Draws one tag: [dotColor] (a "#RRGGBB" highlight colour) then [text]. */
fun highlightTagBitmap(text: String, dotColor: String, focused: Boolean, density: Float): Bitmap {
    val textPaint = tagTextPaint(density)
    val ring = TAG_RING_DP * density
    val height = TAG_HEIGHT_DP * density
    val padH = TAG_PAD_H_DP * density
    val dot = TAG_DOT_DP * density
    val gap = TAG_GAP_DP * density
    val width = padH + dot + gap + textPaint.measureText(text) + padH
    val bmp = Bitmap.createBitmap(
        ceil(width + 2 * ring).toInt(), ceil(height + 2 * ring).toInt(), Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bmp)
    val pill = RectF(ring, ring, ring + width, ring + height)
    val radius = height / 2
    if (focused) {
        val outer = RectF(0f, 0f, width + 2 * ring, height + 2 * ring)
        canvas.drawRoundRect(outer, radius + ring, radius + ring, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
    }
    canvas.drawRoundRect(pill, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(TAG_FILL)
    })
    canvas.drawCircle(pill.left + padH + dot / 2, pill.centerY(), dot / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(dotColor)
    })
    // Baseline that centres the text's ascent-to-descent box in the pill.
    val baseline = pill.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
    canvas.drawText(text, pill.left + padH + dot + gap, baseline, textPaint)
    return bmp
}
