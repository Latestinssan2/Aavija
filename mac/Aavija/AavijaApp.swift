import SwiftUI

// The app's entry point. @main tells Swift: "when this program starts,
// run this struct's body".
@main
struct AavijaApp: App {
    var body: some Scene {
        // MenuBarExtra = a SwiftUI Scene that lives ONLY in the macOS menu bar.
        // The `label` closure draws the tiny icon; the content closure is what
        // appears when you click it.
        MenuBarExtra {
            ContentView()
        } label: {
            Image(systemName: "hand.raised.fill") // SF Symbol — Apple's built-in icon set
        }
        .menuBarExtraStyle(.window) // click opens a small panel, not a dropdown menu
    }
}
