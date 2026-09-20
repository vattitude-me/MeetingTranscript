<p align="center">
  <img src="docs/img/logo.png" width="128" alt="Scribe">
</p>

<h1 align="center">Scribe</h1>

<p align="center">
  Tap a home-screen widget, record the meeting, get a timestamped transcript.<br>
  Everything runs on the phone. No audio ever leaves the device.
</p>

---

## What it does

You are on a call on your laptop. You put your phone next to it and tap one widget.
Scribe records the room, and when you tap stop it turns that audio into a transcript —
line by line, each with a timestamp, each individually shareable.

There is no account, no upload, and no network call except the one-time model download.

**Stage 1 (this repo, today): audio → text.**
**Stage 2 (deliberately out of scope for v0.1): summaries and action points**, produced by
whatever model or agent you like, from the JSON transcript Scribe exports. See
[docs/PLAN.md](docs/PLAN.md) for why the split exists.

## Install

Grab the APK from [Releases](../../releases) and sideload it on an arm64 Android phone
(Android 10 / API 29 or newer). Built and tested against a Pixel 9.

On first launch:

1. Tap **Download model** — about 600 MB, once, over Wi-Fi.
2. Grant the microphone permission.
3. Long-press your home screen → **Widgets** → **Scribe** → drop the 1×1 tile somewhere reachable.

You can record before the model finishes downloading. The audio waits on disk and
transcribes itself once the model is there.

## Using it

- **Tap the widget** to start. The tile turns red and a notification shows the elapsed time.
- **Tap it again** to stop. Transcription starts by itself.
- Open a meeting to read the transcript. Tap the share icon on any line to send just
  that line; use the overflow menu to share the whole thing as `.txt`, or as `.json`
  for a summarizer.

Transcription runs **after** the meeting, not during it. That is on purpose: it keeps the
phone cool while recording, lets the recognizer take its time, and means a crash in the
recognizer can never cost you audio.

## How it works

```
widget tap
   └─ RecordTrampolineActivity        (satisfies Android 14+ FGS-from-background rules)
        └─ RecorderService            foreground service, type=microphone
             └─ 16 kHz mono PCM16, rotating 30-second segments on disk
                  │
                  │  (recorder and recognizer share nothing but the disk)
                  ▼
             TranscribeWorker         WorkManager, resumable segment by segment
                  └─ ParakeetEngine   sherpa-onnx → Parakeet TDT 0.6B v3 int8
                       └─ SQLite: one row per transcript line
```

| Piece | Choice | Why |
|---|---|---|
| ASR model | [Parakeet TDT 0.6B v3](https://github.com/k2-fsa/sherpa-onnx) int8 | 6.34% WER vs Whisper large-v3's 7.44%, at a fraction of the size |
| Runtime | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) prebuilt AAR | ONNX Runtime + a tested TDT decoder; no NDK toolchain needed to build this repo |
| Audio | 16 kHz mono PCM16, 30 s segments | ~115 MB/hour, and a segment is the unit of resumable work |
| Storage | Hand-written `SQLiteOpenHelper` | Two tables; Room's codegen is not worth the build surface |
| UI | Views + ViewBinding | Smaller, faster to build, fewer moving parts at v0.1 |

Recording and transcription are coupled **only through the disk**. If the recognizer runs
out of memory, the PCM files are still sitting there and the job picks up at the segment
it last committed — it does not restart an hour of work.

## Building it yourself

Requires JDK 21 and an Android SDK with platform 35 and build-tools 35.

```bash
git clone https://github.com/vattitude-me/scribe.git
cd scribe
./scripts/setup.sh                                   # fetches the 48 MB sherpa-onnx AAR
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

### Signing a release build

`assembleRelease` looks for `keystore.properties` at the repo root (git-ignored):

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=…
keyAlias=scribe
keyPassword=…
```

Without that file the release build still assembles — it is simply unsigned and will not
install. The keystore must never change between releases, or Android will refuse the
upgrade and you will have to uninstall first.

## Known limits at v0.1

- **Headphones defeat it.** If you listen through headphones the phone only hears your
  half of the conversation. There is nothing software can do about that; use the laptop
  speaker.
- **No speaker labels yet.** Every line is attributed to nobody. Diarization is planned
  (sherpa-onnx ships the models) but is not wired up.
- **Transcription quality is unmeasured** against real meeting audio on a Pixel 9. The
  numbers above are the model author's, not ours.
- **Android only.** The iOS half of [docs/PLAN.md](docs/PLAN.md) is designed but unbuilt.
- **No process isolation.** The plan calls for running ASR in a `:asr` process so an OOM
  cannot take the recorder down; today it shares the main process.

## Privacy

Audio and transcripts live in the app's private storage. `allowBackup` is off, so nothing
is copied into a Google backup. The only network traffic the app generates is the model
download from GitHub.

Recording other people has rules that vary by jurisdiction and employer. That part is on you.

## License

MIT
