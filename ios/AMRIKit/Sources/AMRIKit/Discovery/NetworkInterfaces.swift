import Foundation
import Darwin

/// One address of one interface on this phone.
public struct NetInterface: Equatable, Sendable {
    public enum Family: String, Sendable { case ipv4, ipv6 }

    public var name: String
    public var family: Family
    public var address: String
    public var isUp: Bool
    public var isLoopback: Bool
    /// IPv4 only, in host byte order.
    public var addressBits: UInt32?
    public var maskBits: UInt32?

    public init(name: String, family: Family, address: String, isUp: Bool = true, isLoopback: Bool = false,
                addressBits: UInt32? = nil, maskBits: UInt32? = nil) {
        self.name = name
        self.family = family
        self.address = address
        self.isUp = isUp
        self.isLoopback = isLoopback
        self.addressBits = addressBits
        self.maskBits = maskBits
    }

    public static func ipv4(name: String, address: String, prefix: Int) -> NetInterface {
        let mask: UInt32 = prefix == 0 ? 0 : ~UInt32(0) << UInt32(32 - prefix)
        return NetInterface(name: name, family: .ipv4, address: address, addressBits: NetworkInterfaces.parseIPv4(address), maskBits: mask)
    }

    public var prefix: Int? { maskBits?.nonzeroBitCount }
}

/// The phone's own addresses. Candidates for the car are derived from them, never from an interface's name and never from
/// a fixed hotspot range: Apple says neither is API (TN3179, forum thread 113933).
public enum NetworkInterfaces {
    public static func current() -> [NetInterface] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }

        var out: [NetInterface] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            guard let socketAddress = entry.pointee.ifa_addr else { continue }
            let name = String(cString: entry.pointee.ifa_name)
            let flags = Int32(entry.pointee.ifa_flags)
            let isUp = flags & IFF_UP != 0
            let isLoopback = flags & IFF_LOOPBACK != 0
            switch Int32(socketAddress.pointee.sa_family) {
            case AF_INET:
                let bits = socketAddress.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
                let mask = entry.pointee.ifa_netmask.map { pointer in
                    pointer.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
                } ?? 0
                out.append(NetInterface(name: name, family: .ipv4, address: ipv4String(bits), isUp: isUp, isLoopback: isLoopback,
                                        addressBits: bits, maskBits: mask))
            case AF_INET6:
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let length = socklen_t(socketAddress.pointee.sa_len)
                if getnameinfo(socketAddress, length, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    out.append(NetInterface(name: name, family: .ipv6, address: String(cString: host), isUp: isUp, isLoopback: isLoopback))
                }
            default:
                break
            }
        }
        return out
    }

    /// The hosts to try on 47101 or 5555: for each private IPv4 network of a /24 or smaller, all its hosts but this phone.
    /// Smaller networks first, because a hotspot's /28 is the likely one and costs nothing.
    public static func sweepHosts(from interfaces: [NetInterface]) -> [String] {
        var seen = Set<UInt32>()
        var out: [String] = []
        let usable = interfaces
            .filter { $0.family == .ipv4 && $0.isUp && !$0.isLoopback && $0.addressBits != nil && $0.maskBits != nil }
            .sorted { ($0.prefix ?? 0) > ($1.prefix ?? 0) }
        for interface in usable {
            for host in hosts(address: interface.addressBits ?? 0, mask: interface.maskBits ?? 0) where seen.insert(host).inserted {
                out.append(ipv4String(host))
            }
        }
        return out
    }

    public static func hosts(address: UInt32, mask: UInt32) -> [UInt32] {
        let prefix = mask.nonzeroBitCount
        guard prefix >= 24, prefix <= 30, ~mask & (~mask &+ 1) == 0, isPrivate(address) else { return [] }
        let network = address & mask
        let broadcast = network | ~mask
        var out: [UInt32] = []
        var host = network + 1
        while host < broadcast {
            if host != address { out.append(host) }
            host += 1
        }
        return out
    }

    public static func isPrivate(_ bits: UInt32) -> Bool {
        (bits & 0xFF00_0000) == 0x0A00_0000     // 10.0.0.0/8
            || (bits & 0xFFF0_0000) == 0xAC10_0000 // 172.16.0.0/12
            || (bits & 0xFFFF_0000) == 0xC0A8_0000 // 192.168.0.0/16
    }

    public static func ipv4String(_ bits: UInt32) -> String {
        "\((bits >> 24) & 255).\((bits >> 16) & 255).\((bits >> 8) & 255).\(bits & 255)"
    }

    public static func parseIPv4(_ text: String) -> UInt32? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var bits: UInt32 = 0
        for part in parts {
            guard let value = UInt8(part) else { return nil }
            bits = bits << 8 | UInt32(value)
        }
        return bits
    }
}
