# PocketIDE KotlinLlamaCpp source fork

This directory pins the MIT licensed
[`ljcamargo/kotlinllamacpp`](https://github.com/ljcamargo/kotlinllamacpp)
source at commit `c292c068bdd258203dd41fc6d0f08578eddd59f3`.

PocketIDE keeps the public `org.nehuatl.llamacpp` API and adds only the native
configuration and evidence needed for reproducible long context work:

* explicit K and V cache types
* explicit Flash Attention selection
* actual native context, batch, cache type, and Flash Attention evidence

The vendored source is built with the app so the public repository contains the
code required to reproduce the APK. See `LICENSE` for the upstream license.
