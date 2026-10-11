import AppKit
import SwiftUI

// What you see when you click the menu-bar icon.
struct ContentView: View {
    // ObservedObject = "use an object someone else owns; re-render me when
    // its @Published values change". The app owns the Browser + Connector.
    @ObservedObject var browser: Browser
    @ObservedObject var connector: Connector

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
                        // Tapping a phone begins PAIRING (Phase 3b): we generate
                        // a PIN, connect over TLS, and prove we know it.
                        Button {
                            connector.pair(with: device.endpoint)
                        } label: {
                            HStack(spacing: 8) {
                                Circle()
                                    .fill(.green)
                                    .frame(width: 8, height: 8)
                                Text(device.name)
                                    .font(.callout)
                                Spacer()
                                Text(connector.isPaired ? "Paired" : "Pair")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.horizontal, 4)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            // The PIN to type into the phone — big and unmissable while pairing.
            if let pin = connector.pin {
                VStack(spacing: 2) {
                    Text("On your phone, enter:")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Text(pin)
                        .font(.system(size: 32, weight: .bold, design: .monospaced))
                        .kerning(4)
                }
                .padding(.vertical, 4)
            }

            // Result of the last pairing attempt (paired / rejected / locked).
            if let status = connector.pairingStatus {
                Text(status)
                    .font(.caption2)
                    .foregroundStyle(connector.isPaired ? .green : .secondary)
                    .multilineTextAlignment(.center)
            }

            // Show the result of the last connection attempt.
            switch connector.state {
            case .idle:
                EmptyView()
            case .connecting:
                Text("Connecting…")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            case .connected:
                Text("Connected ✓")
                    .font(.caption2)
                    .foregroundStyle(.green)
            case .failed(let message):
                Text("Failed: \(message)")
                    .font(.caption2)
                    .foregroundStyle(.red)
            }

            if connector.isPaired {
                Button("Unpair") {
                    connector.unpair()
                }
                .font(.caption2)
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
    ContentView(browser: Browser(), connector: Connector())
}
