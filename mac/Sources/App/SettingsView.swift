import MeetingCore
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Section("Recording") {
                Toggle("Record the call’s audio", isOn: $model.captureSystem)
                caption("What the Mac plays — Zoom, Meet, Teams, a browser tab. Needs Screen Recording access. Off records the microphone only.")
                Toggle("Remove the call’s echo from your side", isOn: $model.filterEcho)
                caption("On speakers, the microphone hears the call too. Lines it heard that match what the call said are dropped, so they are not in the transcript twice.")
                Toggle("Apple echo cancellation on the microphone", isOn: $model.voiceProcessing)
                caption("Cancels the speakers at the source. Can lower other apps’ volume while recording. Not needed with headphones.")
            }
            Section("General") {
                Toggle("Open at login", isOn: Binding(get: { model.opensAtLogin }, set: { model.opensAtLogin = $0 }))
                LabeledContent("Permissions") {
                    HStack {
                        Text(model.micStatus == .authorized ? "Microphone ✓" : "Microphone –")
                        Text(model.screenGranted ? "Screen Recording ✓" : "Screen Recording –")
                        Button("Open Privacy Settings") { NSWorkspace.shared.open(Permissions.screenSettingsURL) }
                    }
                    .font(.callout)
                }
            }
            Section("Storage") {
                LabeledContent("Library") {
                    Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([Paths.root]) }
                }
                caption(Paths.root.path + " — meetings, transcripts and audio. Nothing is stored anywhere else.")
                LabeledContent("Speech model") {
                    Text(model.modelsReady ? "Installed" : "Not downloaded").foregroundStyle(.secondary)
                }
                caption("Parakeet TDT 0.6B v3 (int8) and Silero VAD, running on \(Engine.threads) performance cores" +
                        (Engine.shared.speed > 0 ? String(format: " — last run %.0f× real time.", Engine.shared.speed) : "."))
            }
            Section {
                caption("Meeting Transcript \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev") · everything happens on this Mac; no account, no upload.")
            }
        }
        .formStyle(.grouped)
        .frame(width: 520)
        .fixedSize(horizontal: false, vertical: true)
        .onAppear(perform: model.refreshPermissions)
    }

    private func caption(_ s: String) -> some View {
        Text(s).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
    }
}
