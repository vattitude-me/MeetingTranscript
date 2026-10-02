# Strategy: stop competing with the recorder app

**Status:** proposal, 2026-10-02. Nothing in here is built yet. Read it next to
[PLAN.md](PLAN.md) (why things are the way they are) and [MACOS.md](MACOS.md).

## 1. The problem, said plainly

The person who built this app stopped using it as a recorder because the phone's own
recorder is better. That's the right call, and it tells us something: **as a recorder,
this app loses, and it was designed to lose.**

Look at what the code does with audio:

| | Phone's recorder (e.g. Pixel Recorder) | This app on Android today |
|---|---|---|
| Audio quality | Compressed, full-band, tuned for listening | 16 kHz mono raw PCM, tuned for the speech model (`capture/Audio.kt`, `VOICE_RECOGNITION` source) |
| Is the audio kept? | Yes, forever | Deleted 24 hours after transcribing, by default (`store/AudioRetention.kt`, `Settings.deleteAudioAfterTranscribe`) |
| Can you share the audio? | Yes, as a file or a clip | No. There's no WAV/M4A export at all |
| Listening | Waveform, scrub bar, speed, skip silence | "Play from here" on a line, nothing else (`ui/PcmPlayer.kt`) |
| Live transcript | Good, and it's the one you keep | A rough 20M-model preview that's thrown away |
| Who spoke | Speaker labels, on by default | Off by default, because they were wrong too often (`Settings.identifySpeakers`) |
| Summary | Yes, on-device (Gemini Nano on a Pixel 9) | No. "Copy for chatbot" and paste it yourself |
| First use | Open and tap | 615 MB download first |

Every one of those choices was deliberate and well argued (privacy, memory, accuracy).
But together they make an app that's worse at the job people judge it by. No amount
of polish on the Android recorder closes that gap, because Google ships the other
column for free and keeps improving it. [INTELLIGENCE.md](INTELLIGENCE.md) already
says this about transcript quality: "Don't rely on this claim."

**So the strategy is: stop being a recorder. Be the thing that turns your meetings
into notes you actually use, and do the recording only where the built-in recorder
can't.**

## 2. Where this app can win

There are three things the phone's recorder can't do. Everything below is built on
them.

1. **Hear the call on a Mac.** The phone's recorder can only hear the room. Through
   headphones it hears only you. The Mac app records Zoom/Meet/Teams directly, keeps
   *You* and *Them* apart with no guessing, and has the transcript ready seconds after
   the call. This is the strongest thing in the repo and it's the least finished.
2. **Never forget to press record.** A meeting app knows when a meeting starts (the
   calendar says so, and Zoom grabs the microphone). A recorder app doesn't.
3. **Put the notes where your work is.** A recorder keeps transcripts inside its own
   app. Useful notes end up in your notes app, your reminders, your task list, and can
   be searched across months of meetings.

And one thing that's true on both platforms: **nothing leaves the device.** Keep that
promise. It's the reason to choose this over Otter or Granola.

## 3. The plan

Ordered by value for the effort. Each step is useful on its own.

### Step 1: Make the Mac app the main product (1–2 weeks)

The Mac app already does the hard part. What stops it being a daily tool:

- **It doesn't start on its own.** Add *meeting detection*:
  - Watch for another app starting to use the microphone (Core Audio's
    `kAudioDevicePropertyDeviceIsRunningSomewhere` on the default input device,
    together with which app is in front: zoom.us, Teams, a browser on
    `meet.google.com`). When a call starts, show a small notification: **"Zoom is
    using the mic. Record?"** One click. Optionally, "Always record Zoom calls."
  - When the call app lets go of the microphone, offer to stop (or stop after a minute
    of silence on both streams). A recording you forgot to stop is the other common
    failure, and Android already handles it with `checkInMinutes`.
- **Read the calendar (EventKit, read-only).** Name the meeting after the calendar
  event instead of "Untitled meeting", and offer the attendee names when naming
  speakers. No guessing who is who, just a shorter list to pick from.
- **Finish the parts MACOS.md lists as open:** `scribe.backup.v1` import/export (phase
  3), so phone recordings can move to the Mac.
- **A global hotkey to drop a mark** while you're in the call window (⇧⌘M), so you can
  flag "this was a decision" without switching apps.

### Step 2: Notes that leave the app (1 week, both platforms)

The transcript is the raw material, not the result. Make it flow out automatically:

- **Auto-export to a folder.** After each meeting, write a Markdown file (the
  `Exporters.markdown` you already have, plus front matter: date, title, attendees,
  duration) to a folder you pick. Point it at an Obsidian vault, iCloud Drive, or a
  Notes folder, and every meeting shows up in your notes without you doing anything.
  On Android, the same through the Storage Access Framework (a folder in Drive or
  Syncthing).
- **Search across all meetings** on the Mac, the way Android already has it, with
  results that jump to the line and play the audio.
- **Select several lines and share them as a quote.** PLAN.md section 6 lists it as
  not built. It's the most common thing people do with a transcript.

### Step 3: On-device summaries, with sources (2–3 weeks)

PLAN.md kept summaries out because the built-in models couldn't fit a meeting. That
was right in 2025. It's worth checking again, because this is the single feature
people compare against, and the Pixel 9's recorder now has it.

- **Mac, first.** Two options, both local:
  - Apple's Foundation Models framework (macOS 26). Small context window, so work in
    pieces: pull out decisions, action items and open questions from each ~5-minute
    chunk, each one tagged with its line numbers, then merge. Extracting items in
    pieces works much better than summarizing in pieces.
  - A small open model run locally (for example a Qwen 3B–4B class model through
    llama.cpp or MLX). An M-series Mac runs this comfortably and can see a whole
    one-hour meeting at once. Optional download, like the speaker models.
- **Every item links to its lines.** This is already the rule in PLAN.md's appendix
  (`source_line_ids`), and the line numbers exist for exactly this. Tap an action item,
  land on the line, hear the audio. That's what makes a summary trustworthy, and it's
  something no recorder app does.
- **Action items go to Reminders** (Mac, EventKit) or to a shared Android intent, with
  a link back to the meeting.
- **Android:** ML Kit GenAI on the Pixel 9 (Gemini Nano), same chunk-and-extract
  approach. Do this after the Mac version proves the prompts.

Keep "Copy for chatbot". Some people will always prefer their own AI tool.

### Step 4: Android becomes a companion, not a rival recorder (1 week)

Stop trying to out-record Google. Use its recordings instead:

- **"Share to Meeting Transcript."** Add a share target for audio files. Record with the
  phone's own recorder (better audio, kept forever), then share the file here to get
  Parakeet's transcript, speaker counts, Conversation stats, marks, exports, the notes
  folder and, later, summaries. Decoding is `MediaExtractor` + `MediaCodec` to 16 kHz
  mono, written as the same `seg_NNNNN.pcm` files, so everything after that already
  works. The Mac CLI already transcribes `.m4a` files, so this is the same idea.
- **Keep the built-in recording, but fix what makes it feel worse:**
  - Save a compressed copy (AAC or Opus at ~32 kbps, about 15 MB an hour, as PLAN.md
    section 5.4 already suggested) and keep it by default. Delete the raw PCM after
    transcribing instead of all the audio. You keep the privacy argument for the
    big, raw files and lose the "my recording vanished" surprise.
  - Let people share the audio and export it as M4A.
  - A proper player: scrub bar, 1×/1.5×/2× speed, skip silence.
  - A Quick Settings tile (PLAN.md section 8 promised one) so recording starts from
    the lock screen without the home-screen widget.
  - Warn when headphones or a Bluetooth headset are connected (`AudioManager`
    device callback). It's still the top open risk in PLAN.md section 13.
- **Don't try to capture call audio on Android.** `AudioPlaybackCapture` exists, but
  call apps play voice as `USAGE_VOICE_COMMUNICATION`, which Android won't let other
  apps capture. It'll look like it works and record silence. The Mac is the answer
  for calls.

### Step 5: Make it installable by someone else (when wanted)

Only if the goal grows beyond personal use: notarized Mac download with Sparkle
updates (MACOS.md phase 7, needs the paid developer account), and a Play Store or
F-Droid listing for Android. Until then, building from source is fine.

## 4. What not to do

- **Don't build a cloud sync or an account.** Move meetings between devices with the
  backup zip (AirDrop, a shared folder). It keeps the one promise no competitor can
  match.
- **Don't add emotion or "sentiment."** INTELLIGENCE.md section 1 explains why, and it
  still holds.
- **Don't turn speaker labels back on by default on Android** until a better model
  shows up. A wrong name reads as fact. The Mac's You/Them split doesn't have this
  problem.
- **Don't keep polishing the Android live preview.** The phone's own recorder will
  always be ahead there.

## 5. How to tell if it worked

The simplest test, for the one person using it today: **after a month, are meeting
notes showing up in your notes folder without you exporting anything, and did you
record every call on the Mac without remembering to press a button?** If yes, it's
doing something the recorder app can't. If not, it's still a recorder.

## 6. Suggested order

| Order | What | Platform | Effort | Why first |
|---|---|---|---|---|
| 1 | Meeting detection + calendar titles | Mac | ~1 week | Removes "I forgot to record" |
| 2 | Auto-export Markdown to a folder | Mac, then Android | ~3 days | Notes show up where you work |
| 3 | Share audio into the app | Android | ~3 days | Use the better recorder, keep the better transcript |
| 4 | Keep compressed audio, export it, better player | Android | ~1 week | Removes the "worse than the recorder" feeling |
| 5 | Action items and decisions, with line links | Mac | ~2–3 weeks | The feature people compare against |
| 6 | Backup import/export on Mac | Mac | ~3 days | Phone and Mac share one library |
| 7 | Quick Settings tile, headset warning | Android | ~2 days | Small fixes from PLAN.md's open list |
| 8 | On-device summaries | Android | ~2 weeks | After the Mac version proves the prompts |
