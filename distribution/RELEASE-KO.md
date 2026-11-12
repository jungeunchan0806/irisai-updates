<!-- iris-packaging2-start -->
## 재시도 시험본: 메타데이터 보존

이번에 받을 파일은 **`IrisAutoGoal-BrawlStars-70.757-packaging2-test.apk`**입니다. 약 1.97GB이며 게임 APK입니다. 작은 `Startup-Diagnostic1.apk`는 별도의 진단용입니다.

이전 패키징에서 삭제됐던 원본 `META-INF` 파일 128개를 보존했습니다. 서비스 등록 파일 2개, Kotlin/Android 메타데이터와 라이선스 안내가 포함됩니다. 오토골·게임 코드 변경 없이 이 누락만 수정했습니다. 서명·정렬·원본 코드와 자산·복원한 메타데이터 검사를 통과했습니다.

**폰의 `nv.s: 01` 종료가 해결됐는지는 아직 미확인입니다. 정상 실행이 검증된 최종본이 아닙니다.** 게임의 원본 보호 코드와 공식 앱과 다른 서명은 그대로이며, 패키징 누락 수정이 이 오류를 해결한다고 단정하지 않습니다.

기존 후보 또는 `Iris 오류 확인` 앱을 **삭제하지 않고 업데이트 설치**한 뒤 설치 화면의 **열기** 또는 브롤스타즈 아이콘으로 실행하세요. 같은 패키지·버전 코드·서명으로 빌드했으며 데이터 삭제 기능은 없습니다. 공식 Supercell 앱 위에는 설치할 수 없습니다. 설치가 거절되면 앱을 지우지 말고 오류 문구를 알려주세요.

실행 직후 다시 종료된다면 이 시험본에서 나온 종료 기록이 필요합니다. 기존 진단 APK를 업데이트 설치해 `내용 복사`로 확인할 수 있습니다.

[이번 수정 소스](https://github.com/jungeunchan0806/irisai-updates/tree/android-packaging-retry) · [검증 범위](https://github.com/jungeunchan0806/irisai-updates/blob/android-packaging-retry/investigation/packaging2-validation.json)
<!-- iris-packaging2-end -->


**현재 상태: 실기기에서 로고 표시 전 종료 확인. 실행 가능한 수정본은 아직 없습니다.** 기존 `IrisAutoGoal-BrawlStars-70.757-candidate1.apk`를 다시 설치해도 이번 오류가 수정되지는 않습니다.

사용자가 제공한 Android 17/API 37 ARM64 로그에서 `nv.s: 01` 예외가 확인됐습니다. 원본 게임의 `libsupercell_brawlstars.so`를 불러오는 중이며, Iris 초기화 제공자와 오토골 모델 실행 전입니다. 같은 폰에서 공식 앱은 정상 실행됐다고 보고됐습니다. 재패키징·재서명과 관련된 원본 초기화/무결성 문제를 의심하지만, 내부 오류 코드 `01`의 정확한 의미와 수정 방법은 확인하지 못했습니다. [분석 범위와 한계](https://github.com/jungeunchan0806/irisai-updates/blob/android-ingame-70.757/investigation/phone-startup-failure.md)

이 릴리스에 추가한 **`IrisAutoGoal-Startup-Diagnostic1.apk`(약 21KB)는 오류 기록 확인용**입니다. 게임 수정본이 아닙니다. 기존 후보 위에 업데이트 설치하면 앱 데이터는 유지하면서 게임 실행 파일을 임시 진단 화면으로 바꿉니다. 공식 Supercell 앱 위에는 서명이 달라 설치할 수 없습니다. 진단 결과를 이미 전달했다면 다시 설치할 필요는 없습니다.

브롤스타즈 **70.757 / Android 12L 이상 / ARM64**용 오토골 **실행 후보**입니다. 게임 내부에 ON/OFF 버튼과 화면 인식·내부 JNI 입력 모듈을 결합했습니다.

게임이 실행되면 버튼을 한 번 눌러 켜고 다시 눌러 끕니다. 길게 누르면 이동·공격·가젯 버튼 위치를 지정합니다. 직접 게임 화면을 누르거나 앱을 벗어나면 꺼집니다. 화면 공유·접근성·ADB 터치 권한은 사용하지 않습니다.

**실제 게임 실행과 친선전투는 검증되지 않았습니다. 오류 없는 최종본이 아닙니다.** 빌드, Java 자동 테스트 39개, v2/v3 서명·파일 정렬·원본 자산 유지 검사를 통과했습니다. 첨부 검증 JSON에서 범위를 확인하세요.

에뮬레이터 검사에서는 KVM 가속 Android 35 Google APIs / Google Play 환경에 설치됐으나 **원본과 수정본 모두 같은 `nv.Q` 예외로 시작 중 종료**됐습니다. 이는 위의 실제 폰에서 보고된 main 스레드 `nv.s: 01`과 다른 실패입니다. 게임 안의 버튼·오토골 동작과 친선전투는 확인하지 못했습니다. [원본 비교 실행 기록](https://github.com/jungeunchan0806/irisai-updates/actions/runs/43221305252)

공식 앱과 서명이 달라 기존 설치본에 덮어쓰거나 함께 설치할 수 없습니다. 기존 앱을 삭제하거나 데이터를 지우는 기능은 포함하지 않습니다. 게임의 무결성·서버 버전 검사로 실행이나 접속이 거절될 수 있습니다.

APK 용량은 약 1.97GB입니다. 원본 게임 데이터가 포함되어 큰 파일입니다. `.sha256`은 다운로드 무결성 확인용입니다. macOS IrisAI 0.0.9용 파일이 아니며 기존 Mac 자동 업데이트 피드는 유지됩니다.

[수정 소스와 빌드 안내](https://github.com/jungeunchan0806/irisai-updates/tree/android-ingame-70.757)
