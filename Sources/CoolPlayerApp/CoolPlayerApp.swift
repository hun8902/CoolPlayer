import SwiftUI
import AppKit
import AVFoundation
import Combine

@main
struct CoolPlayerApp: App {
    @StateObject private var settings = AppSettings()
    @StateObject private var store = MusicStore()
    var body: some Scene {
        WindowGroup("Cool Player") {
            ContentView().environmentObject(store).environmentObject(settings)
                .frame(minWidth: 1000, minHeight: 680)
                .preferredColorScheme(settings.appearance.scheme)
                .onAppear { store.settings = settings }
        }
        .defaultSize(width: 1240, height: 820)
        .commands {
            CommandGroup(replacing: .newItem) { Button("음원 추가…") { store.chooseFiles() }.keyboardShortcut("o") }
            CommandMenu("재생") {
                Button(store.isPlaying ? "일시 정지" : "재생") { store.togglePlayback() }.keyboardShortcut("p")
                Button("다음 곡") { store.next() }.keyboardShortcut(.rightArrow)
                Button("이전 곡") { store.previous() }.keyboardShortcut(.leftArrow)
                Divider()
                Button("지금 재생으로 이동") { store.page = .nowPlaying }.keyboardShortcut("2")
            }
        }
        Settings { PreferencesView().environmentObject(settings).environmentObject(store).frame(width: 540, height: 530).preferredColorScheme(settings.appearance.scheme) }
    }
}

enum Page: String, CaseIterable { case library, favorites, recent, nowPlaying, convert, settings
    var title: String { switch self { case .library: return "모든 음악"; case .favorites: return "좋아하는 곡"; case .recent: return "최근 재생"; case .nowPlaying: return "지금 재생"; case .convert: return "음원 변환"; case .settings: return "설정" } }
    var icon: String { switch self { case .library: return "music.note.list"; case .favorites: return "heart"; case .recent: return "clock"; case .nowPlaying: return "waveform"; case .convert: return "arrow.triangle.2.circlepath"; case .settings: return "gearshape" } }
}
struct LyricsResult: Decodable, Identifiable {
    let id: Int; let trackName: String; let artistName: String; let albumName: String?; let duration: Double?; let syncedLyrics: String?; let plainLyrics: String?
    var source: String = "LRCLIB"
    var identity: String { source + String(id) }
    var text: String { syncedLyrics?.isEmpty == false ? syncedLyrics! : plainLyrics ?? "" }
    enum CodingKeys: String, CodingKey { case id, trackName, artistName, albumName, duration, syncedLyrics, plainLyrics }
}

@MainActor
final class MusicStore: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published var tracks: [Track] = []
    @Published var playlists: [Playlist] = []
    @Published var history: [Activity] = []
    @Published var page: Page = .library
    @Published var playlistID: UUID?
    @Published var selection: Set<UUID> = []
    @Published var search = ""
    @Published var currentID: UUID?
    @Published var queue: [UUID] = []
    @Published var isPlaying = false
    @Published var position = 0.0
    @Published var duration = 0.0
    @Published var shuffle = false
    @Published var repeatMode = 0
    @Published var importing = false
    @Published var error: String?
    @Published var notice: String?
    @Published var editing: Track?
    @Published var jobs: [ConversionJob] = []
    @Published var converting = false
    @Published var savingMetadata = false
    @Published var lyricResults: [LyricsResult] = []
    @Published var searchingLyrics = false
    @Published var lyricsTarget: UUID?
    @Published var hasMoreLyrics = false
    @Published var automaticLyricsID: UUID?
    private var lyricPage = 0
    private var lyricTitle = ""
    private var lyricArtist = ""
    private var lyricGeneration = UUID()
    var settings: AppSettings?
    private var player: AVAudioPlayer?
    private var timer: Timer?
    private var volumeObserver: AnyCancellable?
    private var worker: ConversionWorker?
    private var played: [UUID] = []
    nonisolated static let extensions: Set<String> = ["flac", "m4a", "mp3", "wav", "aiff", "aif", "aac", "caf"]
    static let dataFolder: URL = {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CoolPlayer", isDirectory: true)
        let previous = url.deletingLastPathComponent().appendingPathComponent("Soriq", isDirectory: true)
        if !FileManager.default.fileExists(atPath: url.path), FileManager.default.fileExists(atPath: previous.path) {
            try? FileManager.default.copyItem(at: previous, to: url)
        }
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()
    var current: Track? { tracks.first { $0.id == currentID } }
    var lyrics: [LyricLine] { LyricsParser.parse(current?.lyrics ?? "") }
    var visibleTracks: [Track] {
        var result = tracks
        if let id = playlistID, let playlist = playlists.first(where: { $0.id == id }) { result = playlist.trackIDs.compactMap { id in tracks.first { $0.id == id } } }
        else if page == .favorites { result = result.filter(\.favorite) }
        else if page == .recent { result = result.filter { $0.lastPlayed != nil }.sorted { $0.lastPlayed! > $1.lastPlayed! } }
        if !search.isEmpty { result = result.filter { [$0.title, $0.artist, $0.album, $0.url.lastPathComponent].joined(separator: " ").localizedCaseInsensitiveContains(search) } }
        return result
    }
    override init() {
        super.init()
        let url = Self.dataFolder.appendingPathComponent("library.json")
        if let data = try? Data(contentsOf: url), let snapshot = try? JSONDecoder().decode(LibrarySnapshot.self, from: data) {
            tracks = snapshot.tracks; playlists = snapshot.playlists; history = snapshot.history
        }
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in guard let self else { return }; if self.isPlaying { self.position = self.player?.currentTime ?? 0 }; self.player?.volume = Float(self.settings?.volume ?? 0.65) }
        }
    }
    func persist() {
        do { let data = try JSONEncoder().encode(LibrarySnapshot(tracks: tracks, playlists: playlists, history: Array(history.prefix(200)))); try data.write(to: Self.dataFolder.appendingPathComponent("library.json"), options: .atomic) }
        catch { self.error = "라이브러리 저장 실패: \(error.localizedDescription)" }
    }
    func record(_ title: String, _ detail: String) { history.insert(Activity(title: title, detail: detail), at: 0); persist() }
    func chooseFiles() {
        let panel = NSOpenPanel(); panel.canChooseDirectories = true; panel.canChooseFiles = true; panel.allowsMultipleSelection = true
        panel.message = "음원 파일이나 폴더를 추가하세요. 원본 파일은 이동하지 않습니다."
        if panel.runModal() == .OK { importURLs(panel.urls) }
    }
    func importURLs(_ urls: [URL]) {
        guard !importing else { notice = "현재 파일을 추가하는 중입니다. 완료 후 다시 추가하세요."; return }
        importing = true
        let known = Set(tracks.map { $0.url.standardizedFileURL.path })
        Task {
            let added = await Task.detached(priority: .userInitiated) { () -> [Track] in
                var candidates: [URL] = []
                for url in urls {
                    var directory: ObjCBool = false
                    if FileManager.default.fileExists(atPath: url.path, isDirectory: &directory), directory.boolValue {
                        let items = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles, .skipsPackageDescendants])
                        while let file = items?.nextObject() as? URL { if Self.extensions.contains(file.pathExtension.lowercased()) { candidates.append(file) } }
                    } else if Self.extensions.contains(url.pathExtension.lowercased()) { candidates.append(url) }
                }
                var seen = known
                return candidates.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }.compactMap { url in
                    guard seen.insert(url.standardizedFileURL.path).inserted else { return nil }
                    return Self.inspect(url)
                }
            }.value
            tracks.append(contentsOf: added); importing = false; persist()
            notice = added.isEmpty ? "새로 추가할 음원이 없습니다. 지원 형식과 중복 여부를 확인하세요." : "\(added.count)곡을 라이브러리에 추가했습니다."
        }
    }
    nonisolated static func inspect(_ url: URL) -> Track {
        var track = Track(url: url, title: url.deletingPathExtension().lastPathComponent)
        if let data = try? runTool("ffprobe", ["-v", "error", "-show_format", "-show_streams", "-of", "json", url.path]), let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let format = json["format"] as? [String: Any] ?? [:]
            let streams = json["streams"] as? [[String: Any]] ?? []
            let audio = streams.first { $0["codec_type"] as? String == "audio" } ?? [:]
            let tags = ((format["tags"] as? [String: String] ?? [:]).merging(audio["tags"] as? [String: String] ?? [:]) { first, _ in first }).reduce(into: [String: String]()) { $0[$1.key.lowercased()] = $1.value }
            track.title = tags["title"] ?? track.title; track.artist = tags["artist"] ?? ""; track.album = tags["album"] ?? ""
            track.duration = Double(format["duration"] as? String ?? "0") ?? 0
            track.sampleRate = Int(audio["sample_rate"] as? String ?? "0") ?? 0
            track.bitDepth = Int(audio["bits_per_raw_sample"] as? String ?? "0") ?? (audio["bits_per_sample"] as? Int ?? 0)
            track.channels = audio["channels"] as? Int ?? 0; track.codec = audio["codec_name"] as? String ?? ""
            if streams.contains(where: { $0["codec_type"] as? String == "video" }) {
                let cover = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CoolPlayer/\(track.id).jpg")
                if (try? runTool("ffmpeg", ["-v", "error", "-y", "-i", url.path, "-an", "-frames:v", "1", "-vf", "scale=800:800:force_original_aspect_ratio=decrease", cover.path])) != nil { track.artwork = cover }
            }
        }
        if let text = try? String(contentsOf: url.deletingPathExtension().appendingPathExtension("lrc"), encoding: .utf8) { track.lyrics = text }
        return track
    }
    func play(_ track: Track, context: [Track]? = nil) {
        do {
            let newPlayer = try AVAudioPlayer(contentsOf: track.url)
            newPlayer.delegate = self; newPlayer.volume = Float(settings?.volume ?? 0.65); newPlayer.prepareToPlay()
            guard newPlayer.play() else { throw ToolFailure(message: "이 오디오를 재생할 수 없습니다.") }
            player?.stop(); player = newPlayer; currentID = track.id; duration = newPlayer.duration; position = 0; isPlaying = true
            if let context { queue = context.map(\.id) } else if !queue.contains(track.id) { queue = [track.id] }
            if let i = tracks.firstIndex(where: { $0.id == track.id }) { tracks[i].lastPlayed = Date(); persist() }
        } catch { self.error = "\(track.title)\n\(error.localizedDescription)\n파일이 이동되었거나 지원되지 않는 형식인지 확인하세요." }
    }
    func togglePlayback() {
        guard let player else { if let first = visibleTracks.first { play(first, context: visibleTracks) }; return }
        if isPlaying { player.pause(); isPlaying = false } else { isPlaying = player.play() }
    }
    func seek(_ time: Double) { let value = max(0, min(time, duration)); player?.currentTime = value; position = value }
    func next(automatic: Bool = false) {
        guard !queue.isEmpty else { return }
        if automatic && repeatMode == 2 { seek(0); isPlaying = player?.play() ?? false; return }
        if automatic && settings?.autoAdvance == false { player?.pause(); isPlaying = false; return }
        let index = queue.firstIndex(of: currentID ?? UUID()) ?? -1
        let nextID: UUID?
        if shuffle { nextID = queue.filter { $0 != currentID }.randomElement() ?? (repeatMode == 1 ? currentID : nil) }
        else if index + 1 < queue.count { nextID = queue[index + 1] }
        else { nextID = repeatMode == 1 ? queue.first : nil }
        if let nextID, let track = tracks.first(where: { $0.id == nextID }) { if let currentID { played.append(currentID) }; play(track) }
        else { player?.pause(); isPlaying = false }
    }
    func previous() {
        if position > 3 { seek(0); return }
        let id: UUID?
        if shuffle { id = played.popLast() }
        else { let i = queue.firstIndex(of: currentID ?? UUID()) ?? 0; id = i > 0 ? queue[i - 1] : queue.first }
        if let id, let track = tracks.first(where: { $0.id == id }) { play(track) }
    }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { Task { @MainActor in self.isPlaying = false; if flag { self.next(automatic: true) } } }
    func toggleFavorite(_ id: UUID) { if let i = tracks.firstIndex(where: { $0.id == id }) { tracks[i].favorite.toggle(); persist() } }
    func remove(_ ids: Set<UUID>) {
        if let currentID, ids.contains(currentID) { player?.stop(); player = nil; self.currentID = nil; isPlaying = false; position = 0; duration = 0 }
        tracks.removeAll { ids.contains($0.id) }; queue.removeAll { ids.contains($0) }
        for i in playlists.indices { playlists[i].trackIDs.removeAll { ids.contains($0) } }; selection.subtract(ids); persist()
    }
    func makePlaylist(_ name: String) { let clean = name.trimmingCharacters(in: .whitespacesAndNewlines); guard !clean.isEmpty else { return }; let list = Playlist(name: clean, trackIDs: tracks.filter { selection.contains($0.id) }.map(\.id)); playlists.append(list); persist() }
    func addToPlaylist(_ ids: Set<UUID>, _ playlist: UUID) { guard let i = playlists.firstIndex(where: { $0.id == playlist }) else { return }; playlists[i].trackIDs += tracks.filter { ids.contains($0.id) && !playlists[i].trackIDs.contains($0.id) }.map(\.id); persist() }
    func updateTrack(_ value: Track) {
        if let i = tracks.firstIndex(where: { $0.id == value.id }) {
            tracks[i].title = value.title; tracks[i].artist = value.artist; tracks[i].album = value.album
            tracks[i].artwork = value.artwork; tracks[i].lyrics = value.lyrics; persist()
        }
    }
    func saveSourceMetadata(_ track: Track) {
        guard !savingMetadata && !converting else { return }; savingMetadata = true
        Task {
            let result = await Task.detached { () -> Result<Void, Error> in
                let temporary = track.url.deletingLastPathComponent().appendingPathComponent(".soriq-\(UUID()).\(track.url.pathExtension)")
                defer { try? FileManager.default.removeItem(at: temporary) }
                do {
                    var args = ["-v", "error", "-i", track.url.path]
                    if let artwork = track.artwork { args += ["-i", artwork.path, "-map", "0:a:0", "-map", "1:v:0", "-c:a", "copy", "-c:v", "mjpeg", "-disposition:v", "attached_pic"] }
                    else { args += ["-map", "0", "-c", "copy"] }
                    args += ["-metadata", "title=\(track.title)", "-metadata", "artist=\(track.artist)", "-metadata", "album=\(track.album)", temporary.path]
                    _ = try runTool("ffmpeg", args)
                    let backup = unusedOutput(in: track.url.deletingLastPathComponent(), name: track.url.lastPathComponent + ".backup", ext: track.url.pathExtension)
                    try FileManager.default.copyItem(at: track.url, to: backup)
                    _ = try FileManager.default.replaceItemAt(track.url, withItemAt: temporary)
                    return .success(())
                } catch { return .failure(error) }
            }.value
            savingMetadata = false
            switch result { case .success: updateTrack(track); record("음원 정보 저장", "\(track.title) · 원본 백업 생성"); notice = "음원 정보를 저장했습니다. 원본 폴더에 백업을 남겼습니다."; case .failure(let error): self.error = error.localizedDescription }
        }
    }
    func searchLyrics(_ track: Track, query: String? = nil, artist: String? = nil) {
        guard automaticLyricsID == nil else { return }
        lyricTitle = LyricsService.clean(query ?? track.title)
        lyricArtist = LyricsService.clean(artist ?? track.artist)
        guard !lyricTitle.isEmpty || !lyricArtist.isEmpty else { return }
        lyricGeneration = UUID(); lyricsTarget = track.id; lyricResults = []; lyricPage = 0
        searchingLyrics = false; hasMoreLyrics = true; notice = nil
        loadMoreLyrics()
    }
    func loadMoreLyrics() {
        guard !searchingLyrics, hasMoreLyrics else { return }
        searchingLyrics = true
        let generation = lyricGeneration, title = lyricTitle, artist = lyricArtist, page = lyricPage
        Task {
            let batch = await LyricsService.search(title: title, artist: artist, page: page)
            guard generation == lyricGeneration else { return }
            let known = Set(lyricResults.map(\.identity))
            let added = batch.results.filter { !known.contains($0.identity) }
            lyricResults.append(contentsOf: added)
            lyricPage += 1; searchingLyrics = false
            hasMoreLyrics = batch.more && !added.isEmpty
            notice = "검색 결과 \(lyricResults.count)개" + (hasMoreLyrics ? " · 아래로 내려 추가 결과 보기" : " · 마지막 결과")
            if !batch.failures.isEmpty { notice! += " · " + batch.failures.joined(separator: " / ") }
            if lyricResults.isEmpty && batch.failures.isEmpty { notice = "일치하는 가사가 없습니다. 곡명 일부로 검색하거나 가수 조건을 지워보세요." }
        }
    }
    func automaticLyrics(_ track: Track) {
        guard automaticLyricsID == nil else { return }
        automaticLyricsID = track.id; lyricGeneration = UUID(); searchingLyrics = false
        lyricsTarget = track.id; notice = "곡 정보로 가사를 찾고 있습니다…"
        Task {
            defer { automaticLyricsID = nil }
            let batch = await LyricsService.search(title: track.title, artist: track.artist, page: 0)
            lyricResults = batch.results; hasMoreLyrics = false
            let matches = batch.results.filter { LyricsService.matches($0, track: track) }
                .sorted { ($0.syncedLyrics?.isEmpty == false ? 1 : 0) > ($1.syncedLyrics?.isEmpty == false ? 1 : 0) }
            guard !matches.isEmpty else {
                notice = batch.results.isEmpty ? "가사를 찾지 못했습니다. 검색어를 직접 바꾸거나 LRC 파일을 가져오세요." : "일치 여부를 확인할 후보를 찾았습니다. 정보 · 가사에서 선택하세요."
                if !batch.failures.isEmpty { notice! += " " + batch.failures.joined(separator: " / ") }
                return
            }
            do {
                var lyric = ""
                var fallback = ""
                var failures: [String] = []
                for result in matches.prefix(8) {
                    do {
                        let candidate = try await LyricsService.resolve(result)
                        if !LyricsParser.parse(candidate).isEmpty { lyric = candidate; break }
                        if fallback.isEmpty { fallback = candidate }
                    } catch { failures.append(result.source + ": " + error.localizedDescription) }
                }
                if lyric.isEmpty { lyric = fallback }
                guard !lyric.isEmpty else { notice = "일치하는 곡의 가사를 가져오지 못했습니다. " + failures.prefix(2).joined(separator: " / "); return }
                var updated = tracks.first(where: { $0.id == track.id }) ?? track
                updated.lyrics = lyric
                let ext = LyricsParser.parse(lyric).isEmpty ? "txt" : "lrc"
                let destination = track.url.deletingPathExtension().appendingPathExtension(ext)
                if FileManager.default.fileExists(atPath: destination.path) {
                    let backup = unusedOutput(in: destination.deletingLastPathComponent(), name: destination.lastPathComponent + ".backup", ext: ext)
                    try FileManager.default.copyItem(at: destination, to: backup)
                }
                try lyric.write(to: destination, atomically: true, encoding: .utf8)
                updateTrack(updated); record("가사 자동 가져오기", destination.lastPathComponent)
                notice = "가사를 적용하고 \(destination.lastPathComponent)을 음원 폴더에 저장했습니다."
            } catch { notice = "가사 파일 저장 실패: \(error.localizedDescription)" }
        }
    }
    func addConversion(_ items: [Track]) { guard !converting else { notice = "변환 완료 후 대기열을 변경할 수 있습니다."; return }; for track in items where !jobs.contains(where: { $0.track.id == track.id }) { jobs.append(ConversionJob(track: track)) }; page = .convert; playlistID = nil }
    func convert() {
        guard !converting, !savingMetadata, !jobs.isEmpty else { return }
        let preset = settings?.preset ?? .original; let folder = settings?.outputFolder ?? ""; let autoImport = settings?.importConverted ?? true
        let targets = jobs; let runner = ConversionWorker(); worker = runner; converting = true
        for i in jobs.indices { jobs[i].progress = 0; jobs[i].status = "대기"; jobs[i].error = nil; jobs[i].output = nil }
        Task {
            for job in targets {
                if runner.isCancelled { break }
                if let i = jobs.firstIndex(where: { $0.id == job.id }) { jobs[i].status = "변환 중" }
                let result = await Task.detached(priority: .userInitiated) {
                    runner.convert(job.track, preset: preset, folder: folder) { progress in
                        Task { @MainActor in if let i = self.jobs.firstIndex(where: { $0.id == job.id }) { self.jobs[i].progress = progress } }
                    }
                }.value
                guard let i = jobs.firstIndex(where: { $0.id == job.id }) else { continue }
                switch result {
                case .success(let output):
                    jobs[i].status = "완료"; jobs[i].progress = 1; jobs[i].output = output
                    record("ALAC 변환 완료", output.lastPathComponent)
                    if autoImport { var converted = job.track; converted.id = UUID(); converted.url = output; converted.codec = "alac"; converted.added = Date(); converted.lastPlayed = nil
                        if preset != .original { converted.bitDepth = preset == .cd ? 16 : 24; converted.sampleRate = Int(preset.arguments.last ?? "0") ?? 0 }
                        tracks.append(converted); persist()
                    }
                case .failure(let error): jobs[i].status = runner.isCancelled ? "취소됨" : "실패"; jobs[i].error = error.localizedDescription; record(jobs[i].status, "\(job.track.title) · \(error.localizedDescription)")
                }
            }
            for i in jobs.indices where jobs[i].status == "대기" { jobs[i].status = "취소됨" }
            converting = false; worker = nil
        }
    }
    func cancelConversion() { worker?.cancel() }
}

final class ConversionWorker: @unchecked Sendable {
    private let lock = NSLock(); private var cancelled = false; private var process: Process?
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
    func cancel() { lock.lock(); cancelled = true; let active = process; lock.unlock(); if active?.isRunning == true { active?.terminate() } }
    func convert(_ track: Track, preset: AudioPreset, folder: String, progress: @escaping (Double) -> Void) -> Result<URL, Error> {
        let directory = folder.isEmpty ? track.url.deletingLastPathComponent() : URL(fileURLWithPath: folder)
        let destination = unusedOutput(in: directory, name: track.url.deletingPathExtension().lastPathComponent + "_ALAC", ext: "m4a")
        let temporary = directory.appendingPathComponent(".soriq-\(UUID()).m4a")
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).log")
        defer { try? FileManager.default.removeItem(at: temporary); try? FileManager.default.removeItem(at: log); lock.lock(); process = nil; lock.unlock() }
        do {
            guard preset != .original || track.bitDepth <= 24 else { throw ToolFailure(message: "ALAC은 최대 24-bit를 지원합니다. 24-bit 출력 규격을 선택해주세요.") }
            let task = Process(); task.executableURL = toolURL("ffmpeg")
            var args = ["-v", "error", "-nostdin", "-nostats", "-i", track.url.path]
            if let cover = track.artwork { args += ["-i", cover.path, "-map", "0:a:0", "-map", "1:v:0"] }
            else { args += ["-map", "0:a:0", "-map", "0:v?"] }
            args += ["-c:a", "alac", "-c:v", "mjpeg", "-disposition:v", "attached_pic"] + preset.arguments
            args += ["-metadata", "title=\(track.title)", "-metadata", "artist=\(track.artist)", "-metadata", "album=\(track.album)", "-progress", "pipe:1", temporary.path]
            task.arguments = args; let pipe = Pipe(); task.standardOutput = pipe
            FileManager.default.createFile(atPath: log.path, contents: nil); let errors = try FileHandle(forWritingTo: log); task.standardError = errors; defer { try? errors.close() }
            lock.lock()
            if cancelled { lock.unlock(); throw ToolFailure(message: "사용자가 변환을 취소했습니다.") }
            process = task
            do { try task.run(); lock.unlock() } catch { lock.unlock(); throw error }
            var buffer = ""
            while true {
                let data = pipe.fileHandleForReading.availableData; if data.isEmpty { break }
                buffer += String(decoding: data, as: UTF8.self)
                while let newline = buffer.firstIndex(of: "\n") {
                    let line = String(buffer[..<newline]); buffer.removeSubrange(...newline)
                    if line.hasPrefix("out_time_us="), let value = Double(line.dropFirst(12)), track.duration > 0 { progress(min(0.99, value / 1_000_000 / track.duration)) }
                }
            }
            task.waitUntilExit()
            if isCancelled { throw ToolFailure(message: "사용자가 변환을 취소했습니다.") }
            guard task.terminationStatus == 0 else { throw ToolFailure(message: String((try? String(contentsOf: log, encoding: .utf8))?.suffix(1800) ?? "변환 실패")) }
            try FileManager.default.moveItem(at: temporary, to: destination)
            return .success(destination)
        } catch { return .failure(error) }
    }
}

// Search providers keep their own paging semantics. LRCLIB has no page cursor;
// ALSong's nCurPage is advanced only after a successful response with new rows.
enum LyricsService {
    struct Batch { var results: [LyricsResult]; var more: Bool; var failures: [String] }
    static func clean(_ value: String) -> String { value.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping }
    static func key(_ value: String) -> String { clean(value).lowercased().filter { $0.isLetter || $0.isNumber } }
    static func artistKeys(_ value: String) -> Set<String> {
        let k = key(value)
        if ["10cm", "십센치"].contains(k) { return ["10cm", "십센치"] }
        if ["iu", "아이유"].contains(k) { return ["iu", "아이유"] }
        return [k]
    }
    static func matches(_ result: LyricsResult, track: Track) -> Bool {
        guard key(result.trackName) == key(track.title),
              !artistKeys(result.artistName).isDisjoint(with: artistKeys(track.artist)) else { return false }
        if let duration = result.duration, duration > 0, track.duration > 0 { return abs(duration - track.duration) < 12 }
        return true
    }
    static func search(title: String, artist: String, page: Int) async -> Batch {
        async let domestic = bugs(title: clean(title), artist: clean(artist), page: page)
        async let international = lrclib(title: clean(title), artist: clean(artist), enabled: page == 0)
        async let catalogue = netease(title: clean(title), artist: clean(artist), page: page)
        let a = await domestic, b = await international, c = await catalogue
        var seen = Set<String>()
        let results = (a.results + b.results + c.results).filter { (!$0.text.isEmpty || $0.source == "NetEase" || $0.source == "벅스") && seen.insert($0.identity).inserted }
        return Batch(results: results, more: a.more || c.more, failures: a.failures + b.failures + c.failures)
    }
    static func request(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode) else { throw ToolFailure(message: "서비스 응답 오류") }
        return data
    }
    static func lrclib(title: String, artist: String, enabled: Bool) async -> Batch {
        guard enabled else { return Batch(results: [], more: false, failures: []) }
        var queries: [[URLQueryItem]] = []
        if !title.isEmpty && !artist.isEmpty { queries.append([.init(name: "track_name", value: title), .init(name: "artist_name", value: artist)]) }
        queries.append([.init(name: "q", value: title.isEmpty ? artist : title)])
        var results: [LyricsResult] = []; var succeeded = false
        for query in queries {
            do {
                var url = URLComponents(string: "https://lrclib.net/api/search")!; url.queryItems = query
                var req = URLRequest(url: url.url!); req.timeoutInterval = 15
                req.setValue("CoolPlayer/2.1", forHTTPHeaderField: "User-Agent")
                let data = try await request(req)
                results += try JSONDecoder().decode([LyricsResult].self, from: data); succeeded = true
            } catch { }
        }
        return Batch(results: results, more: false, failures: succeeded ? [] : ["LRCLIB 연결 실패"])
    }
    static func netease(title: String, artist: String, page: Int) async -> Batch {
        do {
            let limit = 20
            var url = URLComponents(string: "https://music.163.com/api/cloudsearch/pc")!
            url.queryItems = [.init(name: "s", value: [title, artist].filter { !$0.isEmpty }.joined(separator: " ")), .init(name: "type", value: "1"), .init(name: "limit", value: String(limit)), .init(name: "offset", value: String(page * limit))]
            var req = URLRequest(url: url.url!); req.timeoutInterval = 12
            req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
            let data = try await request(req)
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any], (json["code"] as? Int) == 200 else { throw ToolFailure(message: "검색 서비스 응답 오류") }
            let body = json["result"] as? [String: Any] ?? [:]
            let songs = body["songs"] as? [[String: Any]] ?? []
            let items: [LyricsResult] = songs.compactMap { song in
                guard let id = song["id"] as? Int, let name = song["name"] as? String else { return nil }
                let artists = (song["ar"] ?? song["artists"]) as? [[String: Any]] ?? []
                let album = (song["al"] ?? song["album"]) as? [String: Any]
                let duration = (song["dt"] ?? song["duration"]) as? Double
                return LyricsResult(id: id, trackName: name, artistName: artists.compactMap { $0["name"] as? String }.joined(separator: ", "), albumName: album?["name"] as? String, duration: duration.map { $0 / 1000 }, syncedLyrics: nil, plainLyrics: nil, source: "NetEase")
            }
            return Batch(results: items, more: !songs.isEmpty && (page + 1) * limit < (body["songCount"] as? Int ?? 0), failures: [])
        } catch { return Batch(results: [], more: false, failures: ["NetEase 연결 실패"]) }
    }
    static func resolve(_ result: LyricsResult) async throws -> String {
        if result.source == "벅스" { return try await bugsLyrics(id: result.id) }
        if result.source != "NetEase" { return result.text }
        var url = URLComponents(string: "https://music.163.com/api/song/lyric")!
        url.queryItems = [.init(name: "id", value: String(result.id)), .init(name: "lv", value: "-1"), .init(name: "kv", value: "-1"), .init(name: "tv", value: "-1")]
        var req = URLRequest(url: url.url!); req.timeoutInterval = 12
        let data = try await request(req)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any], (json["code"] as? Int) == 200 else { throw ToolFailure(message: "가사를 불러오지 못했습니다.") }
        return (json["lrc"] as? [String: Any])?["lyric"] as? String ?? ""
    }
    static func captures(_ pattern: String, in text: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).map { match in
            (0..<match.numberOfRanges).map { index in Range(match.range(at: index), in: text).map { String(text[$0]) } ?? "" }
        }
    }
    static func htmlText(_ text: String) -> String {
        var result = text.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for entity in captures("&#(x[0-9a-f]+|[0-9]+);", in: result) {
            let hex = entity[1].lowercased().hasPrefix("x")
            if let number = UInt32(hex ? String(entity[1].dropFirst()) : entity[1], radix: hex ? 16 : 10), let scalar = UnicodeScalar(number) {
                result = result.replacingOccurrences(of: entity[0], with: String(scalar))
            }
        }
        for (encoded, decoded) in [("&quot;", "\""), ("&#39;", "'"), ("&apos;", "'"), ("&nbsp;", " "), ("&lt;", "<"), ("&gt;", ">"), ("&amp;", "&")] { result = result.replacingOccurrences(of: encoded, with: decoded) }
        return clean(result)
    }
    static func bugs(title: String, artist: String, page: Int) async -> Batch {
        do {
            var url = URLComponents(string: "https://music.bugs.co.kr/search/track")!
            url.queryItems = [.init(name: "q", value: [title, artist].filter { !$0.isEmpty }.joined(separator: " ")), .init(name: "target", value: "ARTIST_TRACK_ALBUM"), .init(name: "page", value: String(page + 1))]
            var req = URLRequest(url: url.url!); req.timeoutInterval = 12
            let data = try await request(req)
            guard let html = String(data: data, encoding: .utf8), html.contains("music.bugs.co.kr") else { throw ToolFailure(message: "검색 응답 해석 실패") }
            let rows = captures(#"<tr\b[^>]*trackId="([0-9]+)"[^>]*>(.*?)</tr>"#, in: html)
            let results: [LyricsResult] = rows.compactMap { row in
                guard let id = Int(row[1]), let name = captures(#"track_title="([^"]*)""#, in: row[2]).first?[1], let artist = captures(#"artist_disp_nm="([^"]*)""#, in: row[2]).first?[1] else { return nil }
                let album = captures(#"class="album"[^>]*title="([^"]*)""#, in: row[2]).first?[1]
                return LyricsResult(id: id, trackName: htmlText(name), artistName: htmlText(artist), albumName: album.map(htmlText), duration: nil, syncedLyrics: nil, plainLyrics: nil, source: "벅스")
            }
            let pages = captures(#"retriveTrackPage\(([0-9]+)\)"#, in: html).compactMap { Int($0[1]) }
            return Batch(results: results, more: !results.isEmpty && pages.contains(where: { $0 > page + 1 }), failures: [])
        } catch { return Batch(results: [], more: false, failures: ["벅스 연결 실패"]) }
    }
    static func bugsLyrics(id: Int) async throws -> String {
        var lastError: Error?
        for kind in ["T", "N"] {
            do {
                var req = URLRequest(url: URL(string: "https://music.bugs.co.kr/player/lyrics/\(kind)/\(id)")!); req.timeoutInterval = 12
                let data = try await request(req)
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                guard let raw = json?["lyrics"] as? String, !clean(raw).isEmpty else { continue }
                if kind == "N" { return htmlText(raw.replacingOccurrences(of: "＃", with: "\n")) }
                let rows = raw.replacingOccurrences(of: "＃", with: "\n").components(separatedBy: .newlines)
                let timed: [String] = rows.compactMap { row in
                    let pair = row.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
                    guard pair.count == 2, let seconds = Double(pair[0]), seconds.isFinite, seconds >= 0, seconds < 86400 else { return nil }
                    return String(format: "[%02d:%05.2f]", Int(seconds) / 60, seconds.truncatingRemainder(dividingBy: 60)) + htmlText(String(pair[1]))
                }
                if !timed.isEmpty { return timed.joined(separator: "\n") }
            } catch { lastError = error }
        }
        if let lastError { throw lastError }
        return ""
    }
}
