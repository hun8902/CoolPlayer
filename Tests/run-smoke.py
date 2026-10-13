#!/usr/bin/env python3
"""macOS smoke checks using the actual app models and conversion worker. Requires Swift + FFmpeg."""
from pathlib import Path
import shutil, subprocess, tempfile
project = Path(__file__).resolve().parents[1]
ffmpeg = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
with tempfile.TemporaryDirectory(prefix="coolplayer-tests-") as scratch:
    root = Path(scratch)
    for name, rate, duration in [("audio-tests/verification.flac", 96000, 2), ("demo/Blue-Hour.flac", 48000, 60)]:
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", f"sine=frequency=220:sample_rate={rate}:duration={duration}", "-c:a", "flac", "-sample_fmt", "s32", str(target)], check=True)
    sources = project / "Sources/CoolPlayerApp"
    worker = (sources / "CoolPlayerApp.swift").read_text().split("final class ConversionWorker:", 1)[1].split("// Search providers", 1)[0]
    script = root / "Checks.swift"
    script.write_text((sources / "Models.swift").read_text() + "\nfinal class ConversionWorker:" + worker + "\n" + (project / "Tests/AudioSmokeTests.swift").read_text())
    subprocess.run(["swift", "-module-cache-path", str(root / "cache"), str(script), str(root)], check=True)
