# Beyond a recorder app

**Status:** speaker attribution and conversation dynamics implemented, not yet
verified on a real multi-speaker recording.

The question this document exists to answer is the one that gets asked first:
*my phone already has a voice recorder, and it already does transcripts. Why
this?*

---

## 1. The honest version of the answer

Three claims, in descending order of how well they hold up.

**1. It runs entirely on the device.** No audio leaves the phone, there is no
account, and there is no server to subpoena or breach. Otter, Fireflies, Fathom
and Granola all upload. This is not a feature comparison — it is the difference
between a recording existing in one place and existing in two.

This claim is load-bearing and it is defensible, because it is structural. A
competitor cannot match it without dismantling their product.

**2. It is better than the transcript you already have.** Parakeet TDT 0.6B v3
is meaningfully more accurate than the on-device recognisers shipped in stock
recorder apps, and the two-pass design gives live text during the meeting and a
better transcript afterwards.

This claim is true today and will erode. Google and Apple ship improvements to
their own recognisers for free, to everyone, and eventually one of them will be
this good. Do not build the pitch on it.

**3. It tells you things about the conversation that a recording cannot.** Who
spoke, for how long, who asked the questions, who talked over whom. A recorder
app gives you a wall of text with no idea that more than one person was in the
room.

This is the claim worth investing in, and it is what this document covers. It is
also the one that is only partly built.

### What we should not claim

**Emotional tone.** Speech-emotion models are trained on acted corpora and
degrade badly on real meeting audio — worse still on remote voices that have
been through a conferencing codec and a laptop speaker. "She sounded frustrated"
being wrong is worse than saying nothing, because somebody might act on it. The
whole product rests on being trustworthy about what happened in a meeting.
Spending that credit on a guess is a bad trade.

**Who somebody is.** Diarization separates voices. It does not recognise people.
There is no enrolled voiceprint database, and adding one would turn a private
recorder into biometric processing, with the legal exposure that carries in the
EU and Illinois. The app produces "Speaker 2" until a human types a name.

---

## 2. What is built

Everything below runs on the models already downloaded, offline, with no API
calls and no new dependencies.

### Speaker attribution

Two additional ONNX models, 36.6 MB in total, from the same upstream releases
the speech models come from:

| Role | Model | Size |
|---|---|---|
| Segmentation | `sherpa-onnx-pyannote-segmentation-3-0` | 6.96 MB |
| Embedding | `3dspeaker_speech_campplus_sv_en_voxceleb_16k` | 29.6 MB |

Segmentation finds speech regions and speaker changes; the embedding model turns
each region into a vector; clustering decides which regions are the same voice.
`sherpa-onnx`'s `OfflineSpeakerDiarization` runs all three, and it is already in
the AAR the app ships — verified against the bytecode and the JNI symbol table
before any of this was designed around.

These are **optional downloads**, separate from the 615 MB speech models. First
run still asks for 615 MB, and recording still works before that finishes.

### Conversation dynamics

Computed from diarization timings alone, in [`Dynamics.kt`](../app/src/main/java/me/vattitude/scribe/asr/Dynamics.kt):

- talk time per speaker, as a share of the meeting
- turn count, and longest uninterrupted stretch
- questions asked, counted from lines ending in `?`
- interruptions — turns starting more than 300 ms before the previous speaker
  finished

The 300 ms floor matters. Diarization boundaries are approximate, and a 50 ms
brush between turns is an artefact, not somebody talking over somebody.

Every one of these is countable and checkable against the transcript. That is
the selection criterion: if a number cannot be verified by reading the lines, it
does not belong in this feature.

---

## 3. Where it runs in the pipeline

Diarization needs the waveform. Retention deletes the waveform. So the order is
not a detail — it decides whether the feature exists at all.

```
record → segments on disk
       → transcribe (Parakeet, per segment, resumable)
       → diarize     ← here, while the audio is still on disk
       → label lines with speakers
       → delete audio (if retention is on, and only on success)
```

Doing it in the same `TranscribeWorker` pass is what lets "delete audio after
transcribing" stay ON by default. Any later placement would mean either keeping
audio around for a feature the user may never open, or having meetings that can
never be diarized. Neither is acceptable.

The cost is that **a meeting cannot be diarized retroactively** once its audio is
gone. This is stated plainly in Settings rather than hidden — it is a real
limitation and a direct consequence of the privacy default.

Diarization is also wrapped in `runCatching`. A meeting with a good transcript
and no speaker labels is a fine outcome; a meeting that fails because the
speaker-labelling step threw is not.

### Mapping turns to lines

Turn boundaries come from acoustics; line boundaries come from punctuation. They
never align. Each line is assigned the speaker holding the greatest share of its
duration — overlap is the only sound basis for the decision.

---

## 4. Storage

Schema version 2, migrated additively:

```sql
ALTER TABLE lines ADD COLUMN speaker INTEGER NOT NULL DEFAULT -1;
CREATE TABLE speakers (meeting_id, speaker, name, PRIMARY KEY (meeting_id, speaker));
```

`speaker = -1` means "not diarized", which every pre-existing line is.

The previous `onUpgrade` dropped and recreated the tables. That was fine for a
project on one developer's phone and unacceptable for one going public: a
meeting somebody recorded is not reproducible, and a version bump would have
destroyed the only copy. **Migrations are additive from here.**

Names live per meeting, not globally. The same person in two meetings is two
rows. A global identity table is the first step towards a voiceprint database,
and §1 says why that door stays shut.

---

## 5. Interface

- **Speaker labels** appear above a line only when the voice changes. Repeating
  a name on every line buries the words under labels.
- **Six colours**, chosen to stay distinguishable against the dark background and
  under the common forms of colour blindness. The name is always present too, so
  colour is never the only cue.
- **Tapping a label** asks who it is, and applies the name to every line that
  voice spoke. Naming is the user's act.
- **Conversation** in the overflow menu shows the dynamics, with a footer saying
  what the numbers are not: talk time is not contribution, and speaker
  separation can be wrong.
- **Exports** carry speakers in all six formats. JSON gets a structured
  `speaker` and `speaker_name`; the rest get a prefix.
- The prompt-ready export tells the model that labels mean distinct voices, not
  known people, and that the separation can be wrong. Otherwise a summarizer
  will happily assert who said what.

---

## 6. What is left

| | |
|---|---|
| Verify on a real multi-speaker recording | not done — needs a device test |
| Measure the time cost on a long meeting | not done, and it decides whether this stays automatic |
| Ask the speaker count before transcribing | `expectedSpeakers` is far more reliable than a threshold |
| Merge two clusters the model split | the most likely user-visible failure |
| macOS: two capture streams | free "you vs them" without any model — see [MACOS.md](MACOS.md) §3 |
| Topics | §7 |

### Topics

Not built, and the scope deliberately excludes an LLM, so it cannot be done the
easy way. What is available offline: keyphrase extraction over the transcript,
plus diarization's turn structure to spot where the subject shifts.

Done well this is genuinely useful. Done badly it is a tag cloud. It should not
ship until it beats reading the transcript, and it is the one remaining feature
where an honest answer might be "this needs a model we do not have".

---

## 7. The positioning, in one paragraph

*Your phone's recorder gives you audio and, if you are lucky, a wall of text.
Scribe gives you a transcript that knows there were four people in the room —
who spoke, for how long, who asked the questions and who talked over whom — and
it does all of it on the phone, with nothing uploaded and no account. Not
because a server would be hard, but because a meeting recording should only ever
exist in one place.*

That is the answer to the question at the top. The privacy claim is what makes
it defensible; the speaker attribution is what makes it worth having.
