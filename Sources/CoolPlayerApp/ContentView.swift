import SwiftUI
import AppKit
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var store: MusicStore
    @EnvironmentObject var settings: AppSettings
    @State private var playlistDialog = false
    @State private var playlistName = ""
    @State private var dropTarget = false
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                sidebar.frame(width: 195)
                Divider()
                Group {
                    switch store.page {
                    case .nowPlaying: NowPlayingView()
                    case .convert: ConversionView()
                    case .settings: PreferencesView()
                    default: LibraryView()
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Divider()
            PlaybackBar()
        }.background(Color.canvas).tint(.soriq)
        .overlay { if dropTarget { RoundedRectangle(cornerRadius: 12).stroke(Color.soriq, lineWidth: 3).padding(6).allowsHitTesting(false) } }
        .onDrop(of: [.fileURL], isTargeted: $dropTarget) { providers in
            let group = DispatchGroup(); let collector = DroppedURLs()
            for provider in providers {
                group.enter()
                provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { data, _ in
                    defer { group.leave() }
                    if let data = data as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) { collector.add(url) }
                }
            }
            group.notify(queue: .main) { store.importURLs(collector.urls) }; return true
        }
        .sheet(item: $store.editing) { TrackEditor(track: $0).environmentObject(store).environmentObject(settings) }
        .alert("확인이 필요합니다", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
            Button("확인") { store.error = nil }
        } message: { Text(store.error ?? "") }
        .alert("새 재생목록", isPresented: $playlistDialog) {
            TextField("재생목록 이름", text: $playlistName)
            Button("취소", role: .cancel) {}
            Button("만들기") { store.makePlaylist(playlistName); playlistName = "" }
        } message: { Text("선택한 곡이 있다면 함께 담습니다.") }
    }
    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) { BrandMark().frame(width: 31, height: 31); VStack(alignment: .leading, spacing: 1) { Text("Cool Player").font(.system(size: 17, weight: .bold)); Text("YOUR MUSIC. YOUR SPACE.").font(.system(size: 7, weight: .medium)).tracking(0.6).foregroundStyle(.secondary) } }.padding(.horizontal, 18).padding(.top, 24).padding(.bottom, 30)
            ScrollView {
                VStack(alignment: .leading, spacing: 5) {
                    sideButton(.nowPlaying)
                    Text("보관함").font(.caption).foregroundStyle(.secondary).padding(.top, 23).padding(.leading, 10).padding(.bottom, 6)
                    sideButton(.library); sideButton(.favorites); sideButton(.recent)
                    HStack { Text("재생목록").font(.caption).foregroundStyle(.secondary); Spacer(); Button { playlistDialog = true } label: { Image(systemName: "plus") }.buttonStyle(.plain).help("재생목록 만들기") }.padding(.horizontal, 10).padding(.top, 26).padding(.bottom, 7)
                    ForEach(store.playlists) { playlist in
                        Button { store.playlistID = playlist.id; store.page = .library; store.selection = []; store.search = "" } label: {
                            Label(playlist.name, systemImage: "music.note.list").font(.system(size: 13)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading).padding(10).background(store.playlistID == playlist.id ? Color.soriq.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 7))
                        }.buttonStyle(.plain).contextMenu {
                            Button("재생목록 삭제", role: .destructive) { store.playlists.removeAll { $0.id == playlist.id }; if store.playlistID == playlist.id { store.playlistID = nil }; store.persist() }
                        }
                    }
                    if store.playlists.isEmpty { Text("좋아하는 순서로\n음악을 담아보세요.").font(.caption).foregroundStyle(.tertiary).lineSpacing(4).padding(.horizontal, 10) }
                }.padding(.horizontal, 12)
            }
            VStack(spacing: 5) { sideButton(.convert); sideButton(.settings) }.padding(12)
            if store.importing { HStack { ProgressView().controlSize(.small); Text("음원 분석 중…").font(.caption) }.padding(.horizontal, 20).padding(.bottom, 16) }
        }.background(Color(nsColor: .windowBackgroundColor))
    }
    private func sideButton(_ page: Page) -> some View {
        Button { store.page = page; store.playlistID = nil; store.selection = []; store.search = "" } label: {
            HStack(spacing: 11) { Image(systemName: page.icon).frame(width: 18); Text(page.title); Spacer(); if page == .convert && store.converting { ProgressView().controlSize(.mini) } }
                .font(.system(size: 13, weight: store.page == page && store.playlistID == nil ? .semibold : .regular)).padding(10)
                .foregroundStyle(store.page == page && store.playlistID == nil ? Color.soriq : .primary)
                .background(store.page == page && store.playlistID == nil ? Color.soriq.opacity(0.10) : .clear, in: RoundedRectangle(cornerRadius: 7))
        }.buttonStyle(.plain)
    }
}
final class DroppedURLs: @unchecked Sendable {
    private let lock = NSLock(); private var storage: [URL] = []
    func add(_ url: URL) { lock.lock(); storage.append(url); lock.unlock() }
    var urls: [URL] { lock.lock(); defer { lock.unlock() }; return storage }
}

struct LibraryView: View {
    @EnvironmentObject var store: MusicStore
    var title: String { store.playlists.first { $0.id == store.playlistID }?.name ?? store.page.title }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 7) { Text(title).font(.system(size: 28, weight: .bold)); Text("\(store.visibleTracks.count)곡 · 내가 모은 음악, 나의 순서로.").font(.system(size: 13)).foregroundStyle(.secondary) }
                Spacer()
                Button(action: store.chooseFiles) { Label("음원 추가", systemImage: "plus") }.buttonStyle(.borderedProminent).disabled(store.importing)
            }.padding(28)
            HStack(spacing: 12) {
                HStack { Image(systemName: "magnifyingglass").foregroundStyle(.secondary); TextField("곡, 아티스트, 앨범 검색", text: $store.search).textFieldStyle(.plain); if !store.search.isEmpty { Button { store.search = "" } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain).help("검색 지우기") } }.padding(9).background(Color.surface, in: RoundedRectangle(cornerRadius: 8)).frame(maxWidth: 330)
                Spacer()
                Button { if let first = store.visibleTracks.first { store.play(first, context: store.visibleTracks) } } label: { Label("모두 재생", systemImage: "play.fill") }.disabled(store.visibleTracks.isEmpty)
                Menu {
                    Button("ALAC으로 변환") { store.addConversion(store.tracks.filter { store.selection.contains($0.id) }) }
                    Menu("재생목록에 추가") { ForEach(store.playlists) { list in Button(list.name) { store.addToPlaylist(store.selection, list.id) } } }
                    Divider()
                    Button("보관함에서 제거", role: .destructive) { store.remove(store.selection) }
                } label: { Text(store.selection.isEmpty ? "선택한 곡" : "\(store.selection.count)곡 선택") }.disabled(store.selection.isEmpty)
            }.padding(.horizontal, 28).padding(.bottom, 20)
            if store.visibleTracks.isEmpty {
                EmptyState(icon: "music.note", title: store.tracks.isEmpty ? "첫 곡을 들려주세요." : "아직 표시할 곡이 없습니다.", detail: store.tracks.isEmpty ? "FLAC, ALAC, MP3, WAV 파일이나 폴더를 끌어오세요.\n변환하지 않아도 바로 재생할 수 있습니다." : "검색어를 바꾸거나 이 목록에 음악을 추가하세요.", button: "음원 추가", action: store.chooseFiles)
            } else {
                HStack { Text("곡 / 아티스트"); Spacer(); Text("앨범").frame(width: 125, alignment: .leading); Text("형식").frame(width: 70); Text("시간").frame(width: 45); Color.clear.frame(width: 58, height: 1) }.font(.caption).foregroundStyle(.secondary).padding(.horizontal, 39).padding(.bottom, 10)
                Divider().padding(.horizontal, 28)
                List(selection: $store.selection) {
                    ForEach(store.visibleTracks) { track in
                        TrackRow(track: track).tag(track.id).contextMenu {
                            Button("재생") { store.play(track, context: store.visibleTracks) }
                            Button("다음에 재생") { let i = store.queue.firstIndex(of: store.currentID ?? UUID()).map { $0 + 1 } ?? store.queue.count; if !store.queue.contains(track.id) { store.queue.insert(track.id, at: i) } }
                            Button("가사 자동 가져오기 · 파일 저장") { store.automaticLyrics(track) }.disabled(store.automaticLyricsID != nil)
                            Button("음원 정보 / 가사 편집") { store.editing = track }
                            Button(track.favorite ? "좋아하는 곡에서 제거" : "좋아하는 곡에 추가") { store.toggleFavorite(track.id) }
                            Menu("재생목록에 추가") { ForEach(store.playlists) { list in Button(list.name) { store.addToPlaylist([track.id], list.id) } } }
                            Button("ALAC으로 변환") { store.addConversion([track]) }
                            Button("Finder에서 보기") { NSWorkspace.shared.activateFileViewerSelecting([track.url]) }
                            Divider()
                            if let id = store.playlistID { Button("이 재생목록에서 제거") { if let i = store.playlists.firstIndex(where: { $0.id == id }) { store.playlists[i].trackIDs.removeAll { $0 == track.id }; store.persist() } } }
                            Button("보관함에서 제거", role: .destructive) { store.remove([track.id]) }
                        }
                    }
                }.listStyle(.inset).padding(.horizontal, 14)
            }
            HStack { Text(store.notice ?? "원본 파일은 그대로 두고 보관함에 연결합니다.").font(.caption).foregroundStyle(.secondary).lineLimit(1); Spacer(); if store.notice != nil { Button { store.notice = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).help("알림 닫기") } }.padding(.horizontal, 28).padding(.vertical, 13)
        }
    }
}
struct TrackRow: View {
    @EnvironmentObject var store: MusicStore
    let track: Track
    var body: some View {
        HStack(spacing: 12) {
            Button { store.play(track, context: store.visibleTracks) } label: {
                CoverArt(url: track.artwork).frame(width: 42, height: 42).overlay { if store.currentID == track.id { Image(systemName: store.isPlaying ? "waveform" : "pause.fill").foregroundStyle(.white).padding(6).background(.black.opacity(0.55), in: Circle()) } }
            }.buttonStyle(.plain).help("\(track.title) 재생")
            VStack(alignment: .leading, spacing: 5) { Text(track.title).font(.system(size: 13, weight: .medium)).foregroundStyle(store.currentID == track.id ? Color.soriq : .primary).lineLimit(1); Text(track.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1) }.frame(maxWidth: .infinity, alignment: .leading)
            Text(track.album.isEmpty ? "—" : track.album).font(.caption).foregroundStyle(.secondary).lineLimit(1).frame(width: 125, alignment: .leading)
            Text(track.codec.isEmpty ? track.url.pathExtension.uppercased() : track.codec.uppercased()).font(.system(size: 10, weight: .medium, design: .monospaced)).foregroundStyle(.secondary).frame(width: 70)
            Text(clockText(track.duration)).font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 45)
            Button { store.toggleFavorite(track.id) } label: { Image(systemName: track.favorite ? "heart.fill" : "heart").foregroundStyle(track.favorite ? Color.pink : .secondary) }.buttonStyle(.plain).help("좋아하는 곡")
            Button { store.editing = track } label: { Image(systemName: "ellipsis") }.buttonStyle(.plain).help("음원 정보와 가사")
        }.padding(.vertical, 7).contentShape(Rectangle()).onTapGesture(count: 2) { store.play(track, context: store.visibleTracks); store.page = .nowPlaying }
    }
}
struct CoverArt: View {
    let url: URL?
    var body: some View {
        GeometryReader { g in
            Group {
                if let url, let image = ArtworkCache.image(url) { Image(nsImage: image).resizable().scaledToFill() }
                else { ZStack {
                    LinearGradient(colors: [Color(red: 0.20, green: 0.34, blue: 0.59), Color(red: 0.06, green: 0.10, blue: 0.20)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Circle().stroke(.white.opacity(0.12), lineWidth: g.size.width * 0.12).padding(g.size.width * 0.16)
                    Circle().fill(.white.opacity(0.17)).frame(width: g.size.width * 0.15)
                    Image(systemName: "music.note").font(.system(size: max(14, g.size.width * 0.22), weight: .light)).foregroundStyle(.white.opacity(0.85))
                } }
            }.frame(width: g.size.width, height: g.size.height).clipped().clipShape(RoundedRectangle(cornerRadius: min(16, g.size.width * 0.09)))
        }.accessibilityLabel("앨범 커버")
    }
}
struct PlaybackBar: View {
    @EnvironmentObject var store: MusicStore
    @EnvironmentObject var settings: AppSettings
    var body: some View {
        HStack(spacing: 24) {
            Button { store.page = .nowPlaying; store.playlistID = nil } label: {
                HStack(spacing: 11) { CoverArt(url: store.current?.artwork).frame(width: 46, height: 46); VStack(alignment: .leading, spacing: 5) { Text(store.current?.title ?? "음악을 선택하세요").font(.system(size: 12, weight: .semibold)).lineLimit(1); Text(store.current?.subtitle ?? "원본 그대로, 바로 재생").font(.caption).foregroundStyle(.secondary).lineLimit(1) } }.frame(width: 220, alignment: .leading)
            }.buttonStyle(.plain).help("지금 재생 화면 열기")
            VStack(spacing: 5) {
                HStack(spacing: 22) {
                    ControlButton("셔플", icon: "shuffle", active: store.shuffle) { store.shuffle.toggle() }
                    ControlButton("이전 곡", icon: "backward.end.fill", action: store.previous)
                    Button(action: store.togglePlayback) { Image(systemName: store.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20, weight: .semibold)).frame(width: 30, height: 28) }.buttonStyle(.plain).disabled(store.tracks.isEmpty).help("재생 / 일시 정지 · ⌘P")
                    ControlButton("다음 곡", icon: "forward.end.fill") { store.next() }
                    ControlButton(store.repeatMode == 0 ? "반복 꺼짐" : store.repeatMode == 1 ? "전체 반복" : "한 곡 반복", icon: store.repeatMode == 2 ? "repeat.1" : "repeat", active: store.repeatMode != 0) { store.repeatMode = (store.repeatMode + 1) % 3 }
                }
                HStack(spacing: 9) { Text(clockText(store.position)).frame(width: 34); Slider(value: Binding(get: { store.position }, set: store.seek), in: 0...max(1, store.duration)).controlSize(.mini).disabled(store.current == nil).accessibilityLabel("재생 위치"); Text(clockText(store.duration)).frame(width: 34) }.font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity)
            HStack(spacing: 8) { Image(systemName: settings.volume == 0 ? "speaker.slash" : "speaker.wave.2").foregroundStyle(.secondary); Slider(value: $settings.volume, in: 0...1).frame(width: 85).controlSize(.small).accessibilityLabel("볼륨"); Button { store.page = .nowPlaying } label: { Image(systemName: "text.bubble") }.buttonStyle(.plain).help("실시간 가사") }.frame(width: 156)
        }.padding(.horizontal, 24).padding(.vertical, 15).background(Color.surface)
    }
}
struct ControlButton: View {
    let title: String; let icon: String; var active = false; let action: () -> Void
    init(_ title: String, icon: String, active: Bool = false, action: @escaping () -> Void) { self.title = title; self.icon = icon; self.active = active; self.action = action }
    var body: some View { Button(action: action) { Image(systemName: icon).font(.system(size: 14)).foregroundStyle(active ? Color.soriq : .secondary).frame(width: 22, height: 26) }.buttonStyle(.plain).help(title).accessibilityLabel(title) }
}
struct NowPlayingView: View {
    @EnvironmentObject var store: MusicStore
    @EnvironmentObject var settings: AppSettings
    @Environment(\.colorScheme) var scheme
    @State private var showQueue = false
    @State private var follow = true
    var body: some View {
        ZStack {
            Color.canvas
            if let track = store.current {
                GeometryReader { g in CoverArt(url: track.artwork).frame(width: g.size.width, height: g.size.height).blur(radius: 90).opacity(scheme == .dark ? 0.3 : 0.08).clipped() }.allowsHitTesting(false)
                VStack(spacing: 0) {
                    HStack {
                        Text(showQueue ? "재생 대기열" : "지금 재생").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary); Spacer()
                        Toggle("가사 따라가기", isOn: $follow).toggleStyle(.switch).controlSize(.mini).font(.caption).disabled(showQueue)
                        Button { showQueue.toggle() } label: { Image(systemName: showQueue ? "text.quote" : "list.bullet") }.buttonStyle(.plain).help(showQueue ? "가사 보기" : "대기열 보기")
                        Button { NSApp.keyWindow?.toggleFullScreen(nil) } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }.buttonStyle(.plain).help("전체 화면")
                    }.padding(28)
                    HStack(spacing: 46) {
                        VStack(alignment: .leading, spacing: 22) {
                            CoverArt(url: track.artwork).aspectRatio(1, contentMode: .fit).shadow(color: .black.opacity(0.15), radius: 24, y: 14)
                            VStack(alignment: .leading, spacing: 9) {
                                HStack(alignment: .top) { Text(track.title).font(.system(size: 25, weight: .bold)).lineLimit(3); Spacer(); Button { store.toggleFavorite(track.id) } label: { Image(systemName: track.favorite ? "heart.fill" : "heart").foregroundStyle(track.favorite ? Color.pink : .secondary) }.buttonStyle(.plain).help("좋아하는 곡") }
                                Text(track.subtitle).font(.system(size: 17)).foregroundStyle(.secondary)
                                if !track.album.isEmpty { Text(track.album).font(.system(size: 13)).foregroundStyle(.secondary) }
                            }
                            Text(track.format).font(.system(size: 10, weight: .medium, design: .monospaced)).foregroundStyle(.secondary)
                            HStack { Button { store.editing = track } label: { Label("정보 · 가사", systemImage: "info.circle") }; Button { store.addConversion([track]) } label: { Label("변환", systemImage: "arrow.triangle.2.circlepath") } }.controlSize(.small)
                        }.frame(minWidth: 230, idealWidth: 300, maxWidth: 330)
                        if showQueue { queueView.frame(maxWidth: .infinity, maxHeight: .infinity) }
                        else { lyricsView(track).frame(maxWidth: .infinity, maxHeight: .infinity) }
                    }.padding(.horizontal, 38).padding(.top, 12).padding(.bottom, 38)
                }
            } else { EmptyState(icon: "headphones", title: "음악에 머무는 시간.", detail: "보관함에서 곡을 재생하세요.\n앨범 커버와 가사가 이곳에 함께 펼쳐집니다.", button: "보관함 열기") { store.page = .library } }
        }
    }
    private var queueView: some View {
        ScrollView { LazyVStack(spacing: 8) { ForEach(Array(store.queue.enumerated()), id: \.offset) { index, id in
            if let track = store.tracks.first(where: { $0.id == id }) {
                HStack {
                    Text(String(format: "%02d", index + 1)).font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 25)
                    Button { store.play(track) } label: { VStack(alignment: .leading, spacing: 5) { Text(track.title).lineLimit(1).foregroundStyle(id == store.currentID ? Color.soriq : .primary); Text(track.subtitle).font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading) }.buttonStyle(.plain)
                    Button { store.queue.remove(at: index) } label: { Image(systemName: "xmark") }.buttonStyle(.plain).help("대기열에서 제거")
                }.padding(12).background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 8))
            }
        } } }
    }
    @ViewBuilder private func lyricsView(_ track: Track) -> some View {
        let lines = LyricsParser.parse(track.lyrics)
        let active = LyricsParser.activeIndex(lines, at: store.position + settings.lyricOffset)
        if !lines.isEmpty {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 24) {
                        Color.clear.frame(height: 100)
                        ForEach(lines) { line in
                            Button { store.seek(line.time - settings.lyricOffset) } label: {
                                Text(line.text.isEmpty ? "♪" : line.text).font(.system(size: 29, weight: .bold)).lineSpacing(5).multilineTextAlignment(.leading)
                                    .foregroundStyle(active == line.id ? Color.primary : Color.primary.opacity(0.28)).scaleEffect(active == line.id ? 1 : 0.97, anchor: .leading).frame(maxWidth: .infinity, alignment: .leading)
                            }.buttonStyle(.plain).id(line.id).accessibilityLabel("\(clockText(line.time)), \(line.text)").padding(.vertical, 2)
                        }
                        Color.clear.frame(height: 160)
                    }.padding(.horizontal, 5)
                }.scrollIndicators(.hidden)
                    .onChange(of: active) { if follow, let active { withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(active, anchor: .center) } } }
                    .onChange(of: follow) { if follow, let active { proxy.scrollTo(active, anchor: .center) } }
                    .onAppear { if let active { proxy.scrollTo(active, anchor: .center) } }
            }
        } else if !track.lyrics.isEmpty {
            ScrollView { VStack(alignment: .leading, spacing: 22) { Label("시간 정보 없는 가사", systemImage: "text.alignleft").font(.caption).foregroundStyle(.secondary); Text(track.lyrics).font(.system(size: 24, weight: .semibold)).lineSpacing(14).frame(maxWidth: .infinity, alignment: .leading) }.padding(.vertical, 60) }
        } else {
            VStack(alignment: .leading, spacing: 18) { Image(systemName: "text.quote").font(.system(size: 32)).foregroundStyle(.tertiary); Text("가사도 함께 들어요.").font(.system(size: 25, weight: .semibold)); Text("LRC 파일을 불러오거나 가사를 검색하세요.\n시간 정보가 있으면 재생에 맞춰 따라갑니다.").font(.system(size: 14)).foregroundStyle(.secondary).lineSpacing(6); Button(store.automaticLyricsID == track.id ? "가사 가져오는 중…" : "가사 자동 가져오기") { store.automaticLyrics(track) }.buttonStyle(.borderedProminent).disabled(store.automaticLyricsID != nil); Button("직접 검색 / 파일 가져오기") { store.editing = track }.buttonStyle(.bordered) }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
struct EmptyState: View {
    let icon: String; let title: String; let detail: String; let button: String; let action: () -> Void
    var body: some View { VStack(spacing: 18) { Image(systemName: icon).font(.system(size: 42, weight: .ultraLight)).foregroundStyle(.secondary); Text(title).font(.system(size: 23, weight: .semibold)); Text(detail).font(.system(size: 13)).foregroundStyle(.secondary).multilineTextAlignment(.center).lineSpacing(6); Button(button, action: action).buttonStyle(.bordered) }.frame(maxWidth: .infinity, maxHeight: .infinity).padding(30) }
}
struct ConversionView: View {
    @EnvironmentObject var store: MusicStore
    @EnvironmentObject var settings: AppSettings
    @State private var showHistory = false
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack { VStack(alignment: .leading, spacing: 7) { Text("음원 변환").font(.system(size: 28, weight: .bold)); Text("듣던 음악을 ALAC으로. 재생과 변환은 따로 움직입니다.").font(.system(size: 13)).foregroundStyle(.secondary) }; Spacer(); Button { showHistory.toggle() } label: { Label(showHistory ? "대기열" : "작업 기록", systemImage: "clock.arrow.circlepath") } }
            if showHistory {
                List(store.history) { item in VStack(alignment: .leading, spacing: 6) { HStack { Text(item.title).fontWeight(.medium); Spacer(); Text(item.date, style: .date).font(.caption).foregroundStyle(.secondary) }; Text(item.detail).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }.padding(.vertical, 7) }.listStyle(.inset)
            } else {
                HStack(alignment: .top, spacing: 24) {
                    VStack(alignment: .leading, spacing: 9) { Text("출력 규격").font(.caption).foregroundStyle(.secondary); Picker("출력 규격", selection: $settings.preset) { ForEach(AudioPreset.allCases) { Text($0.title).tag($0) } }.labelsHidden().frame(width: 210); Text("높은 수치로 바꿔도 원본 음질이 개선되지는 않습니다.").font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }.frame(width: 245)
                    VStack(alignment: .leading, spacing: 9) { Text("저장 위치").font(.caption).foregroundStyle(.secondary); Text(settings.outputFolder.isEmpty ? "원본 파일 옆" : settings.outputFolder).font(.system(size: 12)).lineLimit(2).truncationMode(.middle); HStack { Button("폴더 선택") { chooseFolder(settings) }; Button("원본 옆") { settings.outputFolder = "" } }.controlSize(.small) }
                    Spacer()
                }.padding(20).background(Color.surface, in: RoundedRectangle(cornerRadius: 10)).disabled(store.converting)
                HStack { Text("변환 대기열 · \(store.jobs.count)곡").font(.system(size: 14, weight: .semibold)); Spacer(); Button("보관함에서 선택") { store.page = .library }.disabled(store.converting); Button("목록 비우기") { store.jobs = [] }.disabled(store.converting || store.jobs.isEmpty) }
                if store.jobs.isEmpty {
                    EmptyState(icon: "arrow.triangle.2.circlepath", title: "변환할 음악을 담아주세요.", detail: "보관함에서 곡을 선택한 뒤 ‘ALAC으로 변환’을 누르세요.\n기존 결과 파일은 덮어쓰지 않습니다.", button: "보관함 열기") { store.page = .library }
                } else {
                    ScrollView { LazyVStack(spacing: 0) { ForEach(store.jobs) { job in ConversionRow(job: job); Divider() } } }
                }
                HStack { Text("원본은 보존 · 같은 이름은 번호를 붙여 저장").font(.caption).foregroundStyle(.secondary); Spacer(); if store.converting { Button("변환 취소", role: .destructive, action: store.cancelConversion) } else { Button("\(store.jobs.count)곡 변환 시작", action: store.convert).buttonStyle(.borderedProminent).disabled(store.jobs.isEmpty || store.savingMetadata) } }
            }
        }.padding(28)
    }
}
struct ConversionRow: View {
    @EnvironmentObject var store: MusicStore
    let job: ConversionJob
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack { CoverArt(url: job.track.artwork).frame(width: 36, height: 36); VStack(alignment: .leading, spacing: 4) { Text(job.track.title).font(.system(size: 13, weight: .medium)); Text(job.track.format).font(.caption).foregroundStyle(.secondary) }; Spacer(); Text(job.status).font(.caption).foregroundStyle(job.status == "실패" ? Color.red : .secondary)
                if let url = job.output {
                    Button { NSWorkspace.shared.activateFileViewerSelecting([url]) } label: { Image(systemName: "folder") }.help("결과 파일 보기")
                    Button { if let track = store.tracks.first(where: { $0.url == url }) { store.play(track) } else { var copy = job.track; copy.id = UUID(); copy.url = url; copy.codec = "alac"; store.tracks.append(copy); store.persist(); store.play(copy) } } label: { Image(systemName: "play.fill") }.help("변환 파일 재생")
                }
            }
            if job.status == "변환 중" { ProgressView(value: job.progress) }
            if let error = job.error { Text(error).font(.caption).foregroundStyle(.red).textSelection(.enabled) }
        }.padding(.vertical, 14)
    }
}
func chooseFolder(_ settings: AppSettings) { let panel = NSOpenPanel(); panel.canChooseFiles = false; panel.canChooseDirectories = true; if panel.runModal() == .OK { settings.outputFolder = panel.url?.path ?? "" } }
struct PreferencesView: View {
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var store: MusicStore
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("설정").font(.system(size: 28, weight: .bold)).padding(.horizontal, 28).padding(.top, 28)
            Form {
                Section("화면") { Picker("화면 모드", selection: $settings.appearance) { ForEach(Appearance.allCases) { Text($0.title).tag($0) } }.pickerStyle(.segmented) }
                Section("재생") {
                    Toggle("곡이 끝나면 다음 곡 재생", isOn: $settings.autoAdvance)
                    HStack { Text("볼륨"); Slider(value: $settings.volume, in: 0...1); Text("\(Int(settings.volume * 100))%").monospacedDigit().frame(width: 38) }
                    Text("출력 장치는 macOS의 사운드 설정을 따릅니다.").font(.caption).foregroundStyle(.secondary)
                }
                Section("가사") {
                    HStack { Text("동기화 보정"); Spacer(); Stepper(value: $settings.lyricOffset, in: -10...10, step: 0.1) { Text(String(format: "%+.1f초", settings.lyricOffset)).monospacedDigit() }.frame(width: 140); Button("초기화") { settings.lyricOffset = 0 } }
                    Text("양수는 가사를 더 일찍 표시합니다. 시간 정보가 있는 LRC에만 적용됩니다.").font(.caption).foregroundStyle(.secondary)
                }
                Section("변환") {
                    Picker("기본 출력 규격", selection: $settings.preset) { ForEach(AudioPreset.allCases) { Text($0.title).tag($0) } }
                    Toggle("변환된 파일을 보관함에 추가", isOn: $settings.importConverted)
                    HStack { Text(settings.outputFolder.isEmpty ? "저장: 원본 파일 옆" : settings.outputFolder).lineLimit(1).truncationMode(.middle); Spacer(); Button("변경") { chooseFolder(settings) } }
                }
                Section("Cool Player") {
                    Text("2.1.0 · 음악 감상, 실시간 가사, 음원 관리와 변환")
                    HStack { Link("홈페이지", destination: URL(string: "https://eumgyeol-mac.rainybuddy128.chatgpt.site")!); Link("소스 코드", destination: URL(string: "https://github.com/hun8902/CoolPlayer")!); Spacer(); Text("younghun kim").foregroundStyle(.secondary) }
                    Text("음악 재생과 변환은 로컬에서 처리합니다. 가사·커버 검색을 누를 때 곡 정보를 벅스·LRCLIB·NetEase·iTunes에 전송합니다.").font(.caption).foregroundStyle(.secondary)
                }
            }.formStyle(.grouped)
        }
    }
}
struct TrackEditor: View {
    @EnvironmentObject var store: MusicStore
    @EnvironmentObject var settings: AppSettings
    @Environment(\.dismiss) var dismiss
    @State var track: Track
    @State private var tab = 0
    @State private var message = ""
    @State private var coverSearch = false
    @State private var lyricsQuery = ""
    @State private var lyricsArtist = ""
    @State private var selectedLyricRequest = UUID()
    var body: some View {
        VStack(spacing: 0) {
            HStack { Text("음원 정보와 가사").font(.title3.bold()); Spacer(); Button("닫기") { dismiss() } }.padding(24)
            Picker("편집", selection: $tab) { Text("음원 정보").tag(0); Text("가사").tag(1) }.pickerStyle(.segmented).padding(.horizontal, 24)
            if tab == 0 { info.padding(24) } else { lyrics.padding(24) }
            Divider()
            HStack {
                Text(message).font(.caption).foregroundStyle(.secondary).lineLimit(2); Spacer()
                Button("보관함에 저장") { store.updateTrack(track); message = "보관함에 저장했습니다. 원본 파일은 변경하지 않았습니다." }
                Button(store.savingMetadata ? "저장 중…" : "원본 태그에 저장") { store.saveSourceMetadata(track) }.disabled(store.savingMetadata || store.converting).help("백업을 만든 뒤 제목, 아티스트, 앨범, 커버를 원본에 기록합니다.")
            }.padding(20)
        }.frame(width: 840, height: 720)
        .onAppear { lyricsQuery = track.title; lyricsArtist = track.artist }
        .onChange(of: store.automaticLyricsID) { _, value in if value == nil, let updated = store.tracks.first(where: { $0.id == track.id }) { track.lyrics = updated.lyrics } }
        .sheet(isPresented: $coverSearch) { CoverSearchView(query: [track.artist, track.album.isEmpty ? track.title : track.album].joined(separator: " "), artwork: $track.artwork) }
    }
    private var info: some View {
        HStack(alignment: .top, spacing: 25) {
            VStack(spacing: 14) { CoverArt(url: track.artwork).frame(width: 190, height: 190); Button("커버 이미지 변경…", action: chooseCover); Button("앨범 커버 검색…") { coverSearch = true }; Text("원본 태그에 저장하면\n백업 파일을 함께 만듭니다.").font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center) }
            VStack(alignment: .leading, spacing: 17) {
                LabeledContent("제목") { TextField("제목", text: $track.title) }
                LabeledContent("아티스트") { TextField("아티스트", text: $track.artist) }
                LabeledContent("앨범") { TextField("앨범", text: $track.album) }
                Divider()
                LabeledContent("오디오", value: track.format)
                LabeledContent("길이", value: clockText(track.duration))
                LabeledContent("채널", value: "\(track.channels)")
                Text(track.url.path).font(.caption).foregroundStyle(.secondary).textSelection(.enabled).lineLimit(3).truncationMode(.middle)
                Button("Finder에서 보기") { NSWorkspace.shared.activateFileViewerSelecting([track.url]) }
                Spacer()
            }.textFieldStyle(.roundedBorder).font(.system(size: 13))
        }
    }
    private var lyrics: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button(store.automaticLyricsID == track.id ? "가사 가져오는 중…" : "가사 자동 가져오기 · 파일 저장") { store.automaticLyrics(track) }
                    .buttonStyle(.borderedProminent).disabled(store.automaticLyricsID != nil)
                Text("현재 곡 정보로 찾아 음원 옆에 LRC / TXT를 저장합니다.").font(.caption).foregroundStyle(.secondary)
            }
            HStack(alignment: .bottom, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("곡명 / 검색어").font(.caption).foregroundStyle(.secondary)
                    TextField("한글·영문으로 직접 입력", text: $lyricsQuery)
                        .onSubmit { runLyricsSearch() }
                }
                VStack(alignment: .leading, spacing: 5) {
                    Text("가수 (선택)").font(.caption).foregroundStyle(.secondary)
                    TextField("가수만 입력해도 검색 가능", text: $lyricsArtist)
                        .onSubmit { runLyricsSearch() }
                }
                Button(store.searchingLyrics ? "검색 중…" : "검색", action: runLyricsSearch)
                    .disabled(store.searchingLyrics || (lyricsQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && lyricsArtist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                Button("곡 정보로 채우기") { lyricsQuery = track.title; lyricsArtist = track.artist }
            }.textFieldStyle(.roundedBorder)
            Text("검색어를 바꿔도 음원 정보는 바뀌지 않습니다. 검색 결과의 곡명·가수명은 서비스에 등록된 표기입니다.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if let notice = store.notice { Text(notice).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
            HSplitView {
                VStack(alignment: .leading, spacing: 8) {
                    Text("검색 결과").font(.headline)
                    if store.lyricsTarget == track.id && !store.lyricResults.isEmpty {
                        ScrollView {
                            LazyVStack(spacing: 6) {
                                ForEach(store.lyricResults, id: \.identity) { result in
                                    Button {
                                        let request = UUID(); selectedLyricRequest = request
                                        message = "가사를 불러오는 중…"
                                        Task {
                                            do {
                                                let value = try await LyricsService.resolve(result)
                                                guard selectedLyricRequest == request else { return }
                                                guard !value.isEmpty else { message = "이 결과에 등록된 가사가 없습니다. 다른 결과를 선택하세요."; return }
                                                track.lyrics = value
                                                message = "가사를 불러왔습니다. 보관함에 저장하거나 가사 파일로 저장하세요."
                                            } catch { if selectedLyricRequest == request { message = error.localizedDescription } }
                                        }
                                    } label: {
                                        VStack(alignment: .leading, spacing: 5) {
                                            Text(result.trackName).fontWeight(.medium).lineLimit(2)
                                            Text(result.artistName + " · " + result.source).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                            HStack { Text(result.albumName ?? "").lineLimit(1); Spacer(); Text(clockText(result.duration ?? 0)); Text(["NetEase", "벅스"].contains(result.source) ? "선택해 불러오기" : result.syncedLyrics?.isEmpty == false ? "동기화" : "일반") }.font(.caption2).foregroundStyle(.secondary)
                                        }.frame(maxWidth: .infinity, alignment: .leading).padding(10).background(Color.surface, in: RoundedRectangle(cornerRadius: 7))
                                    }.buttonStyle(.plain).help(result.trackName + " · " + result.artistName)
                                }
                                if store.hasMoreLyrics {
                                    Button { store.loadMoreLyrics() } label: {
                                        HStack { if store.searchingLyrics { ProgressView().controlSize(.small) }; Text(store.searchingLyrics ? "추가 결과 불러오는 중…" : "결과 더 불러오기") }.frame(maxWidth: .infinity).padding(10)
                                    }.disabled(store.searchingLyrics).onAppear { store.loadMoreLyrics() }
                                } else { Text("마지막 검색 결과입니다.").font(.caption).foregroundStyle(.secondary).padding(8) }
                            }
                        }
                    } else {
                        Text(store.searchingLyrics ? "가사를 찾고 있습니다…" : "위에 곡명이나 가수를 입력하세요.\n영문으로 등록된 곡은 한글 검색에 나오지 않을 수 있습니다.")
                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Spacer()
                    }
                }.frame(minWidth: 230, idealWidth: 285, maxWidth: 340).padding(.trailing, 10)
                VStack(alignment: .leading, spacing: 8) {
                    HStack { Text("가사 편집").font(.headline); Spacer(); Text(LyricsParser.parse(track.lyrics).isEmpty ? "일반 가사" : "동기화 가사").font(.caption).foregroundStyle(.secondary) }
                    TextEditor(text: $track.lyrics).font(.system(size: 13)).padding(8).background(Color.surface).clipShape(RoundedRectangle(cornerRadius: 8)).overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.line))
                }.frame(minWidth: 320).padding(.leading, 10)
            }.frame(maxHeight: .infinity)
            HStack {
                Button("LRC / TXT 가져오기…", action: importLyrics)
                Button("가사 파일 저장…", action: exportLyrics).disabled(track.lyrics.isEmpty)
                Spacer()
                Button("현재 재생 시각 삽입") { let value = store.position; track.lyrics += (track.lyrics.isEmpty ? "" : "\n") + String(format: "[%02d:%05.2f]", Int(value) / 60, value.truncatingRemainder(dividingBy: 60)) }.disabled(store.currentID != track.id)
            }
        }.frame(maxHeight: .infinity)
    }
    private func runLyricsSearch() { store.searchLyrics(track, query: lyricsQuery, artist: lyricsArtist) }
    private func chooseCover() {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.image]
        if panel.runModal() == .OK, let source = panel.url {
            guard let image = NSImage(contentsOf: source), let data = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: data), let png = bitmap.representation(using: .png, properties: [:]) else { message = "지원하지 않는 이미지입니다."; return }
            let target = MusicStore.dataFolder.appendingPathComponent("\(UUID()).png")
            do { try png.write(to: target); track.artwork = target } catch { message = error.localizedDescription }
        }
    }
    private func importLyrics() {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.plainText, UTType(filenameExtension: "lrc") ?? .plainText]
        if panel.runModal() == .OK, let url = panel.url { do { track.lyrics = try String(contentsOf: url, encoding: .utf8); message = "보관함에 저장하면 재생 화면에 적용됩니다." } catch { message = error.localizedDescription } }
    }
    private func exportLyrics() {
        let panel = NSSavePanel(); let synced = !LyricsParser.parse(track.lyrics).isEmpty
        panel.nameFieldStringValue = track.url.deletingPathExtension().lastPathComponent + (synced ? ".lrc" : ".txt")
        if panel.runModal() == .OK, let url = panel.url { do { try track.lyrics.write(to: url, atomically: true, encoding: .utf8); store.updateTrack(track); store.record("가사 파일 저장", url.lastPathComponent); message = "저장 완료: \(url.lastPathComponent)" } catch { message = error.localizedDescription } }
    }
}


@MainActor enum ArtworkCache {
    private static let cache: NSCache<NSURL, NSImage> = { let c = NSCache<NSURL, NSImage>(); c.countLimit = 100; return c }()
    static func image(_ url: URL) -> NSImage? {
        if let image = cache.object(forKey: url as NSURL) { return image }
        guard let image = NSImage(contentsOf: url) else { return nil }
        cache.setObject(image, forKey: url as NSURL); return image
    }
}
struct AlbumResult: Decodable, Identifiable {
    let collectionName: String?; let artistName: String?; let artworkUrl100: String?
    var id: String { artworkUrl100 ?? (collectionName ?? "") }
    var imageURL: URL? { guard let artworkUrl100 else { return nil }; return URL(string: artworkUrl100.replacingOccurrences(of: "100x100bb", with: "600x600bb").replacingOccurrences(of: "http://", with: "https://")) }
}
struct AlbumResponse: Decodable { let results: [AlbumResult] }
struct CoverSearchView: View {
    let query: String
    @Binding var artwork: URL?
    @Environment(\.dismiss) var dismiss
    @State private var results: [AlbumResult] = []
    @State private var loading = true
    @State private var message = "iTunes에서 앨범을 검색합니다."
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("앨범 커버 검색").font(.title3.bold()); Spacer(); Button("닫기") { dismiss() } }
            Text(query).foregroundStyle(.secondary).lineLimit(2)
            if loading { ProgressView().frame(maxWidth: .infinity) }
            Text(message).font(.caption).foregroundStyle(.secondary)
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 125))], spacing: 18) {
                    ForEach(results) { album in
                        Button { select(album) } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                AsyncImage(url: album.imageURL) { image in image.resizable().scaledToFit() } placeholder: { Color.secondary.opacity(0.1) }.frame(height: 125)
                                Text(album.collectionName ?? "앨범").font(.caption).lineLimit(2)
                                Text(album.artistName ?? "").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).disabled(loading)
                    }
                }
            }
        }.padding(24).frame(width: 530, height: 500).task {
            do {
                var c = URLComponents(string: "https://itunes.apple.com/search")!
                c.queryItems = [URLQueryItem(name: "term", value: query), URLQueryItem(name: "entity", value: "album"), URLQueryItem(name: "limit", value: "12")]
                var request = URLRequest(url: c.url!); request.timeoutInterval = 20
                let (data, response) = try await URLSession.shared.data(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw ToolFailure(message: "검색 서비스에 연결하지 못했습니다.") }
                results = try JSONDecoder().decode(AlbumResponse.self, from: data).results.filter { $0.imageURL != nil }
                message = results.isEmpty ? "검색 결과가 없습니다. 아티스트와 앨범 정보를 확인하세요." : "원하는 커버를 선택하세요. 저장 전까지 원본 파일은 바뀌지 않습니다."
            } catch { message = error.localizedDescription }
            loading = false
        }
    }
    private func select(_ album: AlbumResult) {
        guard let url = album.imageURL else { return }; loading = true
        Task {
            defer { loading = false }
            do {
                let (data, response) = try await URLSession.shared.data(from: url)
                guard (response as? HTTPURLResponse)?.statusCode == 200, data.count < 20_000_000, let image = NSImage(data: data), let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff), let png = bitmap.representation(using: .png, properties: [:]) else { throw ToolFailure(message: "이미지를 가져올 수 없습니다.") }
                let file = MusicStore.dataFolder.appendingPathComponent("\(UUID()).png"); try png.write(to: file); artwork = file; dismiss()
            } catch { message = error.localizedDescription }
        }
    }
}
