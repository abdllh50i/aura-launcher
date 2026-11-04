import Foundation
import MapKit

/// Takes everything from the share sheet's attachments, raw, without deciding anything yet. Apple's payload changes
/// between iOS versions (section 2.5), so every provider is read for every type that could hold a place.
public enum ShareInput {
    private static let mapItemType = "com.apple.mapkit.map-item"
    private static let urlType = "public.url"
    private static let textTypes = ["public.plain-text", "public.text", "public.utf8-plain-text"]
    private static let cardType = "public.vcard"

    public static func collect(from items: [NSExtensionItem], timeout: TimeInterval = 3) async -> SharePayload {
        var payload = SharePayload()
        for item in items {
            if let text = item.attributedContentText?.string, !text.isEmpty, !payload.texts.contains(text) { payload.texts.append(text) }
            if let text = item.attributedTitle?.string, !text.isEmpty, !payload.texts.contains(text) { payload.texts.append(text) }
            for provider in item.attachments ?? [] {
                payload.typeIdentifiers.append(provider.registeredTypeIdentifiers)
                if provider.hasItemConformingToTypeIdentifier(mapItemType), payload.mapItem == nil {
                    payload.mapItem = await loadMapItem(provider, timeout: timeout)
                }
                if provider.hasItemConformingToTypeIdentifier(urlType),
                   let text = string(from: await loadItem(provider, type: urlType, timeout: timeout)),
                   !payload.urls.contains(text) {
                    payload.urls.append(text)
                }
                for type in textTypes where provider.hasItemConformingToTypeIdentifier(type) {
                    if let text = string(from: await loadItem(provider, type: type, timeout: timeout)),
                       !text.isEmpty, !payload.texts.contains(text) {
                        payload.texts.append(text)
                    }
                    break
                }
                if provider.hasItemConformingToTypeIdentifier(cardType),
                   let text = string(from: await loadItem(provider, type: cardType, timeout: timeout)),
                   !payload.texts.contains(text) {
                    // Older Apple Maps: a vCard with the place's URL inside. The detector finds it in the text.
                    payload.texts.append(text)
                }
            }
        }
        return payload
    }

    private static func loadItem(_ provider: NSItemProvider, type: String, timeout: TimeInterval) async -> Any? {
        await withCheckedContinuation { (continuation: CheckedContinuation<Any?, Never>) in
            let once = OneShot<Any?> { continuation.resume(returning: $0) }
            provider.loadItem(forTypeIdentifier: type, options: nil) { item, _ in
                once.fire(item)
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { once.fire(nil) }
        }
    }

    private static func string(from item: Any?) -> String? {
        switch item {
        case let url as URL:
            return url.absoluteString
        case let text as String:
            return text
        case let text as NSAttributedString:
            return text.string
        case let data as Data:
            return String(data: data, encoding: .utf8)
        default:
            return nil
        }
    }

    /// The provider hands over the item itself or its archive, depending on the iOS version.
    private static func loadMapItem(_ provider: NSItemProvider, timeout: TimeInterval) async -> SharedMapItem? {
        let raw = await loadItem(provider, type: mapItemType, timeout: timeout)
        var found = raw as? MKMapItem
        if found == nil, let data = raw as? Data { found = unarchive(data) }
        guard let item = found else { return nil }
        let coordinate = item.placemark.coordinate
        guard CLLocationCoordinate2DIsValid(coordinate) else { return nil }
        return SharedMapItem(name: item.name, detail: item.placemark.title, lat: coordinate.latitude,
                             lon: coordinate.longitude, placeID: PlaceQueries.placeID(of: item))
    }

    private static func unarchive(_ data: Data) -> MKMapItem? {
        guard let archive = try? NSKeyedUnarchiver(forReadingFrom: data) else { return nil }
        defer { archive.finishDecoding() }
        return archive.decodeObject(of: [MKMapItem.self], forKey: NSKeyedArchiveRootObjectKey) as? MKMapItem
    }
}
