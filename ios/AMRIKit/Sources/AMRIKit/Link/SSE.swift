import Foundation

/// One Server-Sent Event.
public struct SSEMessage: Equatable, Sendable {
    public var event: String
    public var data: String
    public var id: String?

    public init(event: String, data: String, id: String? = nil) {
        self.event = event
        self.data = data
        self.id = id
    }
}

/// A byte-level parser for `text/event-stream`. Written by hand because `URLSession.AsyncBytes.lines` swallows the empty
/// lines that end each event. Comment lines (the unit's 15 s heartbeat) are ignored here: they only keep the request's
/// inactivity timer from firing.
public struct SSEParser {
    private var line: [UInt8] = []
    private var event = ""
    private var dataLines: [String] = []
    private var id: String?

    public init() {}

    /// Takes one byte; returns a message when this byte ended one.
    public mutating func feed(_ byte: UInt8) -> SSEMessage? {
        guard byte == 0x0A else {
            line.append(byte)
            return nil
        }
        if line.last == 0x0D { line.removeLast() }
        defer { line.removeAll(keepingCapacity: true) }
        if line.isEmpty { return dispatch() }
        take(String(decoding: line, as: UTF8.self))
        return nil
    }

    public mutating func feed(_ bytes: some Sequence<UInt8>) -> [SSEMessage] {
        var out: [SSEMessage] = []
        for byte in bytes {
            if let message = feed(byte) { out.append(message) }
        }
        return out
    }

    private mutating func take(_ text: String) {
        if text.hasPrefix(":") { return }
        let field: Substring
        var value: Substring
        if let colon = text.firstIndex(of: ":") {
            field = text[..<colon]
            value = text[text.index(after: colon)...]
            if value.hasPrefix(" ") { value = value.dropFirst() }
        } else {
            field = Substring(text)
            value = ""
        }
        switch field {
        case "event": event = String(value)
        case "data": dataLines.append(String(value))
        case "id": id = String(value)
        default: break
        }
    }

    private mutating func dispatch() -> SSEMessage? {
        defer {
            event = ""
            dataLines = []
            id = nil
        }
        guard !dataLines.isEmpty else { return nil }
        return SSEMessage(event: event.isEmpty ? "message" : event, data: dataLines.joined(separator: "\n"), id: id)
    }
}

/// What `GET /v1/events` carries, typed.
public enum LinkEvent: Equatable, Sendable {
    case nav(NavEvent)
    case music(MusicStatus)
    case car(CarStatus)
    case snapshot(UnitSnapshot)
    case other(String)

    public init(message: SSEMessage) {
        let json = (try? JSONSerialization.jsonObject(with: Data(message.data.utf8))) as? [String: Any]
        guard let json else {
            self = .other(message.event)
            return
        }
        switch message.event {
        case "nav":
            if let event = NavEvent(json: json) {
                self = .nav(event)
            } else {
                self = .other(message.event)
            }
        case "music":
            self = .music(MusicStatus(json: json))
        case "car":
            self = .car(CarStatus(json: json))
        case "snapshot", "state":
            self = .snapshot(UnitSnapshot(json: json))
        default:
            self = .other(message.event)
        }
    }
}
