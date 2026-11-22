package id.codeagent.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class MdBlock {
    class Code(val text: String) : MdBlock()
    class Line(val text: String) : MdBlock()
}

fun parseBlocks(t: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    var inCode = false
    val buf = StringBuilder()
    for (l in t.lines()) {
        if (l.trimStart().startsWith("```")) {
            if (inCode) { out.add(MdBlock.Code(buf.toString().trimEnd())); buf.clear(); inCode = false } else inCode = true
            continue
        }
        if (inCode) buf.append(l).append('\n') else if (l.isNotBlank()) out.add(MdBlock.Line(l))
    }
    if (inCode) out.add(MdBlock.Code(buf.toString().trimEnd()))
    return out
}

private val inlineRe = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

fun inlineMd(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRe.findAll(s)) {
        append(s.substring(last, m.range.first))
        if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(m.groupValues[2]) }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

@Composable
fun Md(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.Code -> Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
                Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp))
            }
            is MdBlock.Line -> {
                val l = b.text
                val h = Regex("^(#{1,6})\\s+(.*)").find(l)
                val bullet = Regex("^\\s*[-*]\\s+(.*)").find(l)
                when {
                    h != null -> Text(inlineMd(h.groupValues[2], bg), fontWeight = FontWeight.Bold,
                        fontSize = when (h.groupValues[1].length) { 1 -> 20.sp; 2 -> 18.sp; else -> 16.sp })
                    bullet != null -> Text(inlineMd("•  " + bullet.groupValues[1], bg), modifier = Modifier.padding(start = 6.dp))
                    l.startsWith(">") -> Text(inlineMd(l.removePrefix(">").trim(), bg), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp))
                    else -> Text(inlineMd(l, bg))
                }
            }
        }
    }
}
