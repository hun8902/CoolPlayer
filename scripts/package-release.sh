#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
xcodebuild -project CoolPlayer.xcodeproj -scheme CoolPlayer \
  -configuration Release -derivedDataPath build \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=YES build
mkdir -p build/distribution
/usr/bin/ditto build/Build/Products/Release/CoolPlayer.app 'build/distribution/Cool Player.app'
/usr/bin/ditto -c -k --sequesterRsrc --keepParent 'build/distribution/Cool Player.app' build/Cool-Player-Mac.zip
shasum -a 256 build/Cool-Player-Mac.zip > build/SHA256SUMS.txt
