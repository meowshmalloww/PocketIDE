package com.pocketide.data.ai

import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGenerationPipelineTest {

    @Test
    fun `emergency inventory request selects only the three requested source files`() {
        val request = """
            Create exactly three Python files named inventory.py, storage.py, and main.py.
            Build an offline emergency tracker. Store quantities in inventory.json.
            Create the complete runnable code, not an explanation.
        """.trimIndent()

        val targets = CodeGenerationPipeline.resolveTargets(request, null)

        assertEquals(listOf("inventory.py", "storage.py", "main.py"), targets.map { it.filename })
        assertFalse(targets.any { it.filename == "inventory.json" })
    }

    @Test
    fun `hardware request accepts markdown escaped filename`() {
        val targets = CodeGenerationPipeline.resolveTargets(
            "Create one Python file named phone\\_check.py. Use the injected hardware object.",
            null,
        )

        assertEquals(listOf("phone_check.py"), targets.map { it.filename })
        assertEquals(Language.PYTHON, targets.single().language)
    }

    @Test
    fun `web request preserves all explicit project files in order`() {
        val targets = CodeGenerationPipeline.resolveTargets(
            "Create index.html, style.css, and app.ts for an offline field checklist.",
            null,
        )

        assertEquals(listOf("index.html", "style.css", "app.ts"), targets.map { it.filename })
    }

    @Test
    fun `edit request without a filename targets the active file`() {
        val active = CodeFile(name = "sensor.lua", language = Language.LUA, content = "print('old')")

        val targets = CodeGenerationPipeline.resolveTargets("Fix and debug the current program.", active)

        assertEquals(listOf("sensor.lua"), targets.map { it.filename })
    }

    @Test
    fun `continue request without a filename keeps working on the active file`() {
        val active = CodeFile(name = "rescue.html", language = Language.HTML, content = "<html></html>")

        val targets = CodeGenerationPipeline.resolveTargets("Continue and finish this project.", active)

        assertEquals(listOf("rescue.html"), targets.map { it.filename })
    }

    @Test
    fun `unnamed browser app uses one self contained html target`() {
        val targets = CodeGenerationPipeline.resolveTargets(
            "Build a responsive offline website for first aid notes.",
            null,
        )

        assertEquals(listOf("index.html"), targets.map { it.filename })
    }

    @Test
    fun `truncated file is regenerated from scratch and then accepted`() = runBlocking {
        val targets = listOf(CodeFileTask("main.py", Language.PYTHON))
        var calls = 0

        val result = CodeGenerationPipeline.generateProject("Create main.py", targets, 384) {
            calls++
            if (calls == 1) {
                CodeModelReply.Success("FILE: main.py\n```python\nprint('cut off')")
            } else {
                assertTrue(CodeGenerationPipeline.userPrompt(it).contains("Rewrite the whole file"))
                CodeModelReply.Success("FILE: main.py\n```python\nprint('complete')\n```", 8f)
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        result as CodeProjectGenerationResult.Success
        assertEquals(2, calls)
        assertEquals("print('complete')", result.files.single().code)
        assertEquals(8f, result.averageTokensPerSecond)
    }

    @Test
    fun `raw source is accepted without spending tokens on markdown protocol`() = runBlocking {
        val result = CodeGenerationPipeline.generateProject(
            originalRequest = "Create main.py and print Ready",
            targets = listOf(CodeFileTask("main.py", Language.PYTHON)),
            outputTokenLimit = 256,
        ) {
            CodeModelReply.Success("print('Ready')")
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals("print('Ready')", (result as CodeProjectGenerationResult.Success).files.single().code)
    }

    @Test
    fun `attached broken self contained dashboard is rejected and repaired`() = runBlocking {
        val request = "Create only index.html. Build a phone friendly emergency readiness dashboard with six large checklist buttons. Tapping an item must mark it ready and update a readiness percentage. Save progress after every tap. Put all CSS and JavaScript inside index.html."
        var calls = 0
        val broken = """
            <!DOCTYPE html><html><head><link rel="stylesheet" href="styles.css"></head><body>
            <button class="button" onclick="markReady('Water')">Water</button>
            <button class="button" onclick="markReady('Food')">Food</button>
            <button class="button" onclick="markReady('Medicine')">Medicine</button>
            <button class="button" onclick="markReady('First Aid')">First Aid</button>
            <button class="button" onclick="markReady('Flashlight')">Flashlight</button>
            <button class="button" onclick="markReady('Phone Power')">Phone Power</button>
            <span id="readyPercentage">0%</span><script src="script.js"></script></body></html>
        """.trimIndent()
        val repaired = """
            <!DOCTYPE html><html><head><style>button{padding:20px}.ready{background:green}</style></head><body>
            <button data-item="Water">Water</button><button data-item="Food">Food</button><button data-item="Medicine">Medicine</button>
            <button data-item="First Aid">First Aid</button><button data-item="Flashlight">Flashlight</button><button data-item="Phone Power">Phone Power</button>
            <span id="readyPercentage">0%</span><script>
            var buttons=document.querySelectorAll('[data-item]');var saved=JSON.parse(localStorage.getItem('ready')||'[]');
            function draw(){var done=0;for(var i=0;i<buttons.length;i++){var on=saved.indexOf(buttons[i].getAttribute('data-item'))>=0;buttons[i].className=on?'ready':'';if(on)done++;}document.getElementById('readyPercentage').textContent=Math.round(done/buttons.length*100)+'%';}
            for(var i=0;i<buttons.length;i++)buttons[i].onclick=function(){var name=this.getAttribute('data-item'),at=saved.indexOf(name);if(at<0)saved.push(name);else saved.splice(at,1);localStorage.setItem('ready',JSON.stringify(saved));draw();};draw();
            </script></body></html>
        """.trimIndent()

        val result = CodeGenerationPipeline.generateProject(
            originalRequest = request,
            targets = listOf(CodeFileTask("index.html", Language.HTML)),
            outputTokenLimit = 768,
        ) { attempt ->
            calls++
            if (calls == 1) CodeModelReply.Success(broken) else {
                val feedback = CodeGenerationPipeline.userPrompt(attempt)
                assertTrue(feedback.contains("inline all CSS and JavaScript"))
                assertTrue(feedback.contains("localStorage.setItem"))
                CodeModelReply.Success(repaired)
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(2, calls)
    }

    @Test
    fun `interactive web mockup without event wiring is rejected and repaired`() = runBlocking {
        val request =
            "Create only index.html. Build an interactive checklist with a button that marks an item complete."
        val targets = CodeGenerationPipeline.resolveTargets(request, activeFile = null)
        val attempts = mutableListOf<CodeFileAttempt>()

        val result = CodeGenerationPipeline.generateProject(
            originalRequest = request,
            targets = targets,
            outputTokenLimit = 512,
        ) { attempt ->
            attempts += attempt
            if (attempt.attempt == 1) {
                CodeModelReply.Success(
                    """<!doctype html><html><head><style>button{display:block}</style></head><body><button id="done">Done</button><script>function markDone(){document.getElementById("done").textContent="Complete";}</script></body></html>""",
                )
            } else {
                CodeModelReply.Success(
                    """<!doctype html><html><head><style>button{display:block}</style></head><body><button id="done">Done</button><script>function markDone(){document.getElementById("done").textContent="Complete";}document.getElementById("done").addEventListener("click",markDone);</script></body></html>""",
                )
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(2, attempts.size)
        assertTrue(attempts[1].previousProblems.any { "visual mockup" in it })
    }

    @Test
    fun `inline interactive handler satisfies web wiring contract`() = runBlocking {
        val request =
            "Create only index.html. Build an interactive checklist with a button that marks an item complete."
        val targets = CodeGenerationPipeline.resolveTargets(request, activeFile = null)

        val result = CodeGenerationPipeline.generateProject(
            originalRequest = request,
            targets = targets,
            outputTokenLimit = 512,
        ) {
            CodeModelReply.Success(
                """<!doctype html><html><head><style>button{display:block}</style></head><body><button id="done" onclick="markDone()">Done</button><script>function markDone(){document.getElementById("done").textContent="Complete";}</script></body></html>""",
            )
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(1, (result as CodeProjectGenerationResult.Success).totalModelCalls)
    }

    @Test
    fun `compact inline handler code satisfies web wiring contract`() = runBlocking {
        val request =
            "Create only index.html. Build an interactive checklist with a button that marks an item complete."
        val targets = CodeGenerationPipeline.resolveTargets(request, activeFile = null)

        val result = CodeGenerationPipeline.generateProject(
            originalRequest = request,
            targets = targets,
            outputTokenLimit = 512,
        ) {
            CodeModelReply.Success(
                """<!doctype html><html><head><style>button{display:block}</style></head><body><button onclick="this.textContent='Complete'">Done</button><script>var ready=true;</script></body></html>""",
            )
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(1, (result as CodeProjectGenerationResult.Success).totalModelCalls)
    }

    @Test
    fun `multiline CSS ID selector is accepted as complete source`() {
        val target = CodeFileTask("style.css", Language.CSS)
        val issues = GenerationContractValidator.validate(
            request = "Create index.html and style.css with a styled action button.",
            target = target,
            allTargets = listOf(CodeFileTask("index.html", Language.HTML), target),
            completedFiles = emptyList(),
            code = """
                #action {
                    padding: 1rem;
                }
            """.trimIndent(),
        )

        assertTrue(issues.joinToString(), issues.isEmpty())
    }

    @Test
    fun `displayed numeric Python menu must accept the numbers it shows`() = runBlocking {
        val request = "Create main.py. Ask me to choose FIRE or FLOOD. Keep asking until I enter Q."
        var calls = 0
        val result = CodeGenerationPipeline.generateProject(
            originalRequest = request,
            targets = listOf(CodeFileTask("main.py", Language.PYTHON)),
            outputTokenLimit = 384,
        ) { attempt ->
            calls++
            if (calls == 1) {
                CodeModelReply.Success(
                    "while True:\n    print('1. FIRE')\n    print('2. FLOOD')\n    choice=input('Choose: ').lower()\n    if choice=='fire': print('Leave')\n    elif choice=='flood': print('Climb')\n    elif choice=='q': break\n    else: print('Invalid')",
                )
            } else {
                assertTrue(CodeGenerationPipeline.userPrompt(attempt).contains("does not accept"))
                CodeModelReply.Success(
                    "while True:\n    print('1. FIRE')\n    print('2. FLOOD')\n    choice=input('Choose: ').strip().lower()\n    if choice in ('1','fire'): print('Leave')\n    elif choice in ('2','flood'): print('Climb')\n    elif choice in ('q','quit'): break\n    else: print('Invalid')",
                )
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(2, calls)
    }

    @Test
    fun `hardware import is rejected because Android bridge is injected`() = runBlocking {
        val request = "Create phone_signal.py. Do not import anything. Print the phone battery level and network type using hardware. Then vibrate and show a toast. If a hardware call fails, print Unavailable."
        var calls = 0
        val result = CodeGenerationPipeline.generateProject(
            originalRequest = request,
            targets = listOf(CodeFileTask("phone_signal.py", Language.PYTHON)),
            outputTokenLimit = 384,
        ) { attempt ->
            calls++
            if (calls == 1) {
                CodeModelReply.Success("import hardware\nprint(hardware.batteryLevel())")
            } else {
                assertTrue(CodeGenerationPipeline.userPrompt(attempt).contains("injected global"))
                CodeModelReply.Success(
                    "try:\n    print(hardware.batteryLevel())\nexcept:\n    print('Unavailable')\ntry:\n    print(hardware.networkType())\nexcept:\n    print('Unavailable')\ntry:\n    hardware.vibrate(300)\nexcept:\n    print('Unavailable')\ntry:\n    hardware.toast('Emergency check complete')\nexcept:\n    print('Unavailable')",
                )
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(2, calls)
    }

    @Test
    fun `raw response that hits native output cap is retried atomically`() = runBlocking {
        var calls = 0
        val result = CodeGenerationPipeline.generateProject(
            originalRequest = "Create main.py",
            targets = listOf(CodeFileTask("main.py", Language.PYTHON)),
            outputTokenLimit = 256,
        ) { attempt ->
            calls++
            if (calls == 1) CodeModelReply.Success("print('looks complete')", hitOutputLimit = true)
            else {
                assertTrue(CodeGenerationPipeline.userPrompt(attempt).contains("output limit"))
                CodeModelReply.Success("print('complete')")
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(2, calls)
    }

    @Test
    fun `truncation retry uses half the original line budget`() {
        val target = CodeFileTask("index.html", Language.HTML)
        val first = CodeFileAttempt(
            originalRequest = "Create index.html",
            target = target,
            allTargets = listOf(target),
            completedFiles = emptyList(),
            attempt = 1,
            maxAttempts = 3,
            outputTokenLimit = 768,
        )
        val second = first.copy(
            attempt = 2,
            previousProblems = listOf(
                "The response reached the safe 768 token output limit before completing the file.",
            ),
        )

        assertEquals(48, first.lineBudget)
        assertEquals(24, second.lineBudget)
        assertTrue(CodeGenerationPipeline.userPrompt(second).contains("at most 24 short physical lines"))
    }

    @Test
    fun `two consecutive output cutoffs stop without wasting a third generation`() = runBlocking {
        var calls = 0
        val result = CodeGenerationPipeline.generateProject(
            originalRequest = "Create index.html",
            targets = listOf(CodeFileTask("index.html", Language.HTML)),
            outputTokenLimit = 768,
        ) {
            calls++
            CodeModelReply.Success("<!doctype html><html>", hitOutputLimit = true)
        }

        assertTrue(result is CodeProjectGenerationResult.Error)
        assertEquals(2, calls)
        assertTrue((result as CodeProjectGenerationResult.Error).message.contains("after 2 compact attempts"))
    }

    @Test
    fun `browser script selector must exist in completed html sibling`() = runBlocking {
        val targets = listOf(
            CodeFileTask("index.html", Language.HTML),
            CodeFileTask("app.js", Language.JAVASCRIPT),
        )
        var scriptCalls = 0
        val result = CodeGenerationPipeline.generateProject(
            originalRequest = "Create index.html and app.js. Build an interactive dashboard button.",
            targets = targets,
            outputTokenLimit = 384,
        ) { attempt ->
            when (attempt.target.filename) {
                "index.html" -> CodeModelReply.Success(
                    "<!DOCTYPE html><html><body><button id=\"action\">Run</button><script src=\"app.js\"></script></body></html>",
                )
                else -> {
                    scriptCalls++
                    if (scriptCalls == 1) CodeModelReply.Success("document.getElementById('missing').onclick=function(){};")
                    else CodeModelReply.Success("document.getElementById('action').onclick=function(){};")
                }
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(2, scriptCalls)
    }

    @Test
    fun `web files generate html before script even when request names script first`() = runBlocking {
        val targets = listOf(
            CodeFileTask("app.js", Language.JAVASCRIPT),
            CodeFileTask("style.css", Language.CSS),
            CodeFileTask("index.html", Language.HTML),
        )
        val callOrder = mutableListOf<String>()

        val result = CodeGenerationPipeline.generateProject(
            originalRequest = "Create app.js, style.css, and index.html with one interactive button.",
            targets = targets,
            outputTokenLimit = 384,
        ) { attempt ->
            callOrder += attempt.target.filename
            when (attempt.target.filename) {
                "index.html" -> CodeModelReply.Success(
                    "<!DOCTYPE html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><button id=\"action\">Run</button><script src=\"app.js\"></script></body></html>",
                )
                "style.css" -> CodeModelReply.Success("#action { padding: 1rem; }")
                else -> CodeModelReply.Success("document.getElementById('action').onclick=function(){};")
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(listOf("index.html", "style.css", "app.js"), callOrder)
        assertEquals(
            listOf("app.js", "style.css", "index.html"),
            (result as CodeProjectGenerationResult.Success).files.map { it.filename },
        )
    }

    @Test
    fun `multi file generation gives completed sibling context to the next file`() = runBlocking {
        val targets = listOf(
            CodeFileTask("storage.py", Language.PYTHON),
            CodeFileTask("main.py", Language.PYTHON),
        )
        val seenCompletedCounts = mutableListOf<Int>()

        val result = CodeGenerationPipeline.generateProject("Create both files", targets, 384) { attempt ->
            seenCompletedCounts += attempt.completedFiles.size
            CodeModelReply.Success(
                "FILE: ${attempt.target.filename}\n```python\nprint('${attempt.target.filename}')\n```",
            )
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(listOf(0, 1), seenCompletedCounts)
        assertEquals(listOf("storage.py", "main.py"), (result as CodeProjectGenerationResult.Success).files.map { it.filename })
    }

    @Test
    fun `python main is generated first but final files preserve requested order`() = runBlocking {
        val targets = listOf(
            CodeFileTask("inventory.py", Language.PYTHON),
            CodeFileTask("storage.py", Language.PYTHON),
            CodeFileTask("main.py", Language.PYTHON),
        )
        val callOrder = mutableListOf<String>()

        val result = CodeGenerationPipeline.generateProject("Create tracker", targets, 384) { attempt ->
            callOrder += attempt.target.filename
            CodeModelReply.Success(
                "FILE: ${attempt.target.filename}\n```python\nprint('${attempt.target.filename}')\n```",
            )
        }

        assertEquals(listOf("main.py", "inventory.py", "storage.py"), callOrder)
        assertEquals(
            listOf("inventory.py", "storage.py", "main.py"),
            (result as CodeProjectGenerationResult.Success).files.map { it.filename },
        )
    }

    @Test
    fun `provider file retries when it omits an api imported by completed main`() = runBlocking {
        val targets = listOf(
            CodeFileTask("storage.py", Language.PYTHON),
            CodeFileTask("main.py", Language.PYTHON),
        )
        var storageAttempts = 0

        val result = CodeGenerationPipeline.generateProject("Create app", targets, 384) { attempt ->
            when (attempt.target.filename) {
                "main.py" -> CodeModelReply.Success(
                    "FILE: main.py\n```python\nfrom storage import load_data\nprint(load_data())\n```",
                )
                else -> {
                    storageAttempts++
                    if (storageAttempts == 1) {
                        CodeModelReply.Success("FILE: storage.py\n```python\ndef save_data(): pass\n```")
                    } else {
                        assertTrue(CodeGenerationPipeline.systemPrompt(attempt).contains("load_data"))
                        CodeModelReply.Success("FILE: storage.py\n```python\ndef load_data(): return {}\n```")
                    }
                }
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Success)
        assertEquals(2, storageAttempts)
    }

    @Test
    fun `exhausted retries return an atomic failure with completed files recorded`() = runBlocking {
        val targets = listOf(
            CodeFileTask("one.py", Language.PYTHON),
            CodeFileTask("two.py", Language.PYTHON),
        )

        val result = CodeGenerationPipeline.generateProject(
            originalRequest = "Create both",
            targets = targets,
            outputTokenLimit = 256,
            maxAttempts = 2,
        ) { attempt ->
            if (attempt.target.filename == "one.py") {
                CodeModelReply.Success("FILE: one.py\n```python\nprint(1)\n```")
            } else {
                CodeModelReply.Success("FILE: two.py\n```python\nprint(")
            }
        }

        assertTrue(result is CodeProjectGenerationResult.Error)
        result as CodeProjectGenerationResult.Error
        assertEquals("two.py", result.failedFilename)
        assertEquals(listOf("one.py"), result.completedFiles.map { it.filename })
        assertTrue(result.message.contains("No existing project files were replaced"))
    }
}
