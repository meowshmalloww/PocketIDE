package com.pocketide.ui.components

import androidx.compose.ui.graphics.Color
import com.pocketide.data.model.Language

fun languageAbbrev(language: Language): String = when (language) {
    Language.PYTHON -> "PY"
    Language.JAVASCRIPT -> "JS"
    Language.TYPESCRIPT -> "TS"
    Language.KOTLIN -> "KT"
    Language.DART -> "DA"
    Language.SQL -> "SQL"
    Language.HTML -> "HTM"
    Language.CSS -> "CSS"
    Language.JAVA -> "JV"
    Language.LUA -> "LUA"
    Language.SHELL -> "SH"
    Language.YAML -> "YML"
    Language.MARKDOWN -> "MD"
    Language.JSON -> "JSON"
}

fun languageColor(language: Language): Color = when (language) {
    Language.PYTHON -> Color(0xFF4584B6)
    Language.JAVASCRIPT -> Color(0xFFF7DF1E)
    Language.TYPESCRIPT -> Color(0xFF3178C6)
    Language.KOTLIN -> Color(0xFF7F52FF)
    Language.DART -> Color(0xFF0175C2)
    Language.SQL -> Color(0xFFE38900)
    Language.HTML -> Color(0xFFE34F26)
    Language.CSS -> Color(0xFF1572B6)
    Language.JAVA -> Color(0xFFED8B00)
    Language.LUA -> Color(0xFF2C2D72)
    Language.SHELL -> Color(0xFF4EAA25)
    Language.YAML -> Color(0xFFCB171E)
    Language.MARKDOWN -> Color(0xFF6C7A89)
    Language.JSON -> Color(0xFFA0A500)
}
