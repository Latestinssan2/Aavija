import SwiftUI

// The app's entry point. @main tells Swift: "when this program starts,
// run this struct's body".
@main
struct AavijaApp: App {
    var body: some Scene {
        // MenuBarExtra = a SwiftUI Scene that lives ONLY in the macOS menu bar.
        // The `label` closure draws the tiny menu-bar item (Gujarati text);
        // the content closure is what appears when you click it.
        MenuBarExtra {
            ContentView()
        } label: {
            // Text (not an image): macOS renders it sharp at any size and
            // auto-tints it white/black to match the menu bar appearance.
            Text("આવી જા")
        }
        .menuBarExtraStyle(.window) // click opens a small panel, not a dropdown menu
    }
}
