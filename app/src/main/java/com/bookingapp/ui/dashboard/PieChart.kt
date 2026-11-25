package com.bookingapp.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookingapp.domain.ChartGroups
import com.bookingapp.domain.Money
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One slice of a pie: its group, colour and whether it gets a label (5% or more). */
data class Slice(val group: ChartGroups.Group, val color: Color, val share: String, val labelled: Boolean)

fun slicesOf(groups: List<ChartGroups.Group>, colors: Map<String, Long>): List<Slice> {
    val total = groups.sumOf { it.value }
    return groups.mapIndexed { index, group ->
        val argb = colors[group.key] ?: ChartGroups.SLICE_COLORS[index % ChartGroups.SLICE_COLORS.size]
        Slice(group, Color(argb), Money.formatShare(group.value, total), !ChartGroups.isSmallShare(group.value, total))
    }
}

/**
 * A pie drawn with Canvas arcs. Only slices of 5% or more get a label; the labels are also
 * exposed as the chart's content description.
 */
@Composable
fun PieChart(slices: List<Slice>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val outline = MaterialTheme.colorScheme.surface
    val total = slices.sumOf { it.group.value }.toDouble()
    val description = slices.filter { it.labelled }.joinToString(", ") { "${it.group.label} ${it.share}" }
    Canvas(
        modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp)
            .aspectRatio(1f)
            .semantics { contentDescription = "Pie chart: $description" },
    ) {
        if (total <= 0) return@Canvas
        val diameter = min(size.width, size.height)
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val center = Offset(size.width / 2, size.height / 2)
        var start = -90f
        slices.forEach { slice ->
            // Display only: the angle is derived from exact totals and never fed back into sums.
            val sweep = (slice.group.value / total * 360.0).toFloat()
            drawArc(slice.color, start, sweep, useCenter = true, topLeft = topLeft, size = Size(diameter, diameter))
            if (slices.size > 1) {
                drawArc(outline, start, sweep, useCenter = true, topLeft = topLeft, size = Size(diameter, diameter), style = Stroke(2f))
            }
            if (slice.labelled) {
                val mid = Math.toRadians((start + sweep / 2).toDouble())
                val radius = diameter / 2 * if (slices.size == 1) 0f else 0.62f
                val text = measurer.measure("${slice.group.label}\n${slice.share}", labelStyle)
                val at = Offset(
                    center.x + (radius * cos(mid)).toFloat() - text.size.width / 2,
                    center.y + (radius * sin(mid)).toFloat() - text.size.height / 2,
                )
                drawText(text, topLeft = at)
            }
            start += sweep
        }
    }
}
