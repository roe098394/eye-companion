# Eye Companion

A desktop companion for an Android tablet (built for the Galaxy Tab S7 FE): a pair of animated eyes
that listens through the tablet's own microphone, thinks with a small offline language model,
and talks back through a Bluetooth speaker. No root, no cloud.

## How it works

| Part   | What it uses |
|--------|--------------|
| Eyes   | Custom animated view: blinks, glances around, squints when thinking, smiles when talking |
| Ears   | [Vosk](https://alphacephei.com/vosk/) offline speech recognition, pinned to the **built-in mic** |
| Brain  | [llama.cpp](https://github.com/ggml-org/llama.cpp) running Llama 3.2 1B Instruct (Q4_K_M, ~800 MB) |
| Voice  | Android's built-in text-to-speech, routed as media so Bluetooth stays in high-quality mode |

The first launch downloads the two models over Wi-Fi (~850 MB, one time). After that it's fully offline.

## Installing

1. Every push to `main` builds the app on GitHub. Open **Releases** on the tablet and download `EyeCompanion.apk`.
2. Open it and allow "Install unknown apps" for your browser when asked.
3. Launch **Eye Companion**, allow the microphone, and leave it on Wi-Fi while it downloads its brain.
4. Pair your Bluetooth speaker in the normal Android Bluetooth settings.
5. To make it the only thing the tablet runs: press the Home button, choose **Eye Companion**, then **Always**.

## Using it

- Just talk to it. While it thinks or speaks, the mic is muted so it doesn't hear itself.
- **Tap** while it's talking to interrupt. Tap while idle and it looks pleased.
- **Double-tap** or say "go to sleep" to put it to sleep. Tap to wake it.
- Say "start over" to clear what it remembers of the conversation.
- **Long-press** for the menu: captions, Bluetooth, retry setup, and **Choose Home app** to leave companion mode.

## Customising

- **Personality / name:** edit `SYSTEM_PROMPT` in `MainActivity.kt`.
- **Eye colour:** `eyeColor` in `EyesView.kt`.
- **Different model:** copy any `.gguf` file into
  `Android/data/com.eyecompanion/files/` on the tablet and restart the app; it's used instead of the download.

## Notes

- The signing key (`app/companion.jks`) is a throwaway hobby key kept in the repo so that each new
  build installs over the previous one. Don't reuse it for anything else.
