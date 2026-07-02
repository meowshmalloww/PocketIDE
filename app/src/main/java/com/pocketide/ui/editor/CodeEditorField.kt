package com.pocketide.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketide.data.model.Language

// Monospace fonts are roughly 0.6x as wide as they are tall; used to size
// the line-number gutter to fit the largest line number without wrapping.
private const val MONOSPACE_WIDTH_RATIO = 0.6f
private const val GUTTER_HORIZONTAL_PADDING_DP = 12
private const val LINE_HEIGHT_MULTIPLIER = 1.4f

@Composable
fun CodeEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    language: Language,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val transformation = remember(language) {
        VisualTransformation { text ->
            TransformedText(highlightCode(text.text, language), OffsetMapping.Identity)
        }
    }

    val lineCount = remember(value) { value.count { it == '\n' } + 1 }
    val gutterWidth = remember(lineCount, textStyle.fontSize) {
        val digitCount = lineCount.toString().length
        (digitCount * textStyle.fontSize.value * MONOSPACE_WIDTH_RATIO).dp + GUTTER_HORIZONTAL_PADDING_DP.dp
    }

    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current
    val lineHeightDp = with(density) { textStyle.fontSize.toDp() } * LINE_HEIGHT_MULTIPLIER
    val contentHeight = (lineCount * lineHeightDp.value).dp

    Row(modifier = modifier.verticalScroll(scrollState)) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .width(gutterWidth)
                .padding(end = 8.dp),
        ) {
            for (line in 1..lineCount) {
                Text(
                    text = line.toString(),
                    style = textStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
                .requiredHeight(contentHeight),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = textStyle,
                visualTransformation = transformation,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
