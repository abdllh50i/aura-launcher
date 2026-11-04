import AMRIKit
import SwiftUI

@main
struct AmriApp: App {
    @StateObject private var link = CarLink()
    @StateObject private var search = SearchModel()
    @StateObject private var location = LocationModel()
    @StateObject private var router = Router()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(link)
                .environmentObject(search)
                .environmentObject(location)
                .environmentObject(router)
        }
    }
}

/// The app is one map. Everything else comes up over it.
struct RootView: View {
    @EnvironmentObject private var link: CarLink
    @EnvironmentObject private var search: SearchModel
    @EnvironmentObject private var location: LocationModel
    @EnvironmentObject private var router: Router
    @Environment(\.scenePhase) private var phase

    var body: some View {
        content
            .onOpenURL { url in
                // amri://pair?... from the QR's web page
                if let target = PairingTarget.parse(url.absoluteString) {
                    router.route = .pair(target)
                }
            }
            .onChange(of: phase, initial: true) { _, new in
                switch new {
                case .active:
                    link.start()
                    location.start()
                case .background:
                    link.stop()
                    location.stop()
                default:
                    break
                }
            }
            .onChange(of: location.last?.coordinate.latitude) { _, _ in
                search.noteLocation(location.near)
            }
            .task {
                #if DEBUG
                DemoScreens.apply(link: link, search: search, router: router)
                if AppLaunch.mapProbe { await DemoScreens.runMapProbe() }
                #endif
            }
    }

    @ViewBuilder
    private var content: some View {
        #if DEBUG
        if AppLaunch.screen == "sharetest" {
            ShareTestView()
        } else if let screen = AppLaunch.screen, screen.hasPrefix("share-") {
            ShareDemoView(name: screen)
        } else {
            MapScreen()
        }
        #else
        MapScreen()
        #endif
    }
}
