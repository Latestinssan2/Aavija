import SwiftUI

// The app's entry point. @main tells Swift: "when this program starts,
// run this struct's body".
@main
struct AavijaApp: App {
    // StateObject = "own this observable object for the app's whole lifetime".
    // Created here (not in the view) so browsing starts at launch and keeps
    // running even while the menu-bar panel is closed.
    @StateObject private var browser = Browser()
    @StateObject private var connector = Connector()

    // Phase 5a: announce this Mac on the Wi-Fi (name only — no listening
    // socket) so the phone can list us as an available device. Not a
    // StateObject because it publishes nothing the UI observes yet.
    private let macAdvertiser = MacAdvertiser()

    init() {
        // Start at launch, before the panel is ever opened, so the phone's
        // device list fills in immediately rather than after the first click.
        macAdvertiser.start()
    }

    var body: some Scene {
        // MenuBarExtra = a SwiftUI Scene that lives ONLY in the macOS menu bar.
        // The `label` closure draws the tiny menu-bar item (Gujarati text);
        // the content closure is what appears when you click it.
        MenuBarExtra {
            ContentView(browser: browser, connector: connector)
        } label: {
            // Text (not an image): macOS renders it sharp at any size and
            // auto-tints it white/black to match the menu bar appearance.
            Text("આવી જા")
        }
        .menuBarExtraStyle(.window) // click opens a small panel, not a dropdown menu
    }
}
