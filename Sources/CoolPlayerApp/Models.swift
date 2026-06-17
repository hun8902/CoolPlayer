import Foundation

struct ConversionItem: Identifiable {
    let id = UUID()
    let title: String
    let source: String
    let output: String
    let progress: Double
    let statusKey: String
}

struct HistoryItem: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let time: String
}

let previewConversions: [ConversionItem] = [
    ConversionItem(
        title: "Midnight Master.flac",
        source: "FLAC / 32-bit / 384kHz",
        output: "ALAC / 24-bit / 192kHz",
        progress: 0.72,
        statusKey: "status.converting"
    ),
    ConversionItem(
        title: "Blue Avenue.flac",
        source: "FLAC / 24-bit / 192kHz",
        output: "ALAC / 24-bit / 192kHz",
        progress: 0.28,
        statusKey: "status.scanning"
    ),
    ConversionItem(
        title: "City Lights.flac",
        source: "FLAC / 24-bit / 96kHz",
        output: "ALAC / 원본 해상도 유지",
        progress: 0.0,
        statusKey: "status.ready"
    )
]

let previewHistory: [HistoryItem] = [
    HistoryItem(title: "Ocean Drive", detail: "12곡 변환 완료 / 앨범 커버 유지", time: "오늘 13:04"),
    HistoryItem(title: "Seoul Night", detail: "LRC 파일 8개 생성", time: "어제 22:18"),
    HistoryItem(title: "Archive Batch", detail: "FLAC 폴더 43개 분석", time: "6월 14일")
]
