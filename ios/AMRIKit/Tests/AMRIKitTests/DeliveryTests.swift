import XCTest
@testable import AMRIKit

/// A clock that only moves when the delivery sleeps, so a 20 s window takes no time.
private final class FakeClock: @unchecked Sendable {
    private let now = Locked(Date(timeIntervalSince1970: 1_000))
    var current: Date { now.current }
    func advance(_ seconds: TimeInterval) { now.with { $0 = $0.addingTimeInterval(seconds) } }
}

final class DeliveryTests: XCTestCase {
    private let place = LinkPlace(name: "الراشد مول", detail: "الخبر", lat: 26.290457, lon: 50.184555, src: "mapkit")
    private let address = UnitAddress(host: "172.20.10.3")

    func testTheWindowSpansTheHotspotsReassociation() {
        let policy = DeliveryPolicy.standard
        XCTAssertEqual(policy.attempts, 3)
        XCTAssertEqual(policy.ackTimeout, 1.5)
        XCTAssertEqual(policy.start(ofAttempt: 0), 0)
        XCTAssertEqual(policy.start(ofAttempt: 1), 9.25, accuracy: 0.001)
        XCTAssertEqual(policy.start(ofAttempt: 2), 18.5, accuracy: 0.001)
        XCTAssertLessThanOrEqual(policy.start(ofAttempt: 2) + policy.ackTimeout, policy.window)
    }

    private func environment(clock: FakeClock, located: LocateOutcome? = nil, post: @escaping @Sendable (UnitAddress, GoRequest, Bool, TimeInterval) async throws -> GoAck) -> DeliveryEnvironment {
        let found = located ?? LocateOutcome(found: FoundUnit(address: address, ping: UnitPing(app: "AMRI")), via: .sweep)
        return DeliveryEnvironment(locate: { found }, post: post, sleep: { clock.advance($0) }, now: { clock.current })
    }

    func testRetriesKeepTheSameIDAndStopAtTheAck() async {
        let clock = FakeClock()
        let posts = Locked<[(String, Date)]>([])
        let env = environment(clock: clock) { _, request, _, _ in
            posts.with { $0.append((request.id, clock.current)) }
            if posts.current.count < 3 { throw LinkError.noAnswer }
            return GoAck(id: request.id, state: "accepted")
        }
        let request = GoRequest(id: "SAME", place: place)
        let outcome = await Delivery.run(request, hasToken: true, known: address, environment: env)
        guard case .accepted(let ack, _, let usedToken) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(ack.state, "accepted")
        XCTAssertTrue(usedToken)
        XCTAssertEqual(posts.current.map(\.0), ["SAME", "SAME", "SAME"], "three tries, one id")
        let times = posts.current.map { $0.1.timeIntervalSince(posts.current[0].1) }
        XCTAssertEqual(times[1], 9.25, accuracy: 0.01)
        XCTAssertEqual(times[2], 18.5, accuracy: 0.01)
    }

    func testAnAckOnTheFirstTryIsAllItTakes() async {
        let clock = FakeClock()
        let count = Locked(0)
        let env = environment(clock: clock) { _, request, _, timeout in
            count.with { $0 += 1 }
            XCTAssertEqual(timeout, 1.5, "the ack is due within 1.5 s")
            return GoAck(id: request.id, state: "duplicate")
        }
        let outcome = await Delivery.run(GoRequest(place: place), hasToken: true, known: address, environment: env)
        guard case .accepted(let ack, _, _) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertTrue(ack.isDuplicate, "a duplicate is still taken")
        XCTAssertEqual(count.current, 1)
    }

    func testGivesUpAfterThreeTriesAndSaysSo() async {
        let clock = FakeClock()
        let count = Locked(0)
        let env = environment(clock: clock) { _, _, _, _ in
            count.with { $0 += 1 }
            throw LinkError.noAnswer
        }
        let outcome = await Delivery.run(GoRequest(place: place), hasToken: true, known: address, environment: env)
        XCTAssertEqual(outcome, .unreachable(localNetworkDenied: false, unitSeen: true))
        XCTAssertEqual(count.current, 3)
    }

    func testAnUnansweredAddressIsLookedUpAgain() async {
        let clock = FakeClock()
        let locations = Locked(0)
        let posted = Locked<[String]>([])
        let moved = UnitAddress(host: "172.20.10.9")
        let env = DeliveryEnvironment(locate: {
            locations.with { $0 += 1 }
            return LocateOutcome(found: FoundUnit(address: moved, ping: UnitPing(app: "AMRI")), via: .sweep)
        }, post: { address, request, _, _ in
            posted.with { $0.append(address.host) }
            if address.host == "172.20.10.3" { throw LinkError.noAnswer }
            return GoAck(id: request.id, state: "accepted")
        }, sleep: { clock.advance($0) }, now: { clock.current })
        let outcome = await Delivery.run(GoRequest(place: place), hasToken: true, known: address, environment: env)
        guard case .accepted(_, let where_, _) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(where_, moved, "the WifiKeeper may have changed its address")
        XCTAssertEqual(posted.current, ["172.20.10.3", "172.20.10.9"])
        XCTAssertEqual(locations.current, 1)
    }

    func testNoUnitFoundIsNotASend() async {
        let clock = FakeClock()
        let posts = Locked(0)
        let env = environment(clock: clock, located: LocateOutcome(localNetworkDenied: true)) { _, request, _, _ in
            posts.with { $0 += 1 }
            return GoAck(id: request.id, state: "accepted")
        }
        let outcome = await Delivery.run(GoRequest(place: place), hasToken: false, environment: env)
        XCTAssertEqual(outcome, .unreachable(localNetworkDenied: true, unitSeen: false))
        XCTAssertEqual(posts.current, 0)
    }

    func testA409IsTheUnitsAnswerNotAFailureToRetry() async {
        let clock = FakeClock()
        let count = Locked(0)
        let env = environment(clock: clock) { _, _, _, _ in
            count.with { $0 += 1 }
            throw LinkError.conflict("في مكالمة")
        }
        let outcome = await Delivery.run(GoRequest(place: place), hasToken: true, known: address, environment: env)
        XCTAssertEqual(outcome, .conflict("في مكالمة"))
        XCTAssertEqual(count.current, 1)
    }

    func testAStaleTokenRetriesAtOnceWithoutIt() async {
        let clock = FakeClock()
        let tokens = Locked<[Bool]>([])
        let env = environment(clock: clock) { _, request, useToken, _ in
            tokens.with { $0.append(useToken) }
            if useToken { throw LinkError.unauthorized(nil) }
            return GoAck(id: request.id, state: "accepted")
        }
        let outcome = await Delivery.run(GoRequest(place: place), hasToken: true, known: address, environment: env)
        guard case .accepted(_, _, let usedToken) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertFalse(usedToken, "so the screen shows its card and the phone says so")
        XCTAssertEqual(tokens.current, [true, false])
    }

    func testNotPairedFromAnotherPeerIsSaid() async {
        let clock = FakeClock()
        let env = environment(clock: clock) { _, _, _, _ in throw LinkError.unauthorized("not paired") }
        let outcome = await Delivery.run(GoRequest(place: place), hasToken: false, known: address, environment: env)
        XCTAssertEqual(outcome, .notPaired("not paired"))
    }

    func testProgressIsReported() async {
        let clock = FakeClock()
        let steps = Locked<[DeliveryProgress]>([])
        let env = environment(clock: clock) { _, request, _, _ in GoAck(id: request.id, state: "accepted") }
        _ = await Delivery.run(GoRequest(place: place), hasToken: true, environment: env) { step in
            steps.with { $0.append(step) }
        }
        XCTAssertEqual(steps.current, [.locating, .sending(attempt: 1, of: 3)])
    }
}

#if os(macOS)
/// The app's client against the protocol's stand-in, over real sockets.
final class LinkAgainstFakeUnitTests: XCTestCase {
    private let place = LinkPlace(name: "الراشد مول", detail: "الخبر", lat: 26.290457, lon: 50.184555, src: "mapkit")
    private let fast = DeliveryPolicy(attempts: 3, ackTimeout: 0.4, window: 1.5)

    private func paired(_ unit: FakeUnit) async throws -> LinkClient.PairReply {
        try await LinkClient(address: unit.address).pair(code: "246810", device: "test")
    }

    private func environment(_ unit: FakeUnit, token: String?) -> DeliveryEnvironment {
        let address = unit.address
        return .live(token: token, locate: {
            LocateOutcome(found: FoundUnit(address: address, ping: UnitPing(app: "AMRI")), via: .last)
        })
    }

    func testAFrozenHotspotRetriesWithTheSameIDAndStartsOneTrip() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        let reply = try await paired(unit)
        _ = try await unit.control("config", ["dropGo": 1])

        let request = GoRequest(id: "FROZEN", place: place)
        let outcome = await Delivery.run(request, hasToken: true, known: unit.address, policy: fast, environment: environment(unit, token: reply.token))
        guard case .accepted(let ack, _, _) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(ack.state, "accepted")

        let bodies = try await unit.goBodies()
        XCTAssertEqual(bodies.map { $0["id"] as? String }, ["FROZEN", "FROZEN"], "the same id on both tries")
        let starts = try await unit.starts()
        XCTAssertEqual(starts.count, 1)
    }

    func testALostAckIsADuplicateAndOneTrip() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        let reply = try await paired(unit)
        _ = try await unit.control("config", ["dropAck": 1])

        let outcome = await Delivery.run(GoRequest(id: "LOST", place: place), hasToken: true, known: unit.address, policy: fast, environment: environment(unit, token: reply.token))
        guard case .accepted(let ack, _, _) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertTrue(ack.isDuplicate)
        let starts = try await unit.starts()
        XCTAssertEqual(starts.count, 1)
    }

    func testStartedComesFromTheUnitWithItsNumbers() async throws {
        let unit = try await FakeUnit(routeDelay: 150, progressMs: 100)
        defer { unit.stop() }
        let reply = try await paired(unit)
        let client = LinkClient(address: unit.address, token: reply.token)
        let ack = try await client.go(GoRequest(id: "START", place: place), timeout: 2)
        XCTAssertEqual(ack.state, "accepted")
        let outcome = await client.waitForStart(id: "START", timeout: 5)
        guard case .started(let nav) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertNotNil(nav.etaLocal)
        XCTAssertGreaterThan(nav.durationS ?? 0, 0)
    }

    func testNoWordMeansNoClaim() async throws {
        let unit = try await FakeUnit(routeDelay: 5_000)
        defer { unit.stop() }
        let reply = try await paired(unit)
        let client = LinkClient(address: unit.address, token: reply.token)
        _ = try await client.go(GoRequest(id: "SLOW", place: place), timeout: 2)
        let outcome = await client.waitForStart(id: "SLOW", timeout: 0.6)
        XCTAssertEqual(outcome, .noWord(routingSeen: true))
    }

    func testAFailedRouteIsReportedAsTheUnitSaidIt() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        let reply = try await paired(unit)
        _ = try await unit.control("config", ["failRoute": "ما فيه طريق"])
        let client = LinkClient(address: unit.address, token: reply.token)
        _ = try await client.go(GoRequest(id: "FAIL", place: place), timeout: 2)
        let outcome = await client.waitForStart(id: "FAIL", timeout: 5)
        XCTAssertEqual(outcome, .failed("ما فيه طريق"))
    }

    func testNoTokenFromTheGatewayIsTakenAndWaitsForTheCard() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        let outcome = await Delivery.run(GoRequest(id: "CARD", place: place), hasToken: false, known: unit.address, policy: fast, environment: environment(unit, token: nil))
        guard case .accepted(_, _, let usedToken) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertFalse(usedToken)
        let starts = try await unit.starts()
        XCTAssertEqual(starts.count, 0, "nothing starts until the card is tapped on the screen")
        let state = try await unit.control("state")
        XCTAssertNotNil(state["card"] as? [String: Any])
    }

    func testNoTokenFromAnyOtherPeerIsRefused() async throws {
        let unit = try await FakeUnit(gateway: "10.9.9.9")
        defer { unit.stop() }
        let outcome = await Delivery.run(GoRequest(place: place), hasToken: false, known: unit.address, policy: fast, environment: environment(unit, token: nil))
        guard case .notPaired = outcome else { return XCTFail("\(outcome)") }
    }

    func testEventsPlacesRemoteAndStop() async throws {
        let unit = try await FakeUnit(routeDelay: 50, progressMs: 100)
        defer { unit.stop() }
        let reply = try await paired(unit)
        XCTAssertEqual(reply.unit, "k2501-test")
        let client = LinkClient(address: unit.address, token: reply.token)

        var seenMusic: MusicStatus?
        var seenCar: CarStatus?
        for try await event in client.events() {
            if case .music(let music) = event { seenMusic = music }
            if case .car(let car) = event { seenCar = car }
            if seenMusic != nil, seenCar != nil { break }
        }
        XCTAssertEqual(seenMusic?.title, "يا طير", "the snapshot on connect")
        XCTAssertEqual(seenCar?.volumeStep, 12)

        try await client.remote(.volumeUp)
        let state = try await client.state()
        XCTAssertEqual(state.car?.volumeStep, 13)
        try await client.remote(.musicNext)
        let afterNext = try await client.state()
        XCTAssertEqual(afterNext.music?.title, "على البال")

        var doc = try await client.places()
        XCTAssertNil(doc.home)
        doc.home = LinkPlace(name: "البيت", lat: 26.4, lon: 50.1, src: "pin")
        try await client.putPlaces(doc)
        let stored = try await client.places()
        XCTAssertEqual(stored.home?.name, "البيت")
        let ack = try await client.saved(which: "home")
        XCTAssertEqual(ack.state, "accepted")
        try await client.stop()
        let afterStop = try await client.state()
        XCTAssertEqual(afterStop.nav?.kind, .stopped)
        let diag = try await client.diag()
        XCTAssertTrue(diag.contains("AMRI"))
    }

    func testWrongCodeAndNoTokenAreClearErrors() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        do {
            _ = try await LinkClient(address: unit.address).pair(code: "000000", device: "test")
            XCTFail("a wrong code must not pair")
        } catch let error as LinkError {
            XCTAssertEqual(error, .unauthorized("bad code"))
        }
        do {
            _ = try await LinkClient(address: unit.address).state()
            XCTFail("state needs a token")
        } catch let error as LinkError {
            XCTAssertEqual(error, .unauthorized("token required"))
        }
    }

    func testNothingListeningIsNoAnswer() async {
        let client = LinkClient(address: UnitAddress(host: "127.0.0.1", port: freePort()))
        do {
            _ = try await client.ping(timeout: 0.5)
            XCTFail("nothing listens there")
        } catch let error as LinkError {
            XCTAssertEqual(error, .noAnswer)
        } catch {
            XCTFail("\(error)")
        }
    }

    // MARK: Repair

    func testHelloReadsTheBannerAndOpensNothing() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        let hello = await AdbClient.hello(host: "127.0.0.1", port: unit.adbPort)
        guard case .banner(let banner) = hello else { return XCTFail("\(hello)") }
        XCTAssertTrue(banner.isK2501)
        XCTAssertEqual(banner.model, "K2501")
        let opens = try await unit.adbOpens()
        XCTAssertEqual(opens.count, 0, "a handshake only")
    }

    func testTheScanSortsK2501FromTheRest() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        let targets = [SweepTarget(host: "127.0.0.1", port: unit.adbPort), SweepTarget(host: "127.0.0.1", port: unit.otherAdbPort),
                       SweepTarget(host: "127.0.0.1", port: freePort())]
        let scan = await Repair.scan(targets: targets)
        XCTAssertEqual(scan.targetCount, 3)
        XCTAssertEqual(scan.units.map(\.port), [unit.adbPort])
        XCTAssertEqual(scan.hosts.filter { $0.kind == .other }.map(\.port), [unit.otherAdbPort])
        let opens = try await unit.adbOpens()
        XCTAssertEqual(opens.count, 0, "never a shell during a scan")
    }

    func testTheRollbackRunsOnAK2501AndReportsItsOutput() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        let host = Repair.Host(host: "127.0.0.1", port: unit.adbPort, kind: .k2501, banner: nil)
        let result = await Repair.rollback(on: host)
        XCTAssertTrue(result.ran, "\(result)")
        XCTAssertTrue(result.output.contains("EXIT=0"), result.output)
        XCTAssertFalse(result.output.contains("\r"), "line ends are cleaned")
        let opens = try await unit.adbOpens()
        XCTAssertEqual(opens.first?["service"] as? String, "shell:sh /data/local/tmp/amri-rollback/rollback.sh")
    }

    func testAShellIsNeverOpenedOnAnythingElse() async throws {
        let unit = try await FakeUnit()
        defer { unit.stop() }
        // Even if something claims to be a K2501, the client reads the banner again itself.
        let liar = Repair.Host(host: "127.0.0.1", port: unit.otherAdbPort, kind: .k2501, banner: nil)
        let result = await Repair.rollback(on: liar)
        XCTAssertFalse(result.ran)
        XCTAssertTrue(result.problem?.contains("not a K2501") == true, "\(result)")
        let direct = await AdbClient.runShell(host: "127.0.0.1", port: unit.otherAdbPort, command: "reboot")
        XCTAssertFalse(direct.ran)
        let opens = try await unit.adbOpens()
        XCTAssertEqual(opens.count, 0)
        let other = Repair.Host(host: "127.0.0.1", port: unit.otherAdbPort, kind: .other, banner: nil)
        let refused = await Repair.rollback(on: other)
        XCTAssertFalse(refused.ran)
    }
}
#endif

final class AdbPacketTests: XCTestCase {
    func testTheSamePacketsAsLocalAdb() {
        // LocalAdb.kt: cmd, arg0, arg1, length, sum of bytes, cmd inverted, little endian.
        let payload = Data("host::\u{0}".utf8)
        let packet = AdbPacket.encode(command: AdbPacket.cnxn, arg0: 0x0100_0000, arg1: 256 * 1024, payload: payload)
        XCTAssertEqual(packet.count, 24 + payload.count)
        XCTAssertEqual(Array(packet.prefix(4)), Array("CNXN".utf8), "CNXN reads as text on the wire")
        let header = AdbPacket.decodeHeader(packet.prefix(24))
        XCTAssertEqual(header?.command, AdbPacket.cnxn)
        XCTAssertEqual(header?.arg0, 0x0100_0000)
        XCTAssertEqual(header?.arg1, 256 * 1024)
        XCTAssertEqual(header?.length, UInt32(payload.count))
        XCTAssertEqual(Array(packet[16..<20]), [0x32, 0x02, 0x00, 0x00], "the sum of the bytes, 0x232")
    }

    func testABrokenHeaderIsRefused() {
        var packet = AdbPacket.encode(command: AdbPacket.okay, arg0: 1, arg1: 2)
        XCTAssertNotNil(AdbPacket.decodeHeader(packet))
        packet[20] ^= 0xFF
        XCTAssertNil(AdbPacket.decodeHeader(packet), "bad magic")
        XCTAssertNil(AdbPacket.decodeHeader(Data([1, 2, 3])))
    }

    func testBanners() {
        let real = AdbBanner(raw: "device::ro.product.name=mercury_demo2;ro.product.model=K2501;ro.product.device=mercury-demo;features=shell_v2,cmd")
        XCTAssertTrue(real.isK2501)
        XCTAssertEqual(real.device, "mercury-demo")
        XCTAssertFalse(AdbBanner(raw: "device::ro.product.model=Pixel 7;").isK2501)
        XCTAssertFalse(AdbBanner(raw: "device::").isK2501, "a banner that does not say is not a K2501 here")
        XCTAssertFalse(AdbBanner(raw: "device::ro.product.name=K2501;").isK2501, "only the model counts")
        XCTAssertFalse(AdbBanner(raw: "").isK2501)
        XCTAssertEqual(AdbBanner(raw: "device::ro.product.model=a\u{1B}b;").summary, "model a?b, device ?")
    }

    func testTheScriptMissingCase() {
        XCTAssertTrue(Repair.scriptMissing(in: "sh: can't open '/data/local/tmp/amri-rollback/rollback.sh': No such file or directory"))
        XCTAssertFalse(Repair.scriptMissing(in: "EXIT=0"))
    }

    func testTheOnlyCommand() {
        XCTAssertEqual(Repair.rollbackCommand, "sh /data/local/tmp/amri-rollback/rollback.sh")
    }
}
