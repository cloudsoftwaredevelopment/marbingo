package ph.marbingo.core.print

import java.io.ByteArrayOutputStream

/** Minimal ESC/POS byte builder for 58mm thermal printers. Text is forced to ASCII-safe/CP437. */
class EscPos {
    private val out = ByteArrayOutputStream()

    enum class Align(val code: Int) { LEFT(0), CENTER(1), RIGHT(2) }

    fun init() = apply { out.write(byteArrayOf(0x1B, 0x40)) }
    fun align(a: Align) = apply { out.write(byteArrayOf(0x1B, 0x61, a.code.toByte())) }
    fun bold(on: Boolean) = apply { out.write(byteArrayOf(0x1B, 0x45, if (on) 1 else 0)) }
    /** GS ! n: bit 4 = double height. */
    fun doubleHeight(on: Boolean) = apply { out.write(byteArrayOf(0x1D, 0x21, if (on) 0x01 else 0x00)) }
    fun text(s: String) = apply { out.write(transliterate(s).toByteArray(Charsets.ISO_8859_1)) }
    fun line(s: String = "") = apply { text(s); out.write(0x0A) }
    fun feed(n: Int) = apply { out.write(byteArrayOf(0x1B, 0x64, n.coerceIn(0, 255).toByte())) }
    /** GS V 66 n: feed then partial cut. */
    fun cut() = apply { out.write(byteArrayOf(0x1D, 0x56, 66, 0)) }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        /** Maps characters a cheap printer can't print to ASCII equivalents; unknown -> '?'. */
        fun transliterate(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when {
                    c == '\n' -> sb.append(c)
                    c in ' '..'~' -> sb.append(c)
                    c == '₱' -> sb.append('P')
                    c == '‘' || c == '’' || c == '‚' || c == '′' -> sb.append('\'')
                    c == '“' || c == '”' || c == '„' || c == '″' -> sb.append('"')
                    c == '–' || c == '—' || c == '−' -> sb.append('-')
                    c == '…' -> sb.append("...")
                    c == '×' -> sb.append('x')
                    c == ' ' -> sb.append(' ')
                    c == '\t' -> sb.append(' ')
                    else -> {
                        val base = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
                            .filter { it in ' '..'~' }
                        sb.append(if (base.isNotEmpty()) base else "?")
                    }
                }
            }
            return sb.toString()
        }
    }
}
