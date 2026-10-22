# Google Play 제출 (1.4.20 / 25)

- 프로젝트: CoolPlayer/android
- applicationId: com.coolplayer.android
- GitHub APK 자동 확인·다운로드·설치 코드와 REQUEST_INSTALL_PACKAGES 권한 제거.
- 새 런처 아이콘 적용. R8 코드 최적화/난독화 및 리소스 축소 적용.
- 홈페이지 파일 변경 없음.

## 기존 업로드 키로 서명

Android Studio → Build → Generate Signed App Bundle / APK → Android App Bundle.
이전에 Play에 올린 AAB를 만들 때 사용한 동일한 keystore와 alias를 선택하고 release로 생성합니다.
비밀번호는 Android Studio에 직접 입력합니다.

기존 android/app/release/app-release.aab는 사용자 원본으로 보존했습니다.
이번 작업 중 build/outputs/bundle/release/app-release.aab는 미서명 검사 산출물입니다.
그 파일을 그대로 올리지 말고 위 메뉴로 서명된 AAB를 생성하세요.

## Play Console

내부 테스트의 편집 중인 릴리스에서 서명된 AAB를 업로드하거나 라이브러리에서 추가합니다.
버전 코드 25가 포함된 것을 확인하고 저장 → 검토로 진행합니다.
이미 25를 사용한 경우 app/build.gradle의 versionCode를 더 큰 값으로 변경한 뒤 다시 생성합니다.
첨부 오류는 릴리스에 번들이 포함되지 않았다는 의미이며, 코드 수정만으로 콘솔의 빈 릴리스가 채워지지는 않습니다.

R8 mapping은 AAB의 BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map에 포함됩니다.
