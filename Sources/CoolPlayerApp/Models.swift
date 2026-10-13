import Foundation

struct Track: Identifiable, Codable, Hashable {
    var id = UUID()
    var url: URL
    var title: String
    var artist = ""
    var album = ""
    var duration: Double = 0
    var sampleRate = 0
    var bitDepth = 0
    var channels = 0
    var codec = ""
    var artwork: URL?
    var lyrics = ""
    var favorite = false
    var added = Date()
    var lastPlayed: Date?
    var subtitle: String { artist.isEmpty ? "알 수 없는 아티스트" : artist }
    var format: String {
        [codec.isEmpty ? url.pathExtension.uppercased() : codec.uppercased(), bitDepth > 0 ? "\(bitDepth)-bit" : nil, sampleRate > 0 ? String(format: "%g kHz", Double(sampleRate) / 1000) : nil].compactMap { $0 }.joined(separator: " · ")
    }
}
struct Playlist: Identifiable, Codable { var id = UUID(); var name: String; var trackIDs: [UUID] = [] }
struct Activity: Identifiable, Codable { var id = UUID(); var date = Date(); var title: String; var detail: String }
struct LibrarySnapshot: Codable { var tracks: [Track]; var playlists: [Playlist]; var history: [Activity] }
struct LyricLine: Identifiable, Equatable { var id: Int; var time: Double; var text: String }
enum LyricsParser {
    static func parse(_ text: String) -> [LyricLine] {
        let timePattern = try! NSRegularExpression(pattern: #"\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?\]"#)
        let offsetPattern = try! NSRegularExpression(pattern: #"\[offset:([+-]?\d+)\]"#, options: .caseInsensitive)
        let ns = text as NSString
        var offset = 0.0
        if let match = offsetPattern.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) {
            offset = (Double(ns.substring(with: match.range(at: 1))) ?? 0) / 1000
        }
        var result: [LyricLine] = []
        for line in text.components(separatedBy: .newlines) {
            let string = line as NSString
            let matches = timePattern.matches(in: line, range: NSRange(location: 0, length: string.length))
            guard let last = matches.last else { continue }
            let words = string.substring(from: last.range.location + last.range.length).trimmingCharacters(in: .whitespaces)
            for match in matches {
                let minute = Double(string.substring(with: match.range(at: 1))) ?? 0
                let second = Double(string.substring(with: match.range(at: 2))) ?? 0
                guard second < 60 else { continue }
                let fraction = match.range(at: 3).location == NSNotFound ? 0 : (Double("0." + string.substring(with: match.range(at: 3))) ?? 0)
                result.append(LyricLine(id: result.count, time: max(0, minute * 60 + second + fraction + offset), text: words))
            }
        }
        return result.sorted { $0.time == $1.time ? $0.id < $1.id : $0.time < $1.time }.enumerated().map { LyricLine(id: $0.offset, time: $0.element.time, text: $0.element.text) }
    }
    static func activeIndex(_ lines: [LyricLine], at time: Double) -> Int? { lines.lastIndex { $0.time <= time } }
}
enum AudioPreset: String, CaseIterable, Identifiable {
    case original, cd, standard, high, maximum
    var id: String { rawValue }
    var title: String { switch self { case .original: return "원본 해상도 유지"; case .cd: return "16-bit / 44.1 kHz"; case .standard: return "24-bit / 48 kHz"; case .high: return "24-bit / 96 kHz"; case .maximum: return "24-bit / 192 kHz" } }
    var arguments: [String] { switch self { case .original: return []; case .cd: return ["-sample_fmt", "s16p", "-ar", "44100"]; case .standard: return ["-sample_fmt", "s32p", "-ar", "48000"]; case .high: return ["-sample_fmt", "s32p", "-ar", "96000"]; case .maximum: return ["-sample_fmt", "s32p", "-ar", "192000"] } }
}
struct ConversionJob: Identifiable { var id = UUID(); var track: Track; var progress = 0.0; var status = "대기"; var output: URL?; var error: String? }
func clockText(_ seconds: Double) -> String { let n = max(0, Int(seconds.isFinite ? seconds : 0)); return String(format: "%d:%02d", n / 60, n % 60) }
func toolURL(_ name: String) -> URL {
    let candidates = [Bundle.main.resourceURL?.appendingPathComponent(name).path, "/opt/homebrew/bin/\(name)", "/usr/local/bin/\(name)"].compactMap { $0 }
    return URL(fileURLWithPath: candidates.first { FileManager.default.isExecutableFile(atPath: $0) } ?? candidates.last!)
}
struct ToolFailure: LocalizedError { let message: String; var errorDescription: String? { message } }
/// Stdout is drained before waiting; stderr goes to a file so neither pipe can fill and deadlock.
func runTool(_ name: String, _ args: [String]) throws -> Data {
    let process = Process(); process.executableURL = toolURL(name); process.arguments = args
    let pipe = Pipe(); process.standardOutput = pipe
    let log = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".log")
    FileManager.default.createFile(atPath: log.path, contents: nil)
    let handle = try FileHandle(forWritingTo: log); process.standardError = handle
    defer { try? handle.close(); try? FileManager.default.removeItem(at: log) }
    try process.run(); let data = pipe.fileHandleForReading.readDataToEndOfFile(); process.waitUntilExit()
    guard process.terminationStatus == 0 else { throw ToolFailure(message: String((try? String(contentsOf: log, encoding: .utf8))?.suffix(1800) ?? "오디오 처리에 실패했습니다.")) }
    return data
}
func unusedOutput(in folder: URL, name: String, ext: String) -> URL {
    var result = folder.appendingPathComponent(name).appendingPathExtension(ext); var n = 2
    while FileManager.default.fileExists(atPath: result.path) { result = folder.appendingPathComponent("\(name) (\(n))").appendingPathExtension(ext); n += 1 }
    return result
}
