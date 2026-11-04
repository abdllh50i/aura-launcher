import Foundation

/// Waze's `waze.com/ul/h<geohash>` links are decoded on the phone, with no network.
public enum Geohash {
    private static let alphabet = Array("0123456789bcdefghjkmnpqrstuvwxyz")

    /// The centre of the cell.
    public static func decode(_ hash: String) -> (lat: Double, lon: Double)? {
        guard !hash.isEmpty else { return nil }
        var latRange = (low: -90.0, high: 90.0)
        var lonRange = (low: -180.0, high: 180.0)
        var isLon = true
        for character in hash.lowercased() {
            guard let value = alphabet.firstIndex(of: character) else { return nil }
            for bit in stride(from: 4, through: 0, by: -1) {
                let on = (value >> bit) & 1 == 1
                if isLon {
                    let middle = (lonRange.low + lonRange.high) / 2
                    if on { lonRange.low = middle } else { lonRange.high = middle }
                } else {
                    let middle = (latRange.low + latRange.high) / 2
                    if on { latRange.low = middle } else { latRange.high = middle }
                }
                isLon.toggle()
            }
        }
        return ((latRange.low + latRange.high) / 2, (lonRange.low + lonRange.high) / 2)
    }
}
