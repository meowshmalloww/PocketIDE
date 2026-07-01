package com.pocketide.ui.editor

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.pocketide.data.model.Language

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

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        visualTransformation = transformation,
        modifier = modifier,
    )
}
