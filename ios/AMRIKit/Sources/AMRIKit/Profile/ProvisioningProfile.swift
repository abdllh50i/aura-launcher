import Foundation

/// What re-signing really did, read from `embedded.mobileprovision` in the running bundle. It is a property list inside a
/// CMS blob. The App Group's name is read here and never written in code, because a signing tool may rename it.
public struct ProvisioningProfile: Equatable, Sendable {
    public var name: String?
    public var appID: String?
    public var expiration: Date?
    public var created: Date?
    public var appGroups: [String]
    public var entitlementKeys: [String]

    public init(name: String? = nil, appID: String? = nil, expiration: Date? = nil, created: Date? = nil, appGroups: [String] = [], entitlementKeys: [String] = []) {
        self.name = name
        self.appID = appID
        self.expiration = expiration
        self.created = created
        self.appGroups = appGroups
        self.entitlementKeys = entitlementKeys
    }

    public init?(data: Data) {
        guard let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), options: [], in: start.lowerBound..<data.endIndex) else { return nil }
        let plistData = data.subdata(in: start.lowerBound..<end.upperBound)
        guard let plist = try? PropertyListSerialization.propertyList(from: plistData, options: [], format: nil) as? [String: Any] else { return nil }
        name = plist["Name"] as? String
        expiration = plist["ExpirationDate"] as? Date
        created = plist["CreationDate"] as? Date
        let entitlements = plist["Entitlements"] as? [String: Any] ?? [:]
        appID = entitlements["application-identifier"] as? String
        appGroups = entitlements["com.apple.security.application-groups"] as? [String] ?? []
        entitlementKeys = entitlements.keys.sorted()
    }

    public static func embedded(in bundle: Bundle = .main) -> ProvisioningProfile? {
        let url = bundle.bundleURL.appendingPathComponent("embedded.mobileprovision")
        guard let data = try? Data(contentsOf: url) else { return nil }
        return ProvisioningProfile(data: data)
    }

    /// Whole days left, rounded down. Negative once expired.
    public func daysLeft(now: Date = Date()) -> Int? {
        guard let expiration else { return nil }
        return Int((expiration.timeIntervalSince(now) / 86_400).rounded(.down))
    }

    /// The warning's rule: three days or fewer.
    public func needsRefresh(now: Date = Date()) -> Bool {
        guard let days = daysLeft(now: now) else { return false }
        return days <= 3
    }
}

/// The signing facts of the app and of its extension.
public struct SigningFacts: Equatable, Sendable {
    public var appBundleID: String?
    public var shareBundleID: String?
    public var appProfile: ProvisioningProfile?
    public var shareProfile: ProvisioningProfile?
    /// The extension is in the bundle: a tool may remove it.
    public var shareExtensionPresent: Bool

    public init(appBundleID: String?, shareBundleID: String?, appProfile: ProvisioningProfile?, shareProfile: ProvisioningProfile?, shareExtensionPresent: Bool) {
        self.appBundleID = appBundleID
        self.shareBundleID = shareBundleID
        self.appProfile = appProfile
        self.shareProfile = shareProfile
        self.shareExtensionPresent = shareExtensionPresent
    }

    public static func current(bundle: Bundle = .main) -> SigningFacts {
        var extensionBundle: Bundle?
        if let plugins = bundle.builtInPlugInsURL,
           let contents = try? FileManager.default.contentsOfDirectory(at: plugins, includingPropertiesForKeys: nil),
           let appex = contents.first(where: { $0.pathExtension == "appex" }) {
            extensionBundle = Bundle(url: appex)
        }
        return SigningFacts(appBundleID: bundle.bundleIdentifier,
                            shareBundleID: extensionBundle?.bundleIdentifier,
                            appProfile: ProvisioningProfile.embedded(in: bundle),
                            shareProfile: extensionBundle.flatMap { ProvisioningProfile.embedded(in: $0) },
                            shareExtensionPresent: extensionBundle != nil)
    }
}
