# Meeting Transcript for macOS

Records a call on your Mac (what Zoom, Meet or Teams plays, plus your microphone)
and transcribes it on the Mac while the meeting is still going. It uses the same
Parakeet model as the Android app. Nothing leaves the machine, and there is no
account.

It lives in the menu bar. Press Record when the call starts. Lines appear as
people speak, labelled **You** (your microphone) and **Them** (the call). The
library window holds every meeting: search, read, play from any line, rename
speakers, and export as text, Markdown, JSON, SRT or WebVTT, or as a prompt
ready to paste into a chatbot.

## Build it: free, no Apple Developer account

You need macOS 14 or later on Apple silicon, and the Xcode Command Line Tools
(`xcode-select --install`). Xcode itself is not needed.

```
cd mac
scripts/build-app.sh --install     # → ~/Applications/Meeting Transcript.app
open ~/Applications/"Meeting Transcript.app"
```

Leave out `--install` to build into `mac/build/` only. The first build
downloads sherpa-onnx's prebuilt static libraries (~19 MB, checked against
pinned SHA-256 hashes) into `mac/vendor/`. On first launch the app downloads
the speech model and voice detector (~490 MB, once) into
`~/Library/Application Support/Meeting Transcript/models`.

The app is signed locally, not by Apple. Gatekeeper only checks apps that
arrive over the network, and one you built yourself doesn't, so it opens
without warnings.

### Keeping permissions across rebuilds (optional, once)

macOS asks for **Microphone** and **Screen Recording** on first use and ties
each grant to the app's signature. A plain build is signed ad-hoc, and that
signature changes on every build, so each rebuild asks again. To stop that:

```
scripts/make-signing-identity.sh   # self-signed "Meeting Transcript Local" in your login keychain
scripts/build-app.sh --install     # now signs with it
```

The certificate is free, never leaves your Mac, and is only used to sign
what you build. You can remove it in Keychain Access → login → My
Certificates.

### Why Screen Recording

On macOS, ScreenCaptureKit is the only supported way to hear another app's
audio, and macOS files it under Screen Recording. The app asks for the smallest
video frame the API allows (2×2 pixels) and throws it away. After you grant it,
quit and reopen the app, because macOS applies the grant at launch.

## Speed on an M3 Pro

| | |
|---|---|
| Transcription | **18× real time**: a 1-hour meeting takes about 3½ minutes |
| Threads | the performance cores (5 on an M3 Pro); more is slower |
| Model load | 0.7 s |
| CoreML | not used. sherpa-onnx's macOS build has no CoreML provider, and the CPU path is already fast |

Because it transcribes during the call, the transcript is usually complete a
few seconds after you press Stop.

## Command line

The app binary doubles as a CLI. Running it with no arguments starts the menu
bar app.

```
M="$HOME/Applications/Meeting Transcript.app/Contents/MacOS/MeetingTranscript"
"$M" selftest            # synthetic two-sided call → checks speed, order, echo removal
"$M" models              # download the models without opening the app
"$M" transcribe file.m4a # any audio file AVFoundation reads
"$M" record 60           # record the call + mic for 60 s, headless, into the library
"$M" list
"$M" export 3 md         # txt | md | json | srt | vtt | prompt
```

Settings for testing, set as environment variables:

| Variable | Effect |
|---|---|
| `MEETING_TRANSCRIPT_HOME` | use a different library folder (models are always shared) |
| `MEETING_TRANSCRIPT_THREADS` | override the thread count |
| `MEETING_TRANSCRIPT_PROVIDER` | onnxruntime provider (default `cpu`) |
| `MEETING_TRANSCRIPT_INCLUDE_OWN_AUDIO` | also capture sounds from the app that launched this one (see below) |

**Testing system audio from a terminal:** the app excludes its own sound from
capture, and macOS treats everything started from the same terminal or editor
as the same app. So `say` or `afplay` run from that terminal records as
silence. Set `MEETING_TRANSCRIPT_INCLUDE_OWN_AUDIO=1`, or play something from
another app. Real meeting apps are unaffected.

## Development

```
swift build                  # debug build
swift run CoreChecks         # unit checks (echo matching, exporters, segment files, store)
swift run CaptureSpike 10    # the original capture spike: raw 16 kHz PCM segments to disk
```

The unit checks are a plain executable rather than `swift test`, because
`swift test` needs XCTest, and XCTest only ships with Xcode.

| Path | What |
|---|---|
| `Sources/MeetingCore` | capture, recorder, sherpa-onnx wrapper, transcriber, echo filter, SQLite store, exporters |
| `Sources/App` | the SwiftUI menu bar app and the CLI |
| `Sources/CSherpaOnnx` | module map for sherpa-onnx's C API |
| `Resources/Info.plist` | bundle metadata, also embedded in the binary so the CLI gets the mic prompt too |
| `scripts/` | `setup.sh`, `build-app.sh`, `make-signing-identity.sh` |

The design, and what was measured to make it, is in
[../docs/MACOS.md](../docs/MACOS.md).
