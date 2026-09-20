# On-Device Meeting Notes — Project Plan

**Status:** initial planning draft, rev 3 — 2026-09-20. Superseded in part by what
shipped; see the note below before trusting any specific claim in here.
**Shape:** two stages, shipped separately.

> **This is the analysis that preceded the build, not a description of it.**
> Treat it as a record of the reasoning, not current fact. The biggest
> divergence: what shipped is a **native Android (Kotlin) app, not React
> Native**, and there is **no iOS build** — §1.1 and the iOS-specific sections
> below (§1.4's iOS half, §2, §5.2, §8's iOS half, §12) describe a path that
> was not taken. On the Android side, the build is generally *ahead* of this
> plan: §3.1's "both passes" live+accurate pipeline, VAD-less but disk-coupled
> recording, Material 3 UI, and export are all shipped. Still open from here:
> `:asr` process isolation (§4.2 rule 3 — not implemented, see the README's
> Known Limits) and diarization (§5's Phase 3). §​6's export formats have since
> all shipped — markdown, srt, vtt and prompt-ready — along with whole-library
> backup and restore, which this plan never anticipated. The iOS question in
> §14 is now answered: **the second platform is macOS, not iOS**, because iOS
> cannot capture another app's audio. See [MACOS.md](MACOS.md).
> See [README.md](../README.md) for what's actually true of the app today.

- **Stage 1 (this project, v1):** phone sits on the desk beside the work laptop, one-tap widget starts it, records with the screen off for the length of a meeting, and produces a **verbatim, timestamped, line-addressable English transcript** on device. This is the whole deliverable.
- **Stage 2 (later / external):** summary, action points, outcomes. Handed to an external agent or model via a clean export. Not built in v1; the interface for it is.

**Target devices:** Google Pixel 9 (Android) and an **iOS 17** iPhone. Not the latest OS — plan for older platform APIs and leave real RAM headroom.

---

## What changed in rev 3

Your two constraints reshaped the plan more than rev 2's did:

1. **Splitting out summarisation removes ~60 % of the app.** No bundled 1.4–3.4 GB LLM, no llama.rn, no map-reduce pipeline, no prompt templates, no hallucination-anchoring UI. Download drops to **~250–700 MB**. The whole project becomes "a very good on-device recorder/transcriber with a clean export" — which is a thing you can finish.
2. **iOS 17 invalidates rev 2's iOS model picks.** Apple `SpeechTranscriber` is iOS 26+. Apple Foundation Models are iOS 26+. Both are out. iOS falls back to a bundled model, and on an iOS-17-era iPhone the memory ceiling is real (§4).
3. **A third change falls out of stage 1 being the whole job:** with no summary to produce, there's no reason to transcribe *during* the meeting. Transcribe **after**. That single decision deletes rev 2's largest iOS risk — sustained background inference — and buys accuracy for free (§3).

---

## 1. The decisions

### 1.1 Native, React Native (Expo, prebuild / dev-client)

> **Not what shipped.** The actual app is plain native Kotlin, Android only —
> no Expo, no React Native, no iOS. See the top-of-file note.

PWA remains impossible: no home-screen widget for web apps on either OS, and iOS Safari suspends JS and tears down the mic the moment you background or lock — the recording dies mid-meeting. Since "screen off, in the background" is a hard requirement, this isn't close.

React Native because the on-device ASR bindings live there (`whisper.rn`, `react-native-sherpa-onnx`, `react-native-executorch`) and are cross-platform. The widget and foreground-service work is native Swift/Kotlin in any wrapper, so Capacitor would mean hand-writing the hard part *and* getting a worse ML ecosystem.

### 1.2 Capture: acoustic, near-field, mic only

```
  [ remote participants ] ──▶ laptop speakers ──┐
                                                ├──▶ phone mic ──▶ 16 kHz mono PCM ──▶ disk
  [ you, speaking ] ────────────────────────────┘
```

Both sources arrive at 30–60 cm. Near-field, and much easier than a conference table. No OS restriction applies — you're routing around the "phones can't capture system audio" problem physically.

**Four constraints, all of which belong in onboarding:**

1. **Headphones break it completely.** On a headset the phone hears only you, and you get a half-transcript that reads like a monologue. This is the most likely real-world failure. Detect it: if a long stretch shows only one voice, warn non-intrusively.
2. **Laptop volume must be up.** Ship a "test my setup" flow — 5-second record, level meter, one-time validation.
3. **Placement:** face-up, mic end toward the laptop, not under paper, not flat on a hard surface that couples keyboard noise.
4. **Keyboard clatter is near-field too**, and louder than speech. VAD handles it acceptably; verify in Phase 0.

**Expect worse accuracy on remote participants than on your own voice** — their audio has already been through a conferencing codec and small laptop speakers before crossing the air. That's an argument for the most accurate model the memory budget allows, not a reason to worry.

### 1.3 English only

A real simplification: unlocks `.en` Whisper variants and Parakeet (smaller, faster, *more* accurate than multilingual siblings), halves download size, removes language detection. Keep the model registry config-driven so multilingual is an entry, not a rewrite.

### 1.4 Screen off, in the background

- **Android:** foreground service, `android:foregroundServiceType="microphone"`, plus `FOREGROUND_SERVICE_MICROPHONE` (required 14+), `RECORD_AUDIO`, `POST_NOTIFICATIONS`. Persistent notification with elapsed time and stop. Survives screen-off by design.
- **iOS 17:** `UIBackgroundModes: audio` keeps the session alive when locked. Recording in the background is well-supported and low-CPU. **Because ASR now runs after the meeting (§3), there is no sustained background inference to worry about** — this was rev 2's biggest iOS unknown and it's now designed out.

### 1.5 The two-stage boundary

Stage 1's output is a file, not a feature. Define it properly (§6) and stage 2 becomes replaceable: an external agent today, an in-app local LLM later, a different model next year — same artifact.

> **Privacy note, stated once.** Stage 1 is fully local: no audio ever leaves the phone. Stage 2 sending a transcript to an external model *does* mean meeting text leaves your device. That's still far better than a cloud notetaker bot (no audio, no third-party joining the call, and you choose what to send and when) — but it's not "nothing leaves the device", and for internal work meetings that distinction matters. Worth deciding deliberately rather than by default. See §10.

---

## 2. Target devices and what they can run

| | Pixel 9 | iOS 17 iPhone |
|---|---|---|
| RAM | **12 GB** (Pro: 16 GB) | Depends on model — iOS 17 runs on iPhone XS through 15 |
| SoC | Tensor G4, 3rd-gen TPU ("rio") | A12 through A17 Pro |
| **Practical app memory ceiling** | Generous; native (NDK) allocations aren't bound by the Java heap cap | **~2.0 GB hard limit on 4 GB devices** (iPhone XS–11 class), more on 6 GB+. Lower again when backgrounded |
| Relevant APIs | ONNX Runtime, Vulkan, NNAPI (deprecated in 15, still functional) | Core ML, Metal. **No** SpeechTranscriber (26+), **no** Foundation Models (26+) |

**The iPhone is the binding constraint, not the Pixel.** Design the memory budget for a 4 GB iOS-17 iPhone in the background and everything else has slack.

**iOS 17 platform feature check** (things rev 2 assumed that you can't have):

| Feature | Min iOS | Available on 17? |
|---|---|---|
| WidgetKit widget + `widgetURL` | 14 | ✅ |
| App Intents / App Shortcuts / Siri | 16 | ✅ |
| Live Activity (lock-screen control) | 16.1 | ✅ |
| Dynamic Island | 16.1 + iPhone 14 Pro hardware | ⚠️ device-dependent |
| Interactive widgets (buttons in widget) | 17 | ✅ (exactly at the floor) |
| Control Center controls | **18** | ❌ cut it |
| `SpeechAnalyzer` / `SpeechTranscriber` | **26** | ❌ |
| Apple Foundation Models | **26** | ❌ |

---

## 3. Transcribe *after* the meeting, not during

This is the most consequential change in rev 3, so it gets its own section.

With summarisation out of scope, nothing needs the text until the meeting ends. So:

```
during meeting   →  record only. 16 kHz mono PCM, ~32 KB/s, near-zero CPU. Screen off. Cool phone.
on stop          →  queue an ASR job over the segments on disk.
job runs         →  Pixel 9 with Parakeet should chew a 60-min meeting in single-digit minutes.
                    iPhone with small.en, slower but unattended — run it on the charger.
transcript ready →  notification. Read, share, export.
```

**What this buys:**

| | Live transcription (rev 2) | Post-meeting (rev 3) |
|---|---|---|
| iOS background inference risk | High — may be throttled or jetsammed | **Gone.** Background recording only |
| Thermal / battery during meeting | Hour of sustained inference | Negligible |
| Decoding quality | Must be greedy, beam 1, to keep RTF < 1 | **Can afford beam search and a better model** — no real-time deadline |
| Failure blast radius | ASR crash can take the recorder with it | Recorder already finished; just re-run the job |
| State machine | Complex, two concurrent pipelines | Simple, sequential |

**Cost:** you don't see words appear during the meeting. For your use case — read the notes afterwards, hand them to an agent — that's worth nothing. Make live transcription an optional Phase 3 mode if you ever want it.

**Corollary:** the recorder and the transcriber are decoupled *by the disk*. The recorder's only job is bytes-to-disk, and it must be impossible for the ASR path to affect it (§4.2).

### 3.1 Rev 4: both, in that order

Shipped behaviour as of v0.3.0. The table above is not wrong — every row of it still
argues for the post-meeting pass, which is why that pass is still the one that
produces the transcript you keep. What changed is that "you don't see words appear
during the meeting" turned out *not* to be worth nothing: watching it work is how you
know the phone is actually picking up the room, while you can still move it.

So both passes run, and the live one is explicitly scaffolding:

```
during meeting   →  record to disk, AND stream a 20M zipformer for the on-screen preview.
on stop          →  queue the Parakeet job over the same segments on disk.
job runs         →  clears the live lines, writes its own. Same meeting, replaced in place.
```

The live pass buys none of the quality arguments above and gives up all of them — it is
greedy, beam 1, 20M parameters, and it commits words before hearing the end of the
sentence. It is a preview, not a draft. Two things keep it from costing anything that
matters:

- It reads the same `AudioRecord` buffers the writer already has, through a bounded
  queue on its own thread. If it falls behind it drops audio and the recording does not
  notice — the disk copy is always complete regardless (§4.2 still holds).
- Its output is disposable by construction. `TranscribeWorker` clears the meeting's
  lines once the accurate model has loaded — after, so a failed load leaves the preview
  rather than an empty meeting.

**Cost:** two models on disk (~615 MB downloaded) instead of one, and the accurate pass
now always runs rather than being skipped when live produced something.

---

## 4. Memory: budget it, don't discover it

You asked for headroom. Here it is as an explicit design constraint rather than a hope.

### 4.1 The budget

**Rule: peak resident memory stays under 1 GB on iOS, under 2 GB on Android.** On a 4 GB iPhone that leaves >1 GB of headroom against the ~2.0 GB jetsam ceiling, which is the margin that keeps you alive when backgrounded (where limits are tighter) and when the OS is under pressure from other apps.

| Component | iOS budget | Android budget |
|---|---|---|
| ASR model weights | ~250 MB (`small.en` q5) | ~700 MB (Parakeet v3 int8) |
| ASR runtime state / KV | ~150–300 MB | ~500 MB |
| Audio buffers (streamed, never whole-file) | < 10 MB | < 10 MB |
| RN/JS + UI | ~150–250 MB | ~200–300 MB |
| **Peak target** | **< 1 GB** | **< 2 GB** |
| Headroom on worst-case device | **> 1 GB** | **> 10 GB** |

Two iOS entitlements exist if you ever need more — Increased Memory Limit and Extended Virtual Addressing (the latter is what whisper.rn recommends for medium/large models). **Don't reach for them.** Needing an entitlement to avoid a crash means the model is too big for the device, and they don't help on iPhone 11 and earlier anyway.

### 4.2 Architectural rules that make an OOM survivable

1. **The recorder writes rolling ~30 s segments straight to disk.** Any crash — OOM, bug, battery death — loses at most 30 seconds.
2. **The transcriber reads from disk and never from the recorder's memory.** They share a directory, not a buffer.
3. **On Android, run ASR in a separate process** (`android:process=":asr"`). An ASR OOM kills that process only; the recording foreground service keeps going. This is free and it's the single best reliability move available to you. — ⚠️ **Not implemented.** `TranscribeWorker` runs in the main process today. Rules 1, 2 and 9 still hold, so an ASR OOM costs a retry rather than audio; this rule remains the main unclaimed reliability win.
4. **On iOS you can't multi-process**, so the mitigation is that ASR runs *after* recording has finished (§3). An ASR crash costs you a retry, never audio.
5. **Never load a whole meeting's audio into memory.** Stream segment by segment. A 2-hour meeting at 16 kHz mono is ~230 MB of PCM — enough to matter.
6. **Never hold the whole transcript in a non-virtualised list.** 2 hours ≈ 1,500–2,500 lines. Use a virtualised list from day one.
7. **Keep the ASR context warm across segments**, but tear it down when the job finishes. Don't leave 400 MB resident while the user browses old notes.
8. **Check headroom at runtime before starting a job** — `os_proc_available_memory()` on iOS, `ActivityManager.MemoryInfo` + `onTrimMemory` on Android. If headroom is thin, drop to the smaller model tier for that job and say so in the UI rather than crashing. — ⚠️ **Not implemented**, and there is no smaller tier to fall back to: the live model can't do the accurate pass.
9. **Resumable jobs.** The ASR job records which segment it last completed. A crash mid-transcription resumes; it doesn't restart an hour of work.

---

## 5. Model recommendations

### 5.1 Android / Pixel 9 → **Parakeet TDT 0.6B v3, int8**

| | |
|---|---|
| Accuracy | **6.34 % WER** on the HF Open ASR Leaderboard, vs Whisper large-v3's 7.44 % |
| Size | ~600 M params; ~700 MB on disk, ~1.2 GB RAM working set |
| Speed | Dramatically faster than Whisper at a quarter the size; built-in punctuation |
| Runtime | ONNX Runtime (via sherpa-onnx) or GGUF via whisper.rn |
| Fit on Pixel 9 | 12 GB RAM — the 1.2 GB working set is ~10 % of the device. Comfortable, with the §4.1 budget intact |

Best accuracy-per-millisecond available for English, and the Pixel 9 has room to spare. Punctuation without a separate restoration pass is a real bonus for a *verbatim* transcript.

### 5.2 iOS 17 → **Whisper `small.en` q5 via whisper.rn, with the Core ML encoder**

Not Parakeet, and the reason is your headroom request. Parakeet's ~1.2 GB working set against a ~2.0 GB ceiling on a 4 GB iPhone leaves ~800 MB — and less when backgrounded. That's the kind of margin that works on your desk and crashes on a bad day.

| | |
|---|---|
| Size | ~250 MB on disk; ~400–550 MB peak RSS |
| Headroom | **> 1 GB** on the worst-case device |
| Accuracy | Behind Parakeet, ahead of `base.en` by a wide margin — the standard sensible choice for English |
| Acceleration | Core ML encoder on iOS 15+ (large win on Apple silicon); whisper.rn supports it directly |
| Alternative to bake off | `distil-whisper small.en` — often beats base Whisper on English at similar or smaller size |

**Fallbacks:** `base.en` q5 (~60 MB) if even `small.en` proves tight on the specific iPhone. **Do not use** `SFSpeechRecognizer` — it's the only zero-download iOS 17 option, but it's built for short utterances, not hour-long meetings, and the quality gap is not close.

**Not recommended: WhisperKit.** It's faster on Apple silicon and worth knowing about, but it's iOS-only and adds a second runtime to maintain. Revisit only if whisper.rn's Core ML path disappoints in Phase 0.

### 5.3 Accept that the two platforms produce different transcripts

Parakeet and Whisper punctuate and segment differently. Rather than fight it, record `asrModelId` on every meeting and surface it in the export. If cross-device consistency turns out to matter, standardise on `small.en` everywhere and lose some Pixel accuracy — but decide that with Phase 0 data, not now.

### 5.4 Efficiency levers (you asked for "most efficient")

1. **Record at 16 kHz mono PCM directly** — exactly what both models want. No resampling, no decode step, ~32 KB/s (~115 MB/hour). Optionally encode a parallel Opus/AAC archive at ~32 kbps (~15 MB/hour) and delete the PCM after transcription.
2. **VAD-gate the ASR.** Silero VAD ships with whisper.rn. A typical meeting is 60–75 % speech — skipping the rest is a direct 25–40 % saving on the most expensive stage.
3. **Chunk on VAD silence, not on a clock.** Never cut mid-word; cheaper and more accurate at once.
4. **Load the model once per job**, not per chunk. Model load is seconds; a 60-minute meeting has a lot of chunks.
5. **Spend the real-time slack on quality.** No live deadline means beam search instead of greedy, and `small.en` instead of `base.en`. This is the payoff from §3 — take it.
6. **Run the job on the charger by default.** The phone is next to the laptop on a cable. Offer "transcribe now" vs "transcribe when charging" and default to now-if-charging.
7. **Core ML on iOS, GPU/Vulkan on Android.** whisper.rn exposes both; Hexagon NPU support exists but is experimental and irrelevant on Tensor G4.

---

## 6. The handoff artifact — stage 1's real deliverable

Design this carefully; it's the product boundary and the thing an external agent consumes.

**Canonical JSON — as actually emitted** by `export/Exporters.kt` (`schema:
scribe.transcript.v1`). This supersedes the proposal that was here: the shipped
form is flatter, snake_case, carries an explicit schema version, and uses epoch
millis rather than ISO-8601.

```jsonc
{
  "schema": "scribe.transcript.v1",
  "title": "Weekly sync",
  "started_at": 1758362404000,          // epoch ms, not ISO-8601
  "duration_ms": 2847000,
  "language": "en",
  "asr_model": "parakeet-tdt-0.6b-v3-int8",
  "lines": [
    {"i": 0, "t_start_ms": 0,    "t_end_ms": 3120, "text": "Okay, let's get started.", "confidence": 0.940},
    {"i": 1, "t_start_ms": 3120, "t_end_ms": 9840, "text": "...",                      "confidence": 0.880}
  ]
}
```

Dropped from the proposal, deliberately: `meeting.id` (the file is the unit of
handoff; nothing outside the phone needs the row id), `endedAt` (derivable from
`started_at + duration_ms`) and `device` (never used, and it's a fingerprint you
would be handing to whatever consumes the transcript).

**Also export:** ✅ all shipped, behind *Export as…* in the meeting overflow menu.

- **Markdown** — ✅ `Exporters.markdown`.
- **`.srt` / `.vtt`** — ✅ `Exporters.srt` / `Exporters.vtt`.
- **Prompt-ready** — ✅ `Exporters.promptReady`. This was right: it is the
  transcript with the summarise-and-cite instructions already attached, and it
  is the one place the citation contract is actually stated.

**Line ids are the backbone.** Sharing, search, editing and — when stage 2 arrives — citation all hang off them. Shipped as `Line.idx`, stable and monotonic per meeting, exported as `i`.

### Sharing (in-app)

- Tap the share icon on a line → share that line with its timestamp. ✅ (tap, not long-press)
- Drag-select a range → share as a quoted block. — not built.
- Share the whole transcript as `.txt` or `.json`, via the native share sheet. ✅
- **No server, no account, no upload.** ✅

---

## 7. Architecture

```
┌──────────────── Quick capture ────────────────┐
│ Android: 1x1 widget / shortcut / QS tile      │
│ iOS 17:  WidgetKit widget → opens app         │
└───────────────────┬───────────────────────────┘
                    ▼
┌────────── Recorder (native, isolated) ────────┐
│ Android: ForegroundService(type=microphone)   │
│ iOS: AVAudioSession + UIBackgroundModes:audio │
│ 16 kHz mono PCM → rolling 30 s disk segments  │
│ near-zero CPU · screen off · crash loses ≤30s │
└───────────────────┬───────────────────────────┘
                    │  (disk — the only coupling)
                    ▼
┌──────── ASR job queue (after the meeting) ────┐
│ Android: separate process ":asr"              │
│ iOS: in-process, but never while recording    │
│ VAD-gated · resumable · headroom-checked      │
│ Pixel 9: Parakeet v3 · iOS 17: whisper small.en│
└───────────────────┬───────────────────────────┘
                    ▼
┌──────────────── Store (local) ────────────────┐
│ SQLite (op-sqlite) · Meeting + Line           │
│ audio optional-delete after transcription     │
└───────────────────┬───────────────────────────┘
                    ▼
┌────────── Share / export (§6) ────────────────┐
│ json · md · txt · srt · vtt · prompt-ready    │
│                                               │
│   ─ ─ ─ ─ ─ ─ stage 2 boundary ─ ─ ─ ─ ─ ─    │
│   Summarizer interface: stubbed, not built    │
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

`transcriptionState` and `AsrJob.lastSegmentDone` are what make the pipeline resumable after a crash (§4.2 rule 9).

---

## 8. The home-screen entry point

### Android — genuinely one tap

1×1 `AppWidgetProvider` + dynamic launcher shortcut + Quick Settings tile. Tap → `PendingIntent` → transparent trampoline Activity that starts the foreground service and immediately finishes. (Use the trampoline: Android 14+ throws `SecurityException` when a `microphone`-type foreground service starts from the background, and "transitioned from a user-visible state" is the reliable exemption.) Widget shows elapsed time and a stop button.

### iOS 17 — one tap, but the app flashes open

**You cannot start the microphone from a widget, App Intent or extension.** Apple blocks initiating recording from a backgrounded/extension context. So: WidgetKit widget whose `widgetURL` **opens the app** into a recording route that starts immediately (~1 s, nothing to tap), plus an App Shortcut for Siri and Back Tap. Once running, a **Live Activity** (iOS 16.1+, fine on 17) gives stop/elapsed on the lock screen without reopening the app.

Control Center controls are iOS 18 — cut them from the plan.

UI copy: "opens and starts recording". Silent background start isn't achievable on iOS; don't promise it.

---

## 9. Phase 0 — feasibility spike (1 week) — do this first

Narrower than rev 2's, because the scope is narrower. Throwaway RN app, no polish. On **your actual Pixel 9 and your actual iPhone**:

**The benchmark corpus**
1. Record one real 45-minute work meeting, phone at ~40 cm from the laptop, speakers at normal volume. Everything below is measured against this single file. Keep it.

**Accuracy (the make-or-break)**
2. Run **Parakeet v3 int8**, **`small.en` q5**, **`distil-whisper small.en`** and **`base.en` q5** over it. Judge WER by eye, scored **separately** for (a) your own voice and (b) remote participants through the laptop speakers. The gap between those two numbers is the number that decides whether this product works.
3. Does keyboard typing near the phone corrupt lines?
4. Two people talking over each other through one speaker — no spatial separation, harder than a real room. How bad?

**Memory (your explicit ask)**
5. Peak RSS for each model on each device, foreground and background, measured — not estimated. Confirm the §4.1 budget holds with >1 GB iOS headroom.
6. Force an ASR OOM deliberately on Android and confirm the recording service survives (§4.2 rule 3). If it doesn't, fix the process isolation before anything else.

**Platform survival**
7. Does the Android foreground service survive 60 minutes, screen off, on the Pixel's battery management?
8. Does an iOS 17 background-audio session survive 60 minutes locked? (Recording only — no inference, per §3.)

**Throughput**
9. Wall-clock to transcribe the 45-minute file, per model per device, on the charger. If the Pixel does it in under 5 minutes, post-meeting transcription is clearly right and §3 is settled.

**Kill criterion:** if remote-participant WER through laptop speakers is bad enough that lines aren't quotable, the acoustic approach doesn't work — and the answer becomes a Mac app that captures system audio losslessly. Learn that in week one.

---

## 10. Phases

Status as of v0.5.2 (Android only — none of this exists on iOS).

- **Phase 1 — MVP. ✅ Shipped.** Widget/in-app record → background recording with screen off → stop → ASR job → transcript view → line-level share → export → list of past meetings. **This is the whole of stage 1.** Shipped at v0.1; export covers `.txt` and `.json`, not the full §6 format list.
- **Phase 2 — Polish where it actually hurts.** Mostly done: full-text search across meetings ✅, meeting rename ✅, job recovery / never-fail-silently ✅, level meter ✅, audio-retention settings ✅ (Settings → Storage and privacy). Not built: setup-test flow, headphone detection, model tier selection.
- **Phase 3 — Optional depth.** Live transcription mode ✅ — shipped early and reframed as a preview rather than a mode (§3.1). Not built: speaker diarization ([sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), pyannote-segmentation-3.0 + CAM++), transcript editing, calendar read for auto-titles.
- **Phase 4 — Stage 2, if you still want it in-app.** Not started, and still out of scope. See appendix.

**Carried over and still open:** `:asr` process isolation (§4.2 rule 3), the remaining §6 export formats, and the Phase 0 measurements (§9) — the accuracy and memory numbers in §5 and §4.1 are still the model authors' and estimates respectively, never verified on the actual Pixel 9.

---

## 11. Consent and policy — don't defer this

Recording **work meetings with colleagues** raises two separate issues:

1. **Legal.** Two-party-consent jurisdictions (much of the EU, several US states) require everyone's agreement. Ship a one-tap "announce recording" helper and a first-run explainer.
2. **Employer policy.** Many companies prohibit recording internal meetings regardless of local law, and a personal-device recording of a confidential meeting can be a disciplinary matter independent of legality. Worth checking your policy before building a habit around this — and worth re-checking against §1.5 before you paste a transcript into an external model.

Practically: persistent visible recording indicator (lean on the OS mic indicator); default **"delete audio after transcription" to ON**; never transmit anything from stage 1; say so plainly in the README.

---

## 12. Repo layout

> **Superseded.** This section proposed an Expo monorepo (`/apps/mobile`,
> `/packages/core`, per-platform `/ios` and `/android` folders, TypeScript
> throughout). None of it survived the switch to native Kotlin. What's actually
> on disk:

```
/app/src/main/java/me/vattitude/scribe/
  ScribeApp.kt              # Application; notification channels, WorkManager setup
  /widget
    ScribeWidget.kt         # 1x1 AppWidgetProvider, start/stop + elapsed
    RecordTrampolineActivity.kt  # transparent activity — the FGS-from-background exemption (§8)
  /capture
    RecorderService.kt      # foreground service, type=microphone
    SegmentWriter.kt        # 16 kHz mono PCM16 → rolling 30 s disk segments
    LiveTranscriber.kt      # bounded queue off the same buffers, own thread (§3.1)
    LiveTranscript.kt       # in-memory preview state
    RecordingState.kt       # session state machine
    Audio.kt                # format constants, PCM16 → float
  /asr
    AsrEngine.kt            # engine interface + AsrLine
    ParakeetEngine.kt       # accurate, offline — Parakeet TDT 0.6B v3 int8
    StreamingEngine.kt      # live preview — 20M streaming zipformer int8
    ModelManager.kt         # two-model registry, download, tar.bz2 extract, prune
    TranscribeWorker.kt     # WorkManager job, resumable segment by segment
  /store
    Db.kt                   # hand-written SQLiteOpenHelper — meetings + lines
  /export
    Exporters.kt            # txt · md · json · srt · vtt · prompt-ready
    Backup.kt               # scribe.backup.v1 — whole-library zip, export + restore
  /ui
    MainActivity.kt         # meeting list, search, model download
    RecordingActivity.kt    # level meter + live preview
    MeetingDetailActivity.kt, LineAdapter.kt, MeetingAdapter.kt, LevelMeterView.kt
/scripts/setup.sh           # fetches the 48 MB sherpa-onnx AAR into app/libs
/docs
  PLAN.md                   # this file
  /design                   # Claude Design handoff bundle — Material 3 dark theme
```

There is no `/summarize` stub: the Stage 2 boundary is the `.json` export
(§6) and nothing else, which turned out to be the cleaner version of the same
idea. `DECISIONS.md`, `BENCHMARKS.md` and `HANDOFF.md` were never written —
§6 in this file is still the only spec of the handoff artifact, and Phase 0's
numbers (§9) were never formally recorded.

Name candidates: *Deskmate*, *Roomnote*, *Tabletop*, *Offrecord*. Check store/trademark collisions first. **Resolved: shipped as Scribe.**

---

## 13. Risk register

Mitigation column says what was planned; status says what's true at v0.5.2.

| Risk | Severity | Mitigation | Status |
|---|---|---|---|
| **Headphones on → only your half is recorded** | **High** — likeliest real failure | Single-voice detection + warning; setup-test flow; explicit onboarding | ⚠️ **Open on Android.** None of the three built; documented in the README only. The live preview partly covers it by accident — a monologue preview is visible while you can still fix the setup. A Mac app removes the risk rather than mitigating it, by capturing system audio ([MACOS.md](MACOS.md)) |
| Remote-participant WER through laptop speakers too poor to quote | **High** — kill criterion | Phase 0 test #2; best model the budget allows; placement guidance; external mic later | ⚠️ **Unmeasured.** Phase 0 never formally run; the kill criterion was never tested. Best model shipped |
| iOS OOM on a 4 GB iPhone | Medium — *was* high before §4 | 1 GB peak budget, >1 GB headroom, runtime headroom check, `base.en` fallback tier | N/A — no iOS build |
| OEM battery management stops the Android service | Medium | Pixel is well-behaved; still test; rolling disk segments mean a kill isn't fatal | ✅ Segments shipped; battery-level gating removed in `6d480d5` so a low battery no longer blocks transcription |
| ASR job fails on a 2-hour meeting | Medium | Resumable jobs, separate process on Android, segment-level retry | ◐ Resumable + segment-level retry shipped; **separate process not built** (§4.2 rule 3) |
| Transcripts differ between the two devices | Low | Record `asrModelId`; standardise on `small.en` if it ever matters | ✅ `asr_model` recorded and exported. Moot while Android-only — though live vs accurate now differ *within* one device, which is why the accurate pass overwrites |
| Stage-2 export leaks meeting content | Medium | §1.5 and §11 — a deliberate decision, plain-text export so you can read exactly what you're sending | ✅ `.txt` and `.json` are both human-readable before you send them |
| Employer policy on recording | Medium | §11 — check before building the habit | ⚠️ README covers it; no in-app explainer or announce-recording helper |

---

## 14. Open questions

1. ~~**Which iPhone?**~~ ~~**Moot for now.**~~ **Answered: the second platform is macOS.** iOS is off the roadmap — no app can capture another app's audio there, so an iOS Scribe could only ever record the room. macOS captures system audio directly, which sidesteps §1.2's entire acoustic problem and §13's kill criterion. Designed in [MACOS.md](MACOS.md), with a working capture spike in [`mac/`](../mac) that already writes the same 16 kHz PCM segments this app records.
2. ~~**Android first?**~~ **Answered: yes.** Android was built first and is the only platform shipped. The port never started.
3. **Typical meeting length?** Still open. Resumability is built either way, but it's unmeasured against a 2-hour job.
4. ~~**Keep audio for playback-while-reading**, or delete after transcription?~~ **Answered: delete, by default.** Settings now carries a *Delete audio after transcribing* switch, defaulting ON as §11 always said it should, plus a *Clear recorded audio* action that keeps transcripts. Deletion happens in `TranscribeWorker` only on a successful pass, so a failure keeps its audio to retry from. Playback-while-reading was not built and is now mutually exclusive with the default — if it is ever wanted, it is for people who turn the switch off.
5. **Which external agent for stage 2?** Still open. The `.json` export (§6) remains the only boundary.

---

## Appendix — stage 2, preserved for later

Research from rev 2, kept so it isn't re-done. Only relevant if you bring summarisation back in-app.

**The platform models are not good enough for this**, and on your devices they don't exist at all:
- **Apple Foundation Models** — iOS 26+ (❌ on 17), and a 4096-token window covering prompt *and* output. A one-hour meeting is 8–10 k tokens; it physically cannot see the meeting.
- **ML Kit GenAI / Gemini Nano** — beta, under 4000 tokens, English/Japanese/Korean, device-gated via `checkFeatureStatus()`. Pixel 9 supports it, but the context limit is the same problem.

**If bundling a model:** Qwen3.5-2B Instruct Q4 (~1.3–1.5 GB) as default, Qwen3.5-4B (~2.5–3 GB, reported 262 k native context) on ≥8 GB devices — the Pixel 9 handles this easily, an iOS-17 iPhone does not. Qwen3 1.7B (~1.1 GB) is what [Hyprnote](https://github.com/fastrepl/hyprnote) fine-tuned for exactly this task. Long context matters because a single pass over the whole transcript beats map-reduce, which loses cross-references, double-counts and fragments action items.

**Non-negotiable whenever stage 2 lands:** every generated bullet carries the `source_line_ids` it came from, and the UI makes them tap-through. That's the anti-hallucination mechanism and what makes notes trustworthy enough to forward. §6's line ids exist for this.

**Prior art worth mining** (all desktop — nothing open source does this on mobile, which is the gap): [Hyprnote/anarlog](https://github.com/fastrepl/hyprnote) (best product reference), [Meetily](https://github.com/Zackriya-Solutions/meetily), [ownscribe](https://github.com/paberr/ownscribe), [OpenWhispr](https://github.com/OpenWhispr/openwhispr). For mobile: [whisper.rn](https://github.com/mybigday/whisper.rn) (MIT, the runtime), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Parakeet + diarization), [react-native-executorch](https://docs.swmansion.com/react-native-executorch/) (fallback runtime), [WhisperBoard](https://github.com/saik0s/whisperboard) (closest mobile prior art — read for recording UX). Verify licences before copying; only whisper.rn's MIT was confirmed.
