<!-- iris-packaging2-start -->
## 재시도 시험본: 메타데이터 보존

이번에 받을 파일은 **`IrisAutoGoal-BrawlStars-70.757-packaging2-test.apk`**입니다. 약 1.97GB이며 게임 APK입니다. 작은 `Startup-Diagnostic1.apk`는 별도의 진단용입니다.

이전 패키징에서 삭제됐던 원본 `META-INF` 파일 128개를 보존했습니다. 서비스 등록 파일 2개, Kotlin/Android 메타데이터와 라이선스 안내가 포함됩니다. 오토골·게임 코드 변경 없이 이 누락만 수정했습니다. 서명·정렬·원본 코드와 자산·복원한 메타데이터 검사를 통과했습니다.

**폰의 `nv.s: 01` 종료가 해결됐는지는 아직 미확인입니다. 정상 실행이 검증된 최종본이 아닙니다.** 게임의 원본 보호 코드와 공식 앱과 다른 서명은 그대로이며, 패키징 누락 수정이 이 오류를 해결한다고 단정하지 않습니다.

기존 후보 또는 `Iris 오류 확인` 앱을 **삭제하지 않고 업데이트 설치**한 뒤 설치 화면의 **열기** 또는 브롤스타즈 아이콘으로 실행하세요. 같은 패키지·버전 코드·서명으로 빌드했으며 데이터 삭제 기능은 없습니다. 공식 Supercell 앱 위에는 설치할 수 없습니다. 설치가 거절되면 앱을 지우지 말고 오류 문구를 알려주세요.

실행 직후 다시 종료된다면 이 시험본에서 나온 종료 기록이 필요합니다. 기존 진단 APK를 업데이트 설치해 `내용 복사`로 확인할 수 있습니다.

[이번 수정 소스](https://github.com/jungeunchan0806/irisai-updates/tree/android-packaging-retry) · [검증 범위](https://github.com/jungeunchan0806/irisai-updates/blob/android-packaging-retry/investigation/packaging2-validation.json)
<!-- iris-packaging2-end -->
