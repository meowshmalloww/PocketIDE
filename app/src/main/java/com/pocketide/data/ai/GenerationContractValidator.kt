package com.pocketide.data.ai

import com.pocketide.data.model.Language
import kotlin.math.abs

/**
 * Deterministic local checks for common small-model failures before generated files are applied.
 *
 * These checks do not claim to prove program correctness. They reject observable contradictions
 * between the request, PocketIDE's runtimes, sibling files, and the generated source, then feed
 * concise evidence into the next bounded model attempt.
 */
object GenerationContractValidator {

    fun promptGuidance(
        request: String,
        target: CodeFileTask,
        allTargets: List<CodeFileTask>,
    ): List<String> {
        val lower = request.lowercase()
        val guidance = mutableListOf<String>()
        if (target.language == Language.PYTHON && "hardware" in lower) {
            val methods = requestedHardwareMethods(lower)
            guidance += "Use the injected hardware global directly. Never write import hardware."
            if (methods.isNotEmpty()) {
                guidance += "Required Android calls: ${methods.joinToString { "hardware.$it(...)" }}."
            }
            if ("unavailable" in lower || "fails" in lower || "fail" in lower) {
                guidance += "Give every requested hardware call its own try/except block. On failure print exactly Unavailable and continue."
            }
        }
        if (target.language == Language.PYTHON && requestsRepeatedChoice(lower)) {
            guidance += "If the menu displays numbers, accept those numbers as well as the displayed words."
        }
        if (target.language == Language.HTML && allTargets.size == 1) {
            guidance += "This is one self-contained HTML file. Put all CSS in style and all JavaScript in script; reference no sibling files."
        }
        if (target.language in WEB_LANGUAGES && requestsPersistence(lower)) {
            guidance += "Persist after every change with localStorage.setItem and restore on startup with localStorage.getItem."
        }
        if (target.language in WEB_LANGUAGES && "percentage" in lower) {
            guidance += "Compute percentage as completed item count divided by total item count times 100; never use a guessed fixed multiplier."
        }
        if (target.language in WEB_LANGUAGES) {
            guidance += "Every DOM ID, class, and data attribute used by JavaScript must exist in the HTML."
            if (requestsBrowserInteraction(lower)) {
                guidance +=
                    "Connect the requested controls to real click, change, or input handlers. A visual-only mockup is not complete."
            }
        }
        return guidance.distinct()
    }

    fun validate(
        request: String,
        target: CodeFileTask,
        allTargets: List<CodeFileTask>,
        completedFiles: List<ParsedAiFile>,
        code: String,
    ): List<String> {
        val issues = linkedSetOf<String>()
        val lowerRequest = request.lowercase()
        val trimmed = code.trim()
        if (!looksLikeSource(trimmed, target.language)) {
            issues += "Return source code only, without an explanation or Markdown wrapper."
            return issues.toList()
        }
        if (PLACEHOLDER_REGEX.containsMatchIn(trimmed)) {
            issues += "Replace placeholder or TODO text with complete runnable code."
        }

        when (target.language) {
            Language.PYTHON -> validatePython(lowerRequest, trimmed, issues)
            Language.HTML -> validateHtml(lowerRequest, target, allTargets, trimmed, issues)
            Language.JAVASCRIPT, Language.TYPESCRIPT -> {
                val html = completedFiles.firstOrNull { it.language == Language.HTML }?.code
                if (html != null) validateBrowserScript(lowerRequest, html, trimmed, issues)
                validateWebRequirements(lowerRequest, trimmed, html, issues)
                if (html == null) validateScriptRuntime(target.language, trimmed, issues)
            }
            Language.CSS -> if (!balancedDelimiters(trimmed)) {
                issues += "Close every CSS block before the file ends."
            }
            Language.JSON -> runCatching { org.json.JSONTokener(trimmed).nextValue() }
                .onFailure { issues += "Return valid complete JSON: ${it.message ?: "parse failed"}." }
            else -> if (!balancedDelimiters(trimmed, hashStartsComment = target.language == Language.SHELL)) {
                issues += "Close every string, parenthesis, bracket, and block before the file ends."
            }
        }
        return issues.toList()
    }

    fun looksLikeSource(text: String, language: Language): Boolean {
        if (text.isBlank()) return false
        return when (language) {
            Language.PYTHON -> Regex(
                """(?m)^\s*(?:from\s+\S+\s+import|import\s+|def\s+|class\s+|try\s*:|if\s+|while\s+|for\s+|print\s*\(|[A-Za-z_]\w*\s*=)""",
            ).containsMatchIn(text) || "hardware." in text
            Language.HTML -> text.trimStart().let { it.startsWith("<!doctype", true) || it.startsWith("<html", true) }
            Language.CSS -> '{' in text && '}' in text
            Language.JSON -> text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            Language.SQL -> Regex("""(?i)\b(?:select|insert|update|delete|create|with|pragma)\b""").containsMatchIn(text)
            Language.SHELL -> text.startsWith("#!") || Regex("""(?m)^\s*(?:echo|if|for|while|[A-Za-z_]\w*=)""").containsMatchIn(text)
            else -> '=' in text || '{' in text || '(' in text
        }
    }

    private fun validatePython(
        request: String,
        code: String,
        issues: MutableSet<String>,
    ) {
        if (!balancedDelimiters(code, hashStartsComment = true)) {
            issues += "Close every Python string, parenthesis, bracket, and dictionary before the file ends."
        }
        val imports = PYTHON_IMPORT_REGEX.containsMatchIn(code)
        if (("do not import" in request || "no imports" in request || "use no imports" in request) && imports) {
            issues += "The request forbids imports; remove every import statement."
        }
        if ("hardware" in request) {
            if (PYTHON_HARDWARE_IMPORT_REGEX.containsMatchIn(code)) {
                issues += "hardware is an injected global, so remove import hardware."
            }
            requestedHardwareMethods(request).forEach { method ->
                if (!Regex("""\bhardware\s*\.\s*${Regex.escape(method)}\s*\(""").containsMatchIn(code)) {
                    issues += "Add the requested hardware.$method(...) call."
                }
            }
            if (("unavailable" in request || "fail" in request) &&
                !("try:" in code && "except" in code && "unavailable" in code.lowercase())
            ) {
                issues += "Catch hardware failures and print Unavailable."
            }
            if ("unavailable" in request || "fail" in request) {
                val requiredCalls = requestedHardwareMethods(request).size
                val recoveryBlocks = Regex("""(?m)^\s*try\s*:""").findAll(code).count()
                if (requiredCalls > 1 && recoveryBlocks < requiredCalls) {
                    issues += "Give each requested hardware call its own try/except block so one failure cannot skip later calls."
                }
            }
        }
        if (requestsInput(request) && !Regex("""\binput\s*\(""").containsMatchIn(code)) {
            issues += "Use input() for the requested terminal interaction."
        }
        if (requestsRepeatedChoice(request) &&
            !(Regex("""(?m)^\s*while\b""").containsMatchIn(code) && "break" in code)
        ) {
            issues += "Keep the input menu in a loop and break when the quit choice is entered."
        }
        validateDisplayedNumericChoices(code, issues)
    }

    private fun validateDisplayedNumericChoices(code: String, issues: MutableSet<String>) {
        val displayed = PRINTED_NUMERIC_CHOICE_REGEX.findAll(code)
            .map { it.groupValues[1] }
            .toSet()
        if (displayed.isEmpty() || !Regex("""\binput\s*\(""").containsMatchIn(code)) return
        val decisionCode = code.lineSequence()
            .filterNot { it.trimStart().startsWith("print(") }
            .joinToString("\n")
        val missing = displayed.filterNot { number ->
            Regex("""[\"']${Regex.escape(number)}[\"']""").containsMatchIn(decisionCode)
        }
        if (missing.isNotEmpty()) {
            issues += "The menu displays numeric choices ${missing.joinToString()}, but the input logic does not accept them."
        }
    }

    private fun validateHtml(
        request: String,
        target: CodeFileTask,
        allTargets: List<CodeFileTask>,
        code: String,
        issues: MutableSet<String>,
    ) {
        if (!code.contains("</html>", ignoreCase = true)) issues += "Close the HTML document with </html>."
        val targetNames = allTargets.map { it.filename.lowercase() }.toSet()
        val externalCss = LOCAL_STYLESHEET_REGEX.findAll(code).map { it.groupValues[1] }.toList()
        val externalScripts = LOCAL_SCRIPT_REGEX.findAll(code).map { it.groupValues[1] }.toList()
        if (allTargets.size == 1) {
            if (externalCss.isNotEmpty() || externalScripts.isNotEmpty()) {
                issues += "The request allows only ${target.filename}; inline all CSS and JavaScript instead of referencing sibling files."
            }
            if (requestsBrowserInteraction(request) && !STYLE_REGEX.containsMatchIn(code)) {
                issues += "Add the requested phone styling inside a style element."
            }
            if (requestsBrowserInteraction(request) && !SCRIPT_REGEX.containsMatchIn(code)) {
                issues += "Add the requested interaction inside a script element."
            }
        } else {
            (externalCss + externalScripts).forEach { reference ->
                if (reference.substringAfterLast('/').lowercase() !in targetNames) {
                    issues += "The HTML references $reference, but that file is not part of the requested project."
                }
            }
        }
        val script = SCRIPT_REGEX.find(code)?.groupValues?.get(1).orEmpty()
        val hasScriptSibling = allTargets.any {
            it.language == Language.JAVASCRIPT || it.language == Language.TYPESCRIPT
        }
        if (!hasScriptSibling || script.isNotBlank()) {
            validateBrowserScript(request, code, script, issues)
            validateWebRequirements(request, script, code, issues)
        }
    }

    private fun validateBrowserScript(
        request: String,
        html: String,
        script: String,
        issues: MutableSet<String>,
    ) {
        if (requestsBrowserInteraction(request) && script.isBlank()) {
            issues += "Implement the requested browser interaction instead of leaving an empty script."
            return
        }
        val ids = HTML_ID_REGEX.findAll(html).map { it.groupValues[1] }.toSet()
        val attributes = HTML_DATA_ATTRIBUTE_REGEX.findAll(html).map { it.groupValues[1].lowercase() }.toSet()
        val classes = HTML_CLASS_REGEX.findAll(html)
            .flatMap { it.groupValues[1].split(Regex("""\s+""")).asSequence() }
            .filter { it.isNotBlank() }
            .toSet()
        GET_ELEMENT_BY_ID_REGEX.findAll(script).forEach { match ->
            val id = match.groupValues[1]
            if (id !in ids) issues += "JavaScript requests #$id, but the HTML has no id=\"$id\" element."
        }
        QUERY_SELECTOR_REGEX.findAll(script).forEach { match ->
            val selector = match.groupValues[1]
            Regex("""#([A-Za-z_][\w-]*)""").findAll(selector).forEach { idMatch ->
                val id = idMatch.groupValues[1]
                if (id !in ids) issues += "JavaScript selector #$id has no matching HTML element."
            }
            Regex("""\[(data-[A-Za-z_][\w-]*)""").findAll(selector).forEach { attrMatch ->
                val attribute = attrMatch.groupValues[1].lowercase()
                if (attribute !in attributes) issues += "JavaScript selector [$attribute] has no matching HTML attribute."
            }
            Regex("""\.([A-Za-z_][\w-]*)""").findAll(selector).forEach { classMatch ->
                val className = classMatch.groupValues[1]
                if (className !in classes) issues += "JavaScript selector .$className has no matching HTML class."
            }
        }
        INLINE_HANDLER_FUNCTION_REGEX.findAll(html).forEach { match ->
            val function = match.groupValues[1]
            val declared = Regex("""\bfunction\s+${Regex.escape(function)}\s*\(""").containsMatchIn(script) ||
                Regex("""\b(?:var|let|const)\s+${Regex.escape(function)}\s*=\s*function\b""").containsMatchIn(script)
            if (!declared) issues += "HTML calls $function(...), but JavaScript never defines that function."
        }
        if (!balancedDelimiters(script)) {
            issues += "Close every JavaScript string, parenthesis, bracket, and block before the file ends."
        }
    }

    private fun validateWebRequirements(
        request: String,
        script: String,
        html: String?,
        issues: MutableSet<String>,
    ) {
        if (requestsPersistence(request)) {
            if (!Regex("""\blocalStorage\s*\.\s*setItem\s*\(""").containsMatchIn(script)) {
                issues += "Save changed state with localStorage.setItem(...)."
            }
            if (!Regex("""\blocalStorage\s*\.\s*getItem\s*\(""").containsMatchIn(script)) {
                issues += "Restore saved state with localStorage.getItem(...) on startup."
            }
        }
        if (requestsBrowserInteraction(request) && script.isNotBlank()) {
            val hasInlineHandler = html?.let { INLINE_HANDLER_ATTRIBUTE_REGEX.containsMatchIn(it) } == true
            val hasRegisteredHandler = EVENT_LISTENER_REGEX.containsMatchIn(script) ||
                EVENT_PROPERTY_ASSIGNMENT_REGEX.containsMatchIn(script)
            if (!hasInlineHandler && !hasRegisteredHandler) {
                issues +=
                    "Connect the requested controls to a click, change, or input handler; the current page is only a visual mockup."
            }
        }
        if ("percentage" in request) {
            val buttonCount = html?.let { BUTTON_REGEX.findAll(it).count() } ?: 0
            FIXED_PERCENT_MULTIPLIER_REGEX.find(script)?.let { match ->
                val multiplier = match.groupValues[1].toDoubleOrNull()
                if (buttonCount > 1 && multiplier != null && abs(multiplier * buttonCount - 100.0) > 1.0) {
                    issues += "The fixed percentage multiplier $multiplier is wrong for $buttonCount items; calculate completed / total * 100."
                }
            }
            if (script.isNotBlank() && '/' !in script && !CORRECT_PERCENT_FUNCTION_REGEX.containsMatchIn(script)) {
                issues += "Calculate readiness percentage from completed count divided by total count."
            }
        }
    }

    private fun validateScriptRuntime(
        language: Language,
        code: String,
        issues: MutableSet<String>,
    ) {
        val unsupported = when {
            "=>" in code -> "arrow functions"
            Regex("""(?m)^\s*(?:let|const)\s+""").containsMatchIn(code) -> "let or const"
            Regex("""(?m)^\s*class\s+""").containsMatchIn(code) -> "classes"
            "?." in code -> "optional chaining"
            '`' in code -> "template literals"
            else -> null
        }
        if (unsupported != null) {
            issues += "PocketIDE's ${language.displayName} script runtime is ES5-compatible; replace $unsupported."
        }
    }

    private fun requestedHardwareMethods(request: String): Set<String> = buildSet {
        if ("battery" in request && "charging" !in request) add("batteryLevel")
        if ("battery level" in request) add("batteryLevel")
        if ("charging" in request) add("isCharging")
        if ("network" in request) add("networkType")
        if ("vibrat" in request) add("vibrate")
        if ("toast" in request) add("toast")
        if ("flashlight" in request || "torch" in request) add("setFlashlight")
        if ("device info" in request || "device information" in request) add("getDeviceInfo")
        if ("storage free" in request || "free storage" in request) add("storageFree")
        if ("sensor" in request || "accelerometer" in request) add("readSensor")
        if ("location" in request) add("getLocation")
    }

    private fun requestsInput(request: String): Boolean =
        Regex("""\b(?:input|enter|choose|prompt)\b""").containsMatchIn(request) ||
            "ask me" in request || "ask the user" in request ||
            Regex("""\btype\s+(?:in|a|an|the|your)\b""").containsMatchIn(request)

    private fun requestsRepeatedChoice(request: String): Boolean =
        "keep asking" in request || "repeatedly" in request ||
            Regex("""until\s+(?:i\s+)?(?:enter|type|choose)""").containsMatchIn(request)

    private fun requestsPersistence(request: String): Boolean =
        "localstorage" in request || "local storage" in request || "save progress" in request ||
            "preserve" in request || "restore" in request || "remember" in request

    private fun requestsBrowserInteraction(request: String): Boolean =
        Regex("""\b(?:tap|tapping|click|button|checklist|dashboard|percentage|progress|interactive)\b""")
            .containsMatchIn(request)

    /** Lightweight lexical balance check which ignores delimiters inside strings and comments. */
    private fun balancedDelimiters(
        source: String,
        hashStartsComment: Boolean = false,
    ): Boolean {
        val stack = ArrayDeque<Char>()
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var index = 0
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            if (lineComment) {
                if (char == '\n') lineComment = false
                index++
                continue
            }
            if (blockComment) {
                if (char == '*' && next == '/') {
                    blockComment = false
                    index += 2
                } else index++
                continue
            }
            if (quote != null) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == quote) quote = null
                index++
                continue
            }
            if (char == '/' && next == '/') {
                lineComment = true
                index += 2
                continue
            }
            if (char == '/' && next == '*') {
                blockComment = true
                index += 2
                continue
            }
            if (hashStartsComment && char == '#') {
                lineComment = true
                index++
                continue
            }
            when (char) {
                '\'', '"', '`' -> quote = char
                '(', '[', '{' -> stack.addLast(char)
                ')' -> if (stack.removeLastOrNull() != '(') return false
                ']' -> if (stack.removeLastOrNull() != '[') return false
                '}' -> if (stack.removeLastOrNull() != '{') return false
            }
            index++
        }
        return quote == null && !blockComment && stack.isEmpty()
    }

    private val WEB_LANGUAGES = setOf(Language.HTML, Language.JAVASCRIPT, Language.TYPESCRIPT)
    private val PLACEHOLDER_REGEX = Regex(
        """(?im)^\s*(?:#|//|/\*|<!--)?\s*(?:TODO|FIXME|placeholder\b|complete file content\b|rest of (?:the )?code\b)""",
    )
    private val PYTHON_IMPORT_REGEX = Regex("""(?m)^\s*(?:import\s+|from\s+\S+\s+import\s+)""")
    private val PYTHON_HARDWARE_IMPORT_REGEX = Regex("""(?m)^\s*(?:import\s+hardware\b|from\s+hardware\s+import\b)""")
    private val PRINTED_NUMERIC_CHOICE_REGEX = Regex("""(?m)^\s*print\s*\(\s*[\"']\s*(\d+)\s*[.)]""")
    private val LOCAL_STYLESHEET_REGEX = Regex(
        """(?is)<link\b(?=[^>]*\brel\s*=\s*[\"']stylesheet[\"'])[^>]*\bhref\s*=\s*[\"'](?!https?://|data:|#)([^\"']+)[\"'][^>]*>""",
    )
    private val LOCAL_SCRIPT_REGEX = Regex(
        """(?is)<script\b[^>]*\bsrc\s*=\s*[\"'](?!https?://|data:)([^\"']+)[\"'][^>]*>""",
    )
    private val STYLE_REGEX = Regex("""(?is)<style\b[^>]*>[\s\S]*?</style>""")
    private val SCRIPT_REGEX = Regex("""(?is)<script\b(?![^>]*\bsrc\s*=)[^>]*>([\s\S]*?)</script>""")
    private val HTML_ID_REGEX = Regex("""(?i)\bid\s*=\s*[\"']([^\"']+)[\"']""")
    private val HTML_CLASS_REGEX = Regex("""(?i)\bclass\s*=\s*[\"']([^\"']+)[\"']""")
    private val HTML_DATA_ATTRIBUTE_REGEX = Regex("""(?i)\b(data-[A-Za-z_][\w-]*)\s*=""")
    private val GET_ELEMENT_BY_ID_REGEX = Regex("""\bgetElementById\s*\(\s*[\"']([^\"']+)[\"']\s*\)""")
    private val QUERY_SELECTOR_REGEX = Regex("""\bquerySelector(?:All)?\s*\(\s*[\"'`]([^\"'`]+)[\"'`]\s*\)""")
    private val INLINE_HANDLER_ATTRIBUTE_REGEX = Regex(
        """(?i)\bon(?:click|change|input|submit)\s*=\s*[\"'][^\"']+[\"']""",
    )
    private val INLINE_HANDLER_FUNCTION_REGEX = Regex(
        """(?i)\bon(?:click|change|input|submit)\s*=\s*[\"']\s*([A-Za-z_]\w*)\s*\(""",
    )
    private val EVENT_LISTENER_REGEX = Regex(
        """(?i)\baddEventListener\s*\(\s*[\"'](?:click|change|input|submit)[\"']\s*,""",
    )
    private val EVENT_PROPERTY_ASSIGNMENT_REGEX = Regex(
        """(?i)\.(?:onclick|onchange|oninput|onsubmit)\s*=""",
    )
    private val BUTTON_REGEX = Regex("""(?i)<button\b""")
    private val FIXED_PERCENT_MULTIPLIER_REGEX = Regex(
        """(?i)\b(?:readiness|readyCount|completedCount|completed|count)\s*\*\s*([0-9]+(?:\.[0-9]+)?)""",
    )
    private val CORRECT_PERCENT_FUNCTION_REGEX = Regex("""(?i)(?:\.length|\.size)\s*\)??\s*\*\s*100""")
}
