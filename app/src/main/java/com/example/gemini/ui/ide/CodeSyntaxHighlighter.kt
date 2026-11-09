package com.example.gemini.ui.ide

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.util.regex.Pattern

object CodeSyntaxColors {
    val Keyword = Color(0xFFC586C0)      // Soft Purple
    val ControlKeyword = Color(0xFFD16D9E)// Pink Rose
    val Type = Color(0xFF4EC9B0)         // Cyan / Teal
    val Function = Color(0xFFDCDCAA)     // Soft Yellow
    val String = Color(0xFFCE9178)       // Amber / Orange
    val Number = Color(0xFFB5CEA8)       // Mint Green
    val Comment = Color(0xFF6A9955)      // Muted Green
    val Tag = Color(0xFF569CD6)          // Soft Blue
    val Attribute = Color(0xFF9CDCFE)    // Light Cyan
    val Property = Color(0xFF9CDCFE)     // Light Blue Property
    val NumberLiteral = Color(0xFFB5CEA8)// Number mint
    val Default = Color(0xFFD4D4D4)      // VSCode Default Foreground
}

class CodeSyntaxVisualTransformation(private val fileName: String) : VisualTransformation {

    private var lastRawText: String? = null
    private var lastTransformed: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val cached = lastTransformed
        val highlighted = if (lastRawText == raw && cached != null) {
            cached
        } else {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val result = highlightCode(raw, ext)
            lastRawText = raw
            lastTransformed = result
            result
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }

    companion object {

        private val KEYWORDS = setOf(
            // Go, Python, JS, TS, Kotlin, Java, C, C++, Rust, Shell
            "package", "import", "func", "def", "class", "struct", "interface", "type",
            "val", "var", "const", "let", "function", "fn", "pub", "mut", "impl", "trait",
            "enum", "union", "typedef", "namespace", "using", "template", "typename",
            "public", "private", "protected", "override", "abstract", "sealed", "virtual",
            "static", "final", "volatile", "transient", "synchronized", "native", "extern",
            "inline", "constexpr", "explicit", "friend", "operator", "alias", "as"
        )

        private val CONTROL_KEYWORDS = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "default", "break",
            "continue", "return", "goto", "yield", "await", "async", "defer", "go",
            "try", "catch", "finally", "throw", "throws", "raise", "except", "with",
            "select", "range", "in", "is", "not", "and", "or"
        )

        private val TYPES = setOf(
            "int", "int8", "int16", "int32", "int64",
            "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
            "float", "float32", "float64", "double", "real",
            "bool", "boolean", "string", "rune", "byte", "char", "short", "long", "void",
            "any", "object", "nil", "null", "none", "true", "false", "self", "this", "super",
            "True", "False", "None"
        )

        // Precompiled Regex Patterns
        private val SINGLE_LINE_COMMENT = Pattern.compile("(//.*|#.*)")
        private val MULTI_LINE_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/")
        private val DOUBLE_QUOTE_STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"")
        private val SINGLE_QUOTE_STRING = Pattern.compile("'([^'\\\\]|\\\\.)*'")
        private val BACKTICK_STRING = Pattern.compile("`([^`\\\\]|\\\\.)*`")
        private val NUMBER_PATTERN = Pattern.compile("\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?)\\b")
        private val WORD_PATTERN = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
        private val FUNC_CALL_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
        private val HTML_TAG_PATTERN = Pattern.compile("</?[a-zA-Z0-9:-]+(\\s+[^>]*)?/?>")
        private val JSON_KEY_PATTERN = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"(?=\\s*:)")

        fun highlightCode(code: String, ext: String): AnnotatedString {
            // Fast-path: Native Rust single-pass lexer via libantibot_native.so
            val nativeSpans = com.example.gemini.native.AntiBotNative.highlightSyntax(code, ext)
            if (nativeSpans != null) {
                return buildAnnotatedString {
                    append(code)
                    val len = code.length
                    for (i in 0 until nativeSpans.size step 3) {
                        val s = nativeSpans[i].coerceIn(0, len)
                        val e = nativeSpans[i + 1].coerceIn(0, len)
                        val type = nativeSpans[i + 2]
                        if (s < e) {
                            val style = when (type) {
                                com.example.gemini.native.AntiBotNative.TokenType.KEYWORD ->
                                    SpanStyle(color = CodeSyntaxColors.Keyword, fontWeight = FontWeight.Bold)
                                com.example.gemini.native.AntiBotNative.TokenType.CONTROL_KEYWORD ->
                                    SpanStyle(color = CodeSyntaxColors.ControlKeyword, fontWeight = FontWeight.Bold)
                                com.example.gemini.native.AntiBotNative.TokenType.TYPE ->
                                    SpanStyle(color = CodeSyntaxColors.Type)
                                com.example.gemini.native.AntiBotNative.TokenType.FUNCTION ->
                                    SpanStyle(color = CodeSyntaxColors.Function)
                                com.example.gemini.native.AntiBotNative.TokenType.STRING ->
                                    SpanStyle(color = CodeSyntaxColors.String)
                                com.example.gemini.native.AntiBotNative.TokenType.NUMBER ->
                                    SpanStyle(color = CodeSyntaxColors.Number)
                                com.example.gemini.native.AntiBotNative.TokenType.COMMENT ->
                                    SpanStyle(color = CodeSyntaxColors.Comment)
                                com.example.gemini.native.AntiBotNative.TokenType.TAG ->
                                    SpanStyle(color = CodeSyntaxColors.Tag)
                                com.example.gemini.native.AntiBotNative.TokenType.PROPERTY ->
                                    SpanStyle(color = CodeSyntaxColors.Property)
                                com.example.gemini.native.AntiBotNative.TokenType.ANNOTATION ->
                                    SpanStyle(color = CodeSyntaxColors.ControlKeyword, fontWeight = FontWeight.SemiBold)
                                else -> null
                            }
                            if (style != null) {
                                addStyle(style, s, e)
                            }
                        }
                    }
                }
            }

            // Fallback JVM regex path
            return buildAnnotatedString {
                append(code)

                // 1. Strings
                highlightMatches(DOUBLE_QUOTE_STRING.matcher(code), SpanStyle(color = CodeSyntaxColors.String))
                highlightMatches(SINGLE_QUOTE_STRING.matcher(code), SpanStyle(color = CodeSyntaxColors.String))
                highlightMatches(BACKTICK_STRING.matcher(code), SpanStyle(color = CodeSyntaxColors.String))

                // Special handling for HTML / XML / SVG
                if (ext in listOf("html", "xml", "svg", "jsx", "tsx")) {
                    highlightMatches(HTML_TAG_PATTERN.matcher(code), SpanStyle(color = CodeSyntaxColors.Tag))
                }

                // Special handling for JSON
                if (ext == "json") {
                    highlightMatches(JSON_KEY_PATTERN.matcher(code), SpanStyle(color = CodeSyntaxColors.Property))
                }

                // 2. Numbers
                highlightMatches(NUMBER_PATTERN.matcher(code), SpanStyle(color = CodeSyntaxColors.Number))

                // 3. Identifiers & Keywords
                val wordMatcher = WORD_PATTERN.matcher(code)
                while (wordMatcher.find()) {
                    val word = wordMatcher.group()
                    val start = wordMatcher.start()
                    val end = wordMatcher.end()

                    when {
                        CONTROL_KEYWORDS.contains(word) -> {
                            addStyle(SpanStyle(color = CodeSyntaxColors.ControlKeyword, fontWeight = FontWeight.Bold), start, end)
                        }
                        KEYWORDS.contains(word) -> {
                            addStyle(SpanStyle(color = CodeSyntaxColors.Keyword, fontWeight = FontWeight.Bold), start, end)
                        }
                        TYPES.contains(word) -> {
                            addStyle(SpanStyle(color = CodeSyntaxColors.Type), start, end)
                        }
                    }
                }

                // 4. Function Calls
                val funcMatcher = FUNC_CALL_PATTERN.matcher(code)
                while (funcMatcher.find()) {
                    val name = funcMatcher.group(1) ?: continue
                    val start = funcMatcher.start(1)
                    val end = funcMatcher.end(1)
                    if (!KEYWORDS.contains(name) && !CONTROL_KEYWORDS.contains(name)) {
                        addStyle(SpanStyle(color = CodeSyntaxColors.Function), start, end)
                    }
                }

                // 5. Comments (Applied LAST so comments override strings/keywords inside them)
                highlightMatches(SINGLE_LINE_COMMENT.matcher(code), SpanStyle(color = CodeSyntaxColors.Comment))
                highlightMatches(MULTI_LINE_COMMENT.matcher(code), SpanStyle(color = CodeSyntaxColors.Comment))
            }
        }

        private fun AnnotatedString.Builder.highlightMatches(matcher: java.util.regex.Matcher, style: SpanStyle) {
            while (matcher.find()) {
                addStyle(style, matcher.start(), matcher.end())
            }
        }
    }
}
