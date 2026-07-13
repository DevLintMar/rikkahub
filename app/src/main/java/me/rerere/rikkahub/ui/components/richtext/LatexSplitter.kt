package me.rerere.rikkahub.ui.components.richtext

/**
 * 在顶层运算符位置拆分 LaTeX 公式为多段，每段宽度 ≤ maxWidthPx。
 * 纯 Kotlin 移植自 JLatexMathSplitter.java，宽度测量改用 RaTeX。
 *
 * 拆分点位于顶层二元运算符和关系符号之前（不在 {} () [] 或 \left…\right / \begin…\end 内），
 * 使得运算符出现在换行后的行首，符合数学排版惯例。
 *
 * @param latex LaTeX 源码（不含 $ 定界符）
 * @param maxWidthPx 每段最大允许宽度（px）
 * @param fontSizePx 字号（px），用于宽度测量
 * @return 分段列表，空列表表示拆分失败（调用方应回退到单段）
 */
fun splitLatex(
    latex: String,
    maxWidthPx: Float,
    fontSizePx: Float,
): List<String> {
    val splitPositions = findTopLevelSplitPositions(latex)
    if (splitPositions.isEmpty()) return listOf(latex)

    val boundaries = splitPositions + latex.length
    val result = mutableListOf<String>()
    var segmentStart = 0
    var lastGoodEnd = -1

    for (boundary in boundaries) {
        if (boundary <= segmentStart) continue

        val candidate = latex.substring(segmentStart, boundary).trim()
        if (candidate.isEmpty()) continue

        val width = measureWidth(candidate, fontSizePx)

        if (width <= maxWidthPx) {
            lastGoodEnd = boundary
        } else {
            if (lastGoodEnd > segmentStart) {
                // 落盘上一个可以完整容纳的段
                result.add(latex.substring(segmentStart, lastGoodEnd).trim())
                segmentStart = lastGoodEnd
                lastGoodEnd = -1

                // 重新测试从新起点到当前边界能否容纳
                val newCandidate = latex.substring(segmentStart, boundary).trim()
                if (newCandidate.isNotEmpty() && measureWidth(newCandidate, fontSizePx) <= maxWidthPx) {
                    lastGoodEnd = boundary
                }
            } else {
                // 单段本身就超宽，原样落盘（不截断）
                result.add(candidate)
                segmentStart = boundary
                lastGoodEnd = -1
            }
        }
    }

    // 收尾剩余内容
    if (segmentStart < latex.length) {
        val remaining = latex.substring(segmentStart).trim()
        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }
    }

    return result.ifEmpty { listOf(latex) }
}

/**
 * 返回 LaTeX 源码中顶层运算符的字符位置。
 * 这些位置是潜在分段点——新段从该位置开始（运算符在行首）。
 */
private fun findTopLevelSplitPositions(latex: String): List<Int> {
    val positions = mutableListOf<Int>()
    var depth = 0
    var i = 0

    while (i < latex.length) {
        val c = latex[i]

        when {
            c == '{' || c == '(' || c == '[' -> depth++
            (c == '}' || c == ')' || c == ']') && depth > 0 -> depth--
            c == '\\' -> {
                val cmdEnd = findCommandEnd(latex, i)
                val cmd = latex.substring(i, cmdEnd)

                when {
                    cmd == "\\left" || cmd == "\\begin" -> depth++
                    (cmd == "\\right" || cmd == "\\end") && depth > 0 -> depth--
                    depth == 0 && cmd in SPLIT_COMMANDS -> positions.add(i)
                }

                i = cmdEnd
                continue
            }
            depth == 0 && c in SPLIT_CHARS -> positions.add(i)
        }

        i++
    }

    return positions
}

/**
 * 返回从 start（'\\' 位置）开始的 LaTeX 命令结束位置（不含）。
 * 如果下一字符是非字母（如 \{），返回 start+2；
 * 如果是字母命令（如 \times），扫描到字母结束。
 */
private fun findCommandEnd(latex: String, start: Int): Int {
    var i = start + 1
    if (i >= latex.length) return i
    if (!latex[i].isLetter()) return i + 1
    while (i < latex.length && latex[i].isLetter()) i++
    return i
}

/**
 * 测量一段 LaTeX 源码的渲染宽度（px）。
 * 测量失败时返回 [Float.MAX_VALUE]，使该段不被容纳，触发进一步拆分。
 */
private fun measureWidth(latex: String, fontSizePx: Float): Float {
    return assumeLatexSize(latex, fontSizePx)?.widthPx ?: Float.MAX_VALUE
}

/**
 * 分割字符——这些字符出现在顶层时作为分段点。
 */
private val SPLIT_CHARS = setOf('+', '-', '=', '<', '>')

/**
 * 分割命令——这些 LaTeX 命令出现在顶层时作为分段点。
 * 分类：关系符号（\leq、\approx 等）和二元运算符（\times、\cup 等）。
 */
private val SPLIT_COMMANDS = setOf(
    // 关系符号
    "\\leq", "\\le", "\\geq", "\\ge",
    "\\neq", "\\ne", "\\approx", "\\equiv",
    "\\sim", "\\simeq", "\\cong",
    "\\subset", "\\supset", "\\subseteq", "\\supseteq",
    "\\sqsubset", "\\sqsupset", "\\sqsubseteq", "\\sqsupseteq",
    "\\in", "\\notin", "\\ni",
    "\\to", "\\gets", "\\rightarrow", "\\leftarrow",
    "\\Rightarrow", "\\Leftarrow", "\\Leftrightarrow", "\\leftrightarrow",
    "\\longrightarrow", "\\longleftarrow",
    "\\Longrightarrow", "\\Longleftarrow", "\\Longleftrightarrow",
    "\\implies", "\\iff",
    "\\propto", "\\perp", "\\parallel",
    "\\vdash", "\\dashv", "\\models",
    "\\asymp", "\\bowtie", "\\smile", "\\frown",
    // 二元运算符
    "\\pm", "\\mp",
    "\\times", "\\div", "\\cdot",
    "\\cup", "\\cap", "\\sqcup", "\\sqcap",
    "\\oplus", "\\ominus", "\\otimes", "\\oslash", "\\odot",
    "\\wedge", "\\vee",
    "\\setminus", "\\circ", "\\bullet",
    "\\star", "\\ast", "\\dagger", "\\ddagger",
    "\\amalg", "\\uplus",
)
