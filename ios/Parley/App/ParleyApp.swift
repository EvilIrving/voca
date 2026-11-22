import SwiftUI

@main
struct ParleyApp: App {
    @StateObject private var store = ChatStore()

    var body: some Scene {
        WindowGroup {
            RootView(store: store)
                .preferredColorScheme(.light)
        }
    }
}