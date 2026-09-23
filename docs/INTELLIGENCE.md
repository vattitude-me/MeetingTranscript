# Beyond a recorder app

**Status:** telling speakers apart and the conversation stats are built. They have not
yet been checked on a real recording with several people.

This document answers the question people ask first: *my phone already has a voice
recorder, and it already makes transcripts. Why use this?*

---

## 1. The honest answer

We make three claims. Here they are, from strongest to weakest.

**1. Everything runs on your device.** No audio leaves the phone. There is no account,
and there is no server that could be hacked or forced to hand over your data. Otter,
Fireflies, Fathom and Granola all upload your recordings. So the real difference isn't
a feature. It's whether your recording exists in one place or in two.

This is the claim everything else depends on, and we can back it up, because it comes
from how the app is built. A competitor can't match it without rebuilding their whole
product.

**2. The transcript is better than the one you already have.** Parakeet TDT 0.6B v3
makes noticeably fewer mistakes than the speech recognition built into standard phone
recorder apps. The app also works in two steps: you see rough text during the meeting
and get a more accurate transcript afterwards.

This is true today but won't stay true. Google and Apple improve their own speech
recognition for free, for everyone, and one of them will eventually catch up. Don't
rely on this claim.

**3. It tells you things about the conversation that a plain recording can't.** Who
spoke, for how long, who asked the questions, and who interrupted whom. A recorder app
gives you one long block of text. It doesn't know more than one person was in the room.

This is the claim worth investing in, and it's what the rest of this document covers.
It's also only partly built.

### What we should not claim

**How people felt.** Models that detect emotion in speech are trained on actors. They
do badly on real meetings, and even worse on remote voices that have been squeezed
through a video call and played from a laptop speaker. Saying "she sounded frustrated"
when it isn't true is worse than saying nothing, because someone might act on it. The
app is only useful if people can trust what it says about a meeting. Spending that
trust on a guess is a bad deal.

**Who somebody is.** The app separates voices. It doesn't recognize people. There is
no database of voices. Adding one would turn a private recorder into a tool that
processes biometric data, which brings legal risk in the EU and in Illinois. The app
shows "Speaker 2" until a person types in a name.

---

## 2. What is built

Everything below uses models that are already on the device. It works offline, with no
internet calls and no extra libraries.

### Telling speakers apart

This uses two extra models, 36.6 MB in total, from the same place the speech models
come from:

| Job | Model | Size |
|---|---|---|
| Finds where speech is and where the speaker changes | `sherpa-onnx-pyannote-segmentation-3-0` | 6.96 MB |
| Turns each stretch of speech into a voice fingerprint | `3dspeaker_speech_campplus_sv_en_voxceleb_16k` | 29.6 MB |

After that, a grouping step decides which stretches came from the same voice.
sherpa-onnx's `OfflineSpeakerDiarization` does all three steps, and it was already
included in the library the app uses. We checked that before designing anything
around it.

These models are an **optional download**, separate from the 615 MB speech models.
The first download is still 615 MB, and you can still record before it finishes.

### Conversation stats

These are worked out only from when each speaker was talking, in
[`Dynamics.kt`](../app/src/main/java/me/vattitude/scribe/asr/Dynamics.kt):

- how much of the meeting each person spoke
- how many turns each person took, and their longest turn
- how many questions each person asked, counted from lines that end in `?`
- interruptions: a turn that starts more than 300 ms before the previous speaker
  finished

The 300 ms limit matters. The app only roughly knows where one speaker stops and the
next starts. A 50 ms overlap is an error in that estimate, not someone interrupting.

You can check every one of these numbers by reading the transcript. That was the rule
for choosing them: if a number can't be checked against the lines, it doesn't belong
here.

---

## 3. When it runs

Telling speakers apart needs the recording. By default, the app deletes the recording
once the transcript is done. So the order of steps decides whether this feature can
work at all.

```
record → audio files on disk
       → transcribe (Parakeet, one file at a time, can resume)
       → tell speakers apart  ← here, while the audio is still on disk
       → label each line with its speaker
       → delete the audio (only if that setting is on, and only if everything worked)
```

Doing this in the same step as the transcript (`TranscribeWorker`) is what lets
"delete audio after transcribing" stay on by default. Any other order would mean
either keeping audio for a feature the user may never use, or having meetings that
can never get speaker labels. We didn't accept either.

The downside is that **a meeting can't get speaker labels later** once its audio is
gone. Settings says this clearly instead of hiding it. It's a real limit, and it comes
directly from protecting privacy by default.

If this step fails, the app carries on without it. A meeting with a good transcript
and no speaker labels is fine. A meeting that fails because the speaker step crashed
is not.

### Matching speakers to lines

The app knows when each speaker talked from the sound. It splits lines from the
punctuation. These two never line up exactly. So each line gets the speaker who was
talking for most of that line.

---

## 4. Storage

The database moved to version 2 by adding to it, never removing:

```sql
ALTER TABLE lines ADD COLUMN speaker INTEGER NOT NULL DEFAULT -1;
CREATE TABLE speakers (meeting_id, speaker, name, PRIMARY KEY (meeting_id, speaker));
```

`speaker = -1` means "no speaker labels yet". Every line from before this change has
that value.

The old upgrade code deleted the tables and made new ones. That was fine for a project
on one developer's phone, but not for a public app. A recorded meeting can't be
recorded again, so an update would have destroyed the only copy. **From now on,
database updates only ever add.**

Names are saved per meeting, not across all meetings. The same person in two meetings
is saved twice. A shared list of people would be the first step toward a voice
database, and section 1 explains why we won't build one.

---

## 5. What you see

- **Speaker labels** appear above a line only when the speaker changes. A name on
  every line would bury the words.
- **Six colors**, chosen so they are easy to tell apart on the dark background and for
  people with common types of color blindness. The name is always shown as well, so
  color is never the only clue.
- **Tapping a label** asks who that person is, and puts the name on every line they
  spoke. Only the user names people.
- **Conversation** in the menu shows the stats. At the bottom it explains what the
  numbers don't mean: talking more isn't the same as contributing more, and the speaker
  separation can be wrong.
- **Exports** include speakers in all six formats. JSON has separate `speaker` and
  `speaker_name` fields. The others put the name in front of each line.
- The chatbot-ready export tells the AI that labels mean different voices, not known
  people, and that they can be wrong. Without that, an AI summary will confidently
  claim who said what.

---

## 6. What's left

| | |
|---|---|
| Test on a real recording with several people | Not done yet. Needs a test on a phone |
| Measure how much time this adds to a long meeting | Not done yet. This decides whether it stays automatic |
| ~~Ask how many people spoke before transcribing~~ | ✅ Built. When you stop recording, the app can ask "How many people spoke?", and you can correct the number later without redoing the transcript |
| Merge two speakers the model wrongly split | The most likely problem users will see |
| ~~Mac: record the call and the mic separately~~ | ✅ Built. The Mac app labels lines You or Them based on where the sound came from, with no model needed ([MACOS.md](MACOS.md)) |
| Topics | See below |

### Topics

Not built yet. We decided not to use a large AI model, so we can't do it the easy way.
What works offline: picking out key phrases from the transcript, and using the changes
between speakers to spot where the subject changes.

Done well, this would be really useful. Done badly, it's just a list of random words.
It shouldn't ship until it's more useful than reading the transcript. It's the one
remaining feature where the honest answer might be "we need a model we don't have".

---

## 7. The pitch, in one paragraph

*Your phone's recorder gives you audio and, if you're lucky, one long block of text.
Meeting Transcript gives you a transcript that knows there were four people in the
room: who spoke, for how long, who asked the questions, and who interrupted whom. It
does all of this on the phone, with nothing uploaded and no account. We keep it that
way because a recording of a meeting should exist in only one place.*

That answers the question at the top. The privacy is what no one else can copy. The
speaker labels are what make it worth using.
