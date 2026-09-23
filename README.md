<p align="center">
  <img src="docs/img/logo.png" width="128" alt="Meeting Transcript">
</p>

<h1 align="center">Meeting Transcript</h1>

<p align="center">
  Record a meeting on your Android phone or your Mac and get a transcript with timestamps.<br>
  Everything runs on your device. Your audio never leaves it. No account, no subscription.
</p>

---

## What it does

You are on a call on your laptop. You put your phone next to it and tap one button on
your home screen. The phone records the room. When you tap stop, it turns the recording
into a transcript. Each line has a timestamp, and you can share any line on its own.

On a Mac you don't need the phone. The app sits in the menu bar and records the call
directly (whatever Zoom, Meet or Teams is playing) along with your microphone. It writes
the transcript while the meeting is still going.

There is no account and nothing is uploaded. The app only goes online once, to download
the speech model.

**Why not use the recorder app that came with your phone?** That app gives you one long
block of text. It doesn't know that four people were talking. This app tells the voices
apart, so the transcript shows who said what. The **Conversation** view shows how long
each person spoke, who asked the questions, and who interrupted whom. All of this happens
on the phone. We keep it that way because a recording of a meeting should exist in only
one place. [docs/INTELLIGENCE.md](docs/INTELLIGENCE.md) explains this in more detail,
including what the app can't do.

**What this app does:** turn audio into text.
**What it leaves to you:** summaries and action items. Export the transcript and give it
to any AI tool you like. [docs/PLAN.md](docs/PLAN.md) explains why.

## Install

| | Android | Mac |
|---|---|---|
| How to get it | Download the APK from [Releases](../../releases) | Build it yourself for free ([steps below](#on-a-mac)) |
| You need | A 64-bit (arm64) phone with Android 10 or newer | A Mac with Apple silicon and macOS 14 or newer |
| What it records | The room, through the phone's microphone | The call's audio and your microphone, kept separate |
| Who said what | Tells voices apart (optional, still in testing) | Labels each line **You** or **Them** |
| When you get the transcript | After the meeting, with a rough live preview during it | Live, during the meeting |

### Android

Download the APK from [Releases](../../releases) and install it on your phone. It needs
a 64-bit (arm64) phone running Android 10 or newer. It was built and tested on a Pixel 9.

The first time you open it:

1. Tap **Download**. This downloads about 615 MB, one time only. There are two models:
   a small one for the live preview and a large, accurate one for the final transcript.
   The download waits for Wi-Fi unless you choose to use mobile data. It keeps going if
   you leave the app, and picks up where it left off if your connection drops.
2. Tap **Record**. The app asks for microphone access at this point, not as soon as it
   opens.
3. Once the download is done, the app offers to add a record button to your home screen.
   You can also do this later from Settings → **Add the home-screen widget**.

You can start recording before the download finishes. The recording is saved, and the
app transcribes it once the model is ready.

### On a Mac

You build the Mac app yourself. It's free and takes a couple of minutes. You need a Mac
with Apple silicon, macOS 14 or newer, and Apple's free Command Line Tools. To install
those, run `xcode-select --install` in Terminal. You don't need Xcode or a paid Apple
Developer account.

```bash
git clone https://github.com/vattitude-me/MeetingTranscript.git
cd MeetingTranscript/mac
scripts/build-app.sh --install          # → ~/Applications/Meeting Transcript.app
open ~/Applications/"Meeting Transcript.app"
```

The first time you open it:

1. Click the waveform icon in the menu bar, then click **Download (490 MB)**. This
   downloads the speech model, one time only.
2. Under **Before your first meeting**, click **Allow** for Microphone and for Screen
   Recording. macOS needs Screen Recording access before any app can hear another app's
   audio. This app only keeps the sound. It never saves the screen. After you allow it,
   quit the app and open it again.
3. When your call starts, click **Record meeting** in the menu bar or in the main window
   (or press ⇧⌘R). Lines appear as people speak, labelled **You** and **Them**.

If you plan to rebuild the app often, run `scripts/make-signing-identity.sh` once first.
That stops macOS from asking for permission again after every rebuild.
[mac/README.md](mac/README.md) has the details, the command-line tool, and speed results.

## Using it

This section covers the Android app. For the Mac app, see [mac/README.md](mac/README.md).

**Recording**

- **Tap the home-screen button or Record** to start. The recording screen shows how long
  you've been recording, a sound level meter, and a rough live preview of the transcript.
  While it records, the button on the main screen turns magenta and shows the time.
- **Mark** saves a bookmark at the current moment, for example when a decision is made.
  The bookmark shows up in the transcript, and you can play the audio from just before it.
- **Stop** asks you to name the meeting (you can skip this). The final transcript then
  starts on its own.
- If your phone is low on storage, the app warns you before you start. If storage runs
  out while recording, it stops and saves what it has. If the app is closed in the middle
  of a recording, the audio it already saved shows up as a meeting the next time you
  open the app.

**Reading**

- Open a meeting to read its transcript. A banner at the top tells you what's happening:
  transcribing (with progress), waiting its turn, waiting for the download, paused, or
  failed. If it failed, tap **Try again**. Opening a meeting never starts anything on its
  own.
- **Find** (the magnifying glass) searches the transcript, highlights every match, and
  lets you jump between them. If you search from the main list, the search carries over
  when you open a meeting.
- **Tap a line** to copy it, share it, or **play from here**. As long as the recording
  is still saved, you can hear exactly what was said.
- **Copy for chatbot** copies the transcript along with instructions for an AI tool
  (summarize it and point to the lines it used). Paste it into any chatbot.
- Share the whole transcript as a text file, or export it as Markdown, JSON, or
  subtitles (`.srt` or `.vtt`).
- **Speaker labels** appear when a different person starts talking. You need to turn on
  **Identify speakers** in Settings first. It's an extra 37 MB download and also works
  offline. Tap a label to give that person a name. The name appears on every line they
  spoke and in every export. **Conversation** in the menu shows how long each person
  talked.

**Managing**

- **Swipe a meeting** to delete it. You can tap **Undo**. Press and hold a meeting to
  rename or delete it. If you delete a meeting from inside it, the app first tells you
  what will be deleted (how many minutes of audio, how many megabytes, how many lines).
- **Settings** (the gear icon, top right) lets you back up your whole library to one
  file, restore a backup, choose whether to delete recordings once they've been
  transcribed, and see which version you're running.

The transcript you keep always comes from the **accurate** model, which runs **after**
the meeting ends. This keeps your phone from heating up while recording, gives the
model the time it needs, and means a problem with transcription can never lose your
audio. The live preview during recording is only there so you can check that the phone
can hear the room while you still have time to move it. It isn't saved.

### Backups

Settings → **Export a backup** saves your whole library as one `.zip` file. It includes
every meeting and transcript, and you can choose to include the recordings too. The app
asks because transcripts alone are tiny, while recordings can take hundreds of megabytes.

**Restore a backup** loads a backup file. Restoring only adds meetings. It never replaces
or deletes anything already on your phone, so choosing the wrong file can't hurt. You can
also use a backup to move your meetings to a new phone. The Mac app can't read these
backups yet, but that is planned ([docs/MACOS.md](docs/MACOS.md)).

There is no cloud backup, because the app doesn't use the cloud. If you want a copy, you
make one.

### Speakers

Settings → **Identify speakers** downloads two more small models (37 MB, one time,
works offline). After that, every new recording is split into separate voices while it
is transcribed.

It tells *voices* apart. It doesn't know *who* anyone is. There is no voice database,
and it never identifies anybody. You see "Speaker 1" and "Speaker 2" until you type in
names, and those names only apply to that one meeting. It can also get things wrong,
which is why the Conversation view says so, and why you can check every number in it
against the transcript.

This needs the recording, so it runs **before** the recording is deleted, at the same
time as the transcript is made. That means meetings transcribed before you turned it on
can't get speaker labels, because their audio is already gone.

## How it works

This section is for developers. Here is how the Android app is put together:

```
widget tap
   └─ RecordTrampolineActivity        (needed for Android 14+ background start rules)
        └─ RecorderService            foreground service, type=microphone
             └─ 16 kHz mono PCM16, saved as 30-second files on disk
                  │                              │
                  │ (reads from disk)            │ (same audio, separate thread)
                  ▼                              ▼
             TranscribeWorker              LiveTranscriber
             WorkManager, can resume       StreamingEngine → 20M zipformer int8
             file by file                  preview on screen while recording; skips
                  │                        audio if it falls behind, never blocks
                  ▼
             ParakeetEngine   sherpa-onnx → Parakeet TDT 0.6B v3 int8
                  └─ SQLite: removes the preview lines, saves the final ones
```

| Part | What we use | Why |
|---|---|---|
| Accurate speech model | [Parakeet TDT 0.6B v3](https://github.com/k2-fsa/sherpa-onnx) int8 | Fewer mistakes than Whisper large-v3 (6.34% vs 7.44% word error rate) and much smaller |
| Live preview model | Streaming zipformer, 20M parameters, int8 | Small and fast enough to run while recording. Only for the preview |
| Runtime | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) prebuilt AAR | Includes ONNX Runtime and a tested decoder, so you don't need the Android NDK to build |
| Audio | 16 kHz mono PCM16, 30-second files | About 115 MB per hour. Each file is one step that can be resumed |
| Storage | Plain `SQLiteOpenHelper` | Only two tables, so a database library isn't worth it |
| UI | Views + ViewBinding, Material 3 dark theme | Smaller and faster to build |

The Mac app is simpler. Apple silicon is fast enough that it doesn't need a separate
preview model. It detects when each person stops talking, and Parakeet transcribes that
stretch right away, at about 18 times faster than real time. The details and
measurements are in [docs/MACOS.md](docs/MACOS.md).

On Android, recording and transcription only talk to each other **through files on
disk**. If transcription runs out of memory, the audio files are still there, and the
job continues from the last file it finished. It never starts an hour of work over. The
live preview reads the same audio without changing it. If the preview falls behind and
skips some audio, the saved copy and the final transcript are not affected.

## Building it yourself

For the Mac app, see [mac/README.md](mac/README.md). It's one script, with no Xcode and
no Apple Developer account.

To build the Android app you need JDK 21 and the Android SDK (platform 35 and
build-tools 35).

```bash
git clone https://github.com/vattitude-me/MeetingTranscript.git
cd MeetingTranscript
./scripts/setup.sh                                   # downloads the 48 MB sherpa-onnx AAR
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleDebug
```

The APK is saved in `app/build/outputs/apk/debug/`.

### Signing a release build

`assembleRelease` looks for a file called `keystore.properties` in the top folder of the
repo. Git ignores this file, so it is never committed.

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=…
keyAlias=scribe
keyPassword=…
```

Without that file, the release build still finishes, but the APK is unsigned and won't
install. Always use the same keystore for every release. If it changes, Android refuses
the update and people have to uninstall the old version first.

## Known limits

- **Headphones don't work with the Android app.** If you listen through headphones, the
  phone can only hear your side of the call. No phone app can fix that. Use your laptop's
  speakers, or use the [Mac app](mac), which records the call's audio directly.
- **The number of speakers is a guess, and often wrong.** On its own, the app decides how
  many voices it heard, and it often gets this wrong. We tested many settings on
  recordings with one, two and three people, and no single setting got all of them right.
  One person talking for nine minutes can end up split into several "speakers". When we
  told it the right number, it was right every time. So if the count looks wrong, tap the
  line under the meeting title and enter how many people spoke. The app separates the
  voices again without redoing the transcript.
- **We haven't measured accuracy on real meetings** recorded on a Pixel 9. The accuracy
  numbers above come from the model's authors, not from us.
- **There is no iPhone app.** The original plan in [docs/PLAN.md](docs/PLAN.md) included
  one, but iPhones don't let apps record other apps' audio. We built the Mac app instead:
  [mac/](mac) records the call and your microphone and transcribes them live. You can
  build it for free without an Apple Developer account. It can't read or write Android
  backups yet. See [docs/MACOS.md](docs/MACOS.md).
- **On Android, recording and transcription run in the same process.** The plan was to
  run transcription separately, so that if it ran out of memory it couldn't stop a
  recording. That hasn't been done yet.

## Privacy

On Android, your recordings and transcripts are stored in the app's private storage.
Android's automatic backup is turned off for this app, so nothing is copied to Google.
On the Mac, everything is stored in one folder:
`~/Library/Application Support/Meeting Transcript`. On both, the only time the app uses
the internet is to download the models, once, from GitHub.

The rules about recording other people depend on where you live and where you work.
Following them is up to you.

## Contributing

Bug reports, results from real meetings, and pull requests are all welcome. Please open
an [issue](../../issues). The documents in [docs/](docs) explain why the app works the
way it does:
- [docs/PLAN.md](docs/PLAN.md) is the original plan.
- [docs/MACOS.md](docs/MACOS.md) covers the Mac app.
- [docs/INTELLIGENCE.md](docs/INTELLIGENCE.md) covers the speaker and conversation
  features.

## License

MIT. See [LICENSE](LICENSE).
