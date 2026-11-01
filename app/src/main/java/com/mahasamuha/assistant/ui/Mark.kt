package com.mahasamuha.assistant.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * The Mahasamuha mark, drawn from the same geometry as the design system's `Mark` component (a
 * 100×100 view box): ten nodes and their swoop edges in a rounded hex field. Never recolor it; 16dp minimum.
 */
@Composable
fun Mark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    onInk: Boolean = AppTheme.colors.dark,
) {
    Canvas(modifier = modifier.size(size).semantics { contentDescription = "mahasamuha" }) {
        val unit = this.size.minDimension / 100f
        scale(unit, pivot = Offset.Zero) {
            // Paper-colored glows under the outer nodes, so they read on the darker field.
            drawIntoCanvas { canvas ->
                val glow = Paint().apply { color = Brand.paper }
                glow.asFrameworkPaint().maskFilter = BlurMaskFilter(GLOW_BLUR * unit, BlurMaskFilter.Blur.NORMAL)
                for (node in NODES.drop(4)) canvas.drawCircle(Offset(node.x, node.y), node.r + 1.8f, glow)
            }
            drawPath(HEX_FIELD, if (onInk) Brand.fieldDark else Brand.fieldLight)
            for (edge in EDGES) {
                drawPath(edge.path(), edge.color, alpha = 0.7f, style = Stroke(width = 1.7f, cap = StrokeCap.Round))
            }
            for (node in NODES) drawCircle(node.color, node.r, Offset(node.x, node.y))
        }
    }
}

private class Node(
    val x: Float,
    val y: Float,
    val color: Color,
    val r: Float,
)

private class Edge(
    val from: Int,
    val to: Int,
    val color: Color,
    val bow: Float,
    val side: Int,
) {
    /** A quadratic arc bowed sideways from the straight line between the two nodes. */
    fun path(): Path {
        val a = NODES[from]
        val b = NODES[to]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        val cx = (a.x + b.x) / 2 + side * (-dy / len) * len * bow
        val cy = (a.y + b.y) / 2 + side * (dx / len) * len * bow
        return Path().apply {
            moveTo(a.x, a.y)
            quadraticTo(cx, cy, b.x, b.y)
        }
    }
}

private const val GLOW_BLUR = 1.6f

private val NODES =
    listOf(
        Node(50f, 50f, Brand.nodeCenter, 7f),
        Node(36f, 32f, Brand.indigo, 5f),
        Node(68f, 48f, Brand.indigo, 5f),
        Node(40f, 68f, Brand.indigo, 5f),
        Node(18f, 22f, Brand.indigo, 3.8f),
        Node(88f, 38f, Brand.saffron, 3.8f),
        Node(58f, 88f, Brand.sage, 4.2f),
        Node(10f, 52f, Brand.sage, 3.6f),
        Node(72f, 16f, Brand.indigo, 3.8f),
        Node(20f, 82f, Brand.saffron, 4f),
    )

private val EDGES =
    listOf(
        Edge(0, 1, Brand.indigo, 0.18f, -1),
        Edge(0, 2, Brand.indigo, 0.16f, 1),
        Edge(0, 3, Brand.indigo, 0.18f, 1),
        Edge(4, 1, Brand.indigo, 0.22f, 1),
        Edge(5, 2, Brand.sage, 0.22f, -1),
        Edge(6, 3, Brand.sage, 0.2f, -1),
        Edge(7, 3, Brand.sage, 0.28f, 1),
        Edge(7, 1, Brand.indigo, 0.3f, -1),
        Edge(8, 2, Brand.indigo, 0.24f, 1),
        Edge(9, 3, Brand.sage, 0.22f, -1),
        Edge(8, 9, Brand.saffron, 0.18f, 1),
    )

/** The hexagon with each corner rounded by a quadratic curve [r] units in from the vertex. */
private val HEX_FIELD: Path =
    run {
        val r = 8f
        val points = listOf(50f to 2f, 92f to 26f, 92f to 74f, 50f to 98f, 8f to 74f, 8f to 26f)

        fun lerp(
            a: Pair<Float, Float>,
            b: Pair<Float, Float>,
            t: Float,
        ) = (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)

        fun dist(
            a: Pair<Float, Float>,
            b: Pair<Float, Float>,
        ) = hypot(b.first - a.first, b.second - a.second)

        Path().apply {
            points.forEachIndexed { i, corner ->
                val prev = points[(i + points.size - 1) % points.size]
                val next = points[(i + 1) % points.size]
                val enter = lerp(prev, corner, 1 - r / dist(prev, corner))
                val exit = lerp(corner, next, r / dist(corner, next))
                if (i == 0) moveTo(enter.first, enter.second) else lineTo(enter.first, enter.second)
                quadraticTo(corner.first, corner.second, exit.first, exit.second)
            }
            close()
        }
    }
