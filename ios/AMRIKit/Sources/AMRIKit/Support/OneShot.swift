import Foundation

/// Runs its body once, whoever calls first: connection handlers and timers race for the same result.
final class OneShot<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var body: ((Value) -> Void)?

    init(_ body: @escaping (Value) -> Void) { self.body = body }

    func fire(_ value: Value) {
        lock.lock()
        let run = body
        body = nil
        lock.unlock()
        run?(value)
    }
}

/// A lock around one value, for state that handlers on other queues touch.
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) { self.value = value }

    func with<Result>(_ body: (inout Value) -> Result) -> Result {
        lock.lock()
        defer { lock.unlock() }
        return body(&value)
    }

    var current: Value { with { $0 } }
}
