import SwiftUI

/// Phase 8: the Mac's transfer animation.
///
/// A pulsing orb with orbiting particles, shown only while bytes are moving.
/// Everything is pure SwiftUI shapes (no images, no external library), and the
/// whole view scales in on a spring — the "unfold" — then settles into a slow
/// spin. It sits over the menu-bar panel and disappears when the transfer ends.
struct TransferOrbView: View {
    let label: String

    @State private var spin = false
    @State private var pulse = false
    @State private var burst = false

    var body: some View {
        ZStack {
            Color.black.opacity(0.42)

            VStack(spacing: 10) {
                ZStack {
                    // Particles orbit outward and fade.
                    ForEach(0..<14, id: \.self) { i in
                        Circle()
                            .fill(Color(red: 0.50, green: 0.85, blue: 1.0))
                            .frame(width: 5, height: 5)
                            .offset(x: burst ? 64 : 36)
                            .opacity(burst ? 0.05 : 0.9)
                            .rotationEffect(.degrees(Double(i) * (360.0 / 14.0)))
                    }

                    // Spectrum ring.
                    Circle()
                        .stroke(
                            AngularGradient(
                                colors: [
                                    Color(red: 0.16, green: 0.71, blue: 0.96),
                                    Color(red: 0.49, green: 0.30, blue: 1.00),
                                    Color(red: 0.15, green: 0.78, blue: 0.85),
                                    Color(red: 0.16, green: 0.71, blue: 0.96),
                                ],
                                center: .center
                            ),
                            lineWidth: 8
                        )
                        .frame(width: 76, height: 76)
                        .rotationEffect(.degrees(spin ? 360 : 0))

                    // Bright core with a heartbeat.
                    Circle()
                        .fill(.white.opacity(0.92))
                        .frame(width: 38, height: 38)
                        .scaleEffect(pulse ? 1.06 : 0.94)
                }
                .frame(width: 150, height: 150)

                // Gujarati animated transfer title
                Text("જતું છે")
                    .font(.system(size: 26, weight: .black, design: .rounded))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [
                                Color(red: 0.20, green: 0.90, blue: 1.00),
                                Color.white,
                                Color(red: 0.70, green: 0.40, blue: 1.00),
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .shadow(color: Color(red: 0.20, green: 0.90, blue: 1.00).opacity(0.75), radius: 8)
                    .scaleEffect(pulse ? 1.05 : 0.98)

                Text(label)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.85))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 18)
            }
        }
        .onAppear {
            withAnimation(.linear(duration: 2.2).repeatForever(autoreverses: false)) {
                spin = true
            }
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                pulse = true
            }
            withAnimation(.easeOut(duration: 1.3).repeatForever(autoreverses: false)) {
                burst = true
            }
        }
    }
}
