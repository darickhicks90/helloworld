# Third-party notices

SketchSolve includes or downloads the following open-source components.

| Component | Use in SketchSolve | License |
| --- | --- | --- |
| [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) by Google | Reads word problems and writes their setup (downloaded on first use) | Apache License 2.0 |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) by Google | Runs Gemma on the device | Apache License 2.0 |
| [whisper.cpp / ggml](https://github.com/ggml-org/whisper.cpp) by the ggml authors | Push-to-talk speech recognition (bundled in `app/src/main/cpp/whisper.cpp`) | MIT License |
| [Whisper](https://github.com/openai/whisper) models by OpenAI | Speech model weights (downloaded on first use) | MIT License |
| Gson, kotlinx-coroutines (via LiteRT-LM) | Runtime libraries | Apache License 2.0 |

The full MIT license for whisper.cpp is in `app/src/main/cpp/whisper.cpp/LICENSE`.
The Apache License 2.0 text is at https://www.apache.org/licenses/LICENSE-2.0.

This file is provided for convenience and is not legal advice. Have the
licenses reviewed before selling the app.
