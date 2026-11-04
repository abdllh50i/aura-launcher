import Foundation

public enum LocateVia: String, Sendable {
    case last, sweep, bonjour
}

public struct LocateOutcome: Equatable, Sendable {
    public var found: FoundUnit?
    public var via: LocateVia?
    public var sweep: SweepReport?
    /// AMRI units that answered but are not the paired one.
    public var otherUnits: [FoundUnit]
    public var localNetworkDenied: Bool
    public var duration: TimeInterval

    public init(found: FoundUnit? = nil, via: LocateVia? = nil, sweep: SweepReport? = nil, otherUnits: [FoundUnit] = [],
                localNetworkDenied: Bool = false, duration: TimeInterval = 0) {
        self.found = found
        self.via = via
        self.sweep = sweep
        self.otherUnits = otherUnits
        self.localNetworkDenied = localNetworkDenied
        self.duration = duration
    }
}

/// Finding the car, stopping at the first success: the last address, then a sweep of the phone's own networks, then Bonjour.
public struct UnitLocator: Sendable {
    public var port: UInt16
    public var includeBonjour: Bool
    public var sweepEnabled: Bool
    /// Tried like a sweep's hosts, before them. The test stand-in comes in here (debug builds only).
    public var extraTargets: [SweepTarget]
    public var interfaces: @Sendable () -> [NetInterface]

    public init(port: UInt16 = UnitAddress.defaultPort,
                includeBonjour: Bool = false,
                sweepEnabled: Bool = true,
                extraTargets: [SweepTarget] = [],
                interfaces: @escaping @Sendable () -> [NetInterface] = { NetworkInterfaces.current() }) {
        self.port = port
        self.includeBonjour = includeBonjour
        self.sweepEnabled = sweepEnabled
        self.extraTargets = extraTargets
        self.interfaces = interfaces
    }

    public func sweepTargets() -> [SweepTarget] {
        var targets = extraTargets
        if sweepEnabled {
            targets += NetworkInterfaces.sweepHosts(from: interfaces()).map { SweepTarget(host: $0, port: port) }
        }
        return targets
    }

    /// `expectedUnit` is the paired unit's id; nil takes any AMRI unit.
    public func locate(expectedUnit: String?, last: UnitAddress?) async -> LocateOutcome {
        let startedAt = Date()
        var outcome = LocateOutcome()
        let matches: @Sendable (UnitPing) -> Bool = { ping in
            ping.isAMRI && (expectedUnit == nil || ping.unit == expectedUnit)
        }

        if let last {
            let client = LinkClient(address: last)
            if let ping = try? await client.ping(timeout: 1.0), matches(ping) {
                outcome.found = FoundUnit(address: last, ping: ping)
                outcome.via = .last
                outcome.duration = Date().timeIntervalSince(startedAt)
                return outcome
            }
        }

        let report = await Sweep.run(targets: sweepTargets(), stopWhen: matches)
        outcome.sweep = report
        outcome.localNetworkDenied = report.deniedSeen
        if let unit = report.units.first(where: { matches($0.ping) }) {
            outcome.found = unit
            outcome.via = .sweep
        } else {
            outcome.otherUnits = report.units
        }

        if outcome.found == nil, includeBonjour {
            let answer = await Bonjour.browse()
            outcome.localNetworkDenied = outcome.localNetworkDenied || answer.denied
            for address in answer.addresses {
                let client = LinkClient(address: address)
                if let ping = try? await client.ping(timeout: 1.0), matches(ping) {
                    outcome.found = FoundUnit(address: address, ping: ping)
                    outcome.via = .bonjour
                    break
                }
            }
        }
        outcome.duration = Date().timeIntervalSince(startedAt)
        return outcome
    }
}
