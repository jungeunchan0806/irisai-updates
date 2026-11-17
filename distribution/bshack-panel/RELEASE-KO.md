다운로드할 파일: **BSHACK-Autogoal-2.1.0-panel-test.apk**

사용자가 제공한 `BSHACK-Autogoal-Licensed.apk`를 기준으로 수정했습니다. 이 파일과 같은 AOSP 테스트 인증서로 서명했습니다. Supercell 공식 서명이나 이전 Iris panel3 서명이 아닙니다. 제공된 기준 APK의 매니페스트 버전은 `70.648 (70648)`이며 그대로 유지했습니다.

- 기존 AndroidManifest, 게임 DEX, 네이티브 라이브러리와 실제 게임 내부 오토골 엔진 보존.
- 네이티브 렌더링 콜백의 Java 호출을 분리하고 기존 네이티브 HUD 할당 대신 별도 Android UI 사용.
- 오토골 ON/OFF, 오른쪽 위 2배 크기 투명 메뉴 버튼, 전체 UI 숨김·복원, 한국어/영어, Discord 링크.
- 기존 HWID·인증 서버 유지. 길게 눌러 HWID 복사, 메뉴에서 키 등록. 라이선스 상태·남은 기간 카드는 없음.
- 녹화에서만 UI를 제외하는 스트리머 모드는 지원하지 않으며 비활성화되어 있습니다.

기존 BSHACK와 서명이 같고 버전 코드도 같아 Android 업데이트 설치를 시도할 수 있습니다. 다른 서명인 Iris panel3나 공식 앱 위에는 그대로 업데이트 설치되지 않습니다. 앱 삭제는 데이터·기기 인증에 영향을 줄 수 있으므로 기존 앱을 먼저 삭제하지 마세요.

38,360개 원본 항목 바이트 비교, 전체 CRC, 16KiB ZIP 정렬, v2/v3 서명 검사를 통과했습니다. 실제 삼성 Android 17 폰과 친선전투는 여기서 테스트하지 않았습니다. 인메모리 DEX 및 UI 검사는 테스트 Activity에서 수행하며 실제 게임 성공을 뜻하지 않습니다.

검사 결과:
- [Android UI 검사 14개 및 배포 성공](https://github.com/jungeunchan0806/irisai-updates/actions/runs/44098793891). UI는 게임을 모사한 테스트 Activity에서 확인했습니다.
- [동일 APK 단독 설치 검사](https://github.com/jungeunchan0806/irisai-updates/actions/runs/44099321018): 설치 성공, 실제 게임은 ARM 번역 환경의 `libosu.so` 초기화 중 SIGABRT로 종료했습니다.
- [원본·수정본 비교](https://github.com/jungeunchan0806/irisai-updates/actions/runs/44096952479): 제공된 기준 APK도 같은 네이티브 위치에서 종료됐습니다. 최신 파일의 설치 기록은 위 단독 설치 검사를 기준으로 보세요.

**삼성 Android 17에서 튕김이 해결됐다고 확인된 완성본이 아닙니다.**

[수정 소스](https://github.com/jungeunchan0806/irisai-updates/tree/android-bshack-panel) · [키 관리 서버 및 패널 소스](https://github.com/jungeunchan0806/irisai-updates/tree/android-bshack-panel/seller-panel). 관리자 삭제 화면과 판매자 소유 키 정지·삭제 API는 배포했습니다. 판매자 APK/EXE의 새 버튼은 내장 화면 변경본만 준비됐으며 원래 빌드 프로젝트가 있어야 기존 기기 인증을 유지한 앱 업데이트를 만들 수 있습니다.
