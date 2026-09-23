# On-Device Meeting Notes: Project Plan

**Status:** first planning draft, revision 3, 2026-09-20. Parts of it were replaced by
what we actually built. Read the note below before trusting any detail in here.
**Approach:** two stages, released separately.

> **This is the thinking we did before building, not a description of the app.**
> Read it as a record of why decisions were made, not as current fact. The biggest
> difference: we built a **native Android app in Kotlin, not a React Native app**, and
> there is **no iPhone app**. So section 1.1 and the iPhone parts below (half of 1.4,
> section 2, 5.2, half of 8, and 12) describe a path we didn't take.
>
> On Android, the app is mostly *ahead* of this plan. These are all done: the
> live-preview-plus-accurate-transcript design (section 3.1), recording that's
> protected by saving to disk, the Material 3 design, and exports. Still not done:
> running transcription in a separate process (section 4.2, rule 3; see Known limits in
> the README).
>
> Telling speakers apart (Phase 3) is now done as an optional *Identify speakers*
> setting, still in testing (see [INTELLIGENCE.md](INTELLIGENCE.md)). All the export
> formats in section 6 are done (Markdown, SRT, VTT and chatbot-ready), plus full
> library backup and restore, which this plan never expected. The iPhone question in
> section 14 is answered: **the second platform is the Mac, not the iPhone**, because
> iPhones can't record another app's audio. The Mac app is in [`mac/`](../mac), and its
> design is in [MACOS.md](MACOS.md).
>
> See [README.md](../README.md) for what's true about the app today.

- **Stage 1 (this project, version 1):** the phone sits on the desk next to your work
  laptop. One tap on a home-screen button starts it. It records with the screen off for
  the whole meeting, and makes a **word-for-word English transcript with a timestamp on
  every line**, on the phone. This is everything we're delivering.
- **Stage 2 (later, or by another tool):** summaries, action items, outcomes. These come
  from an outside AI tool, using a clean export. Not built in version 1, but the export
  it needs is.

**Target devices:** Google Pixel 9 (Android) and an iPhone on **iOS 17**. Not the newest
system versions, so we plan for older features and leave plenty of spare memory.

---

## What changed in revision 3

Your two requirements changed the plan more than revision 2 did:

1. **Moving summaries out removes about 60% of the app.** No built-in 1.4 to 3.4 GB AI
   model, no llama.rn, no step-by-step summary process, no prompt templates, no screens
   for checking the AI's claims. The download drops to **about 250 to 700 MB**. The
   project becomes "a very good recorder and transcriber that runs on the phone, with a
   clean export", which is something you can actually finish.
2. **iOS 17 rules out revision 2's iPhone model choices.** Apple's `SpeechTranscriber`
   needs iOS 26 or newer. So do Apple's Foundation Models. Both are out. The iPhone has
   to use a model we ship ourselves, and an older iPhone has a real memory limit
   (section 4).
3. **A third change follows from stage 1 being the whole job.** With no summary to
   make, there's no reason to transcribe *during* the meeting. Transcribe **after**.
   That one decision removes revision 2's biggest iPhone risk (running the AI in the
   background for an hour) and improves accuracy for free (section 3).

---

## 1. The decisions

### 1.1 A native app, built with React Native (Expo)

> **Not what we built.** The real app is plain native Kotlin, Android only. No Expo, no
> React Native, no iPhone. See the note at the top.

A web app still can't work. Neither phone lets a web app put a button on the home
screen. On iPhone, Safari pauses web apps and turns off the microphone as soon as you
switch away or lock the screen, so the recording would die in the middle of a meeting.
Recording in the background with the screen off is a must, so this isn't close.

We picked React Native because the speech recognition libraries that run on the phone
are available for it (`whisper.rn`, `react-native-sherpa-onnx`,
`react-native-executorch`) and work on both platforms. The home-screen button and
background recording need native Swift or Kotlin code anyway. With Capacitor we'd have
to write the hard part by hand *and* get fewer AI libraries.

### 1.2 Recording: through the air, up close, microphone only

```
  [ people on the call ] ──▶ laptop speakers ──┐
                                               ├──▶ phone mic ──▶ 16 kHz mono PCM ──▶ disk
  [ you, speaking ] ───────────────────────────┘
```

Both sounds reach the phone from 30 to 60 cm away. That's close, and much easier than a
conference table. No system rules get in the way. Phones can't record other apps'
audio, and this gets around that physically.

**Four limits, all of which the first-run screens should explain:**

1. **Headphones break it completely.** With a headset, the phone only hears you, and
   the transcript reads like a one-person speech. This is the most likely real-world
   failure. Detect it: if only one voice is heard for a long time, show a gentle
   warning.
2. **The laptop volume has to be up.** Include a "test my setup" step: record 5
   seconds, show a level meter, check it once.
3. **Placement:** screen up, microphone end toward the laptop, not under papers, and
   not flat on a hard desk that picks up keyboard noise.
4. **Keyboard noise is close too**, and louder than speech. The voice detector handles
   it well enough. Check this in Phase 0.

**Expect lower accuracy for people on the call than for your own voice.** Their audio
has already been squeezed by the video call and played through small laptop speakers
before it crosses the room. That's a reason to use the most accurate model memory
allows, not a reason to worry.

### 1.3 English only

This makes things much simpler. We can use the English-only Whisper models and
Parakeet, which are smaller, faster and *more* accurate than versions that handle many
languages. The download is half the size, and we don't need to detect the language.
Keep the list of models in a settings file so another language can be added later
without a rewrite.

### 1.4 Screen off, in the background

- **Android:** a foreground service with
  `android:foregroundServiceType="microphone"`, plus the permissions
  `FOREGROUND_SERVICE_MICROPHONE` (needed on Android 14 and newer), `RECORD_AUDIO` and
  `POST_NOTIFICATIONS`. A notification stays visible with the elapsed time and a stop
  button. It keeps running when the screen is off, by design.
- **iOS 17:** `UIBackgroundModes: audio` keeps recording going when the phone is
  locked. Background recording is well supported and uses little power. **Because
  transcription now runs after the meeting (section 3), there's no long background AI
  work to worry about.** That was revision 2's biggest iPhone unknown, and it's now
  designed away.

### 1.5 The line between the two stages

Stage 1 produces a file, not a feature. If we define that file properly (section 6),
stage 2 can be swapped out freely: an outside AI tool today, an AI model inside the app
later, a different model next year. They all use the same file.

> **A note on privacy.** Stage 1 is fully local. No audio ever leaves the phone. Sending
> a transcript to an outside AI in stage 2 *does* mean the meeting's text leaves your
> device. That's still much better than a cloud note-taking bot: no audio is sent, no
> outside bot joins the call, and you choose what to send and when. But it isn't
> "nothing leaves the device", and for internal work meetings that difference matters.
> Decide this on purpose, not by default. See section 11.

---

## 2. Target devices and what they can run

| | Pixel 9 | iPhone on iOS 17 |
|---|---|---|
| Memory | **12 GB** (Pro: 16 GB) | Depends on the model. iOS 17 runs on iPhone XS to 15 |
| Chip | Tensor G4, with Google's 3rd-generation AI chip ("rio") | A12 to A17 Pro |
| **How much memory one app can really use** | Plenty. Native memory isn't limited by Android's Java memory cap | **About 2.0 GB at most on 4 GB phones** (iPhone XS to 11), more on 6 GB and up. Even less in the background |
| Useful system features | ONNX Runtime, Vulkan, NNAPI (outdated in Android 15 but still works) | Core ML, Metal. **No** SpeechTranscriber (needs 26), **no** Foundation Models (needs 26) |

**The iPhone sets the limits, not the Pixel.** Plan the memory for a 4 GB iPhone on iOS
17 running in the background, and everything else has room to spare.

**What iOS 17 can and can't do** (revision 2 assumed some things you can't have):

| Feature | Needs iOS | Works on 17? |
|---|---|---|
| Home-screen widget that opens a link (WidgetKit + `widgetURL`) | 14 | ✅ |
| App Intents, App Shortcuts, Siri | 16 | ✅ |
| Live Activity (controls on the lock screen) | 16.1 | ✅ |
| Dynamic Island | 16.1 + iPhone 14 Pro or newer | ⚠️ Depends on the phone |
| Widgets with buttons | 17 | ✅ (just barely) |
| Control Center buttons | **18** | ❌ Drop it |
| `SpeechAnalyzer` / `SpeechTranscriber` | **26** | ❌ |
| Apple Foundation Models | **26** | ❌ |

---

## 3. Transcribe *after* the meeting, not during

This is the biggest change in revision 3, so it gets its own section.

With summaries out of scope, nothing needs the text until the meeting ends. So:

```
during meeting   →  record only. 16 kHz mono audio, ~32 KB/s, almost no CPU. Screen off. Phone stays cool.
on stop          →  queue a transcription job for the audio files on disk.
job runs         →  Pixel 9 with Parakeet should finish a 60-minute meeting in under 10 minutes.
                    iPhone with small.en is slower but needs no attention. Run it while charging.
transcript ready →  notification. Read, share, export.
```

**What this gets us:**

| | Live transcription (revision 2) | After the meeting (revision 3) |
|---|---|---|
| Risk of iPhone stopping background AI work | High. It may be slowed down or killed | **Gone.** Only background recording |
| Heat and battery during the meeting | An hour of constant AI work | Almost none |
| Transcript quality | Must use the fastest, least accurate settings to keep up | **Can use slower, more accurate settings and a better model.** No time pressure |
| What a crash can break | A transcription crash can take the recording with it | Recording is already finished. Just run the job again |
| How complicated it is | Two things running at once | One step after another |

**The cost:** you don't see words appear during the meeting. For your use (read the
notes afterwards, give them to an AI), that's worth nothing. Live transcription could
be an optional Phase 3 feature if you ever want it.

**What follows from this:** the recorder and the transcriber only connect *through
files on disk*. The recorder's only job is saving audio to disk, and transcription
must never be able to affect it (section 4.2).

### 3.1 Revision 4: both, in that order

This is how the app works as of v0.3.0. The table above is still right. Every row
still favors transcribing after the meeting, which is why that step still makes the
transcript you keep. What changed is that seeing words appear during the meeting
turned out *not* to be worth nothing. It's how you know the phone is really hearing
the room, while you still have time to move it.

So both steps run, and the live one is only a temporary helper:

```
during meeting   →  save audio to disk, AND run a small 20M zipformer model for the on-screen preview.
on stop          →  queue the Parakeet job for the same audio files on disk.
job runs         →  removes the preview lines and writes its own. Same meeting, replaced in place.
```

The live step gets none of the quality benefits above. It uses the fastest settings, a
small 20M-parameter model, and it commits to words before hearing the end of the
sentence. It's a preview, not a draft. Two things stop it from costing anything that
matters:

- It reads the same audio the recorder already has, through a limited queue on its own
  thread. If it falls behind, it skips audio, and the recording isn't affected. The
  copy on disk is always complete (section 4.2 still holds).
- Its output is meant to be thrown away. `TranscribeWorker` removes the meeting's
  preview lines once the accurate model has loaded. It waits until after loading, so if
  loading fails, you keep the preview instead of getting an empty meeting.

**The cost:** two models to download (about 615 MB) instead of one, and the accurate
step now always runs, even if the live step already produced something.

---

## 4. Memory: plan it, don't find out the hard way

You asked for spare memory. Here it is as a firm design rule, not a hope.

### 4.1 The memory budget

**Rule: the app never uses more than 1 GB on iPhone or 2 GB on Android at its peak.**
On a 4 GB iPhone, that leaves more than 1 GB spare below the roughly 2.0 GB point where
iOS kills the app. That margin is what keeps it alive in the background (where limits
are stricter) and when other apps need memory.

| Part | iPhone budget | Android budget |
|---|---|---|
| Speech model | ~250 MB (`small.en` q5) | ~700 MB (Parakeet v3 int8) |
| Memory the model uses while working | ~150 to 300 MB | ~500 MB |
| Audio (read in pieces, never the whole file) | Under 10 MB | Under 10 MB |
| App code and screens | ~150 to 250 MB | ~200 to 300 MB |
| **Peak target** | **Under 1 GB** | **Under 2 GB** |
| Spare memory on the weakest phone | **Over 1 GB** | **Over 10 GB** |

iOS has two special permissions for apps that need more memory: Increased Memory Limit
and Extended Virtual Addressing (whisper.rn recommends the second for its larger
models). **Don't use them.** If you need special permission to avoid crashing, the
model is too big for the phone. And they don't help on iPhone 11 and older anyway.

### 4.2 Design rules so running out of memory isn't a disaster

1. **The recorder saves audio straight to disk in files of about 30 seconds each.** Any
   crash (out of memory, a bug, a dead battery) loses 30 seconds at most.
2. **Transcription reads from disk, never from the recorder's memory.** They share a
   folder, not memory.
3. **On Android, run transcription in a separate process**
   (`android:process=":asr"`). If transcription runs out of memory, only that process
   dies, and recording keeps going. This costs nothing and is the single best way to
   make the app reliable. ⚠️ **Not done yet.** `TranscribeWorker` runs in the main
   process today. Rules 1, 2 and 9 still hold, so running out of memory means a retry,
   not lost audio. This rule is still the biggest reliability improvement left.
4. **iPhone apps can't use multiple processes**, so the protection there is that
   transcription runs *after* recording has finished (section 3). A crash means a
   retry, never lost audio.
5. **Never load a whole meeting's audio into memory.** Read it one file at a time. A
   2-hour meeting is about 230 MB of audio, which is enough to matter.
6. **Never keep the whole transcript in a list that draws every line at once.** Two
   hours is about 1,500 to 2,500 lines. Use a list that only draws what's on screen,
   from the start.
7. **Keep the speech model loaded between audio files**, but unload it when the job
   finishes. Don't leave 400 MB in memory while someone browses old notes.
8. **Check free memory before starting a job**, using `os_proc_available_memory()` on
   iPhone and `ActivityManager.MemoryInfo` + `onTrimMemory` on Android. If memory is
   tight, use a smaller model for that job and say so on screen instead of crashing.
   ⚠️ **Not done.** There's also no smaller model to switch to, because the live
   preview model can't make the accurate transcript.
9. **Jobs can resume.** The transcription job remembers the last audio file it
   finished. A crash in the middle picks up where it left off. It doesn't start an hour
   of work over.

---

## 5. Which models to use

### 5.1 Android (Pixel 9): **Parakeet TDT 0.6B v3, int8**

| | |
|---|---|
| Accuracy | **6.34% word error rate** on the Hugging Face Open ASR Leaderboard, compared with 7.44% for Whisper large-v3 |
| Size | About 600 million parameters. About 700 MB on disk and 1.2 GB of memory while working |
| Speed | Much faster than Whisper at a quarter of the size. Adds punctuation by itself |
| Runs on | ONNX Runtime (through sherpa-onnx), or GGUF through whisper.rn |
| Fits on a Pixel 9? | Yes. It has 12 GB of memory, and 1.2 GB is about 10% of that. Plenty of room, within the budget in section 4.1 |

It's the best balance of accuracy and speed for English, and the Pixel 9 has room to
spare. Punctuation without an extra step is a real bonus for a word-for-word
transcript.

### 5.2 iPhone on iOS 17: **Whisper `small.en` q5 through whisper.rn, with Core ML**

Not Parakeet, because you asked for spare memory. Parakeet needs about 1.2 GB. On a
4 GB iPhone with a 2.0 GB limit, that leaves about 800 MB, and less in the background.
That's the kind of margin that works at your desk and crashes on a bad day.

| | |
|---|---|
| Size | About 250 MB on disk. About 400 to 550 MB of memory at peak |
| Spare memory | **Over 1 GB** on the weakest phone |
| Accuracy | Worse than Parakeet, much better than `base.en`. The usual sensible choice for English |
| Speed-up | Core ML on iOS 15 and newer (a big boost on Apple chips). whisper.rn supports it directly |
| Also worth testing | `distil-whisper small.en`. Often better than regular Whisper for English at the same size or smaller |

**Backup plan:** `base.en` q5 (about 60 MB) if even `small.en` is too big for a
particular iPhone. **Don't use** `SFSpeechRecognizer`. It's the only iOS 17 option that
needs no download, but it's made for short phrases, not hour-long meetings, and it's
much worse.

**Not recommended: WhisperKit.** It's faster on Apple chips and worth knowing about,
but it only works on Apple devices and would mean maintaining a second engine. Only
look at it again if whisper.rn's Core ML support disappoints in Phase 0.

### 5.3 Accept that the two platforms will write different transcripts

Parakeet and Whisper punctuate and split lines differently. Instead of fighting that,
save which model was used (`asrModelId`) with every meeting and include it in exports.
If matching transcripts across devices turns out to matter, use `small.en` everywhere
and give up some accuracy on the Pixel. But decide that with Phase 0 results, not now.

### 5.4 Ways to make it efficient (you asked for "most efficient")

1. **Record directly as 16 kHz mono audio.** That's exactly what both models want. No
   conversion needed, about 32 KB per second (about 115 MB per hour). Optionally, also
   save a compressed copy (Opus or AAC at about 32 kbps, about 15 MB per hour) and
   delete the raw audio after transcribing.
2. **Only transcribe when someone is talking.** whisper.rn includes the Silero voice
   detector. A typical meeting is 60 to 75% speech, so skipping the rest saves 25 to
   40% on the slowest step.
3. **Cut audio at pauses, not at fixed times.** Never cut in the middle of a word. It's
   faster and more accurate.
4. **Load the model once per job**, not once per piece. Loading takes seconds, and a
   60-minute meeting has a lot of pieces.
5. **Use the spare time for quality.** With no live deadline, use the more accurate
   settings and `small.en` instead of `base.en`. That's the payoff from section 3, so
   take it.
6. **Transcribe while charging by default.** The phone is next to the laptop on a
   cable. Offer "transcribe now" or "transcribe when charging", and default to now if
   it's already charging.
7. **Use Core ML on iPhone and the GPU (Vulkan) on Android.** whisper.rn supports both.
   There's also support for Qualcomm's AI chip, but it's experimental and doesn't apply
   to the Pixel's Tensor G4.

---

## 6. The export file: what stage 1 really delivers

Design this carefully. It's where this app ends and where an outside AI tool starts.

**The JSON format the app actually produces**, from `export/Exporters.kt` (`schema:
scribe.transcript.v1`). This replaces the proposal that used to be here. The real
format is flatter, uses snake_case names, includes a version number, and stores times
as milliseconds since 1970 instead of ISO-8601 dates.

```jsonc
{
  "schema": "scribe.transcript.v1",
  "title": "Weekly sync",
  "started_at": 1758362404000,          // milliseconds since 1970, not ISO-8601
  "duration_ms": 2847000,
  "language": "en",
  "asr_model": "parakeet-tdt-0.6b-v3-int8",
  "lines": [
    {"i": 0, "t_start_ms": 0,    "t_end_ms": 3120, "text": "Okay, let's get started.", "confidence": 0.940},
    {"i": 1, "t_start_ms": 3120, "t_end_ms": 9840, "text": "...",                      "confidence": 0.880}
  ]
}
```

We removed three fields from the proposal on purpose:
- `meeting.id`: the file itself is what gets handed over, and nothing outside the phone
  needs the database ID.
- `endedAt`: you can work it out from `started_at + duration_ms`.
- `device`: never used, and it would identify your phone to whatever reads the
  transcript.

**Other export formats:** ✅ all done, under *Export as…* in the meeting's menu.

- **Markdown:** ✅ `Exporters.markdown`.
- **Subtitles (`.srt` and `.vtt`):** ✅ `Exporters.srt` and `Exporters.vtt`.
- **Chatbot-ready:** ✅ `Exporters.promptReady`. This was a good idea. It's the
  transcript with instructions to summarize and point to the lines used, and it's the
  one place those instructions are actually written down.

**Line numbers hold everything together.** Sharing, search, editing and, when stage 2
arrives, pointing back to sources all depend on them. They're stored as `Line.idx`,
never change, count up within each meeting, and are exported as `i`.

### Sharing inside the app

- Tap a line to share it with its timestamp. ✅ (a tap, not a long press)
- Select several lines to share them as a quote. Not built.
- Share the whole transcript as `.txt` or `.json` through the phone's share menu. ✅
- **No server, no account, no upload.** ✅

---

## 7. How it's put together

```
┌──────────────── Quick start ──────────────────┐
│ Android: 1x1 widget / shortcut / QS tile      │
│ iOS 17:  WidgetKit widget → opens app         │
└───────────────────┬───────────────────────────┘
                    ▼
┌────────── Recorder (native, isolated) ────────┐
│ Android: ForegroundService(type=microphone)   │
│ iOS: AVAudioSession + UIBackgroundModes:audio │
│ 16 kHz mono PCM → 30 s files on disk          │
│ almost no CPU · screen off · crash loses ≤30s │
└───────────────────┬───────────────────────────┘
                    │  (disk is the only link)
                    ▼
┌──────── Transcription queue (after meeting) ──┐
│ Android: separate process ":asr"              │
│ iOS: same process, but never while recording  │
│ skips silence · resumable · checks memory     │
│ Pixel 9: Parakeet v3 · iOS 17: whisper small.en│
└───────────────────┬───────────────────────────┘
                    ▼
┌──────────────── Storage (local) ──────────────┐
│ SQLite (op-sqlite) · Meeting + Line           │
│ audio can be deleted after transcription      │
└───────────────────┬───────────────────────────┘
                    ▼
┌────────── Share / export (section 6) ─────────┐
│ json · md · txt · srt · vtt · chatbot-ready   │
│                                               │
│   ─ ─ ─ ─ ─ ─ stage 2 starts here ─ ─ ─ ─ ─   │
│   Summarizer: placeholder only, not built     │
└───────────────────────────────────────────────┘
```

### Data model

```ts
Meeting { id, title, startedAt, endedAt, durationMs, audioPath?, asrModelId, device, transcriptionState, deletedAudioAt? }
Line    { id, meetingId, idx, tStartMs, tEndMs, text, confidence }
AsrJob  { id, meetingId, lastSegmentDone, state: queued|running|failed|done, error?, attempts }
// stage 2, later:
// Speaker { ... }  Summary { ... }  Bullet { ..., sourceLineIds[] }
```

`transcriptionState` and `AsrJob.lastSegmentDone` are what let a job pick up where it
left off after a crash (section 4.2, rule 9).

---

## 8. The home-screen button

### Android: really one tap

A 1×1 home-screen widget, plus a launcher shortcut and a Quick Settings tile. A tap
opens an invisible screen that starts the recording service and closes straight away.
We need that invisible screen because Android 14 and newer block a microphone service
from starting in the background (`SecurityException`). Starting it from a screen the
user can see is the reliable way around that. The widget shows the elapsed time and a
stop button.

### iPhone on iOS 17: one tap, but the app opens briefly

**An iPhone widget, shortcut or extension can't start the microphone.** Apple blocks
starting a recording from the background. So the widget **opens the app** straight
into recording, which starts right away (about 1 second, nothing else to tap). Add an
App Shortcut for Siri and Back Tap too. Once it's recording, a **Live Activity**
(iOS 16.1 and newer, fine on 17) shows the time and a stop button on the lock screen
without opening the app.

Control Center buttons need iOS 18, so drop them from the plan.

Button text: "opens and starts recording". Starting silently in the background isn't
possible on iPhone, so don't promise it.

---

## 9. Phase 0: a one-week test to see if it works (do this first)

Smaller than revision 2's, because the goal is smaller. A throwaway React Native app,
no polish. On **your own Pixel 9 and your own iPhone**:

**The test recording**
1. Record one real 45-minute work meeting, with the phone about 40 cm from the laptop
   and the speakers at normal volume. Measure everything below against this one file.
   Keep it.

**Accuracy (this decides everything)**
2. Run **Parakeet v3 int8**, **`small.en` q5**, **`distil-whisper small.en`** and
   **`base.en` q5** on it. Judge the mistakes by eye, **separately** for (a) your own
   voice and (b) the people coming through the laptop speakers. The gap between those
   two decides whether this product works.
3. Does typing near the phone mess up lines?
4. Two people talking over each other through one speaker (harder than a real room,
   because the voices come from the same spot). How bad is it?

**Memory (you asked for this)**
5. Measure the peak memory for each model on each phone, with the app open and in the
   background. Measure it, don't estimate it. Check that the budget in section 4.1
   leaves more than 1 GB spare on the iPhone.
6. On Android, make transcription run out of memory on purpose, and check that
   recording keeps going (section 4.2, rule 3). If it doesn't, fix the process
   separation before anything else.

**Staying alive**
7. Does the Android recording service keep running for 60 minutes with the screen
   off, with the Pixel's battery saving turned on?
8. Does background recording on iOS 17 keep running for 60 minutes with the phone
   locked? (Recording only, no transcription, as in section 3.)

**Speed**
9. How long does it take to transcribe the 45-minute file, for each model on each
   phone, while charging? If the Pixel does it in under 5 minutes, transcribing after
   the meeting is clearly right and section 3 is settled.

**When to stop:** if the transcript of people coming through the laptop speakers is
too wrong to quote, recording through the air doesn't work. Then the answer is a Mac
app that records the computer's audio directly, with no loss. Find that out in the
first week.

---

## 10. Phases

Status as of v0.7.0 on Android. There's no iPhone app. The Mac app has its own phases
in [MACOS.md](MACOS.md), section 8.

- **Phase 1: first working version. ✅ Done.** Widget or in-app record → background
  recording with the screen off → stop → transcription job → transcript screen →
  share one line → export → list of past meetings. **This is all of stage 1.** Done in
  v0.1. Exports were `.txt` and `.json` only at that point, not every format in
  section 6.
- **Phase 2: fix what actually hurts.** Mostly done:
  - ✅ search across all meetings
  - ✅ rename meetings
  - ✅ jobs recover after problems and never fail without telling you
  - ✅ level meter
  - ✅ settings for keeping or deleting audio (Settings → Storage and privacy)
  - Not built: the setup test, headphone detection, choosing a model size.
- **Phase 3: optional extras.**
  - ✅ Live transcription. Done early, as a preview instead of a separate mode
    (section 3.1).
  - ✅ Telling speakers apart. Optional and marked as still in testing. It uses
    [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (pyannote to find speaker
    changes, plus a voice fingerprint model), with speaker names and a Conversation
    view ([INTELLIGENCE.md](INTELLIGENCE.md)).
  - Not built: editing the transcript, reading your calendar for meeting titles.
- **Phase 4: stage 2 inside the app, if you still want it.** Not started, and still out
  of scope. See the appendix.

**Still open from earlier:** running transcription in a separate process (section
4.2, rule 3), and the Phase 0 measurements (section 9). The accuracy numbers in
section 5 come from the model's authors, and the memory numbers in section 4.1 are
estimates. Neither was ever checked on the actual Pixel 9.

---

## 11. Consent and rules: don't put this off

Recording **work meetings with colleagues** raises two separate issues:

1. **The law.** In some places (much of the EU and several US states), everyone in the
   conversation has to agree to being recorded. Include a one-tap "tell people I'm
   recording" helper and an explanation on first launch.
2. **Your employer's rules.** Many companies ban recording internal meetings, whatever
   the law says. Recording a confidential meeting on your own phone can get you in
   trouble at work even if it's legal. Check your company's policy before making this
   a habit. Check again against section 1.5 before you paste a transcript into an
   outside AI.

In practice: always show that the microphone is on (rely on the phone's own mic
indicator); set **"delete audio after transcription" to ON** by default; never send
anything anywhere in stage 1; and say all this plainly in the README.

---

## 12. Folder layout

> **Replaced.** This section proposed an Expo project with shared folders
> (`/apps/mobile`, `/packages/core`, separate `/ios` and `/android` folders, TypeScript
> everywhere). None of it survived the switch to native Kotlin. This is what's actually
> there:

```
/app/src/main/java/me/vattitude/scribe/
  ScribeApp.kt              # app startup: notification channels, WorkManager
  /widget
    ScribeWidget.kt         # 1x1 home-screen widget, start/stop + time
    RecordTrampolineActivity.kt  # invisible screen that lets recording start (section 8)
  /capture
    RecorderService.kt      # foreground service, type=microphone
    SegmentWriter.kt        # 16 kHz mono audio → 30 s files on disk
    LiveTranscriber.kt      # reads the same audio on its own thread (section 3.1)
    LiveTranscript.kt       # the live preview, kept in memory
    RecordingState.kt       # tracks what the recording is doing
    Audio.kt                # audio format settings and conversion
  /asr
    AsrEngine.kt            # shared interface for speech engines
    ParakeetEngine.kt       # accurate transcript: Parakeet TDT 0.6B v3 int8
    StreamingEngine.kt      # live preview: 20M streaming zipformer int8
    ModelManager.kt         # list of models, download, unpack, clean up
    TranscribeWorker.kt     # background job, resumes file by file
  /store
    Db.kt                   # the SQLite database: meetings + lines
  /export
    Exporters.kt            # txt · md · json · srt · vtt · chatbot-ready
    Backup.kt               # scribe.backup.v1: whole library as a zip, export + restore
  /ui
    MainActivity.kt         # meeting list, search, model download
    RecordingActivity.kt    # level meter + live preview
    MeetingDetailActivity.kt, LineAdapter.kt, MeetingAdapter.kt, LevelMeterView.kt
/scripts/setup.sh           # downloads the 48 MB sherpa-onnx library into app/libs
/docs
  PLAN.md                   # this file
  /design                   # design files from Claude Design: Material 3 dark theme
```

There's no summary placeholder in the code. The line between stage 1 and stage 2 is
the `.json` export (section 6) and nothing else, which turned out to be a cleaner
version of the same idea. `DECISIONS.md`, `BENCHMARKS.md` and `HANDOFF.md` were never
written. Section 6 of this file is still the only description of the export file, and
the Phase 0 results (section 9) were never formally recorded.

Name ideas were *Deskmate*, *Roomnote*, *Tabletop* and *Offrecord*, to be checked
against app stores and trademarks first. **Decided: it launched as Scribe and was later
renamed Meeting Transcript.** The package ID `me.vattitude.scribe` stays the same on
purpose, so existing installs keep updating. The GitHub repo is now called
`MeetingTranscript`.

---

## 13. Risks

"Plan" says what we meant to do about each risk. "Status" says what's true at v0.7.0.

| Risk | How serious | Plan | Status |
|---|---|---|---|
| **Wearing headphones means only your side is recorded** | **High.** The most likely real problem | Detect a single voice and warn; a setup test; explain it on first launch | ⚠️ **Still open on Android.** None of the three were built. It's only explained in the README. The live preview helps by accident: if only one voice shows up, you can see it while there's still time to fix things. The Mac app removes this risk completely by recording the computer's audio directly ([`mac/`](../mac)) |
| Transcripts of people on the call are too wrong to quote | **High.** This would stop the project | Phase 0 test 2; the best model memory allows; placement advice; an external mic later | ⚠️ **Not measured.** Phase 0 was never formally run, so this was never tested. The best model did ship |
| iPhone runs out of memory on a 4 GB phone | Medium (was high before section 4) | 1 GB peak budget, more than 1 GB spare, check memory before each job, `base.en` as a backup | Doesn't apply. There's no iPhone app |
| Phone's battery saving stops the Android recording | Medium | The Pixel behaves well, but test anyway. Saving to disk in pieces means a stop isn't fatal | ✅ Saving in pieces is done. In `6d480d5`, we removed the low-battery check, so a low battery no longer blocks transcription |
| Transcription fails on a 2-hour meeting | Medium | Jobs that resume, a separate process on Android, retry each file | ◐ Resuming and per-file retry are done. **The separate process isn't** (section 4.2, rule 3) |
| The two devices produce different transcripts | Low | Save which model was used. Use `small.en` everywhere if it ever matters | ✅ The model name is saved and exported. Android and Mac use the same Parakeet model, but the Mac cuts audio at pauses while Android uses fixed 30-second files, so wording can differ a little where the cuts fall. The live preview and the final transcript also differ *within* Android, which is why the final one replaces the preview |
| Exporting for stage 2 leaks what was said in the meeting | Medium | Sections 1.5 and 11: a conscious decision, and plain-text exports so you can read exactly what you're sending | ✅ `.txt` and `.json` can both be read by a person before you send them |
| Your employer's rules about recording | Medium | Section 11: check before making it a habit | ⚠️ The README covers it. There's no explanation in the app and no "tell people I'm recording" helper |

---

## 14. Open questions

1. ~~**Which iPhone?**~~ **Answered: the second platform is the Mac.** An iPhone app
   isn't planned. No iPhone app can record another app's audio, so an iPhone version
   could only record the room. A Mac can record its own audio directly, which avoids
   the whole problem in section 1.2 and the "stop the project" risk in section 13.
   Built: a menu bar app in [`mac/`](../mac) that saves the same 16 kHz audio files as
   this app. Its design is in [MACOS.md](MACOS.md).
2. ~~**Android first?**~~ **Answered: yes.** Android was built first. The second
   platform is the Mac, not the iPhone (see 1).
3. **How long is a typical meeting?** Still open. Jobs can resume either way, but it
   hasn't been tested on a 2-hour meeting.
4. ~~**Keep the audio for playback while reading, or delete it after transcribing?**~~
   **Answered: delete it, by default.** Settings has a *Delete audio after transcribing*
   switch, on by default as section 11 always said it should be. There's also a *Clear
   recorded audio* button that keeps the transcripts. `TranscribeWorker` only deletes
   audio after a successful run, so if something fails, the audio is kept for a retry.
   Playback while reading has since been built (*Play from here* on any line) for
   meetings whose audio is kept. So it's for people who turn that switch off.
5. **Which outside AI tool for stage 2?** Still open. The `.json` export (section 6) is
   still the only link.

---

## Appendix: stage 2, saved for later

Research from revision 2, kept so nobody has to redo it. It only matters if summaries
come back into the app.

**The AI models built into phones aren't good enough for this**, and on your devices
they don't exist anyway:
- **Apple Foundation Models** need iOS 26 or newer (❌ on 17). They can only handle
  about 4,096 tokens (pieces of words), counting both the instructions *and* the
  answer. A one-hour meeting is 8,000 to 10,000 tokens, so the model can't even see
  the whole meeting.
- **ML Kit GenAI / Gemini Nano** is still in beta, handles under 4,000 tokens, supports
  only English, Japanese and Korean, and only works on some phones (checked with
  `checkFeatureStatus()`). The Pixel 9 supports it, but the length limit is the same
  problem.

**If we ship our own model:** Qwen3.5-2B Instruct Q4 (about 1.3 to 1.5 GB) by default,
and Qwen3.5-4B (about 2.5 to 3 GB, reportedly handles 262,000 tokens) on phones with 8 GB
or more. The Pixel 9 handles that easily. An iPhone on iOS 17 doesn't. Qwen3 1.7B (about
1.1 GB) is what [Hyprnote](https://github.com/fastrepl/hyprnote) fine-tuned for exactly
this job. Handling long text matters because reading the whole transcript at once is
better than summarizing it in pieces. Summarizing in pieces loses connections between
parts, counts things twice, and splits up action items.

**A must-have whenever stage 2 happens:** every point the AI writes must list the line
numbers (`source_line_ids`) it came from, and you must be able to tap through to those
lines. That's how you catch the AI making things up, and it's what makes the notes
trustworthy enough to send to others. The line numbers in section 6 exist for this.

**Similar projects worth learning from.** They're all for computers. Nothing open source
does this on a phone, which is the gap.
- For computers: [Hyprnote/anarlog](https://github.com/fastrepl/hyprnote) (the best
  product to learn from), [Meetily](https://github.com/Zackriya-Solutions/meetily),
  [ownscribe](https://github.com/paberr/ownscribe),
  [OpenWhispr](https://github.com/OpenWhispr/openwhispr).
- For phones: [whisper.rn](https://github.com/mybigday/whisper.rn) (MIT license, the
  engine), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Parakeet plus telling
  speakers apart),
  [react-native-executorch](https://docs.swmansion.com/react-native-executorch/) (a
  backup engine), and [WhisperBoard](https://github.com/saik0s/whisperboard) (the
  closest phone app, worth reading for how it handles recording).
- Check licenses before copying anything. Only whisper.rn's MIT license was confirmed.
