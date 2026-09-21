# Meeting Transcript for macOS

A capture spike, not an app. It proves the one thing a macOS port rests on:
that system audio — what Zoom or Meet is playing — can be landed on disk in
exactly the format the Android transcriber already reads.

```
swift run MeetingTranscript [seconds] [output-dir]
```

Writes `seg_00000.pcm`, `seg_00001.pcm`, … — 30-second rolling segments of
16 kHz mono signed 16-bit little-endian PCM, headerless. Identical to what
`RecorderService` produces on Android, so the same reader parses both.

On exit it reports sample count, duration and peak input level. A peak near
zero means capture succeeded and recorded silence, which is the failure worth
naming explicitly.

**Screen Recording permission is required.** macOS attributes the request to
whatever launched the binary, so running it under a terminal or IDE prompts for
that app and needs it restarted once. See [../docs/MACOS.md](../docs/MACOS.md)
for why the entitlement is named that way.

## Running it from source

Nothing here needs an Apple Developer account. `swift build` signs the binary
ad-hoc, and Gatekeeper only quarantines binaries that arrive over the network —
one you compiled yourself is not one of those. macOS 13+ and the Xcode Command
Line Tools are the whole prerequisite list.

The $99/year Developer ID matters for shipping a double-clickable `.app` to
people who will not open a terminal. It is not needed to try this.
