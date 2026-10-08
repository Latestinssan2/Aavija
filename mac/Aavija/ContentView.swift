import AppKit
import SwiftUI

// What you see when you click the menu-bar icon.
struct ContentView: View {
    var body: some View {
        VStack(spacing: 10) {
            Image("Logo")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 180, height: 120) // wide (3:2) wordmark

            Text("Aavija")
                .font(.headline)

            Text("Gesture sharing over Wi-Fi")
                .font(.caption)
                .foregroundStyle(.secondary)

            Divider()

            Text("Waiting for discovery…")
                .font(.caption2)
                .foregroundStyle(.secondary)

            Button("Quit Aavija") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding(20)
        .frame(width: 240)
    }
}

#Preview {
    ContentView()
}
