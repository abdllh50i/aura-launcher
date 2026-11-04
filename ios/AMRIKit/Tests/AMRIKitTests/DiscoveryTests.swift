import XCTest
@testable import AMRIKit

final class InterfaceTests: XCTestCase {
    func testAHotspotsNetworkIsThirteenHosts() {
        let hosts = NetworkInterfaces.sweepHosts(from: [.ipv4(name: "bridge100", address: "172.20.10.1", prefix: 28)])
        XCTAssertEqual(hosts.count, 13)
        XCTAssertEqual(hosts.first, "172.20.10.2")
        XCTAssertEqual(hosts.last, "172.20.10.14")
        XCTAssertFalse(hosts.contains("172.20.10.1"), "not the phone itself")
    }

    func testAClientOnThatNetworkSkipsItself() {
        let hosts = NetworkInterfaces.sweepHosts(from: [.ipv4(name: "en0", address: "172.20.10.5", prefix: 28)])
        XCTAssertEqual(hosts.count, 13)
        XCTAssertTrue(hosts.contains("172.20.10.1"))
        XCTAssertFalse(hosts.contains("172.20.10.5"))
    }

    func testASlash24() {
        let hosts = NetworkInterfaces.sweepHosts(from: [.ipv4(name: "en0", address: "192.168.1.20", prefix: 24)])
        XCTAssertEqual(hosts.count, 253)
        XCTAssertFalse(hosts.contains("192.168.1.0"))
        XCTAssertFalse(hosts.contains("192.168.1.255"))
    }

    func testOnlyPrivateNetworksOfASlash24OrSmaller() {
        let skipped: [NetInterface] = [
            .ipv4(name: "en0", address: "192.168.1.20", prefix: 16),    // too wide
            .ipv4(name: "pdp_ip0", address: "10.20.30.40", prefix: 8),  // a carrier's: never walk that
            .ipv4(name: "pdp_ip1", address: "10.20.30.40", prefix: 32), // no hosts
            .ipv4(name: "en1", address: "10.0.0.1", prefix: 31),
            .ipv4(name: "en2", address: "8.8.8.8", prefix: 24),         // public
            .ipv4(name: "en3", address: "169.254.3.4", prefix: 24),     // link-local
            NetInterface(name: "lo0", family: .ipv4, address: "127.0.0.1", isLoopback: true, addressBits: 0x7F00_0001, maskBits: 0xFF00_0000),
            NetInterface(name: "en4", family: .ipv6, address: "fe80::1"),
            NetInterface(name: "en5", family: .ipv4, address: "192.168.9.9", isUp: false, addressBits: 0xC0A8_0909, maskBits: 0xFFFF_FF00),
        ]
        XCTAssertEqual(NetworkInterfaces.sweepHosts(from: skipped), [])
    }

    func testTheSmallerNetworkComesFirst() {
        let hosts = NetworkInterfaces.sweepHosts(from: [.ipv4(name: "en0", address: "192.168.1.20", prefix: 24), .ipv4(name: "bridge100", address: "172.20.10.1", prefix: 28)])
        XCTAssertEqual(hosts.first, "172.20.10.2")
        XCTAssertEqual(hosts.count, 13 + 253)
    }

    func testTheRealCallDoesNotCrash() {
        XCTAssertFalse(NetworkInterfaces.current().isEmpty, "a Mac has at least a loopback")
    }

    func testAddressConversion() {
        XCTAssertEqual(NetworkInterfaces.ipv4String(0xAC14_0A01), "172.20.10.1")
        XCTAssertEqual(NetworkInterfaces.parseIPv4("172.20.10.1"), 0xAC14_0A01)
        XCTAssertNil(NetworkInterfaces.parseIPv4("172.20.10"))
        XCTAssertNil(NetworkInterfaces.parseIPv4("172.20.10.300"))
        XCTAssertNil(NetworkInterfaces.parseIPv4("example.com"))
    }
}

final class SweepTests: XCTestCase {
    func testFindsTheUnitAmongClosedPorts() async throws {
        let unit = try TestHTTPServer.amri()
        defer { unit.stop() }
        let closed = (0..<12).map { _ in SweepTarget(host: "127.0.0.1", port: freePort()) }
        let startedAt = Date()
        let report = await Sweep.run(targets: closed + [unit.target])
        XCTAssertEqual(report.targetCount, 13)
        XCTAssertEqual(report.units.map(\.address), [unit.address])
        XCTAssertEqual(report.units.first?.ping.unit, "k2501-test")
        XCTAssertLessThan(Date().timeIntervalSince(startedAt), 3)
        XCTAssertFalse(report.deniedSeen)
    }

    func testAnOpenPortThatIsNotAMRIIsNotAUnit() async throws {
        let other = try TestHTTPServer { _ in (200, #"{"app":"SomethingElse"}"#) }
        let notJSON = try TestHTTPServer { _ in (200, "hello") }
        defer {
            other.stop()
            notJSON.stop()
        }
        let report = await Sweep.run(targets: [other.target, notJSON.target])
        XCTAssertEqual(report.units, [])
        XCTAssertEqual(report.openHosts.count, 2, "both ports were open")
    }

    func testStopsAtTheFirstMatch() async throws {
        let unit = try TestHTTPServer.amri()
        defer { unit.stop() }
        let report = await Sweep.run(targets: [unit.target], stopWhen: { $0.isAMRI })
        XCTAssertEqual(report.units.count, 1)
    }

    func testNoTargetsIsFine() async {
        let report = await Sweep.run(targets: [])
        XCTAssertEqual(report.targetCount, 0)
        XCTAssertEqual(report.units, [])
    }

    func testMoreTargetsThanTheLimitAreAllTried() async throws {
        let unit = try TestHTTPServer.amri()
        defer { unit.stop() }
        var targets = (0..<20).map { _ in SweepTarget(host: "127.0.0.1", port: freePort()) }
        targets.append(unit.target)
        let report = await Sweep.run(targets: targets, concurrency: 4)
        XCTAssertEqual(report.units.count, 1, "the last target, behind the limit")
        XCTAssertEqual(report.closedCount + report.timedOutCount + report.openHosts.count, 21)
    }
}

final class LocatorTests: XCTestCase {
    private func locator(_ targets: [SweepTarget]) -> UnitLocator {
        UnitLocator(sweepEnabled: false, extraTargets: targets)
    }

    func testTheLastAddressWins() async throws {
        let unit = try TestHTTPServer.amri()
        defer { unit.stop() }
        let outcome = await locator([]).locate(expectedUnit: "k2501-test", last: unit.address)
        XCTAssertEqual(outcome.via, .last)
        XCTAssertEqual(outcome.found?.address, unit.address)
    }

    func testAStaleLastAddressFallsToTheSweep() async throws {
        let unit = try TestHTTPServer.amri()
        defer { unit.stop() }
        let stale = UnitAddress(host: "127.0.0.1", port: freePort())
        let outcome = await locator([unit.target]).locate(expectedUnit: "k2501-test", last: stale)
        XCTAssertEqual(outcome.via, .sweep)
        XCTAssertEqual(outcome.found?.address, unit.address)
    }

    func testAnotherUnitIsNotThePairedOne() async throws {
        let other = try TestHTTPServer.amri(unit: "k2501-other")
        defer { other.stop() }
        let outcome = await locator([other.target]).locate(expectedUnit: "k2501-mine", last: nil)
        XCTAssertNil(outcome.found)
        XCTAssertEqual(outcome.otherUnits.first?.ping.unit, "k2501-other")
        let unpaired = await locator([other.target]).locate(expectedUnit: nil, last: nil)
        XCTAssertEqual(unpaired.found?.ping.unit, "k2501-other", "unpaired takes any AMRI unit")
    }

    func testNothingThereIsAnAnswerToo() async {
        let outcome = await locator([SweepTarget(host: "127.0.0.1", port: freePort())]).locate(expectedUnit: nil, last: nil)
        XCTAssertNil(outcome.found)
        XCTAssertNil(outcome.via)
        XCTAssertNotNil(outcome.sweep)
        XCTAssertFalse(outcome.localNetworkDenied)
    }

    func testSweepTargetsComeFromTheInterfacesGiven() {
        let locator = UnitLocator(interfaces: { [.ipv4(name: "x", address: "172.20.10.1", prefix: 28)] })
        XCTAssertEqual(locator.sweepTargets().count, 13)
        XCTAssertEqual(locator.sweepTargets().first, SweepTarget(host: "172.20.10.2", port: 47101))
    }
}
