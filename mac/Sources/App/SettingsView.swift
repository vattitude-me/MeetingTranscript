import MeetingCore
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Section("Recording") {
                Toggle("Record the call’s audio", isOn: $model.captureSystem)
                caption("Whatever the Mac plays: Zoom, Meet, Teams or a browser tab. Needs Screen Recording access. When off, only the microphone is recorded.")
                Toggle("Remove the call’s echo from your side", isOn: $model.filterEcho)
                caption("When you use speakers, the microphone also hears the call. Those lines are removed so they don't appear in the transcript twice.")
                Toggle("Apple echo cancellation on the microphone", isOn: $model.voiceProcessing)
                caption("Stops the microphone from hearing your speakers. It can make other apps quieter while recording. Not needed with headphones.")
            }
            Section("General") {
                Toggle("Open at login", isOn: Binding(get: { model.opensAtLogin }, set: { model.opensAtLogin = $0 }))
                LabeledContent("Permissions") {
                    HStack {
                        Text(model.micStatus == .authorized ? "Microphone ✓" : "Microphone: not allowed")
                        Text(model.screenGranted ? "Screen Recording ✓" : "Screen Recording: not allowed")
                        Button("Open Privacy Settings") { NSWorkspace.shared.open(Permissions.screenSettingsURL) }
                    }
                    .font(.callout)
                }
            }
            Section("Storage") {
                LabeledContent("Library") {
                    Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([Paths.root]) }
                }
                caption(Paths.root.path + ". Your meetings, transcripts and audio are all here. Nothing is stored anywhere else.")
                LabeledContent("Speech model") {
                    Text(model.modelsReady ? "Installed" : "Not downloaded").foregroundStyle(.secondary)
                }
                caption("Parakeet TDT 0.6B v3 (int8) and Silero VAD, running on \(Engine.threads) performance cores" +
                        (Engine.shared.speed > 0 ? String(format: ". Last run: %.0f× faster than real time.", Engine.shared.speed) : "."))
            }
            Section {
                caption("Meeting Transcript \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev") · Everything happens on this Mac. No account, no upload.")
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
