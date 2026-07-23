# Physical benchmark artifacts

These five files are the exact PocketIDE schema 9 text exports copied from the LGE LM Q620 physical phone on July 18, 2026. They are preserved as raw evidence and are not regenerated from the README table.

| Backend | Protocol | Raw report |
|---|---|---|
| llama.cpp GGUF | Quick | [report](2026-07-18-lge-lm-q620-gguf-quick-schema9.txt) |
| ExecuTorch PTE | Quick | [report](2026-07-18-lge-lm-q620-pte-quick-schema9.txt) |
| llama.cpp GGUF | Deep evidence | [report](2026-07-18-lge-lm-q620-gguf-deep-schema9.txt) |
| ExecuTorch PTE | Sustained evidence | [report](2026-07-18-lge-lm-q620-pte-sustained-schema9.txt) |
| llama.cpp GGUF | Sustained evidence | [report](2026-07-18-lge-lm-q620-gguf-sustained-schema9.txt) |

[benchmark-evidence.json](benchmark-evidence.json) is a machine-readable index and selected-metric transcription of those raw reports. It is deliberately labeled as repository-derived rather than an in-app native JSON export. The current app exports schema 12 text and JSON with additional context, KV cache, mapped-weight, and verified native-library fields; a new physical schema 12 capture should replace these schema 9 artifacts only after another complete phone run.

The SHA-256 values in the JSON index make accidental edits detectable. Energy fields describe Android whole-device fuel-gauge measurements and are not app-only power measurements.
