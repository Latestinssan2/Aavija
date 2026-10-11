import AppKit
import SwiftUI

// What you see when you click the menu-bar icon.
struct ContentView: View {
    // ObservedObject = "use an object someone else owns; re-render me when
    // its @Published values change". The app owns the Browser.
    @ObservedObject var browser: Browser

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

            // Status line: are we searching at all?
            HStack(spacing: 6) {
                Circle()
                    .fill(browser.isSearching ? .green : .orange)
                    .frame(width: 8, height: 8)
                Text(browser.isSearching ? "Searching on this Wi-Fi…" : "Not searching")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            // List of phones we've found. Empty state explains what to do.
            if browser.devices.isEmpty {
                Text(browser.isSearching
                     ? "No devices found yet. Open Aavija on your phone."
                     : "Discovery is off.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            } else {
                VStack(spacing: 6) {
                    ForEach(browser.devices) { device in
                        HStack(spacing: 8) {
                            Circle()
                                .fill(.green)
                                .frame(width: 8, height: 8)
                            Text(device.name)
                                .font(.callout)
                            Spacer()
                        }
                        .padding(.horizontal, 4)
                    }
                }
            }

            Button("Quit Aavija") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding(20)
        .frame(width: 240)
    }
}

#Preview {
    ContentView(browser: Browser())
}
