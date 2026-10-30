import SwiftUI

@main
struct EyeCompanionApp: App {
    @StateObject private var companion = Companion()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(companion)
                .statusBarHidden(true)
                .persistentSystemOverlays(.hidden)
                .onAppear {
                    UIApplication.shared.isIdleTimerDisabled = true
                    companion.start()
                }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var companion: Companion
    @State private var showMenu = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            EyesView(animator: companion.animator)
                .ignoresSafeArea()
            VStack {
                if companion.showCaptions, !companion.subCaption.isEmpty {
                    Text(companion.subCaption)
                        .font(.system(size: 15))
                        .foregroundStyle(.white.opacity(0.45))
                        .lineLimit(1)
                        .padding(.top, 24)
                }
                Spacer()
                if companion.showCaptions || companion.phase == .setup {
                    Text(companion.caption)
                        .font(.system(size: 18))
                        .foregroundStyle(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                        .padding(.horizontal, 32)
                        .padding(.bottom, 36)
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { companion.doubleTap() }
        .onTapGesture(count: 1) { companion.tap() }
        .onLongPressGesture(minimumDuration: 1.0) { showMenu = true }
        .confirmationDialog("Eye Companion", isPresented: $showMenu, titleVisibility: .visible) {
            Button(companion.phase == .asleep ? "Wake up" : "Go to sleep") {
                companion.phase == .asleep ? companion.wakeUp() : companion.goToSleep()
            }
            Button(companion.showCaptions ? "Hide captions" : "Show captions") {
                companion.showCaptions.toggle()
            }
            Button("Forget our conversation") { companion.forget() }
            Button("Retry setup") { companion.start(force: true) }
            Button("Cancel", role: .cancel) {}
        }
    }
}
