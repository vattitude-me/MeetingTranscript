# Meeting Transcript for macOS

**Status:** a working menu bar app in [`mac/`](../mac). It records the call and
your microphone as separate streams and transcribes them live with Parakeet, at
18× real time on an M3 Pro. It builds and runs from source with no Apple
Developer account; see [`mac/README.md`](../mac/README.md). It is not notarized
or distributed yet (phase 6).

The Android app records the room through the phone's microphone. On a Mac the
interesting audio is not in the room — it is inside the machine, in Zoom, Meet
and Teams. That single difference is what this document is about. Everything
downstream of capture already exists and does not need rethinking.

---

## 1. Why macOS and not iOS

PLAN.md assumed iOS. It should not have.

iOS gives an app no access to the audio of another app. There is no API for it
and there is not going to be one; the sandbox is the product. An iOS build
could only ever record the room, which means holding a phone up to a laptop
speaker during a call — worse than the Android app, not better.

macOS has the opposite property. System audio capture is a supported, entitled,
user-consented API. The meetings people actually want transcribed are the ones
they take at a desk, on a Mac, in a browser tab. That is the gap worth filling.

**Decision: the second platform is macOS. iOS is not on the roadmap.**

---

## 2. What is already proven

`mac/Sources/MeetingTranscript/main.swift` is a ~200-line command-line spike
(`swift run CaptureSpike`). It
captures system audio via ScreenCaptureKit and writes the exact on-disk format
the Android transcriber already consumes.

```
cd mac && swift run CaptureSpike 8 ./out
```

Verified on macOS 26.6 / Swift 6.3 / Apple silicon:

| Check | Result |
|---|---|
| Captures system audio | peak input level 0.198, not silence |
| Sample rate after conversion | 16 000 Hz |
| Format | mono, signed 16-bit, little endian, headerless |
| Segment layout | `seg_00000.pcm`, 30 s rolling |
| Byte-level parity with Android | same reader parses both; 8.4 s ⇄ 135 034 samples |

The energy envelope of an 8-second capture tracked the individual sounds played
through the speakers, with silence between them — so the bytes are real audio at
the claimed rate, not a mis-parsed buffer that happens to be the right length.

The two things the spike deliberately gets right, because they are the two
places this is easy to get wrong:

- **Resampling.** ScreenCaptureKit delivers 48 kHz deinterleaved float.
  The models want 16 kHz Int16. The spike uses `AVAudioConverter`. Dropping
  every third sample would also produce a 16 kHz file, and it would alias —
  damage a speech model cannot be told to ignore, and which looks fine until
  the word error rate is inexplicably bad.
- **Proving it is not silence.** The common failure is that capture "succeeds",
  permission looks granted, and every sample is zero. The spike tracks peak
  input level and says so on exit.

### The one manual step

Screen Recording is a TCC permission. The binary inherits the grant of whatever
launched it, so running under a terminal or IDE prompts for *that* app, once,
and requires restarting it. A shipped `.app` prompts for itself and this stops
being a consideration.

---

## 3. Capture: ScreenCaptureKit, not CoreAudio taps

Two APIs can do this. ScreenCaptureKit is the right one.

| | ScreenCaptureKit | CoreAudio process taps |
|---|---|---|
| Minimum macOS | 13 | 14.4 |
| Permission | Screen Recording | Audio Capture |
| Per-app capture | yes, by `SCRunningApplication` | yes, by PID |
| Excludes our own audio | `excludesCurrentProcessAudio` | manual |
| API shape | high level, stable | low level, HAL-adjacent |

ScreenCaptureKit reaches two OS versions further back, is the better-documented
path, and hands us `excludesCurrentProcessAudio`, which matters more than it
sounds: without it, any sound the app itself plays is captured and transcribed.

Its one real cost is the permission name. The app asks for *Screen Recording*
in order to record audio, which reads as alarming for a privacy-first tool.
Mitigation is honesty at the point of asking: explain in the pre-permission
screen that the entitlement is what macOS requires for system audio, that the
video stream is configured to a 2×2 pixel frame that is captured and discarded,
and that nothing leaves the machine. This wording should be reviewed carefully —
it is the single highest-risk moment in the product's trust story.

Revisit CoreAudio taps if and when macOS 14.4 becomes an acceptable floor.
Capture is the only layer that would change.

### Microphone too

A meeting is both sides. System audio is the far end; the near end is the
built-in mic. Capture both, as two streams, and either:

1. **Mix to one channel.** Simplest. What the Android app effectively does. One
   transcript, no speaker attribution.
2. **Keep two streams.** Transcribe separately and interleave by timestamp.
   Yields "you" versus "them" for free — which is the cheapest useful diarization
   available anywhere in this product, and PLAN.md §5's Phase 3 never delivered.

**Recommendation: two streams.** The extra cost is one more `AVAudioEngine` tap
and a merge by `t_start_ms`. It converts a feature that needed a diarization
model into bookkeeping. Ship (1) first if it shortens the path to something
usable, but do not design the storage layer as if only one stream exists.

---

## 4. ASR: the same models, unchanged

sherpa-onnx ships prebuilt macOS arm64 binaries and has Swift bindings, from the
same upstream releases the Android app already pulls (`k2-fsa/sherpa-onnx`,
tag `asr-models`). The two models are byte-identical across platforms:

| | id | size |
|---|---|---|
| Live preview | `streaming-zipformer-en-20M-int8` | 128 MB |
| Accurate pass | `parakeet-tdt-0.6b-v3-int8` | 487 MB |

Expect the accurate pass to run **substantially faster than real time** on
Apple silicon — the phone is the constrained device here, not the laptop. If M-series
throughput makes the two-pass split unnecessary, collapse it: run Parakeet on a
short rolling window and drop the streaming model entirely. That would remove
128 MB of download and an entire engine.

**Do not assume this.** Measure it before designing around it. The whole reason
the Android app has two passes is that Parakeet cannot stream — a rolling-window
workaround has its own accuracy cost at the boundaries, and it needs to be
quantified rather than hoped for.

**Measured (M3 Pro, sherpa-onnx 1.13.8 static, int8):** 64 s of speech in 3.5 s,
so **18× real time**, with the model loading in 0.7 s. Throughput by thread count:
2 threads 10×, 4 threads 16×, 5 threads 18×, 8 threads 14×. It peaks at the
number of performance cores, because the efficiency cores slow each step down.
The app uses `hw.perflevel0.physicalcpu`. CoreML is not an option with the
prebuilt libraries: sherpa-onnx's macOS build logs "CoreML is for Apple only
since onnxruntime>=1.15" and falls back to CPU.

So the streaming model is dropped. Instead of a fixed rolling window, Silero
VAD cuts each stream into utterances and Parakeet decodes each one as it ends.
That removes the boundary problem, because cuts fall in silence, and it
removes the 128 MB model. Lines appear a second or two after someone stops
speaking. A monologue is cut at 20 s, so lines keep coming during long turns.

---

## 5. Storage and the shared format

The Mac app is a peer, not a client. No sync server, no account — that is the
product, not an unfinished part of it.

What the two platforms share is **the file format**, and the Android app already
has both halves of it:

- `scribe.transcript.v1` — a single meeting's transcript, from `Exporters.json`
- `scribe.backup.v1` — the whole library as a zip, from `Backup.kt`

`scribe.backup.v1` is the interchange format. It is already a zip containing a
manifest plus `audio/<id>/seg_NNNNN.pcm`, its import path is already additive
and non-destructive, and it already round-trips on Android (verified: export 1
meeting → restore → 2 meetings, audio byte-identical).

So "move my meetings to my Mac" needs no new format and no new protocol. Export
on the phone, AirDrop the zip, import on the Mac. **The Mac app must read and
write `scribe.backup.v1` unchanged.** If a field needs adding, it is added on
both platforms in the same change, or the version is bumped.

A local SQLite store with the same two tables (`meetings`, `lines`) keeps the
two implementations close enough to reason about together. The schema is small
enough that sharing code across Kotlin and Swift would cost more than it saves.

---

## 6. Shape of the app

A menu-bar app, not a window-first one. Recording a meeting is something you
start and then ignore; a Dock icon and a window are ceremony around a toggle.

- **Menu bar:** record/stop, elapsed time, a level meter to prove it is hearing
  something, and recent meetings.
- **Main window:** the library — search, read, rename, export. Opened
  deliberately, except on the very first launch: an app launched from Finder
  that shows only a small menu bar icon looks like it failed to open.
- **During a call:** the transcript itself, line by line. §4 removed the need for a
  separate preview.

Everything the Android app learned about first-run applies: recording works
before the models finish downloading, audio is kept, and it transcribes when
they land. Do not gate recording on a 490 MB download.

---

## 7. Distribution

Direct download, notarized, outside the App Store.

The App Store is a poor fit and possibly not a fit at all: a 490 MB model
download on first launch, a Screen Recording entitlement that needs a paragraph
of explanation, and no in-app purchase to justify review friction. Direct
distribution also matches the privacy claim — there is no account, so there is
nothing to sign in to.

Required for distributing to other people: Developer ID signing, hardened
runtime, notarization, and a signed update path (Sparkle). Unsigned builds of a
tool that asks for Screen Recording will be refused by users, and rightly so.

None of that is needed to run it yourself. `mac/scripts/build-app.sh` builds and
signs the `.app` locally, either ad-hoc or with a free self-signed certificate
from `make-signing-identity.sh`. Gatekeeper doesn't check an app you built on
your own Mac. The self-signed certificate matters only because TCC ties
Microphone and Screen Recording grants to the signature's designated
requirement. An ad-hoc signature's requirement is its cdhash, which changes
with every build. A certificate's requirement is `identifier
"me.vattitude.scribe.mac" and certificate leaf = H"…"`, which stays the same
across builds.

---

## 8. Plan

| Phase | Outcome | State |
|---|---|---|
| 0 | System audio → 16 kHz PCM on disk | ✅ done, `mac/` |
| 1 | + microphone as a second stream; mixed and separate | ✅ separate at rest, aligned by host time |
| 2 | sherpa-onnx Swift bindings; Parakeet on a captured file | ✅ C API via a module map; 18× real time |
| 3 | SQLite store, `scribe.backup.v1` import/export | ◐ store done; backup import/export not yet |
| 4 | Menu-bar UI, level meter, library window | ✅ plus playback, find, speaker names, exports |
| 5 | Live preview, or its removal per §4 | ✅ streaming model removed; VAD + Parakeet live |
| 6 | Signing, notarization, Sparkle | ◐ free local signing; notarization needs a paid account |

Phase 2 is the one with real unknowns — Swift bindings and Apple-silicon
throughput. Do it before phases 3–6, because its answer decides whether the
two-pass architecture survives the port.

---

## 9. Open questions

1. ~~**Does Parakeet run fast enough on Apple silicon to drop the streaming
   model?**~~ Yes. See §4.
2. ~~**Mixed or separate streams at rest?**~~ Separate:
   `audio/<id>/mic/seg_NNNNN.pcm` and `audio/<id>/system/…`, each padded to
   the same timeline. "You" and "Them" come from which stream a line was
   heard on, not from voice clustering. When you use speakers instead of
   headphones, the mic also picks up the call. A mic line that repeats a
   system line with the same words at the same moment is dropped as echo.
   The setting is on by default and can be turned off.
3. **How is the Screen Recording prompt worded?** The highest-risk sentence in
   a privacy-first product. macOS writes the system prompt itself. The app's
   own card before it says why ScreenCaptureKit is the only way to hear the
   call, that the smallest allowed frame (2×2 pixels) is thrown away, and that
   nothing leaves the Mac. That wording still needs a pass by someone who
   isn't a developer.
4. **Does a meeting recorded on the Mac need to open on the phone?**
   `scribe.backup.v1` already permits it in both directions. Worth confirming
   anyone wants it before building UI for it.
