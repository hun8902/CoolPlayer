import Foundation
import SwiftUI

enum AppLanguage: String, CaseIterable, Identifiable {
    case ko
    case en
    case ja

    var id: String { rawValue }

    var title: String {
        switch self {
        case .ko: return "한국어"
        case .en: return "English"
        case .ja: return "日本語"
        }
    }
}

final class AppSettings: ObservableObject {
    @Published var language: AppLanguage = .ko
    @Published var theme: AppTheme = .system

    func text(_ key: String) -> String {
        LocalizedText.value(key, language: language)
    }
}

enum AppTheme: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    var icon: String {
        switch self {
        case .system: return "circle.lefthalf.filled"
        case .light: return "sun.max.fill"
        case .dark: return "moon.fill"
        }
    }

    func title(language: AppLanguage) -> String {
        switch (self, language) {
        case (.system, .ko): return "시스템"
        case (.light, .ko): return "라이트"
        case (.dark, .ko): return "다크"
        case (.system, .ja): return "システム"
        case (.light, .ja): return "ライト"
        case (.dark, .ja): return "ダーク"
        case (.system, _): return "System"
        case (.light, _): return "Light"
        case (.dark, _): return "Dark"
        }
    }
}

enum LocalizedText {
    private static let ko: [String: String] = [
        "app.name": "Cool Player",
        "app.tagline": "ALAC 24-bit / 192kHz 제작 도구",
        "nav.dashboard": "대시보드",
        "nav.convert": "변환",
        "nav.history": "히스토리",
        "nav.metadata": "음원 정보",
        "nav.lyrics": "실시간 가사",
        "nav.settings": "설정",
        "drop.title": "FLAC 파일 또는 폴더를 드래그하세요",
        "drop.subtitle": "24-bit / 192kHz 초과 음원은 ALAC 24-bit / 192kHz로 정리합니다.",
        "drop.button": "파일 선택",
        "progress.title": "실시간 진행 사항",
        "progress.current": "현재 작업",
        "progress.queue": "대기열",
        "history.title": "최근 작업 히스토리",
        "details.title": "해당 음원 상세 정보",
        "details.source": "원본",
        "details.output": "출력",
        "details.artwork": "앨범 아트워크",
        "lyrics.title": "LRC 실시간 가사",
        "lyrics.sync": "타임스탬프 편집",
        "player.ready": "재생 확인",
        "player.file": "변환 완료 파일 없음",
        "language.title": "언어",
        "theme.title": "테마",
        "section.menu": "메뉴",
        "section.preset": "프리셋",
        "section.format": "포맷",
        "table.file": "파일",
        "table.source": "원본",
        "table.output": "출력",
        "table.status": "상태",
        "status.ready": "대기 중",
        "status.scanning": "앨범 커버 확인",
        "status.converting": "ALAC 변환 중",
        "status.lyrics": "LRC 생성 준비"
    ]

    private static let en: [String: String] = [
        "app.name": "Cool Player",
        "app.tagline": "ALAC 24-bit / 192kHz production tool",
        "nav.dashboard": "Dashboard",
        "nav.convert": "Convert",
        "nav.history": "History",
        "nav.metadata": "Audio Details",
        "nav.lyrics": "Live Lyrics",
        "nav.settings": "Settings",
        "drop.title": "Drop FLAC files or folders",
        "drop.subtitle": "Files above 24-bit / 192kHz will be normalized to ALAC 24-bit / 192kHz.",
        "drop.button": "Choose Files",
        "progress.title": "Live Progress",
        "progress.current": "Current Job",
        "progress.queue": "Queue",
        "history.title": "Recent History",
        "details.title": "Audio Details",
        "details.source": "Source",
        "details.output": "Output",
        "details.artwork": "Album Artwork",
        "lyrics.title": "LRC Live Lyrics",
        "lyrics.sync": "Timestamp Editor",
        "player.ready": "Preview",
        "player.file": "No converted file yet",
        "language.title": "Language",
        "theme.title": "Theme",
        "section.menu": "Menu",
        "section.preset": "Preset",
        "section.format": "Format",
        "table.file": "File",
        "table.source": "Source",
        "table.output": "Output",
        "table.status": "Status",
        "status.ready": "Ready",
        "status.scanning": "Checking album art",
        "status.converting": "Converting to ALAC",
        "status.lyrics": "Preparing LRC"
    ]

    private static let ja: [String: String] = [
        "app.name": "Cool Player",
        "app.tagline": "ALAC 24-bit / 192kHz 制作ツール",
        "nav.dashboard": "ダッシュボード",
        "nav.convert": "変換",
        "nav.history": "履歴",
        "nav.metadata": "音源情報",
        "nav.lyrics": "リアルタイム歌詞",
        "nav.settings": "設定",
        "drop.title": "FLACファイルまたはフォルダをドロップ",
        "drop.subtitle": "24-bit / 192kHzを超える音源はALAC 24-bit / 192kHzへ整理します。",
        "drop.button": "ファイル選択",
        "progress.title": "リアルタイム進行状況",
        "progress.current": "現在の作業",
        "progress.queue": "キュー",
        "history.title": "最近の履歴",
        "details.title": "音源の詳細情報",
        "details.source": "入力",
        "details.output": "出力",
        "details.artwork": "アルバムアート",
        "lyrics.title": "LRCリアルタイム歌詞",
        "lyrics.sync": "タイムスタンプ編集",
        "player.ready": "再生確認",
        "player.file": "変換済みファイルなし",
        "language.title": "言語",
        "theme.title": "テーマ",
        "section.menu": "メニュー",
        "section.preset": "プリセット",
        "section.format": "フォーマット",
        "table.file": "ファイル",
        "table.source": "入力",
        "table.output": "出力",
        "table.status": "状態",
        "status.ready": "待機中",
        "status.scanning": "アルバムアート確認",
        "status.converting": "ALAC変換中",
        "status.lyrics": "LRC作成準備"
    ]

    static func value(_ key: String, language: AppLanguage) -> String {
        let table: [String: String]
        switch language {
        case .ko: table = ko
        case .en: table = en
        case .ja: table = ja
        }
        return table[key] ?? ko[key] ?? key
    }
}
