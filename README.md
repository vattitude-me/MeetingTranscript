<p align="center">
  <img src="docs/img/logo.png" width="128" alt="Meeting Transcript">
</p>

<h1 align="center">Meeting Transcript</h1>

<p align="center">
  Record a meeting on your Android phone or your Mac, get a timestamped transcript.<br>
  Everything runs on your device. No audio ever leaves it. No account, no subscription.
</p>

---

## What it does

You are on a call on your laptop. You put your phone next to it and tap one widget.
It records the room, and when you tap stop it turns that audio into a transcript —
line by line, each with a timestamp, each individually shareable.

On a Mac there is no phone to put anywhere: the menu bar app records the call itself
(whatever Zoom, Meet or Teams is playing) plus your microphone, and writes the transcript
while the meeting is still going.

There is no account, no upload, and no network call except the one-time model download.

**Why not the recorder app already on your phone?** Because it hands you a wall of text
with no idea that four people were in the room. This app separates the voices, so the
transcript says who spoke, and a **Conversation** view says who spoke for how long, who
asked the questions and who talked over whom. All of it on the phone, with nothing
uploaded — not because a server would be hard, but because a meeting recording should
only ever exist in one place. [docs/INTELLIGENCE.md](docs/INTELLIGENCE.md) has the long
version, including what we deliberately do *not* claim.

**Stage 1 (this repo, today): audio → text.**
**Stage 2 (deliberately out of scope): summaries and action points**, produced by
whatever model or agent you like, from the JSON transcript the app exports. See
[docs/PLAN.md](docs/PLAN.md) for why the split exists.

## Install

| | Android | Mac |
|---|---|---|
| Get it | APK from [Releases](../../releases) | build from source, free ([below](#on-a-mac)) |
| Needs | arm64 phone, Android 10+ | Apple silicon, macOS 14+ |
| Records | the room, through the phone's mic | the call's audio and your mic, separately |
| Who said what | speaker separation by voice (optional, beta) | **You** / **Them**, from which stream a line came from |
| Transcript | after the meeting, plus a live preview | live, during the meeting |

### Android

Grab the APK from [Releases](../../releases) and sideload it on an arm64 Android phone
(Android 10 / API 29 or newer). Built and tested against a Pixel 9.

On first launch:

1. Tap **Download** — about 615 MB total (two models: a small streaming one for the
   live preview, and the large accurate one), once. It waits for Wi-Fi unless you
   choose to spend mobile data, carries on in the background if you leave the app,
   and resumes where it stopped if the connection drops.
2. Tap **Record**. The microphone permission is asked for then, when it is needed,
   not the moment the app opens.
3. Once the models are in, a card offers to put the 1×1 record tile on your home
   screen. Settings → **Add the home-screen widget** does the same later.

You can record before the model finishes downloading. The audio waits on disk and
transcribes itself once the model is there.

### On a Mac

The Mac app is built from source. It is free and takes a couple of minutes. You need
Apple silicon, macOS 14 or newer, and the Xcode Command Line Tools
(`xcode-select --install`). You don't need Xcode itself or an Apple Developer account.

```bash
git clone https://github.com/vattitude-me/scribe.git
cd scribe/mac
scripts/build-app.sh --install          # → ~/Applications/Meeting Transcript.app
open ~/Applications/"Meeting Transcript.app"
```

On first launch:

1. Click the waveform icon in the menu bar, then **Download (490 MB)**. This fetches
   the speech model and voice detector, once.
2. Under **Before your first meeting**, click **Allow** for Microphone and Screen
   Recording. Screen Recording is how macOS names access to another app's audio.
   The app takes a 2×2-pixel frame and throws it away. After granting it, quit and
   reopen the app once.
3. When the call starts, press **Record meeting**, from the menu bar or the library
   window (⇧⌘R). Lines appear as people speak, labelled **You** and **Them**.

If you plan to rebuild the app, run `scripts/make-signing-identity.sh` once first,
so macOS doesn't ask for the permissions again after every build.
[mac/README.md](mac/README.md) has the details, the command line, and speed
measurements.

## Using it

This section is the Android app. The Mac app (menu bar recorder, library window,
playback, exports, the command line) is covered in [mac/README.md](mac/README.md).

**Recording**

- **Tap the widget or Record** to start. The recording screen shows elapsed time, a
  level meter, and a live, rough preview of the transcript as it picks up the room.
  While it records, the button on the main screen turns magenta and counts the time.
- **Mark** drops a bookmark at the current moment ("that was the decision") — it shows
  up in the transcript where it happened, and you can play the audio from just before it.
- **Stop** asks you to name the meeting while it is fresh (or skip), and the accurate
  transcription starts by itself.
- If the phone runs low on storage it warns you before you start, and stops a recording
  cleanly rather than failing half-written. If the app is killed mid-recording, the
  audio already on disk is recovered as a meeting the next time you open it.

**Reading**

- Open a meeting to read the transcript. A banner at the top says what is happening to
  it — transcribing (with progress), queued, waiting for models, paused, or failed with
  **Try again** — so nothing happens behind your back when you merely open it.
- **Find** (magnifier) searches inside the transcript, highlights every match and steps
  through them. Searching from the main list carries the query into the meeting.
- **Tap a line** to copy it, share it, or **play from here** — while the recording is
  still kept, you can hear exactly what was said.
- **Copy for chatbot** puts the transcript on the clipboard with a summarise-and-cite
  prompt already attached, so handing a meeting to a model is one paste.
- Share the whole transcript as `.txt`, or export Markdown, `.json`, `.srt` or `.vtt`.
- **Speaker labels** appear above a line when the voice changes, once you have turned
  speaker separation on in Settings (an extra 37 MB download, also offline). Tap a label
  to name that voice; the name applies to every line they spoke and follows the
  transcript into every export. **Conversation** in the menu shows talk time per speaker.

**Managing**

- **Swipe a meeting** to delete it, with **Undo**. Long-press for Rename and Delete.
  Deleting from inside a meeting tells you what goes — minutes of audio, megabytes,
  transcript lines — before you confirm.
- **Settings** (the gear, top right) holds your data: export the whole library as a
  single backup file, restore one, decide whether recordings are deleted once they have
  been transcribed, and check which build you are running.

The transcript you keep is always produced by the **accurate** pass, which runs **after**
the meeting, not during it. That is on purpose: it keeps the phone cool while recording,
lets the recognizer take its time, and means a crash in the recognizer can never cost you
audio. The live preview shown during recording is a disposable scaffold — it's there so
you can see the phone is actually hearing the room while you can still move it, not to be
the transcript itself.

### Backups

Settings → **Export a backup** writes your whole library as one `.zip` — a manifest of
every meeting and transcript, optionally with the recordings alongside. It asks which,
because transcripts on their own are kilobytes and the same archive with audio is
hundreds of megabytes.

**Restore a backup** reads one back. Restoring is **additive**: meetings are added to
what is already on the phone and nothing is replaced, so picking the wrong file cannot
cost you anything. That also makes the backup the way to move a library between phones —
and, in time, to a Mac: it is the interchange format [docs/MACOS.md](docs/MACOS.md)
builds on.

There is no cloud backup, because there is no cloud. If you want a copy, you make one.

### Speakers

Settings → **Identify speakers** downloads two more models (37 MB, once, offline like
everything else). After that, each new recording is split into distinct voices as part of
the same pass that transcribes it.

It finds *voices*, not people. There is no voiceprint database and nothing identifies
anybody — you get "Speaker 1" and "Speaker 2" until you type a name, and the names stay
on that one meeting. Speaker separation can also simply be wrong, which is why the
Conversation view says so and every number in it can be checked against the transcript.

Because it reads the waveform, it runs **before** the audio is deleted, in the same pass
as transcription. The consequence is that meetings transcribed before you turned it on
cannot be relabelled — their audio is already gone.

## How it works

On Android:

```
widget tap
   └─ RecordTrampolineActivity        (satisfies Android 14+ FGS-from-background rules)
        └─ RecorderService            foreground service, type=microphone
             └─ 16 kHz mono PCM16, rotating 30-second segments on disk
                  │                              │
                  │ (disk only)                  │ (same buffers, own thread, bounded queue)
                  ▼                              ▼
             TranscribeWorker              LiveTranscriber
             WorkManager, resumable        StreamingEngine → 20M zipformer int8
             segment by segment            on-screen preview while recording; drops
                  │                        audio under load rather than blocking it
                  ▼
             ParakeetEngine   sherpa-onnx → Parakeet TDT 0.6B v3 int8
                  └─ SQLite: clears the live preview lines, writes its own
```

| Piece | Choice | Why |
|---|---|---|
| Accurate ASR model | [Parakeet TDT 0.6B v3](https://github.com/k2-fsa/sherpa-onnx) int8 | 6.34% WER vs Whisper large-v3's 7.44%, at a fraction of the size |
| Live preview model | streaming zipformer, 20M params, int8 | Small and fast enough to decode while still recording; a disposable preview, not the kept transcript |
| Runtime | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) prebuilt AAR | ONNX Runtime + a tested TDT decoder; no NDK toolchain needed to build this repo |
| Audio | 16 kHz mono PCM16, 30 s segments | ~115 MB/hour, and a segment is the unit of resumable work |
| Storage | Hand-written `SQLiteOpenHelper` | Two tables; Room's codegen is not worth the build surface |
| UI | Views + ViewBinding, Material 3 dark theme | Smaller, faster to build, fewer moving parts at v0.1 |

On the Mac the shape is simpler, because Apple silicon is fast enough to drop the
preview model: each stream is cut into utterances by a voice detector and Parakeet
transcribes each one as it ends, at about 18× real time. Details and measurements are in
[docs/MACOS.md](docs/MACOS.md).

Recording and transcription are coupled **only through the disk**. If the recognizer runs
out of memory, the PCM files are still sitting there and the job picks up at the segment
it last committed — it does not restart an hour of work. The live preview reads the same
audio buffers non-destructively; if it falls behind and drops audio, the disk copy — and
the accurate pass that runs over it after the meeting — is unaffected.

## Building it yourself

The Mac app: see [mac/README.md](mac/README.md) — one script, no Xcode, no Apple
Developer account.

The Android app requires JDK 21 and an Android SDK with platform 35 and build-tools 35.

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

## Known limits

- **Headphones defeat it.** If you listen through headphones the phone only hears your
  half of the conversation. There is nothing software can do about that on a phone; use
  the laptop speaker, or run the [Mac app](mac), which captures the call's audio directly.
- **The speaker count is a guess, and often a bad one.** Left to itself, the clusterer
  decides how many voices it heard from a similarity threshold, and no single threshold
  works: swept from 0.2 to 0.9 over one-, two- and three-speaker recordings, none gave
  the right count for all of them. One person talking for nine minutes drifts enough to
  be split into several speakers. Told the true number, it was right every time — so
  when the count looks wrong, tap the line under the title and say how many people
  spoke, and the voices are separated again without re-transcribing.
- **Transcription quality is unmeasured** against real meeting audio on a Pixel 9. The
  numbers above are the model author's, not ours.
- **No iOS app.** The iOS app in [docs/PLAN.md](docs/PLAN.md) was never built, and is no
  longer the plan — iOS cannot capture another app's audio. The second platform is macOS:
  [mac/](mac) is a menu bar app that records the call and your microphone and transcribes
  them live. You build it from source for free, without an Apple Developer account. It
  does not yet read or write Android backups. See [docs/MACOS.md](docs/MACOS.md).
- **No process isolation.** The plan calls for running ASR in a `:asr` process so an OOM
  cannot take the recorder down; today it shares the main process.

## Privacy

On Android, audio and transcripts live in the app's private storage. `allowBackup` is
off, so nothing is copied into a Google backup. On the Mac, everything is in one folder,
`~/Library/Application Support/Meeting Transcript`. On both, the only network traffic
the app generates is the one-time model download from GitHub.

Recording other people has rules that vary by jurisdiction and employer. That part is on you.

## Contributing

Bug reports, measurements from real meetings and pull requests are welcome — open an
[issue](../../issues). The design documents in [docs/](docs) explain why things are the
way they are; [docs/PLAN.md](docs/PLAN.md) is the original plan, [docs/MACOS.md](docs/MACOS.md)
the Mac design, and [docs/INTELLIGENCE.md](docs/INTELLIGENCE.md) the speaker and
conversation features.

## License

MIT — see [LICENSE](LICENSE).
