import SwiftUI
import AppKit

enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var title: String { switch self { case .system: return "시스템"; case .light: return "라이트"; case .dark: return "다크" } }
    var scheme: ColorScheme? { self == .system ? nil : self == .dark ? .dark : .light }
}
final class AppSettings: ObservableObject {
    @Published var appearance = Appearance(rawValue: UserDefaults.standard.string(forKey: "soriq.appearance") ?? "light") ?? .light { didSet { save(appearance.rawValue, "appearance") } }
    @Published var volume = UserDefaults.standard.object(forKey: "soriq.volume") as? Double ?? 0.65 { didSet { save(volume, "volume") } }
    @Published var autoAdvance = UserDefaults.standard.object(forKey: "soriq.autoAdvance") as? Bool ?? true { didSet { save(autoAdvance, "autoAdvance") } }
    @Published var lyricOffset = UserDefaults.standard.double(forKey: "soriq.lyricOffset") { didSet { save(lyricOffset, "lyricOffset") } }
    @Published var outputFolder = UserDefaults.standard.string(forKey: "soriq.outputFolder") ?? "" { didSet { save(outputFolder, "outputFolder") } }
    @Published var preset = AudioPreset(rawValue: UserDefaults.standard.string(forKey: "soriq.preset") ?? "original") ?? .original { didSet { save(preset.rawValue, "preset") } }
    @Published var importConverted = UserDefaults.standard.object(forKey: "soriq.importConverted") as? Bool ?? true { didSet { save(importConverted, "importConverted") } }
    private func save(_ value: Any, _ key: String) { UserDefaults.standard.set(value, forKey: "soriq." + key) }
}
extension Color {
    static let soriq = Color(red: 0.16, green: 0.38, blue: 0.90)
    static let canvas = Color(nsColor: .textBackgroundColor)
    static let surface = Color(nsColor: .controlBackgroundColor)
    static let line = Color(nsColor: .separatorColor).opacity(0.35)
}
struct BrandMark: View {
    var body: some View {
        Image("Logo").resizable().scaledToFit().accessibilityLabel("Cool Player")
    }
}
