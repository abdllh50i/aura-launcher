import AMRIKit
import Foundation

/// The launch arguments of the test and screenshot runs. A release build has none of this: every bypass is behind DEBUG.
enum AppLaunch {
    #if DEBUG
    private static let arguments = ProcessInfo.processInfo.arguments

    private static func value(_ flag: String) -> String? {
        guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }

    private static func target(_ text: String) -> SweepTarget? {
        let parts = text.split(separator: ":")
        guard parts.count == 2, let port = UInt16(parts[1]) else { return nil }
        return SweepTarget(host: String(parts[0]), port: port)
    }

    /// `-fakeUnit 127.0.0.1:47101`: the stand-in instead of a sweep of the Mac's networks.
    static var fakeUnit: SweepTarget? { value("-fakeUnit").flatMap(target) }
    /// `-fakeAdb 127.0.0.1:15555,127.0.0.1:15556`
    static var fakeAdb: [SweepTarget] { (value("-fakeAdb") ?? "").split(separator: ",").compactMap { target(String($0)) } }
    /// Fixed results in place of MapKit, whose answers change from run to run.
    static var demoSearch: Bool { arguments.contains("-demoSearch") }
    static var reset: Bool { arguments.contains("-reset") }
    /// `-screen started`: a screen in a set state, with no network (tools/capture_simulator.py).
    static var screen: String? { value("-screen") }
    /// The CI's live MapKit probe: prints JSON and exits.
    static var mapProbe: Bool { arguments.contains("-mapProbe") }
    static var skipMapKitCheck: Bool { arguments.contains("-skipMapKitCheck") }
    static var skipLocation: Bool { arguments.contains("-skipLocation") }
    /// A flat colour in place of the map, for the UI tests that are about the link and not the map: on a loaded CI machine a software
    /// rendered map makes each look at the screen take up to a minute. The tests that are about the map keep it.
    static var plainMap: Bool { arguments.contains("-plainMap") }
    #else
    static let fakeUnit: SweepTarget? = nil
    static let fakeAdb: [SweepTarget] = []
    static let demoSearch = false
    static let reset = false
    static let screen: String? = nil
    static let mapProbe = false
    static let skipMapKitCheck = false
    static let skipLocation = false
    static let plainMap = false
    #endif

    static var version: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))"
    }
}
