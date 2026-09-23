# Meeting Transcript for macOS

Records a call on your Mac and turns it into text while the meeting is still going. It
records two things: the call's audio (whatever Zoom, Meet or Teams plays) and your
microphone. It uses the same speech model as the Android app. Nothing leaves your Mac,
and there is no account.

The app lives in the menu bar. Click **Record** when the call starts. Lines appear as
people speak, labelled **You** (your microphone) and **Them** (the call). The main
window keeps all your meetings. There you can search them, read them, play the audio
from any line, name the speakers, and export a meeting as plain text, Markdown, JSON,
or subtitles (SRT or WebVTT). You can also copy it with instructions for an AI chatbot,
ready to paste.

## Build it for free (no Apple Developer account)

You need a Mac with Apple silicon, macOS 14 or newer, and Apple's free Command Line
Tools. To install those, run `xcode-select --install` in Terminal. You don't need Xcode.

```
cd mac
scripts/build-app.sh --install     # → ~/Applications/Meeting Transcript.app
open ~/Applications/"Meeting Transcript.app"
```

Leave out `--install` if you only want the app built into `mac/build/`.

- The first build downloads the sherpa-onnx speech libraries (about 19 MB) into
  `mac/vendor/`. The script checks each download against a known fingerprint (SHA-256)
  so it can't be swapped for something else.
- The first time you open the app, it downloads the speech model (about 490 MB, one
  time only) into `~/Library/Application Support/Meeting Transcript/models`.

The app is signed on your own Mac, not by Apple. macOS only checks apps that were
downloaded from the internet. An app you built yourself wasn't downloaded, so it opens
without any warnings.

### Stop macOS asking for permission after every rebuild (optional, one time)

The first time you use the app, macOS asks for **Microphone** and **Screen Recording**
access. It remembers your answer by the app's signature. A normal build gets a new
signature every time, so macOS asks again after each rebuild. To fix that:

```
scripts/make-signing-identity.sh   # creates "Meeting Transcript Local" in your login keychain
scripts/build-app.sh --install     # now signs with it
```

This creates a free signing certificate. It never leaves your Mac and is only used to
sign apps you build. You can delete it any time in Keychain Access → login → My
Certificates.

### Why the app needs Screen Recording access

macOS only lets an app hear another app's audio through Screen Recording. There is no
other way. The app asks for the smallest possible picture (2×2 pixels) and throws it
away. It only keeps the sound. After you allow it, quit the app and open it again,
because macOS only applies the change when the app starts.

## Speed on an M3 Pro

| | |
|---|---|
| Transcription | **18 times faster than real time.** A 1-hour meeting takes about 3½ minutes |
| Processor cores used | The fast cores (5 on an M3 Pro). Using more cores makes it slower |
| Model load time | 0.7 seconds |
| CoreML (Apple's AI engine) | Not used. The macOS version of sherpa-onnx doesn't support it, and the processor is already fast enough |

Because the app transcribes during the call, the transcript is usually finished a few
seconds after you click Stop.

## Command line

The app also works from Terminal. If you run it with no commands, it opens the normal
menu bar app.

```
M="$HOME/Applications/Meeting Transcript.app/Contents/MacOS/MeetingTranscript"
"$M" selftest            # tests speed and accuracy on a made-up two-person call
"$M" models              # downloads the models without opening the app
"$M" transcribe file.m4a # transcribes an audio file
"$M" record 60           # records the call and your mic for 60 seconds
"$M" list                # lists your meetings
"$M" export 3 md         # exports meeting 3: txt, md, json, srt, vtt or prompt
```

For testing, you can change these settings with environment variables:

| Variable | What it does |
|---|---|
| `MEETING_TRANSCRIPT_HOME` | Keeps meetings in a different folder. The models are still shared |
| `MEETING_TRANSCRIPT_THREADS` | Sets how many processor cores to use |
| `MEETING_TRANSCRIPT_PROVIDER` | Sets the onnxruntime provider (the default is `cpu`) |
| `MEETING_TRANSCRIPT_INCLUDE_OWN_AUDIO` | Also records sound from the app that started this one (see below) |

**Testing call audio from Terminal:** the app never records its own sound. macOS
treats everything you start from one Terminal window (or code editor) as the same
app. So if you play a sound with `say` or `afplay` from that Terminal, the app records
silence. Set `MEETING_TRANSCRIPT_INCLUDE_OWN_AUDIO=1`, or play the sound from a
different app. Real meeting apps are not affected.

## Development

```
swift build                  # debug build
swift run CoreChecks         # runs the tests
swift run CaptureSpike 10    # the first audio-capture experiment: saves 10 seconds of raw audio
```

The tests are a normal program instead of `swift test`. That's because `swift test`
needs XCTest, which only comes with the full Xcode.

| Folder | What's in it |
|---|---|
| `Sources/MeetingCore` | Recording, the speech engine, transcription, echo removal, the database, and exports |
| `Sources/App` | The menu bar app (SwiftUI) and the command line |
| `Sources/CSherpaOnnx` | Connects Swift to sherpa-onnx's C library |
| `Resources/Info.plist` | App details and permission messages. Also built into the program so the command line can ask for the microphone |
| `scripts/` | `setup.sh`, `build-app.sh`, `make-signing-identity.sh` |

How the Mac app was designed, and the tests behind those choices, are in
[../docs/MACOS.md](../docs/MACOS.md).
