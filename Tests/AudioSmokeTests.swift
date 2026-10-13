import AVFoundation
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let fixture = root.appendingPathComponent("audio-tests/verification.flac")
var source = Track(url: fixture, title: "QA round trip")
source.duration = 2
let folder = root.appendingPathComponent("test-results", isDirectory: true)
try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
func probe(_ url: URL) throws -> [String:Any] {
 let data = try runTool("ffprobe", ["-v","error","-select_streams","a:0","-show_streams","-of","json",url.path])
 return (try JSONSerialization.jsonObject(with: data) as! [String:Any])["streams"].flatMap { $0 as? [[String:Any]] }!.first!
}
let sourceProbe = try probe(fixture)
var first: URL?
for preset in AudioPreset.allCases {
 let result = try ConversionWorker().convert(source, preset: preset, folder: folder.path) { _ in }.get()
 let info = try probe(result)
 precondition(info["codec_name"] as? String == "alac")
 let expectedRate = preset == .original ? sourceProbe["sample_rate"] as! String : preset.arguments.last!
 precondition(info["sample_rate"] as? String == expectedRate)
 if preset == .original { first = result }
 print("PASS conversion", preset.rawValue, expectedRate)
}
let before = try Data(contentsOf: first!)
let second = try ConversionWorker().convert(source, preset: .original, folder: folder.path) { _ in }.get()
precondition(second != first! && (try! Data(contentsOf: first!)) == before)
let hashArgs = ["-v","error","-i"]
let sourceHash = try runTool("ffmpeg", hashArgs + [fixture.path,"-map","0:a:0","-c:a","pcm_s32le","-f","hash","-hash","sha256","-"])
let outputHash = try runTool("ffmpeg", hashArgs + [first!.path,"-map","0:a:0","-c:a","pcm_s32le","-f","hash","-hash","sha256","-"])
precondition(sourceHash == outputHash)
print("PASS decoded PCM bit-perfect and duplicate output protection")
let cancelled = ConversionWorker(); cancelled.cancel()
if case .success = cancelled.convert(source, preset: .original, folder: folder.path, progress: { _ in }) { fatalError("Cancellation ignored") }
let remainingFiles = try FileManager.default.contentsOfDirectory(atPath: folder.path)
precondition(!remainingFiles.contains { $0.hasPrefix(".soriq-") })
print("PASS cancellation and temporary-file cleanup")
let lines = LyricsParser.parse("[offset:-100]\n[00:01.50][00:03.125]Hello\n[00:02.00]World\n[00:90.00]invalid\nplain")
precondition(lines.count == 3 && abs(lines[0].time - 1.4) < 0.001 && abs(lines[2].time - 3.025) < 0.001)
precondition(LyricsParser.activeIndex(lines, at: 0) == nil && LyricsParser.activeIndex(lines, at: 2) == 1)
precondition(LyricsParser.parse("plain lyrics").isEmpty)
print("PASS LRC multi-tags, millisecond precision, offset, invalid timestamp and active-line boundaries")
let demo = root.appendingPathComponent("demo/Blue-Hour.flac")
let audio = try AVAudioPlayer(contentsOf: demo); audio.volume = 0
precondition(audio.prepareToPlay() && audio.play())
RunLoop.current.run(until: Date().addingTimeInterval(0.3))
precondition(audio.currentTime > 0); audio.pause(); audio.currentTime = 24
precondition(abs(audio.currentTime - 24) < 0.05)
print("PASS native FLAC playback, pause and seek", audio.duration)
let encoded = try AVAudioPlayer(contentsOf: first!); precondition(encoded.prepareToPlay())
print("PASS ALAC playback loading")
print("ALL CHECKS PASSED")
