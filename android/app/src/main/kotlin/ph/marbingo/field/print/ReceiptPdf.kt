package ph.marbingo.field.print

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import ph.marbingo.field.data.Order
import ph.marbingo.field.data.Session
import ph.marbingo.field.ui.Fmt
import java.io.File

/** Renders an order slip as a 58mm-wide PDF (about 165pt) and hands it to the share sheet. */
object ReceiptPdf {
    const val PAGE_WIDTH = 165
    private const val MARGIN = 8f
    private const val FOOTER_TEXT = "Order only — not yet delivered"

    private val ink = 0xFF000000.toInt()

    private fun paint(size: Float, bold: Boolean = false, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textSize = size
        typeface = when {
            mono -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            else -> Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** Draws onto [canvas] (or just measures when null) and returns the total content height in points. */
    private fun layoutSlip(canvas: Canvas?, order: Order, session: Session): Float {
        val width = PAGE_WIDTH - 2 * MARGIN
        var y = MARGIN

        fun block(text: String, p: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, gap: Float = 2f) {
            val sl = StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt())
                .setAlignment(align).setLineSpacing(0f, 1f).setIncludePad(false).build()
            canvas?.let { c -> c.save(); c.translate(MARGIN, y); sl.draw(c); c.restore() }
            y += sl.height + gap
        }
        fun divider() {
            canvas?.let { c ->
                val p = Paint().apply { color = ink; strokeWidth = 0.6f; style = Paint.Style.STROKE
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 2f), 0f) }
                c.drawLine(MARGIN, y + 2f, PAGE_WIDTH - MARGIN, y + 2f, p)
            }
            y += 6f
        }
        fun twoCol(left: String, right: String, pl: TextPaint, pr: TextPaint, bold: Boolean = false) {
            val rightW = pr.measureText(right)
            val leftW = (width - rightW - 4f).toInt().coerceAtLeast(20)
            val sl = StaticLayout.Builder.obtain(left, 0, left.length, pl, leftW).setIncludePad(false).build()
            canvas?.let { c ->
                c.save(); c.translate(MARGIN, y); sl.draw(c); c.restore()
                c.drawText(right, PAGE_WIDTH - MARGIN - rightW, y + pr.textSize, pr)
            }
            y += maxOf(sl.height.toFloat(), pr.textSize + 1f) + 2f
        }

        val title = paint(11f, bold = true)
        val body = paint(8f)
        val bold = paint(8f, bold = true)
        val mono = paint(8f, mono = true)
        val monoBold = paint(10f, bold = true, mono = true)
        val small = paint(7f)
        val center = Layout.Alignment.ALIGN_CENTER

        block(session.shopName, title, center)
        block("ORDER SLIP", bold, center, gap = 4f)
        divider()
        block("Order ${order.reference}", bold)
        block("Store: ${order.storeName}", body)
        block("Agent: ${order.agentName ?: session.name}", body)
        block(Fmt.dateTime(order.orderedAt), mono, gap = 4f)
        divider()
        order.lines.forEach { l ->
            block(l.name, bold, gap = 1f)
            val parts = l.breakdown
            if (parts.isNullOrEmpty()) {
                twoCol("${l.qty}${l.unit?.let { " $it" } ?: ""} x ${Fmt.money(l.unitPrice)}", Fmt.money(l.lineTotal), mono, mono)
            } else {
                val u = l.unit ?: "pc"
                parts.forEach { p ->
                    val left = if (p.name != null) "  ${p.count} ${p.name} (${p.size} $u) x ${Fmt.money(p.price)}" else "  ${p.count} $u x ${Fmt.money(p.price)}"
                    twoCol(left, Fmt.money(p.amount), mono, mono)
                }
                if (parts.size > 1) twoCol("  Item total", Fmt.money(l.lineTotal), mono, mono)
            }
        }
        divider()
        twoCol("TOTAL", Fmt.money(order.total), paint(10f, bold = true), monoBold)
        if (!order.notes.isNullOrBlank()) {
            y += 2f
            block("Notes: ${order.notes}", body, gap = 4f)
        }
        divider()
        block(FOOTER_TEXT, bold, center, gap = 2f)
        block(if (order.serverId == null) "Not yet synced" else "Synced", small, center, gap = 2f)
        return y + MARGIN
    }

    fun render(context: Context, order: Order, session: Session): File {
        val height = Math.ceil(layoutSlip(null, order, session).toDouble()).toInt().coerceAtLeast(120)
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, height, 1).create())
            layoutSlip(page.canvas, order, session)
            doc.finishPage(page)
            val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
            val file = File(dir, fileName(order))
            file.outputStream().use { doc.writeTo(it) }
            return file
        } finally {
            doc.close()
        }
    }

    fun fileName(order: Order) = order.reference.replace(Regex("[^A-Za-z0-9_-]"), "") .ifEmpty { "order" } + ".pdf"

    /** Opens the system share sheet for the generated PDF. */
    fun share(context: Context, file: File, order: Order) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Order ${order.reference} - ${order.storeName}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share order slip").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
