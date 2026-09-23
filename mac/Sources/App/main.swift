import AppKit
import MeetingCore

// One binary, two faces: a subcommand runs in Terminal and exits; no
// arguments starts the menu bar app.
if let code = CLI.runIfCommand() { exit(code) }
MeetingApp.main()
