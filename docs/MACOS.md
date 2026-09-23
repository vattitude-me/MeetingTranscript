# Meeting Transcript for macOS

**Status:** a working menu bar app in [`mac/`](../mac). It records the call and your
microphone separately and transcribes them live with Parakeet, 18 times faster than
real time on an M3 Pro. You can build and run it from source without an Apple Developer
account (see [`mac/README.md`](../mac/README.md)). It isn't notarized by Apple or
available as a download yet (phase 6).

The Android app records the room through the phone's microphone. On a Mac, the audio
you care about isn't in the room. It's inside the computer, in Zoom, Meet and Teams.
This document is about that one difference. Everything after recording already exists
and doesn't need to change.

---

## 1. Why Mac and not iPhone

The original plan (PLAN.md) assumed an iPhone app. That was a mistake.

iPhones don't let an app hear another app's audio. There is no way to do it, and there
won't be one, because keeping apps separate is a core part of how iPhones work. An
iPhone app could only record the room. You'd have to hold your phone up to a laptop
speaker during a call, which is worse than the Android app, not better.

Macs are the opposite. Recording the computer's audio is officially supported, as long
as the user gives permission. And the meetings people most want transcribed are the
ones they take at a desk, on a Mac, in a browser tab. That's the gap worth filling.

**Decision: the second platform is the Mac. An iPhone app is not planned.**

---

## 2. What we proved first

`mac/Sources/MeetingTranscript/main.swift` is a small command-line test program, about
200 lines (`swift run CaptureSpike`). It records the Mac's audio using ScreenCaptureKit
and saves it in exactly the same file format the Android app already reads.

```
cd mac && swift run CaptureSpike 8 ./out
```

Tested on macOS 26.6, Swift 6.3, Apple silicon:

| Check | Result |
|---|---|
| Records the Mac's audio | Peak level 0.198, so it's real sound, not silence |
| Sample rate after conversion | 16,000 Hz |
| Format | Mono, 16-bit, no header |
| How files are split | `seg_00000.pcm`, a new file every 30 seconds |
| Same as Android, byte for byte | The same code reads both. 8.4 s = 135,034 samples |

The volume of an 8-second recording rose and fell with each sound we played through
the speakers, with silence in between. So the files hold real audio at the right
speed, not garbage that just happens to be the right size.

The test program gets two things right on purpose, because they're the two easiest
things to get wrong:

- **Converting the audio.** ScreenCaptureKit gives 48 kHz audio. The models need
  16 kHz. The program uses Apple's `AVAudioConverter`. Just keeping every third sample
  would also give a 16 kHz file, but the sound would be distorted in a way the speech
  model can't ignore. It would look fine until the transcripts came out mysteriously
  bad.
- **Checking it isn't silence.** A common failure is that recording seems to work,
  permission seems granted, and every sample is zero. The program tracks the loudest
  sound it heard and reports it when it finishes.

### The one manual step

macOS needs Screen Recording permission. A command-line program gets its permission
from whatever started it. So if you run it from Terminal or a code editor, macOS asks
permission for *that* app, once, and you have to restart it. A finished `.app` asks for
itself, so this problem goes away.

---

## 3. Recording: ScreenCaptureKit, not CoreAudio taps

There are two ways to record a Mac's audio. ScreenCaptureKit is the better choice.

| | ScreenCaptureKit | CoreAudio process taps |
|---|---|---|
| Oldest macOS it works on | 13 | 14.4 |
| Permission it needs | Screen Recording | Audio Capture |
| Can record just one app | Yes | Yes |
| Can leave out the app's own sound | Yes, built in | Have to do it yourself |
| How easy it is | Simpler and stable | More complicated, closer to the hardware |

ScreenCaptureKit works on older versions of macOS, is better documented, and can
leave out the app's own sound. That matters more than it seems. Without it, any sound
the app plays would be recorded and transcribed too.

Its one real downside is the permission name. The app asks for *Screen Recording* when
it only wants audio. That sounds alarming for an app that's all about privacy. The fix
is to be honest when asking. Before macOS shows its request, the app explains that
macOS requires this permission to hear the call, that the picture is set to 2×2 pixels
and thrown away, and that nothing leaves the Mac. This wording needs careful review.
It's the moment where users are most likely to stop trusting the app.

CoreAudio taps are worth another look once it's fine to require macOS 14.4 or newer.
Only the recording part would change.

### The microphone too

A meeting has two sides. The Mac's audio is the other people. Your side comes through
the microphone. The app records both as separate streams. There were two options:

1. **Mix them into one.** Simplest, and roughly what the Android app does. One
   transcript, no way to tell who spoke.
2. **Keep them separate.** Transcribe each one, then put the lines in time order.
   This tells you "you" versus "them" for free. It's the cheapest way to tell speakers
   apart anywhere in this project, and the original plan's Phase 3 never delivered it.

**We chose separate streams.** It costs one more audio input and a sort by time. It
turns something that needed an AI model into simple bookkeeping.

---

## 4. Speech recognition: the same models

sherpa-onnx provides ready-made Mac libraries, from the same place the Android app
gets its models (`k2-fsa/sherpa-onnx`, tag `asr-models`). The model files are exactly
the same on both platforms:

| | Name | Size |
|---|---|---|
| Live preview | `streaming-zipformer-en-20M-int8` | 128 MB |
| Accurate transcript | `parakeet-tdt-0.6b-v3-int8` | 487 MB |

We expected the accurate model to run much faster than real time on Apple silicon.
The phone is the slow device here, not the laptop. If it was fast enough, we could
drop the two-step approach: run Parakeet on short stretches of audio as they come in
and remove the preview model completely. That would cut 128 MB from the download and
a whole engine from the code.

We didn't assume this. We measured it first. The Android app uses two steps because
Parakeet can't process audio as a continuous stream. Cutting audio into short
stretches can hurt accuracy where the cuts fall, so that cost had to be measured, not
guessed.

**Results (M3 Pro, sherpa-onnx 1.13.8, int8 model):** 64 seconds of speech took 3.5
seconds, so **18 times faster than real time**. The model loads in 0.7 seconds. Speed
by number of threads:

| Threads | Speed |
|---|---|
| 2 | 10× |
| 4 | 16× |
| 5 | 18× |
| 8 | 14× |

It's fastest when it uses exactly the number of performance cores. Adding the
efficiency cores slows each step down. The app reads the number of performance cores
from `hw.perflevel0.physicalcpu`. CoreML (Apple's AI engine) isn't an option with the
ready-made libraries. sherpa-onnx's Mac version says "CoreML is for Apple only since
onnxruntime>=1.15" and uses the processor instead.

So we dropped the preview model. Instead of cutting audio at fixed points, a voice
detector (Silero VAD) cuts each stream wherever someone stops talking, and Parakeet
transcribes each piece as soon as it ends. This avoids the accuracy problem, because
the cuts land in silence, and it removes the 128 MB model. Lines appear a second or
two after someone stops speaking. If someone talks for a long time, their speech is
cut every 20 seconds, so lines keep appearing.

---

## 5. Storage and a shared file format

The Mac app works on its own. It isn't a client of some server. There's no sync server
and no account, and that's the point, not something unfinished.

What the two apps share is **the file format**. The Android app already has both parts:

- `scribe.transcript.v1`: one meeting's transcript, from `Exporters.json`
- `scribe.backup.v1`: the whole library as a zip file, from `Backup.kt`

`scribe.backup.v1` is how the apps would exchange meetings. It's already a zip file
with a list of meetings plus the audio files (`audio/<id>/seg_NNNNN.pcm`). Restoring
it only adds meetings and never deletes anything. It already works on Android: we
exported 1 meeting, restored it, got 2 meetings, and the audio was identical.

So moving meetings to a Mac doesn't need a new format or anything new at all. Export
on the phone, AirDrop the zip, import on the Mac. **The Mac app must read and write
`scribe.backup.v1` exactly as it is.** If a field needs adding, it's added to both
apps in the same change, or the version number goes up.

The Mac app uses a local SQLite database with the same two tables (`meetings` and
`lines`), so the two apps stay similar. The database is small enough that sharing
code between Kotlin and Swift would cost more than it saves.

---

## 6. How the app is laid out

The app lives in the menu bar instead of opening as a window. You start recording a
meeting and then forget about it. A Dock icon and a window would be a lot of fuss for
an on/off switch.

- **Menu bar:** record and stop, time elapsed, a sound level meter to show it's
  hearing something, and recent meetings.
- **Main window:** all your meetings. Search, read, rename, export. It only opens when
  you ask, except the very first time. An app opened from Finder that only shows a
  tiny menu bar icon looks like it failed to start.
- **During a call:** the transcript itself, line by line. Section 4 removed the need
  for a separate preview.

Everything the Android app learned about first launch applies here too. You can record
before the models finish downloading. The audio is kept and transcribed once they
arrive. Don't make people wait for a 490 MB download before they can record.

---

## 7. Distribution

The plan is a direct download from the web, notarized by Apple, not through the App
Store.

The App Store is a poor fit and may not work at all. The app downloads 490 MB on first
launch, needs Screen Recording permission that takes a paragraph to explain, and has
no in-app purchases to make the review process worth it. A direct download also fits
the privacy promise. There's no account, so there's nothing to sign in to.

To give the app to other people, it needs: signing with a paid Developer ID, Apple's
hardened runtime, notarization, and a safe way to update (Sparkle). People will refuse
an unsigned app that asks for Screen Recording, and they'd be right to.

None of that is needed to run it yourself. `mac/scripts/build-app.sh` builds and signs
the app on your own Mac. It signs either ad-hoc or with a free certificate created by
`make-signing-identity.sh`. macOS doesn't check apps you built on your own Mac.

The free certificate is only useful because macOS remembers Microphone and Screen
Recording permission by the app's signature. An ad-hoc signature is based on the app's
exact contents (its cdhash), so it changes with every build. A certificate signature is
based on the app's ID and the certificate (`identifier "me.vattitude.scribe.mac" and
certificate leaf = H"…"`), so it stays the same across builds.

---

## 8. Plan

| Phase | Goal | Status |
|---|---|---|
| 0 | Mac audio → 16 kHz audio files on disk | ✅ Done, in `mac/` |
| 1 | Add the microphone as a second stream | ✅ Saved separately, lined up in time |
| 2 | sherpa-onnx in Swift, Parakeet transcribing a recorded file | ✅ Uses the C library directly. 18× real time |
| 3 | SQLite database, import and export of `scribe.backup.v1` | ◐ Database done. Backup import and export not yet |
| 4 | Menu bar app, level meter, main window | ✅ Plus playback, search, speaker names, exports |
| 5 | Live preview, or remove it (see section 4) | ✅ Preview model removed. Voice detector + Parakeet run live |
| 6 | Signing, notarization, Sparkle updates | ◐ Free local signing works. Notarization needs a paid account |

Phase 2 had the real unknowns: whether sherpa-onnx would work from Swift, and how fast
Apple silicon would be. We did it before phases 3 to 6, because the answer decided
whether the two-step design would carry over to the Mac.

---

## 9. Open questions

1. ~~**Is Parakeet fast enough on Apple silicon to drop the preview model?**~~ Yes.
   See section 4.
2. ~~**Save the two streams mixed or separate?**~~ Separate:
   `audio/<id>/mic/seg_NNNNN.pcm` and `audio/<id>/system/…`, both lined up on the
   same timeline. "You" and "Them" come from which stream a line was heard in, not
   from guessing voices. If you use speakers instead of headphones, the microphone
   also picks up the call. When a microphone line has the same words at the same
   moment as a call line, it's treated as an echo and dropped. This is on by default
   and can be turned off.
3. **How should the Screen Recording request be worded?** This is the most important
   sentence in an app built on privacy. macOS writes its own request. Before that, the
   app shows its own card saying why this is the only way to hear the call, that the
   smallest allowed picture (2×2 pixels) is thrown away, and that nothing leaves the
   Mac. That wording still needs a review by someone who isn't a developer.
4. **Should a meeting recorded on the Mac open on the phone?** `scribe.backup.v1`
   already allows it in both directions. We should check that anyone wants this before
   building it.
