package stream.kleeamp.mobile.chrome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn geometry only: rectangles, triangles, straight paths. No rounded
 * caps, no gradients, no emoji. Every path below is copied out of the concept
 * SVGs so the shapes stay identical; colour comes from the Icon tint.
 */
private fun solid(w: Float, h: Float, vararg d: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = w.dp, defaultHeight = h.dp,
        viewportWidth = w, viewportHeight = h,
    ).apply {
        d.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

private fun stroked(w: Float, h: Float, sw: Float, vararg d: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = w.dp, defaultHeight = h.dp,
        viewportWidth = w, viewportHeight = h,
    ).apply {
        d.forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = sw,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
    }.build()

private fun rect(x: Float, y: Float, w: Float, h: Float) = "M$x ${y}h${w}v${h}h${-w}z"

private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float): String = buildString {
    append("M${x + r} ${y}")
    append("h${w - 2 * r}a$r $r 0 0 1 $r ${r}")
    append("v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}")
    append("h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}")
    append("v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z")
}

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} $cy" +
        "a$r $r 0 1 1 ${2 * r} 0" +
        "a$r $r 0 1 1 ${-2 * r} 0z"

object KleeampIcons {

    /**
     * The cliamp mark: Icon 103h's six band spectrum, envelope "h", peak left
     * of centre. The same geometry the launcher icon is drawn from, so app
     * chrome and home screen carry one mark. Square, unlike the three-bar mark
     * it replaces - give it a square size or it distorts.
     */
    val Mark = solid(
        48f, 48f,
        rect(5f, 20f, 5f, 8f),
        rect(12f, 12f, 5f, 24f),
        rect(19f, 4f, 5f, 40f),
        rect(26f, 14f, 5f, 20f),
        rect(33f, 18f, 5f, 12f),
        rect(40f, 22f, 3f, 4f),
    )

    val PlayTiny = solid(9f, 10f, "M0 0l9 5-9 5z")
    val PlayRow = solid(14f, 14f, "M1 1l12 6-12 6z")
    val PlayTab = solid(18f, 18f, "M2 1l14 8-14 8z")
    val MusicNote = solid(
        16f, 16f,
        circle(4f, 12f, 2.1f),
        circle(11.5f, 12f, 2.1f),
        rect(4.4f, 3f, 1.5f, 9f),
        rect(11.4f, 3f, 1.5f, 9f),
        rect(4.4f, 3f, 8.5f, 2.4f),
    )

    val Prev = solid(22f, 18f, "M12 9L22 1v16z", "M2 9L12 1v16z", rect(0f, 1f, 2.4f, 16f))
    val Next = solid(22f, 18f, "M10 9L0 17V1z", "M20 9L10 17V1z", rect(19.6f, 1f, 2.4f, 16f))
    val Pause = solid(20f, 22f, rrect(1f, 0f, 6.5f, 22f, 1f), rrect(12.5f, 0f, 6.5f, 22f, 1f))

    val Shuffle = stroked(
        18f, 14f, 1.8f,
        "M1 3h4l8 8h3",
        "M14.5 9.5l1.5 1.5-1.5 1.5",
        "M1 11h4l8-8h3",
        "M14.5 2.5l1.5 1.5-1.5 1.5",
    )

    /** Circular arrow: refetch this list. Spins while the fetch runs. */
    val Refresh = stroked(
        16f, 16f, 1.8f,
        "M13.8 8a5.8 5.8 0 1 1-1.7-4.1",
        "M13.8 1.4v3.2h-3.2",
    )

    /** Counterclockwise circular arrow: skip back. Mirrors the refresh arrow. */
    val Rewind = stroked(
        16f, 16f, 1.8f,
        "M2.2 8a5.8 5.8 0 1 0 1.7-4.1",
        "M2.2 1.4v3.2h3.2",
    )

    /** Clockwise circular arrow: skip forward. */
    val Forward = stroked(
        16f, 16f, 1.8f,
        "M13.8 8a5.8 5.8 0 1 1-1.7-4.1",
        "M13.8 1.4v3.2h-3.2",
    )

    /**
     * Two chased circular arrows: the lyrics offset key. Refresh and Rewind
     * arcs point the same way but one arrow reads as "fetch again"; a pair
     * reads as "line the two up", which is what the key does.
     */
    val Sync = stroked(
        16f, 16f, 1.8f,
        "M13.8 8a5.8 5.8 0 1 1-1.7-4.1",
        "M13.8 1.4v3.2h-3.2",
        "M2.2 8a5.8 5.8 0 1 1 1.7 4.1",
        "M2.2 14.6v-3.2h3.2",
    )

    /** Looped arrows: two straight shafts with wedge heads, Shuffle-style. */
    val Repeat = stroked(
        18f, 14f, 1.8f,
        "M1.5 3.5H12.5",
        "M10 1.7L12.5 3.5L10 5.3",
        "M16.5 10.5H5.5",
        "M8 8.7L5.5 10.5L8 12.3",
    )

    /**
     * Repeat-one: the same loop with a single filled dot for the single
     * item, clear of both shafts. One vector glyph, no text badge.
     */
    val RepeatOne: ImageVector = ImageVector.Builder(
        defaultWidth = 18.dp, defaultHeight = 14.dp,
        viewportWidth = 18f, viewportHeight = 14f,
    ).apply {
        for (d in arrayOf(
            "M1.5 3.5H12.5",
            "M10 1.7L12.5 3.5L10 5.3",
            "M16.5 10.5H5.5",
            "M8 8.7L5.5 10.5L8 12.3",
        )) {
            addPath(
                addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
        addPath(addPathNodes(circle(9f, 7f, 1.7f)), fill = SolidColor(Color.White))
    }.build()
    /**
     * A geometric heart: two straight lobes, a notched top, one bottom
     * point. No curves, like the rest of the set - the favourite mark.
     */
    private const val HeartPath = "M8 14.2L1.8 8V4.6L4.4 2H7.2L8 2.9L8.8 2H11.6L14.2 4.6V8z"
    val Heart = stroked(16f, 16f, 1.8f, HeartPath)
    val HeartFilled = solid(16f, 16f, HeartPath)

    /** Headphones: band over two ear cups, for wired and hearing-aid routes. */
    val Headphones = stroked(
        16f, 16f, 1.6f,
        "M2.5 11V9a5.5 5.5 0 0 1 11 0v2",
        rect(1.5f, 10f, 3.2f, 5.2f),
        rect(11.3f, 10f, 3.2f, 5.2f),
    )

    /** Bluetooth rune: the vertical spine with its two chevrons. */
    val Bluetooth = stroked(
        14f, 16f, 1.7f,
        "M7 1.5v13",
        "M7 1.5l4 3.5-4 3.5",
        "M7 14.5l4-3.5-4-3.5",
    )

    /** USB: connector shell with the cable and its plug. */
    val Usb = stroked(
        16f, 16f, 1.6f,
        rect(4.5f, 5.5f, 7f, 5f),
        "M6.5 5.5V2.5h3v3",
        "M8 10.5v1.6",
        circle(8f, 14.2f, 1.4f),
    )

    /**
     * Radio waves: a symmetric stack of three domed waves rising from a
     * transmitter dot set on a grounding baseline. Balanced left and right, the
     * glyph reads as a complete, planted radio mark at tab size. Each dome is a
     * straight-line polyline, so it stays inside the straight-paths-only rule,
     * and it sits cleanly beside the playlists vinyl disc and the queue
     * line-list.
     */
    val StationsTab = ImageVector.Builder(
        defaultWidth = 18.dp, defaultHeight = 18.dp, viewportWidth = 18f, viewportHeight = 18f,
    ).apply {
        // grounding baseline
        addPath(
            addPathNodes("M1.5 16.6h15"),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.3f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        // transmitter
        addPath(addPathNodes(circle(9f, 14.9f, 1.3f)), fill = SolidColor(Color.White))
        // three domed waves (left -> over the top -> right)
        listOf(
            "M5.5 14.2L5.97 12.45L7.25 11.17L9 10.7L10.75 11.17L12.03 12.45L12.5 14.2",
            "M3 14.2L3.8 11.2L6 9L9 8.2L12 9L14.2 11.2L15 14.2",
            "M1 14.2L2.07 10.2L5 7.27L9 6.2L13 7.27L15.93 10.2L17 14.2",
        ).forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
    }.build()

    /**
     * A book-stack: two upright stroked books with a slanted volume on top.
     * Reads as "library" at tab size and stays distinct from the queue's
     * line-list and STATIONS' broadcast-signal.
     */
    val LibTab = stroked(18f, 18f, 1.7f, rect(1f, 1f, 5f, 16f), rect(8f, 1f, 5f, 16f), "M15 2l2 15")
    val DragHandle = stroked(16f, 16f, 1.5f, "M1 3h14M1 8h14M1 13h14")
    /**
     * The queue: a line-list headed by a play triangle. The top line stops
     * short and the triangle takes its head, so the glyph names "what plays
     * next" instead of a generic list. Stroked outline like the rest of the
     * family, so it holds its weight at the 14dp chip size.
     */
    val UpNextTabLines = stroked(18f, 18f, 1.7f, "M1 4h7.5M1 9h11M1 14h11", "M10.5 1l6 3-6 3z")
    val Search = stroked(16f, 16f, 1.5f, circle(7.1f, 7.1f, 3.9f), "M9.9 9.9L14 14.2")

    /**
     * A microphone: capsule, cradle, stand. Stroked like [LibTab] because the
     * tab bar reads as a row of outlines and a solid mic would sit heavier than
     * the three it stands beside.
     */
    val PodsTab = stroked(
        18f, 18f, 1.6f,
        rrect(6.2f, 1f, 5.6f, 9.4f, 2.8f),
        "M3.4 8.4a5.6 5.6 0 0 0 11.2 0",
        "M9 14.1v2.4",
        "M5.8 16.5h6.4",
    )

    /** The same mic at row size, for a show that has no artwork yet. */
    val PodRow = stroked(
        14f, 14f, 1.5f,
        rrect(4.6f, 0.8f, 4.8f, 7.6f, 2.4f),
        "M2.4 6.6a4.6 4.6 0 0 0 9.2 0",
        "M7 11.2v1.9",
    )
    /**
     * Lyrics: a page with text rails. Stroked like the family so it reads as
     * words, not as another music note beside the queue glyph.
     */
    val Lyrics = stroked(
        16f, 16f, 1.5f,
        rrect(2f, 1f, 12f, 14f, 2f),
        "M5 5h6M5 8h6M5 11h3.5",
    )
    val Plus = solid(14f, 14f, rect(6f, 0f, 2f, 14f), rect(0f, 6f, 14f, 2f))
    val Minus = solid(12f, 12f, rect(0f, 5f, 12f, 2f))
    val Check = stroked(13f, 13f, 2.2f, "M1.5 7l3.2 3.2L11.5 3")
    val Xmark = stroked(12f, 12f, 1.8f, "M2 2l8 8M10 2l-8 8")
    val CaretDown = solid(10f, 10f, "M0 2h10L5 8z")
    val CaretRight = solid(10f, 10f, "M2 0v10l6-5z")
    /**
     * A downward chevron used to collapse / dismiss the expanded player; reads
     * as the inverse of "expanded" (pull down to close).
     */
    val Down = stroked(16f, 10f, 1.8f, "M1 1l7 8 7-8")
    val Left = stroked(16f, 16f, 1.8f, "M8 1L1 8l7 7")
    val Download = stroked(16f, 16f, 1.6f, "M8 1v9", "M4.5 6.5L8 10l3.5-3.5", "M1.5 13.5h13")
    /** A lowercase i from two bars: the info / details mark. */
    val Info = solid(16f, 16f, rect(7f, 1.5f, 2f, 2.2f), rect(7f, 5f, 2f, 9f))
    /**
     * Three list rails with a plus badge: add the row to a playlist. Rails
     * are stroked like the other line glyphs, the badge is solid so it
     * reads at row size.
     */
    val PlaylistAdd = ImageVector.Builder(
        defaultWidth = 18.dp, defaultHeight = 18.dp, viewportWidth = 18f, viewportHeight = 18f,
    ).apply {
        listOf("M1 3.5h10", "M1 8h10", "M1 12.5h6").forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
        listOf(rect(11.5f, 12f, 5f, 2f), rect(13f, 10.25f, 2f, 5.5f)).forEach {
            addPath(addPathNodes(it), fill = SolidColor(Color.White))
        }
    }.build()
    /** A lidded bin with two inner rails: delete / remove download. */
    val Trash = stroked(
        16f, 16f, 1.7f,
        "M1.5 3.5h13",
        "M6 3.5V1.5h4v2",
        "M3.5 3.5L4.6 14.5h6.8L12.5 3.5",
        "M7 6.5v5.5M10 6.5v5.5",
    )
    /** A pushpin: round head, shaft, tip. Pin to the top / unpin. */
    val Pin = solid(
        16f, 16f,
        circle(8f, 4f, 2.6f),
        rect(7.2f, 6.2f, 1.6f, 5f),
        "M7.2 11.2h1.6L8 15z",
    )
    /** A diagonal pencil: rename / edit name. */
    val Pencil = stroked(
        16f, 16f, 1.7f,
        "M11.5 2.5l2 2L6 12l-2.7.7L4 10z",
        "M10 4l2 2",
    )
    /** Vertical ellipsis: row overflow menu. */
    val More = solid(16f, 16f, circle(8f, 3f, 2.2f), circle(8f, 8f, 2.2f), circle(8f, 13f, 2.2f))
    val Lines = solid(16f, 14f, rect(0f, 0f, 16f, 2f), rect(0f, 6f, 16f, 2f), rect(0f, 12f, 16f, 2f))
    val ListShort = stroked(16f, 16f, 1.7f, "M1 3h14M1 8h9M1 13h9")
    /** 2x2 square grid: the "show playlists as tiles" layout toggle. */
    val Grid = stroked(
        16f, 16f, 1.6f,
        rrect(1f, 1f, 6f, 6f, 1f),
        rrect(9f, 1f, 6f, 6f, 1f),
        rrect(1f, 9f, 6f, 6f, 1f),
        rrect(9f, 9f, 6f, 6f, 1f),
    )
    /**
     * Sliders: three horizontal rails, each with a knob positioned at a
     * different setting offset. A well-known "settings / controls" glyph built
     * from straight paths only, so it stays inside the straight-paths-only rule.
     */
    val Settings = stroked(
        16f, 16f, 1.7f,
        "M2 3.5h12", "M6 1.5v4",
        "M2 8h12", "M11 6v4",
        "M2 12.5h12", "M8 10.5v4",
    )
    /**
     * A gear: a stroked ring with eight straight teeth radiating from it. The
     * classic settings glyph drawn from circles and lines, so it stays inside
     * the straight-paths-only rule.
     */
    val Gear = stroked(
        16f, 16f, 1.8f,
        circle(8f, 8f, 4.6f),
        "M12.6 8L15 8", "M11.25 11.25L12.95 12.95", "M8 12.6L8 15", "M4.75 11.25L3.05 12.95",
        "M3.4 8L1 8", "M4.75 4.75L3.05 3.05", "M8 3.4L8 1", "M11.25 4.75L12.95 3.05",
    )
    val MeterSmall = solid(
        14f, 14f,
        rect(0f, 9f, 2.4f, 5f), rect(3.9f, 5f, 2.4f, 9f),
        rect(7.8f, 1f, 2.4f, 13f), rect(11.6f, 6f, 2.4f, 8f),
    )
    val Speaker = stroked(20f, 20f, 1.7f, "M2 7l5-4v14l-5-4z", "M11 6.5a4 4 0 010 7")
    val SpeakerSolid = solid(20f, 20f, "M2 7l5-4v14l-5-4z")
    /**
     * Speaker with two radiating waves: sound going somewhere. The Sound
     * Output row's mark; per-sink rows keep their own kind glyphs.
     */
    val SpeakerWaves = stroked(
        20f, 20f, 1.7f,
        "M2 7l5-4v14l-5-4z",
        "M11.5 8l2.8 2.8-2.8 2.8",
        "M14.2 5.3l4 4.7-4 4.7",
    )
    /**
     * Rising bars with a solid plus badge: louder-plus. The boost row's
     * mark; bare bars read as signal strength, which is the output rows'
     * job, so the badge carries the "more" meaning. Same stroked-plus-solid
     * mix as [PlaylistAdd].
     */
    val BoostPlus = ImageVector.Builder(
        defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 20f, viewportHeight = 20f,
    ).apply {
        listOf(
            rect(1f, 12f, 3.2f, 6.5f),
            rect(5.7f, 8.5f, 3.2f, 10f),
            rect(10.4f, 5f, 3.2f, 13.5f),
        ).forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
        listOf(
            rect(13.4f, 14.6f, 6.2f, 2.4f),
            rect(15.3f, 12.7f, 2.4f, 6.2f),
        ).forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()
    /**
     * An hourglass: two triangles meeting at a waist. Replaces a circle with
     * hands, which broke the no-curves rule and read as a generic clock; two
     * triangles say "time passing" with nothing but straight edges.
     */
    val Clock = ImageVector.Builder(
        defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 20f, viewportHeight = 20f,
    ).apply {
        listOf(
            "M4.2 2.8L15.8 2.8L10 9.3z",
            "M10 10.7L15.8 17.2L4.2 17.2z",
        ).forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()
    /**
     * A wristwatch: square face, strap loops, two hands, crown nub. The
     * sleep timer key wears this instead of the hourglass, so "sleep" reads
     * as a watch at a glance. Straight edges only, like the rest of the set.
     */
    val Watch = ImageVector.Builder(
        defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 20f, viewportHeight = 20f,
    ).apply {
        listOf(
            rect(5f, 5f, 10f, 10f),
            "M7.6 5V1.6h4.8V5",
            "M7.6 15v3.4h4.8V15",
            "M10 10V6.8",
            "M10 10l2.6 1.7",
        ).forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
        addPath(addPathNodes(rect(15f, 9.1f, 1.8f, 1.8f)), fill = SolidColor(Color.White))
    }.build()
    /**
     * Two stacked rack units with a status light and a vent line each.
     *
     * This replaces a globe built from a circle and two ellipse arcs, which
     * rendered as a spiky asterisk at 14dp and broke the no-curves rule
     * anyway. A rack is rectangles only, and it says "a machine you own"
     * rather than "the internet", which is the actual distinction: these are
     * self-hosted servers, not web services.
     */
    val Server = ImageVector.Builder(
        defaultWidth = 18.dp, defaultHeight = 18.dp, viewportWidth = 18f, viewportHeight = 18f,
    ).apply {
        listOf(
            rrect(1.2f, 2.4f, 15.6f, 5.2f, 1.2f),
            rrect(1.2f, 10.0f, 15.6f, 5.2f, 1.2f),
            "M11.8 5.0h3.2",
            "M11.8 12.6h3.2",
        ).forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
        listOf(rect(3.6f, 4.2f, 1.7f, 1.7f), rect(3.6f, 11.8f, 1.7f, 1.7f))
            .forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

    val SignalBars = solid(
        17f, 12f,
        rect(0f, 8f, 3f, 4f), rect(4.6f, 5.5f, 3f, 6.5f),
        rect(9.2f, 3f, 3f, 9f), rect(13.8f, 0f, 3f, 12f),
    )
    val Battery = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 12.dp, viewportWidth = 24f, viewportHeight = 12f,
    ).apply {
        addPath(
            addPathNodes(rrect(0.5f, 0.5f, 20f, 11f, 3f)),
            stroke = SolidColor(Color.White), strokeLineWidth = 1f,
        )
        addPath(addPathNodes(rrect(2.5f, 2.5f, 15f, 7f, 1.5f)), fill = SolidColor(Color.White))
        addPath(addPathNodes(rrect(22f, 4f, 2f, 4f, 1f)), fill = SolidColor(Color.White))
    }.build()
}
