import AppKit
import AVFoundation
import SwiftUI
import UniformTypeIdentifiers

// MARK: - 소스 읽는 순서

// Xcode를 처음 볼 때는 아래 순서로 읽으면 됩니다.
// 1. ContentView: 앱 전체 화면의 가장 큰 틀입니다. 왼쪽 파일 목록과 오른쪽 작업 화면을 배치합니다.
// 2. AudioJob: FLAC 파일 1개의 상태를 저장합니다. 파일명, 음원 정보, 앨범 커버, 변환 진행률이 들어갑니다.
// 3. OutputPreset: 사용자가 고르는 출력 품질입니다. ALAC 16/44.1, 24/48, 24/192 같은 값을 정의합니다.
// 4. Sidebar: 왼쪽 파일 리스트입니다. 파일 추가, 선택, 제거, 드래그 추가가 여기서 동작합니다.
// 5. WorkbenchView: 오른쪽 탭 화면입니다. 변환, 정보, 가사, 기록, 앱 정보 탭을 바꿔 보여줍니다.
// 6. PrimaryConversionPanel: 변환 탭 UI입니다. 출력 설정, 변환 대상, 진행 상태를 보여줍니다.
// 7. LyricsView: 가사 검색, LRC 편집, LRC 저장을 담당합니다.
// 8. 아래쪽 함수들: ffprobe 분석, ffmpeg 변환, 앨범 커버 추출, iTunes 커버 검색 같은 실제 작업입니다.

// MARK: - 탭

/// 앱의 실제 작업 화면입니다.
///
/// 이번 버전의 핵심은 "파일 추가 위치를 하나로 고정"하는 것입니다.
/// 파일은 왼쪽 파일 리스트에서만 추가하고, 각 탭은 선택된 파일을 기준으로 정보/가사/변환을 보여줍니다.
enum StudioTab: String, CaseIterable, Identifiable {
    case convert
    case details
    case lyrics
    case history
    case settings

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .convert: return "arrow.triangle.2.circlepath"
        case .details: return "info.circle"
        case .lyrics: return "text.quote"
        case .history: return "clock.arrow.circlepath"
        case .settings: return "slider.horizontal.3"
        }
    }

    var title: String {
        switch self {
        case .convert: return "변환"
        case .details: return "상세 정보"
        case .lyrics: return "LRC 가사"
        case .history: return "히스토리"
        case .settings: return "설정"
        }
    }
}

// MARK: - 출력 프리셋

/// 사용자가 고를 수 있는 ALAC 출력 품질입니다.
///
/// 숫자만 보여주면 의미가 약하므로, 실제 음악 서비스에서 이해하기 쉬운 이름을 같이 씁니다.
/// ffmpeg 변환 시에는 `sampleRate`와 `sampleFormat` 값을 인자로 사용합니다.
enum OutputPreset: String, CaseIterable, Identifiable {
    case cd
    case appleLossless
    case hiRes
    case studio
    case keepSource

    var id: String { rawValue }

    var title: String {
        switch self {
        case .cd: return "CD급 무손실"
        case .appleLossless: return "Apple Music 무손실급"
        case .hiRes: return "Apple Hi-Res Lossless급"
        case .studio: return "스튜디오 마스터급"
        case .keepSource: return "원본 유지"
        }
    }

    var shortTitle: String {
        switch self {
        case .cd: return "ALAC 16/44.1"
        case .appleLossless: return "ALAC 24/48"
        case .hiRes: return "ALAC 24/192"
        case .studio: return "ALAC 24/96"
        case .keepSource: return "ALAC 원본"
        }
    }

    var outputDescription: String {
        switch self {
        case .cd: return "ALAC 16-bit / 44.1kHz (.m4a)"
        case .appleLossless: return "ALAC 24-bit / 48kHz (.m4a)"
        case .hiRes: return "ALAC 24-bit / 192kHz (.m4a)"
        case .studio: return "ALAC 24-bit / 96kHz (.m4a)"
        case .keepSource: return "ALAC 원본 해상도 유지 (.m4a)"
        }
    }

    var detail: String {
        switch self {
        case .cd: return "CD와 같은 기준입니다. 용량이 가장 작습니다."
        case .appleLossless: return "일반적인 Apple Music 무손실 음원 기준에 가깝습니다."
        case .hiRes: return "Apple Hi-Res Lossless 최대급 기준입니다."
        case .studio: return "음질과 용량 균형이 좋은 스튜디오 마스터 기준입니다."
        case .keepSource: return "원본 bit depth와 sample rate를 최대한 유지합니다."
        }
    }

    var sampleRate: Int? {
        switch self {
        case .cd: return 44_100
        case .appleLossless: return 48_000
        case .studio: return 96_000
        case .hiRes: return 192_000
        case .keepSource: return nil
        }
    }

    var sampleFormat: String? {
        switch self {
        case .cd: return "s16p"
        case .appleLossless, .hiRes, .studio: return "s32p"
        case .keepSource: return nil
        }
    }

    var filenameSuffix: String {
        shortTitle
            .replacingOccurrences(of: "ALAC ", with: "")
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: " ", with: "_")
    }
}

// MARK: - 데이터 모델

/// 왼쪽 파일 리스트에 들어가는 FLAC 파일 1개입니다.
struct AudioJob: Identifiable {
    let id = UUID()
    let url: URL
    var isSelected = true
    var sourceDescription = "FLAC / 분석 대기"
    var outputDescription = OutputPreset.hiRes.outputDescription
    var status = "대기 중"
    var progress = 0.0
    var duration = "-"
    var durationSeconds = 0.0
    var channels = "-"
    var title = ""
    var artist = ""
    var album = ""
    var artworkStatus = "확인 전"
    var artworkURL: URL?
    var outputURL: URL?

    var filename: String { url.lastPathComponent }

    /// 가사 검색에 사용할 자동 검색어입니다.
    ///
    /// 1순위는 오디오 태그의 아티스트 + 곡명이고, 태그가 없으면 파일명을 정리해서 사용합니다.
    var lyricsSearchQuery: String {
        let taggedQuery = [artist, title]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")

        if !taggedQuery.isEmpty {
            return taggedQuery
        }

        return url.deletingPathExtension().lastPathComponent
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// ffprobe 결과 중 화면에 필요한 값만 정리한 모델입니다.
struct AudioProbeResult {
    let codec: String
    let bitDepth: String
    let sampleRate: String
    let channels: String
    let duration: String
    let durationSeconds: Double
    let title: String
    let artist: String
    let album: String
    let hasArtwork: Bool

    var sourceDescription: String {
        "\(codec) / \(bitDepth) / \(sampleRate)"
    }

    var artworkStatus: String {
        hasArtwork ? "앨범 커버 있음" : "앨범 커버 없음"
    }
}

/// LRCLIB 공개 API 응답입니다.
///
/// `syncedLyrics`가 있으면 LRC 실시간 가사로 바로 사용할 수 있습니다.
/// 없으면 일반 가사만 가져와 사용자가 직접 타임스탬프를 넣을 수 있게 합니다.
struct LRCLyricsResult: Decodable {
    let trackName: String?
    let artistName: String?
    let albumName: String?
    let syncedLyrics: String?
    let plainLyrics: String?
}

struct ITunesSearchResponse: Decodable {
    let resultCount: Int
    let results: [ITunesTrack]
}

struct ITunesTrack: Decodable {
    let artistName: String?
    let collectionName: String?
    let trackName: String?
    let artworkUrl100: String?
}

// MARK: - 메인 화면

struct ContentView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var jobs: [AudioJob] = []
    @State private var selectedJobID: UUID?
    @State private var outputPreset: OutputPreset = .hiRes
    @State private var outputFolder: URL?
    @State private var historyItems: [HistoryItem] = []
    @State private var playbackURL: URL?
    @State private var playbackProgress = 0.0
    @State private var isFileDropTargeted = false

    var body: some View {
        HStack(spacing: 0) {
            Sidebar(
                jobs: $jobs,
                selectedJobID: $selectedJobID,
                isDropTargeted: $isFileDropTargeted,
                addFiles: chooseInputFiles,
                handleDrop: handleFileDrop
            )
            .frame(width: 520)

            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 18) {
                    mainContent
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.horizontal, 30)
                .padding(.top, 24)
                .padding(.bottom, 18)

                PlayerBar(progress: $playbackProgress, playbackURL: playbackURL)
            }
        }
        .background(AppBackground())
        .preferredColorScheme(settings.theme.colorScheme)
    }

    @ViewBuilder
    private var mainContent: some View {
        WorkbenchView(
            jobs: jobs,
            selectedJob: selectedJob,
            outputPreset: $outputPreset,
            outputFolder: outputFolder,
            historyItems: historyItems,
            startConversion: convertSelectedJobs,
            chooseOutputFolder: chooseOutputFolder,
            resetOutputFolder: { outputFolder = nil },
            updateMetadata: updateSelectedMetadata,
            chooseArtwork: chooseArtworkForSelectedJob,
            saveMetadata: saveSelectedMetadata
        )
    }

    private var selectedJob: AudioJob? {
        jobs.first(where: { $0.id == selectedJobID }) ?? jobs.first
    }

    private var selectedJobs: [AudioJob] {
        jobs.filter(\.isSelected)
    }

    /// 왼쪽 파일 리스트의 + 버튼에서만 파일을 추가합니다.
    ///
    /// 이 앱에서 파일 추가 진입점은 오직 하나입니다. 변환 탭에는 파일 선택 버튼을 두지 않습니다.
    private func chooseInputFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        panel.allowedContentTypes = [
            UTType(filenameExtension: "flac") ?? .audio,
            .folder
        ]

        if panel.runModal() == .OK {
            addURLs(panel.urls)
        }
    }

    /// 왼쪽 파일 리스트 영역으로 드래그한 파일/폴더를 추가합니다.
    ///
    /// 드래그 추가도 왼쪽 파일 리스트에서만 동작합니다.
    private func handleFileDrop(_ providers: [NSItemProvider]) -> Bool {
        for provider in providers {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                guard let data = item as? Data,
                      let url = URL(dataRepresentation: data, relativeTo: nil) else {
                    return
                }

                DispatchQueue.main.async {
                    addURLs([url])
                }
            }
        }
        return true
    }

    /// 파일과 폴더 URL에서 FLAC 파일만 골라 작업 목록에 넣습니다.
    private func addURLs(_ urls: [URL]) {
        let flacFiles = urls.flatMap { url -> [URL] in
            if url.hasDirectoryPath {
                return findFLACFiles(in: url)
            }
            return url.pathExtension.lowercased() == "flac" ? [url] : []
        }

        let existingPaths = Set(jobs.map { $0.url.path(percentEncoded: false) })
        let newJobs = flacFiles
            .filter { !existingPaths.contains($0.path(percentEncoded: false)) }
            .map { url in
                AudioJob(
                    url: url,
                    outputDescription: outputPreset.outputDescription,
                    status: "분석 중"
                )
            }

        guard !newJobs.isEmpty else {
            return
        }

        jobs.append(contentsOf: newJobs)
        selectedJobID = newJobs.first?.id

        for job in newJobs {
            analyze(jobID: job.id, url: job.url)
        }
    }

    /// 선택된 파일만 ALAC로 변환합니다.
    ///
    /// 사용자는 왼쪽 파일 리스트의 체크박스로 변환 대상을 정합니다.
    private func convertSelectedJobs() {
        let targets = selectedJobs.map(\.id)
        guard !targets.isEmpty else {
            NSSound.beep()
            return
        }

        DispatchQueue.global(qos: .userInitiated).async {
            for id in targets {
                guard let job = currentJob(id: id) else {
                    continue
                }

                guard FileManager.default.fileExists(atPath: job.url.path(percentEncoded: false)) else {
                    updateJob(id) { job in
                        job.status = "파일 없음"
                        job.progress = 0
                    }
                    continue
                }

                let destination = outputURL(for: job.url)
                updateJob(id) { job in
                    job.status = "변환 중"
                    job.progress = 0.18
                    job.outputDescription = outputPreset.outputDescription
                    job.outputURL = destination
                }

                let result = convert(
                    inputURL: job.url,
                    outputURL: destination,
                    jobID: id,
                    durationSeconds: job.durationSeconds
                )

                updateJob(id) { job in
                    switch result {
                    case .success:
                        job.status = "변환 완료"
                        job.progress = 1
                        job.outputURL = destination
                        playbackURL = destination
                        playbackProgress = 0
                        historyItems.insert(
                            HistoryItem(
                                title: destination.lastPathComponent,
                                detail: "\(outputPreset.outputDescription) 변환 완료",
                                time: formatNow()
                            ),
                            at: 0
                        )
                    case .failure:
                        job.status = "변환 실패"
                        job.progress = 0
                    }
                }
            }
        }
    }

    private func currentJob(id: UUID) -> AudioJob? {
        var job: AudioJob?
        DispatchQueue.main.sync {
            job = jobs.first(where: { $0.id == id })
        }
        return job
    }

    /// 백그라운드 작업에서 UI 상태를 안전하게 갱신합니다.
    private func updateJob(_ id: UUID, mutate: @escaping (inout AudioJob) -> Void) {
        let applyMutation = {
            guard let index = jobs.firstIndex(where: { $0.id == id }) else {
                return
            }
            mutate(&jobs[index])
        }

        if Thread.isMainThread {
            applyMutation()
        } else {
            DispatchQueue.main.sync(execute: applyMutation)
        }
    }

    private func outputURL(for inputURL: URL) -> URL {
        let folder = outputFolder ?? inputURL.deletingLastPathComponent()
        let baseName = inputURL.deletingPathExtension().lastPathComponent
        return folder.appendingPathComponent("\(baseName)_ALAC_\(outputPreset.filenameSuffix).m4a")
    }

    private func chooseOutputFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK {
            outputFolder = panel.url
        }
    }

    /// 상세 정보 탭의 텍스트 필드에서 바뀐 메타데이터를 앱 상태에 먼저 반영합니다.
    ///
    /// 실제 FLAC 파일에 쓰는 작업은 `saveSelectedMetadata`에서 별도로 실행합니다.
    private func updateSelectedMetadata(title: String, artist: String, album: String) {
        guard let selectedJobID,
              let index = jobs.firstIndex(where: { $0.id == selectedJobID }) else {
            return
        }

        jobs[index].title = title
        jobs[index].artist = artist
        jobs[index].album = album
    }

    /// 상세 정보 탭에서 사용자가 새 앨범 커버 이미지를 고릅니다.
    ///
    /// 선택한 이미지는 화면에 즉시 반영되고, `메타데이터 저장`을 누르면 FLAC 파일에도 같이 기록합니다.
    private func chooseArtworkForSelectedJob() {
        guard let selectedJobID,
              let index = jobs.firstIndex(where: { $0.id == selectedJobID }) else {
            NSSound.beep()
            return
        }

        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.jpeg, .png, .tiff]

        if panel.runModal() == .OK, let url = panel.url {
            jobs[index].artworkURL = url
            jobs[index].artworkStatus = "앨범 커버 변경됨"
        }
    }

    /// 상세 정보 탭의 변경 내용을 실제 FLAC 파일에 저장합니다.
    ///
    /// ffmpeg로 임시 FLAC 파일을 만든 뒤 원본을 교체합니다. 원본 옆에는 백업 파일이 남습니다.
    private func saveSelectedMetadata(title: String, artist: String, album: String) {
        guard let selectedJobID,
              let job = jobs.first(where: { $0.id == selectedJobID }) else {
            NSSound.beep()
            return
        }

        updateSelectedMetadata(title: title, artist: artist, album: album)
        updateJob(selectedJobID) { job in
            job.status = "메타데이터 저장 중"
        }

        DispatchQueue.global(qos: .userInitiated).async {
            let result = rewriteFLACMetadata(
                inputURL: job.url,
                title: title,
                artist: artist,
                album: album,
                artworkURL: job.artworkURL
            )

            DispatchQueue.main.async {
                guard let index = jobs.firstIndex(where: { $0.id == selectedJobID }) else {
                    return
                }

                switch result {
                case .success:
                    jobs[index].title = title
                    jobs[index].artist = artist
                    jobs[index].album = album
                    jobs[index].status = "메타데이터 저장 완료"
                    jobs[index].artworkStatus = jobs[index].artworkURL == nil ? jobs[index].artworkStatus : "앨범 커버 저장됨"
                    historyItems.insert(
                        HistoryItem(
                            title: job.filename,
                            detail: "메타데이터와 앨범 커버 저장 완료",
                            time: formatNow()
                        ),
                        at: 0
                    )
                    analyze(jobID: selectedJobID, url: job.url)
                case .failure:
                    jobs[index].status = "메타데이터 저장 실패"
                    NSSound.beep()
                }
            }
        }
    }

    private func rewriteFLACMetadata(inputURL: URL, title: String, artist: String, album: String, artworkURL: URL?) -> Result<Void, Error> {
        do {
            let folder = inputURL.deletingLastPathComponent()
            let baseName = inputURL.deletingPathExtension().lastPathComponent
            let tempURL = folder.appendingPathComponent(".\(baseName).coolplayer-metadata.flac")

            try? FileManager.default.removeItem(at: tempURL)

            var args = [
                "-y",
                "-i", inputURL.path(percentEncoded: false)
            ]

            if let artworkURL {
                args += ["-i", artworkURL.path(percentEncoded: false)]
            }

            args += [
                "-map", "0:a:0",
                "-c:a", "copy",
                "-metadata", "title=\(title)",
                "-metadata", "artist=\(artist)",
                "-metadata", "album=\(album)"
            ]

            if artworkURL != nil {
                args += ["-map", "1:v:0", "-c:v", "copy", "-disposition:v", "attached_pic"]
            } else {
                args += ["-map", "0:v?", "-c:v", "copy", "-disposition:v", "attached_pic"]
            }

            args.append(tempURL.path(percentEncoded: false))

            let process = Process()
            process.executableURL = audioToolURL(named: "ffmpeg")
            process.arguments = args
            process.standardOutput = Pipe()
            process.standardError = Pipe()
            try process.run()
            process.waitUntilExit()

            guard process.terminationStatus == 0 else {
                throw NSError(domain: "CoolPlayer.Metadata", code: Int(process.terminationStatus))
            }

            let backupURL = uniqueBackupURL(for: inputURL)
            try FileManager.default.moveItem(at: inputURL, to: backupURL)
            do {
                try FileManager.default.moveItem(at: tempURL, to: inputURL)
            } catch {
                try? FileManager.default.moveItem(at: backupURL, to: inputURL)
                throw error
            }
            return .success(())
        } catch {
            return .failure(error)
        }
    }

    private func uniqueBackupURL(for inputURL: URL) -> URL {
        let folder = inputURL.deletingLastPathComponent()
        let baseName = inputURL.deletingPathExtension().lastPathComponent
        let first = folder.appendingPathComponent("\(baseName).backup.flac")

        guard FileManager.default.fileExists(atPath: first.path(percentEncoded: false)) else {
            return first
        }

        let stamp = ISO8601DateFormatter()
            .string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        return folder.appendingPathComponent("\(baseName).backup.\(stamp).flac")
    }

    /// ffmpeg로 FLAC를 ALAC `.m4a` 파일로 변환합니다.
    ///
    /// 첫 번째 시도에서는 앨범 커버 스트림을 함께 복사하고, 실패하면 오디오만 변환합니다.
    private func convert(inputURL: URL, outputURL: URL, jobID: UUID, durationSeconds: Double) -> Result<Void, Error> {
        let withArtwork = ffmpegArguments(inputURL: inputURL, outputURL: outputURL, includeArtwork: true)
        let firstTry = runFFmpeg(arguments: withArtwork, jobID: jobID, durationSeconds: durationSeconds)
        if case .success = firstTry {
            return firstTry
        }

        let audioOnly = ffmpegArguments(inputURL: inputURL, outputURL: outputURL, includeArtwork: false)
        return runFFmpeg(arguments: audioOnly, jobID: jobID, durationSeconds: durationSeconds)
    }

    private func ffmpegArguments(inputURL: URL, outputURL: URL, includeArtwork: Bool) -> [String] {
        var args = [
            "-y",
            "-nostats",
            "-i", inputURL.path(percentEncoded: false),
            "-map", "0:a:0",
            "-c:a", "alac"
        ]

        if includeArtwork {
            args += ["-map", "0:v?", "-c:v", "copy", "-disposition:v", "attached_pic"]
        }

        if let sampleFormat = outputPreset.sampleFormat {
            args += ["-sample_fmt", sampleFormat]
        }

        if let sampleRate = outputPreset.sampleRate {
            args += ["-ar", "\(sampleRate)"]
        }

        args += [
            "-progress", "pipe:1",
            outputURL.path(percentEncoded: false)
        ]
        return args
    }

    private func runFFmpeg(arguments: [String], jobID: UUID, durationSeconds: Double) -> Result<Void, Error> {
        do {
            let process = Process()
            process.executableURL = audioToolURL(named: "ffmpeg")
            process.arguments = arguments
            let outputPipe = Pipe()
            let errorPipe = Pipe()
            process.standardOutput = outputPipe
            process.standardError = errorPipe

            var progressBuffer = ""

            /// ffmpeg의 `-progress pipe:1` 출력은 `out_time_ms=...` 같은 key=value 줄로 옵니다.
            /// 이 값을 전체 길이와 비교해서 화면의 진행률을 실제 변환 상황에 가깝게 갱신합니다.
            outputPipe.fileHandleForReading.readabilityHandler = { handle in
                let data = handle.availableData
                guard !data.isEmpty, let chunk = String(data: data, encoding: .utf8) else {
                    return
                }

                progressBuffer += chunk
                let lines = progressBuffer.components(separatedBy: .newlines)
                progressBuffer = lines.last ?? ""

                for line in lines.dropLast() {
                    updateProgress(from: line, jobID: jobID, durationSeconds: durationSeconds)
                }
            }

            /// stderr는 읽지 않으면 ffmpeg 출력이 많을 때 프로세스가 멈출 수 있어서 버리더라도 계속 비웁니다.
            errorPipe.fileHandleForReading.readabilityHandler = { handle in
                _ = handle.availableData
            }

            try process.run()
            process.waitUntilExit()
            outputPipe.fileHandleForReading.readabilityHandler = nil
            errorPipe.fileHandleForReading.readabilityHandler = nil

            guard process.terminationStatus == 0 else {
                throw NSError(domain: "CoolPlayer.FFmpeg", code: Int(process.terminationStatus))
            }
            return .success(())
        } catch {
            return .failure(error)
        }
    }

    private func updateProgress(from line: String, jobID: UUID, durationSeconds: Double) {
        if line == "progress=end" {
            updateJob(jobID) { job in
                job.progress = 1
            }
            return
        }

        let parts = line.split(separator: "=", maxSplits: 1).map(String.init)
        guard parts.count == 2 else {
            return
        }

        let key = parts[0]
        let value = parts[1]
        let seconds: Double?

        if key == "out_time_us" || key == "out_time_ms" {
            seconds = Double(value).map { $0 / 1_000_000.0 }
        } else if key == "out_time" {
            seconds = parseFFmpegTime(value)
        } else {
            seconds = nil
        }

        guard let seconds, durationSeconds > 0 else {
            return
        }

        let progress = min(max(seconds / durationSeconds, 0.18), 0.98)
        updateJob(jobID) { job in
            job.progress = progress
            job.status = "변환 중 \(Int(progress * 100))%"
        }
    }

    /// ffprobe로 선택 파일의 실제 오디오 정보를 읽습니다.
    private func analyze(jobID: UUID, url: URL) {
        DispatchQueue.global(qos: .userInitiated).async {
            let result = probeAudio(url: url)
            let artworkURL: URL?
            if case .success(let metadata) = result {
                if metadata.hasArtwork {
                    artworkURL = extractEmbeddedArtwork(from: url)
                } else {
                    artworkURL = fetchArtworkFromITunes(metadata: metadata, sourceURL: url)
                }
            } else {
                artworkURL = nil
            }

            DispatchQueue.main.async {
                guard let index = jobs.firstIndex(where: { $0.id == jobID }) else {
                    return
                }

                switch result {
                case .success(let metadata):
                    jobs[index].sourceDescription = metadata.sourceDescription
                    jobs[index].channels = metadata.channels
                    jobs[index].duration = metadata.duration
                    jobs[index].durationSeconds = metadata.durationSeconds
                    jobs[index].title = metadata.title
                    jobs[index].artist = metadata.artist
                    jobs[index].album = metadata.album
                    jobs[index].artworkURL = artworkURL
                    if artworkURL != nil {
                        jobs[index].artworkStatus = metadata.hasArtwork ? "앨범 커버 있음" : "앨범 커버 자동 가져옴"
                    } else {
                        jobs[index].artworkStatus = metadata.artworkStatus
                    }
                    jobs[index].status = "분석 완료"
                case .failure:
                    jobs[index].sourceDescription = "FLAC / 분석 실패"
                    jobs[index].status = "확인 필요"
                }
            }
        }
    }

    private func probeAudio(url: URL) -> Result<AudioProbeResult, Error> {
        do {
            let process = Process()
            process.executableURL = audioToolURL(named: "ffprobe")
            process.arguments = [
                "-v", "error",
                "-show_entries", "stream=codec_type,codec_name,sample_rate,channels,bits_per_raw_sample,bits_per_sample:stream_disposition=attached_pic",
                "-show_entries", "format=duration:format_tags=title,artist,album,album_artist",
                "-of", "json",
                url.path(percentEncoded: false)
            ]

            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = Pipe()
            try process.run()
            process.waitUntilExit()

            guard process.terminationStatus == 0 else {
                throw NSError(domain: "CoolPlayer.FFProbe", code: Int(process.terminationStatus))
            }

            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let streams = object?["streams"] as? [[String: Any]] ?? []
            let stream = streams.first { ($0["codec_type"] as? String) == "audio" } ?? streams.first ?? [:]
            let format = object?["format"] as? [String: Any] ?? [:]
            let tags = normalizedTags(format["tags"] as? [String: Any] ?? [:])

            let codec = (stream["codec_name"] as? String)?.uppercased() ?? "FLAC"
            let sampleRate = Int(stream["sample_rate"] as? String ?? "")
            let bitDepth = Int(stream["bits_per_raw_sample"] as? String ?? "")
                ?? Int(stream["bits_per_sample"] as? String ?? "")
            let channels = stream["channels"] as? Int
            let duration = Double(format["duration"] as? String ?? "")
            let title = tags["title"] ?? ""
            let artist = tags["artist"] ?? tags["album_artist"] ?? ""
            let album = tags["album"] ?? ""
            let hasArtwork = streams.contains { stream in
                if (stream["codec_type"] as? String) == "video" {
                    return true
                }

                let disposition = stream["disposition"] as? [String: Any]
                return (disposition?["attached_pic"] as? Int) == 1
            }

            return .success(
                AudioProbeResult(
                    codec: codec,
                    bitDepth: bitDepth.map { "\($0)-bit" } ?? "bit depth 알 수 없음",
                    sampleRate: sampleRate.map(formatSampleRate) ?? "sample rate 알 수 없음",
                    channels: formatChannels(channels),
                    duration: duration.map(formatDuration) ?? "-",
                    durationSeconds: duration ?? 0,
                    title: title,
                    artist: artist,
                    album: album,
                    hasArtwork: hasArtwork
                )
            )
        } catch {
            return .failure(error)
        }
    }

    /// FLAC 안에 들어있는 앨범 커버를 이미지 파일로 추출합니다.
    ///
    /// SwiftUI에서 표시하려면 `NSImage`가 읽을 수 있는 파일 URL이 필요하므로,
    /// ffmpeg로 attached picture 스트림을 캐시 폴더의 `.jpg` 파일로 뽑아둡니다.
    private func extractEmbeddedArtwork(from url: URL) -> URL? {
        let outputURL = artworkCacheURL(for: url, suffix: "embedded.jpg")

        do {
            try FileManager.default.createDirectory(
                at: outputURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )

            let process = Process()
            process.executableURL = audioToolURL(named: "ffmpeg")
            process.arguments = [
                "-y",
                "-i", url.path(percentEncoded: false),
                "-map", "0:v:0",
                "-frames:v", "1",
                outputURL.path(percentEncoded: false)
            ]
            process.standardOutput = Pipe()
            process.standardError = Pipe()
            try process.run()
            process.waitUntilExit()

            guard process.terminationStatus == 0,
                  FileManager.default.fileExists(atPath: outputURL.path(percentEncoded: false)) else {
                return nil
            }

            return outputURL
        } catch {
            return nil
        }
    }

    /// FLAC에 앨범 커버가 없을 때 iTunes Search API에서 커버 이미지를 찾아 저장합니다.
    ///
    /// 인증키가 필요 없는 API라서 초기 버전에 붙이기 좋습니다. 검색어는 아티스트+앨범을 우선하고,
    /// 없으면 아티스트+곡명, 마지막으로 파일명을 사용합니다.
    private func fetchArtworkFromITunes(metadata: AudioProbeResult, sourceURL: URL) -> URL? {
        let candidates = uniqueNonEmpty([
            [metadata.artist, metadata.album].joined(separator: " "),
            [metadata.artist, metadata.title].joined(separator: " "),
            sourceURL.deletingPathExtension().lastPathComponent
        ])

        for candidate in candidates {
            guard let artworkURL = searchITunesArtworkURL(query: candidate),
                  let savedURL = downloadArtwork(from: artworkURL, sourceURL: sourceURL) else {
                continue
            }
            return savedURL
        }

        return nil
    }

    private func searchITunesArtworkURL(query: String) -> URL? {
        var components = URLComponents(string: "https://itunes.apple.com/search")
        components?.queryItems = [
            URLQueryItem(name: "term", value: query),
            URLQueryItem(name: "entity", value: "song"),
            URLQueryItem(name: "limit", value: "1")
        ]

        guard let url = components?.url else {
            return nil
        }

        var request = URLRequest(url: url)
        request.setValue("CoolPlayer/1.0", forHTTPHeaderField: "User-Agent")

        let semaphore = DispatchSemaphore(value: 0)
        var resultURL: URL?

        URLSession.shared.dataTask(with: request) { data, _, _ in
            defer { semaphore.signal() }

            guard let data,
                  let response = try? JSONDecoder().decode(ITunesSearchResponse.self, from: data),
                  response.resultCount > 0,
                  let rawURL = response.results.first?.artworkUrl100 else {
                return
            }

            let largeURL = rawURL.replacingOccurrences(of: "100x100bb", with: "600x600bb")
            resultURL = URL(string: largeURL)
        }.resume()

        _ = semaphore.wait(timeout: .now() + 8)
        return resultURL
    }

    private func downloadArtwork(from remoteURL: URL, sourceURL: URL) -> URL? {
        let semaphore = DispatchSemaphore(value: 0)
        var savedURL: URL?

        URLSession.shared.dataTask(with: remoteURL) { data, _, _ in
            defer { semaphore.signal() }

            guard let data, !data.isEmpty else {
                return
            }

            let outputURL = artworkCacheURL(for: sourceURL, suffix: "itunes.jpg")
            do {
                try FileManager.default.createDirectory(
                    at: outputURL.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                try data.write(to: outputURL, options: .atomic)
                savedURL = outputURL
            } catch {
                savedURL = nil
            }
        }.resume()

        _ = semaphore.wait(timeout: .now() + 8)
        return savedURL
    }

    private func artworkCacheURL(for sourceURL: URL, suffix: String) -> URL {
        let cacheRoot = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CoolPlayerArtwork", isDirectory: true)
        let base = sourceURL.deletingPathExtension().lastPathComponent
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return cacheRoot.appendingPathComponent("\(base)_\(suffix)")
    }

    private func findFLACFiles(in folder: URL) -> [URL] {
        FileManager.default.enumerator(
            at: folder,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )?
        .compactMap { $0 as? URL }
        .filter { $0.pathExtension.lowercased() == "flac" } ?? []
    }
}

// MARK: - 사이드바

struct Sidebar: View {
    @Binding var jobs: [AudioJob]
    @Binding var selectedJobID: UUID?
    @Binding var isDropTargeted: Bool
    let addFiles: () -> Void
    let handleDrop: ([NSItemProvider]) -> Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            BrandHeader()

            FileListCard(
                jobs: $jobs,
                selectedJobID: $selectedJobID,
                isDropTargeted: $isDropTargeted,
                addFiles: addFiles,
                handleDrop: handleDrop
            )
        }
        .padding(.top, 18)
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
        .background(Color.sidebarBackground)
        .overlay(alignment: .trailing) {
            Rectangle()
                .fill(Color.white.opacity(0.07))
                .frame(width: 1)
        }
    }
}

struct BrandHeader: View {
    var body: some View {
        HStack(spacing: 12) {
            LogoMark()
                .frame(width: 42, height: 42)

            VStack(alignment: .leading, spacing: 3) {
                Text("Cool Player")
                    .font(.system(size: 20, weight: .bold))
                Text("FLAC to ALAC")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct SidebarTabs: View {
    @Binding var selectedTab: StudioTab

    var body: some View {
        HStack(spacing: 6) {
            ForEach(StudioTab.allCases) { tab in
                Button {
                    selectedTab = tab
                } label: {
                    VStack(spacing: 5) {
                        Image(systemName: tab.icon)
                            .font(.system(size: 15, weight: .bold))

                        Text(tab.title)
                            .font(.system(size: 11, weight: .bold))
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .foregroundStyle(selectedTab == tab ? .white : Color.primary.opacity(0.84))
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(selectedTab == tab ? Color.coolBlue : Color.clear)
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
            }
        }
        .padding(6)
        .background(Color.panelBackground, in: RoundedRectangle(cornerRadius: 14))
    }
}

struct FileListCard: View {
    @Binding var jobs: [AudioJob]
    @Binding var selectedJobID: UUID?
    @Binding var isDropTargeted: Bool
    let addFiles: () -> Void
    let handleDrop: ([NSItemProvider]) -> Bool

    private var selectedCount: Int { jobs.filter(\.isSelected).count }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("파일 리스트")
                    .font(.system(size: 19, weight: .bold))
                Spacer()
                Text("\(jobs.count)")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 9)
                    .frame(height: 24)
                    .background(Color.white.opacity(0.08), in: Capsule())
            }

            HStack(spacing: 8) {
                IconButton(systemName: "plus", help: "파일/폴더 추가", action: addFiles)
                IconButton(systemName: "checklist.checked", help: "전체 선택", action: selectAll)
                IconButton(systemName: "circle", help: "전체 해제", action: clearSelection)
                IconButton(systemName: "trash", help: "선택 파일 제거", action: removeSelected)
                    .disabled(selectedCount == 0)
                Spacer()
            }

            Text("선택 \(selectedCount)개 / 전체 \(jobs.count)개")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)

            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach($jobs) { $job in
                        FileRow(
                            job: $job,
                            isFocused: selectedJobID == job.id
                        ) {
                            selectedJobID = job.id
                        }
                    }
                }
            }
            .frame(maxHeight: .infinity)
            .overlay {
                if jobs.isEmpty {
                    EmptyFileState()
                }
            }
        }
        .padding(14)
        .frame(maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 18)
                .fill(isDropTargeted ? Color.coolBlue.opacity(0.16) : Color.panelBackground)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .stroke(isDropTargeted ? Color.coolBlue : Color.cardBorder, lineWidth: 1.5)
        }
        .onDrop(of: [.fileURL], isTargeted: $isDropTargeted, perform: handleDrop)
    }

    private func selectAll() {
        for index in jobs.indices {
            jobs[index].isSelected = true
        }
    }

    private func clearSelection() {
        for index in jobs.indices {
            jobs[index].isSelected = false
        }
    }

    private func removeSelected() {
        let removedIDs = Set(jobs.filter(\.isSelected).map(\.id))
        jobs.removeAll { removedIDs.contains($0.id) }
        if let selectedJobID, removedIDs.contains(selectedJobID) {
            self.selectedJobID = jobs.first?.id
        }
    }
}

struct FileRow: View {
    @Binding var job: AudioJob
    let isFocused: Bool
    let focus: () -> Void

    var body: some View {
        Button(action: focus) {
            HStack(spacing: 12) {
                Button {
                    job.isSelected.toggle()
                } label: {
                    Image(systemName: job.isSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(isFocused ? .white : Color.coolBlue)
                        .font(.system(size: 15, weight: .bold))
                }
                .buttonStyle(.plain)

                ArtworkThumbnail(url: job.artworkURL)
                    .frame(width: 38, height: 38)

                VStack(alignment: .leading, spacing: 4) {
                    Text(job.filename)
                        .font(.system(size: 13, weight: .bold))
                        .lineLimit(1)
                    Text(job.sourceDescription)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(isFocused ? Color.white.opacity(0.78) : .secondary)
                        .lineLimit(1)
                }

                Spacer()

                ProgressDot(progress: job.progress, isFocused: isFocused)
                    .frame(width: 22, height: 22)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(height: 58)
            .background(isFocused ? Color.coolBlue : Color.rowBackground, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
    }
}

struct EmptyFileState: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "music.note")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(Color.coolBlue)
            Text("FLAC 파일을 추가하세요")
                .font(.system(size: 14, weight: .bold))
            Text("+ 버튼 또는 이 영역에 드래그")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .allowsHitTesting(false)
    }
}

// MARK: - 통합 작업대

enum WorkbenchTab: String, CaseIterable, Identifiable {
    case convert
    case info
    case lyrics
    case history
    case about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .convert: return "변환"
        case .info: return "정보"
        case .lyrics: return "가사"
        case .history: return "기록"
        case .about: return "앱 정보"
        }
    }
}

struct WorkbenchView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var activeTab: WorkbenchTab = .convert
    let jobs: [AudioJob]
    let selectedJob: AudioJob?
    @Binding var outputPreset: OutputPreset
    let outputFolder: URL?
    let historyItems: [HistoryItem]
    let startConversion: () -> Void
    let chooseOutputFolder: () -> Void
    let resetOutputFolder: () -> Void
    let updateMetadata: (String, String, String) -> Void
    let chooseArtwork: () -> Void
    let saveMetadata: (String, String, String) -> Void

    private var selectedCount: Int { jobs.filter(\.isSelected).count }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                WorkbenchHeader(activeTab: $activeTab)

                tabContent
                    .frame(maxWidth: .infinity, alignment: .topLeading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 18)
        }
    }

    @ViewBuilder
    private var tabContent: some View {
        switch activeTab {
        case .convert:
            PrimaryConversionPanel(
                jobs: jobs,
                selectedJob: selectedJob,
                selectedCount: selectedCount,
                outputPreset: $outputPreset,
                outputFolder: outputFolder,
                chooseOutputFolder: chooseOutputFolder,
                resetOutputFolder: resetOutputFolder,
                startConversion: startConversion
            )
            .frame(alignment: .topLeading)
        case .info:
            SelectedFilePanel(
                job: selectedJob,
                updateMetadata: updateMetadata,
                chooseArtwork: chooseArtwork,
                saveMetadata: saveMetadata
            )
        case .lyrics:
            LyricsView(job: selectedJob)
                .frame(height: 520)
        case .history:
            HistoryMiniPanel(items: historyItems)
                .frame(minHeight: 360, alignment: .topLeading)
        case .about:
            AppInfoPanel()
                .frame(minHeight: 360, alignment: .topLeading)
        }
    }
}

struct WorkbenchHeader: View {
    @EnvironmentObject private var settings: AppSettings
    @Binding var activeTab: WorkbenchTab

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("ALAC 변환")
                        .font(.system(size: 24, weight: .semibold))
                    Text("파일 선택, 변환, 정보 수정, 가사 저장")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                }

                Spacer()

                HStack(spacing: 10) {
                    ThemeControl()

                    Picker("언어", selection: $settings.language) {
                        ForEach(AppLanguage.allCases) { language in
                            Text(language.title).tag(language)
                        }
                    }
                    .labelsHidden()
                    .frame(width: 128)
                }
            }

            WorkbenchTabBar(activeTab: $activeTab)
        }
    }
}

struct WorkbenchTabBar: View {
    @Binding var activeTab: WorkbenchTab

    var body: some View {
        HStack(spacing: 4) {
            ForEach(WorkbenchTab.allCases) { tab in
                Button {
                    activeTab = tab
                } label: {
                    Text(tab.title)
                        .font(.system(size: 13, weight: .semibold))
                        .frame(width: tab == .about ? 82 : 62, height: 32)
                        .foregroundStyle(activeTab == tab ? .white : Color.primary.opacity(0.72))
                        .background(activeTab == tab ? Color.coolBlue : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(Color.panelBackground, in: RoundedRectangle(cornerRadius: 10))
        .overlay(CardStroke(radius: 10))
    }
}

struct PrimaryConversionPanel: View {
    let jobs: [AudioJob]
    let selectedJob: AudioJob?
    let selectedCount: Int
    @Binding var outputPreset: OutputPreset
    let outputFolder: URL?
    let chooseOutputFolder: () -> Void
    let resetOutputFolder: () -> Void
    let startConversion: () -> Void
    private var selectedJobs: [AudioJob] { jobs.filter(\.isSelected) }
    private var currentProgress: Double { selectedJob?.progress ?? 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ConvertCommandBar(
                totalCount: jobs.count,
                selectedCount: selectedCount,
                outputPreset: outputPreset,
                startConversion: startConversion
            )

            HStack(alignment: .top, spacing: 16) {
                ConvertSetupColumn(
                    outputPreset: $outputPreset,
                    outputFolder: outputFolder,
                    chooseOutputFolder: chooseOutputFolder,
                    resetOutputFolder: resetOutputFolder
                )
                .frame(width: 330)

                ConvertTargetColumn(
                    jobs: selectedJobs,
                    outputPreset: outputPreset
                )
            }

            ConvertProgressStrip(progress: currentProgress, status: selectedJob?.status ?? "대기 중")
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}

struct ConvertCommandBar: View {
    let totalCount: Int
    let selectedCount: Int
    let outputPreset: OutputPreset
    let startConversion: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            VStack(alignment: .leading, spacing: 8) {
                Text("체크한 FLAC을 ALAC로 변환")
                    .font(.system(size: 20, weight: .medium))
                Text("왼쪽 파일 리스트에서 체크된 파일만 처리합니다. 이 화면에서는 출력 품질과 저장 위치만 정하면 됩니다.")
                    .font(.system(size: 13, weight: .regular))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer()

            HStack(spacing: 8) {
                ConvertCountBadge(title: "전체", value: "\(totalCount)")
                ConvertCountBadge(title: "선택", value: "\(selectedCount)")
                ConvertCountBadge(title: "출력", value: outputPreset.shortTitle)
                    .frame(minWidth: 112)
            }

            Button {
                startConversion()
            } label: {
                Label("변환 시작", systemImage: "play.fill")
                    .font(.system(size: 14, weight: .medium))
                    .frame(width: 116, height: 32)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.coolBlue)
            .disabled(selectedCount == 0)
        }
        .padding(18)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
    }
}

struct ConvertCountBadge: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .center, spacing: 2) {
            Text(title)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(size: 13, weight: .medium))
                .lineLimit(1)
        }
        .padding(.horizontal, 14)
        .frame(height: 48)
        .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct ConvertSetupColumn: View {
    @Binding var outputPreset: OutputPreset
    let outputFolder: URL?
    let chooseOutputFolder: () -> Void
    let resetOutputFolder: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ConvertSectionTitle(
                number: "1",
                title: "출력 설정",
                subtitle: "음질과 저장 위치를 먼저 확인합니다."
            )

            VStack(alignment: .leading, spacing: 12) {
                Text("품질")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.secondary)

                Picker("출력 품질", selection: $outputPreset) {
                    ForEach(OutputPreset.allCases) { preset in
                        Text(preset.title).tag(preset)
                    }
                }
                .labelsHidden()
                .frame(maxWidth: .infinity)

                VStack(alignment: .leading, spacing: 4) {
                    Text(outputPreset.outputDescription)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Color.coolBlue)
                    Text(outputPreset.detail)
                        .font(.system(size: 12, weight: .regular))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(14)
            .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 14))

            VStack(alignment: .leading, spacing: 12) {
                Text("저장 위치")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.secondary)

                Text(outputFolder?.path(percentEncoded: false) ?? "원본 FLAC 파일 옆에 저장")
                    .font(.system(size: 13, weight: .regular))
                    .lineLimit(2)
                    .truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 8)

                HStack {
                    Button("폴더 선택", action: chooseOutputFolder)
                    Button("원본 옆", action: resetOutputFolder)
                    Spacer()
                }
            }
            .padding(14)
            .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 14))
        }
        .padding(18)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
    }
}

struct ConvertSectionTitle: View {
    let number: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(number)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(Color.coolBlue, in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 15, weight: .medium))
                Text(subtitle)
                    .font(.system(size: 12, weight: .regular))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

struct ConvertTargetColumn: View {
    let jobs: [AudioJob]
    let outputPreset: OutputPreset

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ConvertSectionTitle(
                number: "2",
                title: "변환할 파일",
                subtitle: "왼쪽 파일 리스트에서 체크한 항목이 여기에 표시됩니다."
            )

            if jobs.isEmpty {
                ConvertEmptyTargetState()
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(jobs) { job in
                            ConvertTargetRow(job: job, outputPreset: outputPreset)
                        }
                    }
                }
                .frame(minHeight: 270, maxHeight: 430)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
    }
}

struct ConvertEmptyTargetState: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "checklist")
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(Color.coolBlue)
            Text("아직 체크된 파일이 없습니다")
                .font(.system(size: 15, weight: .medium))
            Text("왼쪽 파일 리스트에서 변환할 파일을 체크하면 이곳에 한 줄씩 나타납니다.")
                .font(.system(size: 12, weight: .regular))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 260)
        .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 14))
    }
}

struct ConvertTargetRow: View {
    let job: AudioJob
    let outputPreset: OutputPreset

    var body: some View {
        HStack(spacing: 12) {
            ArtworkThumbnail(url: job.artworkURL)
                .frame(width: 38, height: 38)

            VStack(alignment: .leading, spacing: 4) {
                Text(job.filename)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                Text(job.sourceDescription)
                    .font(.system(size: 11, weight: .regular))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(outputPreset.shortTitle)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.coolBlue)
                .frame(width: 92, alignment: .leading)

            Text(job.status)
                .font(.system(size: 11, weight: .regular))
                .foregroundStyle(.secondary)
                .frame(width: 78, alignment: .trailing)
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .frame(height: 58)
        .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct ConvertProgressStrip: View {
    let progress: Double
    let status: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                ConvertSectionTitle(
                    number: "3",
                    title: "진행 상태",
                    subtitle: "변환 시작 후 현재 작업 상태와 진행률을 확인합니다."
                )

                Spacer()

                Text(status)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Color.coolBlue)
            }

            ProgressView(value: progress)
                .tint(Color.coolBlue)
        }
        .padding(18)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
    }
}

struct SelectedFilePanel: View {
    let job: AudioJob?
    let updateMetadata: (String, String, String) -> Void
    let chooseArtwork: () -> Void
    let saveMetadata: (String, String, String) -> Void
    @State private var editTitle = ""
    @State private var editArtist = ""
    @State private var editAlbum = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                ArtworkView(url: job?.artworkURL)
                    .frame(width: 142, height: 142)

                VStack(alignment: .leading, spacing: 10) {
                    Text(job?.filename ?? "선택된 파일 없음")
                        .font(.system(size: 17, weight: .bold, design: .rounded))
                        .lineLimit(2)
                    Text(job?.sourceDescription ?? "왼쪽 파일 리스트에서 FLAC을 추가하세요.")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.secondary)
                    Text(job?.artworkStatus ?? "앨범 커버 대기")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)

                    Button {
                        chooseArtwork()
                    } label: {
                        Label("커버 변경", systemImage: "photo")
                    }
                    .disabled(job == nil)
                }
            }

            MetadataEditCard(
                title: $editTitle,
                artist: $editArtist,
                album: $editAlbum,
                isEnabled: job != nil,
                updateMetadata: updateMetadata,
                saveMetadata: {
                    saveMetadata(editTitle, editArtist, editAlbum)
                }
            )

            HStack(spacing: 10) {
                SummaryPill(label: "채널", value: job?.channels ?? "-")
                SummaryPill(label: "길이", value: job?.duration ?? "-")
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
        .onAppear(perform: syncFields)
        .onChange(of: job?.id) {
            syncFields()
        }
    }

    private func syncFields() {
        editTitle = job?.title ?? ""
        editArtist = job?.artist ?? ""
        editAlbum = job?.album ?? ""
    }
}

struct AppInfoPanel: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("앱 정보")
                .font(.system(size: 20, weight: .semibold))

            VStack(spacing: 10) {
                InfoLine(label: "앱", value: "Cool Player")
                InfoLine(label: "버전", value: appVersionText())
                InfoLine(label: "제작자", value: "younghun kim")
                InfoLine(label: "이메일", value: "hun8902@gmail.com")
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
    }
}

struct InfoLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 14) {
            Text(label)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 72, alignment: .leading)
            Text(value)
                .font(.system(size: 14, weight: .semibold))
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
        }
        .padding(.horizontal, 14)
        .frame(height: 40)
        .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 11))
    }
}

struct HistoryMiniPanel: View {
    let items: [HistoryItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("최근 작업")
                    .font(.system(size: 20, weight: .semibold, design: .rounded))
                Spacer()
                Text("\(items.count)개")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary)
            }

            if items.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("아직 완료된 작업이 없습니다")
                        .font(.system(size: 14, weight: .semibold))
                    Text("변환, 메타데이터 저장, 가사 저장 기록이 여기에 쌓입니다.")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 12))
            } else {
                ForEach(items.prefix(4)) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.title)
                                .font(.system(size: 13, weight: .semibold))
                                .lineLimit(1)
                            Text(item.detail)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Text(item.time)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .padding(12)
                    .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
        .padding(18)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
    }
}

// MARK: - 변환 탭

struct ConvertView: View {
    let jobs: [AudioJob]
    let selectedJob: AudioJob?
    @Binding var outputPreset: OutputPreset
    let outputFolder: URL?
    let startConversion: () -> Void

    private var selectedCount: Int { jobs.filter(\.isSelected).count }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            PageHeader(
                title: "변환",
                subtitle: "왼쪽 파일 리스트에서 추가하고 체크한 파일만 변환합니다."
            )

            VStack(spacing: 14) {
                OutputFormatCard(outputPreset: $outputPreset)

                ConversionTargetCard(
                    selectedCount: selectedCount,
                    totalCount: jobs.count,
                    outputFolder: outputFolder,
                    selectedJob: selectedJob,
                    outputPreset: outputPreset,
                    startConversion: startConversion
                )

                ProgressStatusCard(selectedJob: selectedJob)
            }
            .frame(maxWidth: 1040)

            Spacer(minLength: 0)
        }
    }
}

struct OutputFormatCard: View {
    @Binding var outputPreset: OutputPreset

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("출력 형식")
                    .font(.system(size: 20, weight: .bold))
                Text(outputPreset.detail)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text(outputPreset.outputDescription)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Color.coolBlue)
            }

            Spacer()

            Picker("출력 형식", selection: $outputPreset) {
                ForEach(OutputPreset.allCases) { preset in
                    Text(preset.title).tag(preset)
                }
            }
            .labelsHidden()
            .frame(width: 280)
        }
        .padding(20)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 22))
        .overlay(CardStroke(radius: 22))
    }
}

struct ConversionTargetCard: View {
    let selectedCount: Int
    let totalCount: Int
    let outputFolder: URL?
    let selectedJob: AudioJob?
    let outputPreset: OutputPreset
    let startConversion: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("변환 대상")
                        .font(.system(size: 20, weight: .bold))
                    Text("파일 추가는 왼쪽 파일 리스트에서만 합니다.")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button {
                    startConversion()
                } label: {
                    Label("선택 파일 변환 시작", systemImage: "play.fill")
                        .font(.system(size: 14, weight: .bold))
                        .frame(height: 38)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.coolBlue)
                .disabled(selectedCount == 0)
            }

            HStack(spacing: 12) {
                SummaryPill(label: "전체 파일", value: "\(totalCount)개")
                SummaryPill(label: "변환 대상", value: "\(selectedCount)개")
                SummaryPill(label: "저장 위치", value: outputFolder?.lastPathComponent ?? "원본 파일 옆")
            }

            CurrentFileStrip(job: selectedJob, outputPreset: outputPreset)
        }
        .padding(20)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 22))
        .overlay(CardStroke(radius: 22))
    }
}

struct CurrentFileStrip: View {
    let job: AudioJob?
    let outputPreset: OutputPreset

    var body: some View {
        HStack(spacing: 13) {
            ProgressDot(progress: job?.progress ?? 0, isFocused: false)
                .frame(width: 26, height: 26)

            VStack(alignment: .leading, spacing: 4) {
                Text(job?.filename ?? "선택된 파일 없음")
                    .font(.system(size: 14, weight: .semibold))
                    .lineLimit(1)
                Text(job.map { "\($0.sourceDescription) → \(outputPreset.outputDescription)" } ?? "왼쪽 파일 리스트에서 파일을 추가하세요.")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            Text(job?.status ?? "대기")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(job == nil ? .secondary : Color.coolBlue)
        }
        .padding(.horizontal, 14)
        .frame(height: 62)
        .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 14))
    }
}

struct ProgressStatusCard: View {
    let selectedJob: AudioJob?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("실시간 진행 상황")
                .font(.system(size: 18, weight: .bold))
            Text(selectedJob?.status ?? "변환 대기 중")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)

            ProgressView(value: selectedJob?.progress ?? 0)
                .tint(Color.coolBlue)
        }
        .padding(18)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 20))
        .overlay(CardStroke(radius: 20))
    }
}

// MARK: - 상세 정보

struct DetailsView: View {
    let job: AudioJob?
    let updateMetadata: (String, String, String) -> Void
    let chooseArtwork: () -> Void
    let saveMetadata: (String, String, String) -> Void
    @State private var editTitle = ""
    @State private var editArtist = ""
    @State private var editAlbum = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            PageHeader(title: "상세 정보", subtitle: "선택한 FLAC의 메타데이터와 앨범 커버를 수정합니다.")

            HStack(alignment: .top, spacing: 18) {
                VStack(alignment: .leading, spacing: 12) {
                    ArtworkView(url: job?.artworkURL)
                        .frame(width: 250, height: 250)
                        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 24))

                    Button {
                        chooseArtwork()
                    } label: {
                        Label("앨범 커버 변경", systemImage: "photo")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.coolBlue)
                    .disabled(job == nil)

                    Text(job?.artworkStatus ?? "선택된 파일 없음")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                VStack(alignment: .leading, spacing: 14) {
                    MetadataEditCard(
                        title: $editTitle,
                        artist: $editArtist,
                        album: $editAlbum,
                        isEnabled: job != nil,
                        updateMetadata: updateMetadata,
                        saveMetadata: {
                            saveMetadata(editTitle, editArtist, editAlbum)
                        }
                    )

                    VStack(spacing: 10) {
                        DetailRow(label: "파일", value: job?.filename ?? "선택된 파일 없음")
                        DetailRow(label: "원본", value: job?.sourceDescription ?? "-")
                        DetailRow(label: "출력", value: job?.outputDescription ?? "-")
                        DetailRow(label: "채널", value: job?.channels ?? "-")
                        DetailRow(label: "길이", value: job?.duration ?? "-")
                    }
                }
                .frame(maxWidth: 720)
            }
            .frame(maxWidth: 1040, alignment: .leading)

            Spacer()
        }
        .onAppear(perform: syncEditFields)
        .onChange(of: job?.id) {
            syncEditFields()
        }
    }

    private func syncEditFields() {
        editTitle = job?.title ?? ""
        editArtist = job?.artist ?? ""
        editAlbum = job?.album ?? ""
    }
}

struct MetadataEditCard: View {
    @Binding var title: String
    @Binding var artist: String
    @Binding var album: String
    let isEnabled: Bool
    let updateMetadata: (String, String, String) -> Void
    let saveMetadata: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("메타데이터 수정")
                    .font(.system(size: 20, weight: .semibold))
                Spacer()
                Button {
                    saveMetadata()
                } label: {
                    Label("파일에 저장", systemImage: "square.and.arrow.down")
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.coolBlue)
                .disabled(!isEnabled)
            }

            VStack(spacing: 10) {
                MetadataField(label: "곡명", text: $title, isEnabled: isEnabled)
                MetadataField(label: "아티스트", text: $artist, isEnabled: isEnabled)
                MetadataField(label: "앨범", text: $album, isEnabled: isEnabled)
            }
            .onChange(of: title) { updateMetadata(title, artist, album) }
            .onChange(of: artist) { updateMetadata(title, artist, album) }
            .onChange(of: album) { updateMetadata(title, artist, album) }
        }
        .padding(18)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
    }
}

struct MetadataField: View {
    let label: String
    @Binding var text: String
    let isEnabled: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 78, alignment: .leading)
            TextField(label, text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: 14, weight: .semibold))
                .disabled(!isEnabled)
                .padding(.horizontal, 12)
                .frame(height: 38)
                .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 10))
        }
    }
}

// MARK: - 가사

struct LyricsView: View {
    let job: AudioJob?
    @State private var status = "선택 파일 기준으로 LRC 가사를 자동 검색합니다."
    @State private var isFetching = false
    @State private var lyricsText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PageHeader(title: "LRC 가사", subtitle: "LRCLIB에서 실시간 가사를 가져오거나 직접 편집해 저장합니다.")

            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(job?.lyricsSearchQuery.isEmpty == false ? job!.lyricsSearchQuery : "선택된 파일 없음")
                            .font(.system(size: 15, weight: .bold))
                            .lineLimit(1)
                        Text("곡명/아티스트 태그가 있으면 태그로, 없으면 파일명으로 자동 검색합니다.")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Button {
                        fetchLyrics()
                    } label: {
                        Label(isFetching ? "가져오는 중" : "가사 자동 가져오기", systemImage: "arrow.down.doc")
                    }
                    .disabled(isFetching || job == nil)

                    Button("LRC 저장") {
                        saveLRC()
                    }
                }
            }
            .padding(16)
            .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
            .overlay(CardStroke(radius: 18))
            .frame(maxWidth: 1040)

            Text(status)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)

            TextEditor(text: $lyricsText)
                .font(.system(size: 14, weight: .medium, design: .monospaced))
                .scrollContentBackground(.hidden)
                .padding(14)
                .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
                .overlay(CardStroke(radius: 18))
                .frame(maxWidth: 1040, maxHeight: .infinity)
        }
        .onAppear(perform: syncLyricsForSelectedJob)
        .onChange(of: job?.id) {
            syncLyricsForSelectedJob()
        }
    }

    /// LRCLIB 공개 API에서 가사를 검색합니다.
    private func fetchLyrics() {
        guard let job else {
            status = "먼저 왼쪽 파일 리스트에서 파일을 선택하세요."
            return
        }

        let candidates = lyricsCandidates(for: job)
        guard !candidates.isEmpty else {
            status = "자동 검색어를 만들 수 없습니다."
            return
        }

        isFetching = true
        status = "가사를 자동 검색 중입니다."
        fetchLyrics(candidates: candidates, index: 0)
    }

    private func fetchLyrics(candidates: [String], index: Int) {
        guard index < candidates.count else {
            DispatchQueue.main.async {
                isFetching = false
                status = "검색된 가사가 없습니다."
            }
            return
        }

        let search = candidates[index]
        var components = URLComponents(string: "https://lrclib.net/api/search")
        components?.queryItems = [URLQueryItem(name: "q", value: search)]
        guard let url = components?.url else {
            fetchLyrics(candidates: candidates, index: index + 1)
            return
        }

        var request = URLRequest(url: url)
        request.setValue("CoolPlayer/1.0", forHTTPHeaderField: "User-Agent")

        DispatchQueue.main.async {
            status = "\"\(search)\" 기준으로 가사를 검색 중입니다."
        }

        URLSession.shared.dataTask(with: request) { data, _, error in
            if let error {
                DispatchQueue.main.async {
                    isFetching = false
                    status = "가사 가져오기 실패: \(error.localizedDescription)"
                }
                return
            }

            guard let data else {
                fetchLyrics(candidates: candidates, index: index + 1)
                return
            }

            do {
                let results = try JSONDecoder().decode([LRCLyricsResult].self, from: data)
                guard let first = bestLyricsResult(from: results) else {
                    fetchLyrics(candidates: candidates, index: index + 1)
                    return
                }

                DispatchQueue.main.async {
                    isFetching = false
                    if let synced = first.syncedLyrics, !synced.isEmpty {
                        lyricsText = synced
                        let savedURL = saveLyricsBesideSource(synced)
                        status = "\(first.trackName ?? search) 실시간 가사를 가져왔습니다." + savedMessage(savedURL)
                    } else if let plain = first.plainLyrics, !plain.isEmpty {
                        lyricsText = plain
                        let generated = makeBasicLRC(from: plain)
                        lyricsText = generated
                        let savedURL = saveLyricsBesideSource(generated)
                        status = "일반 가사를 LRC 초안으로 변환했습니다." + savedMessage(savedURL)
                    } else {
                        fetchLyrics(candidates: candidates, index: index + 1)
                    }
                }
            } catch {
                fetchLyrics(candidates: candidates, index: index + 1)
            }
        }.resume()
    }

    private func saveLRC() {
        guard !lyricsText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            status = "저장할 가사가 없습니다."
            NSSound.beep()
            return
        }

        let panel = NSSavePanel()
        let defaultURL = job?.url.deletingPathExtension().appendingPathExtension("lrc")
        panel.nameFieldStringValue = defaultURL?.lastPathComponent ?? "lyrics.lrc"
        panel.directoryURL = defaultURL?.deletingLastPathComponent()
        panel.allowedContentTypes = [UTType(filenameExtension: "lrc") ?? .plainText]
        if panel.runModal() == .OK, let url = panel.url {
            try? lyricsText.write(to: url, atomically: true, encoding: .utf8)
        }
    }

    /// 실시간 가사를 가져온 경우 원본 FLAC 파일 옆에 같은 이름의 `.lrc` 파일을 자동 저장합니다.
    ///
    /// 예: `sample.flac` -> `sample.lrc`
    private func saveLyricsBesideSource(_ text: String) -> URL? {
        guard let job else {
            return nil
        }

        let url = job.url.deletingPathExtension().appendingPathExtension("lrc")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }

    private func savedMessage(_ url: URL?) -> String {
        guard let url else {
            return " 자동 저장은 실패했습니다."
        }
        return " \(url.lastPathComponent)로 자동 저장했습니다."
    }

    private func syncLyricsForSelectedJob() {
        guard let job else {
            lyricsText = ""
            status = "왼쪽 파일 리스트에서 FLAC 파일을 선택하세요."
            return
        }

        let lrcURL = job.url.deletingPathExtension().appendingPathExtension("lrc")
        if let text = try? String(contentsOf: lrcURL, encoding: .utf8),
           !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lyricsText = text
            status = "\(lrcURL.lastPathComponent) 파일을 불러왔습니다."
        } else {
            lyricsText = ""
            status = "이 음원의 LRC 파일이 없습니다. 가사 자동 가져오기를 눌러 검색하세요."
        }
    }

    private func bestLyricsResult(from results: [LRCLyricsResult]) -> LRCLyricsResult? {
        let usable = results.filter { result in
            let synced = result.syncedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let plain = result.plainLyrics?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return !synced.isEmpty || !plain.isEmpty
        }

        return usable.first { result in
            containsHangul(result.syncedLyrics ?? "") || containsHangul(result.plainLyrics ?? "")
        } ?? usable.first
    }

    private func lyricsCandidates(for job: AudioJob) -> [String] {
        let titleWithoutParentheses = removeParentheses(from: job.title)
        return uniqueNonEmpty([
            [job.artist, job.title].joined(separator: " "),
            [job.artist, titleWithoutParentheses].joined(separator: " "),
            job.title,
            titleWithoutParentheses,
            job.lyricsSearchQuery,
            removeParentheses(from: job.lyricsSearchQuery),
            job.url.deletingPathExtension().lastPathComponent
        ])
    }

    private func makeBasicLRC(from plainLyrics: String) -> String {
        let lines = plainLyrics
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        return lines.enumerated()
            .map { index, line in
                let seconds = index * 5
                return String(format: "[%02d:%02d.00]%@", seconds / 60, seconds % 60, line)
            }
            .joined(separator: "\n")
    }
}

// MARK: - 히스토리 / 설정

struct HistoryView: View {
    let items: [HistoryItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            PageHeader(title: "히스토리", subtitle: "변환 완료 기록을 확인합니다.")

            if items.isEmpty {
                EmptyPanel(title: "아직 완료된 작업이 없습니다", subtitle: "변환이 끝나면 이곳에 기록됩니다.")
            } else {
                ForEach(items) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.title)
                                .font(.system(size: 14, weight: .bold))
                            Text(item.detail)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(item.time)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.secondary)
                    }
                    .padding(14)
                    .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 14))
                }
            }

            Spacer()
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @Binding var outputPreset: OutputPreset
    @Binding var outputFolder: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            PageHeader(title: "설정", subtitle: "앱 기본 동작과 표시 언어를 정합니다.")

            SettingsRow(title: "테마") {
                ThemeControl()
            }

            SettingsRow(title: "언어") {
                Picker("언어", selection: $settings.language) {
                    ForEach(AppLanguage.allCases) { language in
                        Text(language.title).tag(language)
                    }
                }
                .labelsHidden()
                .frame(width: 150)
            }

            SettingsRow(title: "기본 출력 형식") {
                Picker("출력 형식", selection: $outputPreset) {
                    ForEach(OutputPreset.allCases) { preset in
                        Text(preset.title).tag(preset)
                    }
                }
                .labelsHidden()
                .frame(width: 250)
            }

            SettingsRow(title: "저장 위치") {
                HStack(spacing: 10) {
                    Text(outputFolder?.path(percentEncoded: false) ?? "원본 파일 옆")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Button("폴더 선택") { chooseOutputFolder() }
                    Button("기본값") { outputFolder = nil }
                }
            }

            Spacer()
        }
    }

    private func chooseOutputFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK {
            outputFolder = panel.url
        }
    }
}

// MARK: - 플레이어

struct PlayerBar: View {
    @Binding var progress: Double
    let playbackURL: URL?
    @State private var player: AVAudioPlayer?
    @State private var isPlaying = false

    var body: some View {
        HStack(spacing: 14) {
            Button {
                togglePlayback()
            } label: {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 14, weight: .bold))
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            .background(Color.coolNavy, in: Circle())

            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    Text("재생 확인")
                        .font(.system(size: 12, weight: .bold))
                    Text(playbackURL?.lastPathComponent ?? "변환 완료 파일 없음")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer()
                    Text(playbackURL == nil ? "00:00 / 00:00" : "재생 가능")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.secondary)
                }

                Slider(value: $progress)
                    .tint(Color.coolBlue)
                    .disabled(playbackURL == nil)
            }
        }
        .padding(.horizontal, 22)
        .frame(height: 82)
        .background(.bar)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(Color.white.opacity(0.08))
                .frame(height: 1)
        }
    }

    private func togglePlayback() {
        guard let playbackURL else {
            NSSound.beep()
            return
        }

        do {
            if player?.url != playbackURL {
                player = try AVAudioPlayer(contentsOf: playbackURL)
                player?.prepareToPlay()
            }

            if isPlaying {
                player?.pause()
                isPlaying = false
            } else {
                player?.play()
                isPlaying = true
            }
        } catch {
            NSSound.beep()
            isPlaying = false
        }
    }
}

// MARK: - 공통 UI

struct PageHeader: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title)
                .font(.system(size: 34, weight: .bold, design: .rounded))
            Text(subtitle)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.secondary)
        }
    }
}

struct SummaryPill: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.system(size: 13, weight: .bold))
        }
        .padding(.horizontal, 14)
        .frame(height: 44)
        .background(Color.rowBackground, in: RoundedRectangle(cornerRadius: 13))
    }
}

struct DetailRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(.secondary)
                .frame(width: 95, alignment: .leading)
            Text(value)
                .font(.system(size: 14, weight: .bold))
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
        }
        .padding(15)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 14))
        .overlay(CardStroke(radius: 14))
    }
}

struct SettingsRow<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        HStack {
            Text(title)
                .font(.system(size: 17, weight: .bold))
            Spacer()
            content
        }
        .padding(18)
        .frame(maxWidth: 1040)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 18))
        .overlay(CardStroke(radius: 18))
    }
}

struct EmptyPanel: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 9) {
            Text(title)
                .font(.system(size: 16, weight: .bold))
            Text(subtitle)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: 1040)
        .padding(.vertical, 42)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 20))
        .overlay(CardStroke(radius: 20))
    }
}

struct ArtworkView: View {
    let url: URL?

    var body: some View {
        Group {
            if let url, let image = NSImage(contentsOf: url) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 24)
                        .fill(Color.cardBackground)
                    LogoMark()
                        .frame(width: 120, height: 120)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(CardStroke(radius: 24))
    }
}

struct ArtworkThumbnail: View {
    let url: URL?

    var body: some View {
        Group {
            if let url, let image = NSImage(contentsOf: url) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 9)
                        .fill(Color.white.opacity(0.08))
                    Image(systemName: "music.note")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color.coolBlue)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 9))
        .overlay {
            RoundedRectangle(cornerRadius: 9)
                .stroke(Color.white.opacity(0.08), lineWidth: 1)
        }
    }
}

struct IconButton: View {
    let systemName: String
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 15, weight: .bold))
                .frame(width: 30, height: 30)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.secondary)
        .help(help)
    }
}

struct ProgressDot: View {
    let progress: Double
    let isFocused: Bool

    var body: some View {
        ZStack {
            Circle()
                .stroke((isFocused ? Color.white : Color.coolBlue).opacity(0.22), lineWidth: 4)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(isFocused ? Color.white : Color.coolBlue, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 28, height: 28)
    }
}

struct CardStroke: View {
    let radius: CGFloat

    var body: some View {
        RoundedRectangle(cornerRadius: radius)
            .stroke(Color.cardBorder, lineWidth: 1)
    }
}

struct LogoMark: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.white)
            Circle()
                .fill(LinearGradient(colors: [Color.coolSky, Color.coolNavy], startPoint: .topLeading, endPoint: .bottomTrailing))
                .padding(7)
            Image(systemName: "music.note")
                .font(.system(size: 20, weight: .black))
                .foregroundStyle(.white)
        }
    }
}

struct ThemeControl: View {
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        Picker("테마", selection: $settings.theme) {
            ForEach(AppTheme.allCases) { theme in
                Text(theme.title(language: settings.language)).tag(theme)
            }
        }
        .pickerStyle(.segmented)
        .frame(width: 154)
    }
}

struct AppBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        LinearGradient(
            colors: colorScheme == .dark ? darkColors : lightColors,
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }

    private var darkColors: [Color] {
        [
            Color(red: 0.07, green: 0.09, blue: 0.10),
            Color(red: 0.10, green: 0.11, blue: 0.11),
            Color(red: 0.10, green: 0.09, blue: 0.07)
        ]
    }

    private var lightColors: [Color] {
        [
            Color(red: 0.96, green: 0.98, blue: 1.00),
            Color(red: 0.93, green: 0.96, blue: 0.98),
            Color(red: 0.98, green: 0.97, blue: 0.94)
        ]
    }
}

// MARK: - 포맷 유틸리티

private func formatSampleRate(_ value: Int) -> String {
    if value % 1000 == 0 {
        return "\(value / 1000)kHz"
    }
    return String(format: "%.1fkHz", Double(value) / 1000.0)
}

private func formatChannels(_ value: Int?) -> String {
    switch value {
    case 1: return "Mono"
    case 2: return "Stereo"
    case let value?: return "\(value) channels"
    case nil: return "-"
    }
}

private func formatDuration(_ value: Double) -> String {
    let seconds = max(Int(value.rounded()), 0)
    return String(format: "%02d:%02d", seconds / 60, seconds % 60)
}

private func formatNow() -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ko_KR")
    formatter.dateFormat = "오늘 HH:mm"
    return formatter.string(from: Date())
}

private func parseFFmpegTime(_ value: String) -> Double? {
    let parts = value.split(separator: ":").map(String.init)
    guard parts.count == 3,
          let hours = Double(parts[0]),
          let minutes = Double(parts[1]),
          let seconds = Double(parts[2]) else {
        return nil
    }
    return hours * 3600 + minutes * 60 + seconds
}

private func emptyDash(_ value: String?) -> String {
    let text = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return text.isEmpty ? "-" : text
}

private func normalizedTags(_ tags: [String: Any]) -> [String: String] {
    Dictionary(
        uniqueKeysWithValues: tags.compactMap { key, value in
            guard let text = value as? String else {
                return nil
            }
            return (key.lowercased(), text)
        }
    )
}

private func uniqueNonEmpty(_ values: [String]) -> [String] {
    var seen = Set<String>()
    var result: [String] = []

    for value in values {
        let trimmed = value
            .replacingOccurrences(of: "  ", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            continue
        }

        let key = trimmed.lowercased()
        if seen.insert(key).inserted {
            result.append(trimmed)
        }
    }

    return result
}

private func removeParentheses(from value: String) -> String {
    value
        .replacingOccurrences(of: #"\([^)]*\)"#, with: "", options: .regularExpression)
        .replacingOccurrences(of: #"\[[^\]]*\]"#, with: "", options: .regularExpression)
        .replacingOccurrences(of: "  ", with: " ")
        .trimmingCharacters(in: .whitespacesAndNewlines)
}

private func containsHangul(_ value: String) -> Bool {
    value.range(of: #"[가-힣]"#, options: .regularExpression) != nil
}

/// ffmpeg/ffprobe 실행 파일 위치를 찾습니다.
///
/// 배포된 앱에서는 `CoolPlayer.app/Contents/Resources/ffmpeg`처럼 앱 내부에 포함된 파일을 먼저 씁니다.
/// 개발 중이거나 앱 안에 파일이 없을 때만 Homebrew 설치 경로를 확인합니다.
private func audioToolURL(named toolName: String) -> URL {
    let fileManager = FileManager.default

    if let bundledURL = Bundle.main.resourceURL?.appendingPathComponent(toolName),
       fileManager.isExecutableFile(atPath: bundledURL.path(percentEncoded: false)) {
        return bundledURL
    }

    let fallbackPaths = [
        "/opt/homebrew/bin/\(toolName)",
        "/usr/local/bin/\(toolName)"
    ]

    if let fallbackPath = fallbackPaths.first(where: { fileManager.isExecutableFile(atPath: $0) }) {
        return URL(fileURLWithPath: fallbackPath)
    }

    return URL(fileURLWithPath: "/opt/homebrew/bin/\(toolName)")
}

private func appVersionText() -> String {
    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String

    switch (version, build) {
    case let (version?, build?) where !version.isEmpty && !build.isEmpty:
        return "\(version) (\(build))"
    case let (version?, _) where !version.isEmpty:
        return version
    default:
        return "1.0"
    }
}

// MARK: - 색상

extension Color {
    static let coolBlue = Color(red: 0.18, green: 0.49, blue: 0.79)
    static let coolSky = Color(red: 0.43, green: 0.78, blue: 0.95)
    static let coolNavy = Color(red: 0.04, green: 0.15, blue: 0.25)
    static let sidebarBackground = Color(nsColor: .windowBackgroundColor).opacity(0.82)
    static let panelBackground = Color(nsColor: .controlBackgroundColor).opacity(0.72)
    static let cardBackground = Color(nsColor: .controlBackgroundColor).opacity(0.86)
    static let rowBackground = Color(nsColor: .textBackgroundColor).opacity(0.72)
    static let cardBorder = Color(nsColor: .separatorColor).opacity(0.56)
}
