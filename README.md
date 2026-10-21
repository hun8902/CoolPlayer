# Cool Player

<img src="docs/assets/icon.png" width="110" alt="Cool Player 아이콘">

**Mac과 Android에서 내 음악과 가사를 함께 듣는 플레이어입니다. Mac에서는 음원 정보 편집과 ALAC 변환도 지원합니다.**

[홈페이지](https://hun8902.github.io/CoolPlayer/) · [Mac용 다운로드](https://github.com/hun8902/CoolPlayer/releases/download/v2.1.0/Cool-Player-Mac.zip) · [Android APK](https://github.com/hun8902/CoolPlayer/releases/download/android-v1.4.16/Cool-Player-Android.apk) · [업데이트 내역](https://github.com/hun8902/CoolPlayer/releases) · [문제 제보](https://github.com/hun8902/CoolPlayer/issues)

## Android

Android 8.1 이상에서 FLAC/WAV/MP3/AAC/Ogg/Opus 재생, 앨범과 재생목록, 실시간 가사, 가사 자동·수동 검색, 앨범아트 변경, 백그라운드 재생, 홈 화면 위젯을 지원합니다. 펼친 폴드와 태블릿에서는 앨범아트·재생 제어와 가사를 좌우로 나란히 표시합니다.

**업데이트:** 앱의 **설정 → 앱 업데이트 → 업데이트 확인**을 사용하세요. 자신의 설치본에 맞는 GitHub 릴리스를 내려받고 Android 설치 화면에서 업데이트할 수 있습니다. 자동 확인은 하루에 한 번이며 다운로드와 설치는 직접 선택합니다. 기존 앱을 삭제하지 마세요.


[Android 사용법과 빌드 안내](android/README.md) · [릴리스 배포 안내](scripts/ANDROID-RELEASE.md)

## Mac 지원 환경

- macOS 14 이상
- Apple Silicon Mac
- FLAC, ALAC/M4A, MP3, WAV, AIFF, AAC, CAF

릴리스 ZIP에는 FFmpeg와 FFprobe가 포함됩니다. 음악 파일은 원래 위치에 보관하며, 앱은 파일을 보관함에 연결합니다.

## 설치

1. [Mac 릴리스](https://github.com/hun8902/CoolPlayer/releases/tag/v2.1.0)에서 `Cool-Player-Mac.zip`을 다운로드합니다.
2. 압축을 풀고 `Cool Player.app`을 응용 프로그램 폴더로 옮깁니다.
3. 앱을 열고 음원 파일 또는 폴더를 추가합니다.

현재 배포 파일은 ad-hoc 서명이며 Apple 공증은 적용되지 않았습니다. macOS의 실행 확인이 표시될 수 있습니다.

## 사용법

### 음악 재생과 보관함

`음원 추가` 또는 `⌘O`로 파일과 폴더를 추가합니다. 변환하지 않은 원본도 바로 재생할 수 있습니다. 곡·아티스트·앨범으로 검색하고, 좋아하는 곡과 재생목록을 만들어 음악을 정리합니다.

재생 대기열, 다음에 재생, 셔플, 반복, 볼륨과 재생 위치 탐색을 지원합니다. `⌘P`로 재생과 일시정지를 전환합니다. 보관함에서 제거해도 원본 파일은 삭제하지 않습니다.

### 가사 자동 가져오기

`지금 재생`의 `가사 자동 가져오기` 또는 곡 메뉴의 `가사 자동 가져오기 · 파일 저장`을 누릅니다. 곡명과 가수가 일치하는 후보에서 가사를 가져와 플레이어에 적용합니다.

- 시간 정보가 있으면 음원 옆에 같은 이름의 `.lrc` 파일을 저장합니다.
- 일반 가사이면 `.txt` 파일을 저장합니다.
- 같은 이름의 가사 파일이 있으면 백업 후 저장합니다.
- 시간 정보가 있는 가사는 현재 줄을 강조하고 따라갑니다. 가사를 누르면 해당 위치로 이동합니다.

### 가사 직접 검색과 편집

`정보 · 가사`에서 `가사` 탭을 엽니다. 곡명과 가수를 직접 입력해 검색하고 결과를 선택합니다. 검색어를 바꿔도 음원 태그는 바뀌지 않습니다.

벅스, LRCLIB, NetEase를 사용합니다. 다음 페이지를 제공하는 검색원은 목록 아래에서 추가 결과를 불러옵니다. LRCLIB는 페이지 기능이 없어 해당 검색의 반환 결과만 표시합니다. 등록되지 않은 곡, 다른 언어로 등록된 곡, 연결이 제한된 검색원에서는 결과가 없을 수 있습니다.

LRC/TXT 파일 가져오기, 직접 편집, 현재 재생 시각 삽입, 가사 파일 저장도 지원합니다. 시간 정보가 없는 가사는 일반 텍스트로 표시되며 자동 동기화되지 않습니다. 설정에서 가사 표시 시간을 보정할 수 있습니다.

### 음원 정보와 커버

곡 메뉴에서 `음원 정보 / 가사 편집`을 엽니다. 제목·아티스트·앨범과 커버를 바꿀 수 있습니다. 커버는 로컬 파일을 선택하거나 앨범 검색으로 가져옵니다.

- **보관함에 저장:** 앱의 보관함에 적용합니다.
- **원본 태그에 저장:** 원본 백업을 만든 뒤 파일의 태그와 커버에 반영합니다. 오디오는 재인코딩하지 않습니다.

### ALAC 변환

곡을 선택하고 `ALAC으로 변환`을 눌러 대기열에 담습니다. 출력 폴더와 규격을 선택해 변환합니다.

| 출력 설정 | 설명 |
| --- | --- |
| 원본 유지 | 원본의 비트 깊이와 샘플레이트 유지 |
| 16-bit / 44.1 kHz | CD 규격 |
| 24-bit / 48 kHz | 지정 규격으로 변환 |
| 24-bit / 96 kHz | 지정 규격으로 변환 |
| 24-bit / 192 kHz | 지정 규격으로 변환 |

원본 파일은 유지됩니다. 같은 출력 이름이 있으면 새 이름을 사용합니다. 진행률 표시, 변환 취소, 완료 기록, 결과 자동 보관함 추가를 지원합니다. 원본 유지 설정은 24-bit 이하에서 지원하며, 그보다 높은 원본은 출력 규격을 선택해야 합니다. 샘플레이트나 비트 깊이를 높인다고 원본에 없는 음질 정보가 생기지는 않습니다.

### 설정

시스템·라이트·다크 모드, 자동 다음 곡 재생, 가사 시간 보정, 기본 출력 폴더·규격, 변환 결과 자동 추가를 설정합니다.

## 파일과 네트워크

- 재생, 변환과 보관함 관리는 Mac에서 처리합니다.
- 가사 검색 시 곡명·가수 등의 검색어를 벅스·LRCLIB·NetEase에 전송합니다.
- 커버 검색 시 검색어를 Apple iTunes 검색 서비스에 전송합니다.
- 보관함과 편집 내용은 `~/Library/Application Support/CoolPlayer/`에 저장합니다.
- 홈페이지에서 연 파일은 브라우저 안에서만 사용하며 서버에 업로드하지 않습니다.

## 소스에서 빌드

Xcode와 FFmpeg가 필요합니다. 빌드 단계에서 로컬 FFmpeg·FFprobe 및 필요한 라이브러리를 앱에 포함합니다.

```sh
brew install ffmpeg
xcodebuild -project CoolPlayer.xcodeproj -scheme CoolPlayer \
  -configuration Release -derivedDataPath build \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=YES build
```

| 경로 | 내용 |
| --- | --- |
| `Sources/CoolPlayerApp/` | SwiftUI 앱, 재생·가사·변환 처리 |
| `docs/` | GitHub Pages 홈페이지와 웹 플레이어 |
| `Tests/` | 오디오 및 변환 스모크 도구 |
| `scripts/package-release.sh` | 릴리스 앱과 ZIP 생성 |

GitHub Pages는 `main` 브랜치의 `docs/` 폴더를 게시합니다. Mac 다운로드는 `v2.1.0`의 `Cool-Player-Mac.zip`, Android 다운로드는 해당 Android 릴리스의 APK로 연결됩니다.

## 홈페이지 음원

웹 플레이어의 기본 곡은 Rocks From The Garden이 부른 **Amazing Grace**입니다. 연주자가 퍼블릭 도메인으로 공개한 2006년 라이브의 첫 절을 발췌했으며 해당 녹음에 맞춘 LRC를 함께 제공합니다. 음원·가사·녹음 출처는 [음원 안내](docs/assets/ATTRIBUTION.md)에 기록합니다.

## 문제 제보

[Issues](https://github.com/hun8902/CoolPlayer/issues)에 macOS 버전, 음원 형식, 수행한 작업과 표시된 오류를 남겨 주세요. 가사 문제는 곡명·가수와 선택한 검색원을 함께 적어 주세요. 개인 음악 파일을 공개로 첨부할 필요는 없습니다.

## Android

안드로이드 앱 소스와 빌드 방법은 [android/README.md](android/README.md)에 있습니다. Android 8.1 이상을 대상으로 FLAC 재생, 앨범아트, 실시간 가사, 백그라운드 재생, 홈 화면 위젯과 재생목록을 구현했습니다. macOS 앱과는 별도 네이티브 프로젝트이며, 지원 범위와 설치 방법은 안드로이드 문서에서 확인할 수 있습니다.


### 1.4.12 Android 업데이트

기존 셔플 버튼의 무작위 재생과 활성 표시를 정리하고, 반복 상태를 꺼짐·전체·한 곡으로 구분했습니다. 미니 플레이어는 화면 크기에 관계없이 하단에 고정되도록 배치를 수정했습니다.

기존 앱의 **설정 → 앱 업데이트 → 업데이트 확인**에서 새 버전을 설치하세요. 1.4.1 사용자를 위한 같은 패키지의 호환 릴리스도 제공합니다. 기존 앱을 삭제하지 않아야 보관함과 설정이 유지됩니다. 앱에 별도 기기 승인 절차는 없습니다.
