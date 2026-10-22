package app.kinframe.family.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Frame codes: 8 digits, shown as `1234 5678` (README §5.1). People type, paste a code with spaces or dashes, or
 * paste the whole join link from the QR code; all of those work.
 */
object FrameCodes {
    const val LENGTH = 8

    /** The digits someone typed or pasted, at most 8. A pasted join link yields the code inside it. */
    fun digits(input: String): String {
        DeepLinks.parse(input.trim())?.let { if (it is DeepLink.FrameCode) return it.code }
        return input.filter { it in '0'..'9' }.take(LENGTH)
    }

    /** A complete code, or null. */
    fun normalize(input: String): String? = digits(input).takeIf { it.length == LENGTH }

    /** `12345678` → `1234 5678`; partial input stays partial (`12345` → `1234 5`). */
    fun format(digits: String): String =
        if (digits.length <= 4) digits else digits.take(4) + " " + digits.drop(4)

    /** Spoken form for screen readers: "1 2 3 4, 5 6 7 8". */
    fun spoken(digits: String): String =
        digits.chunked(4).joinToString(", ") { it.toList().joinToString(" ") }

    /** Shows the code with a space after the fourth digit while the field holds digits only. */
    val visualTransformation = VisualTransformation { text ->
        val out = format(text.text)
        TransformedText(AnnotatedString(out), CodeOffsetMapping)
    }

    object CodeOffsetMapping : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int = if (offset <= 4) offset else offset + 1
        override fun transformedToOriginal(offset: Int): Int = if (offset <= 4) offset else offset - 1
    }
}
