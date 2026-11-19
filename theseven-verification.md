# 더세븐 오토팜 2.0.1 검증 기록

기준 APK의 versionCode는 5, 내장 연습 데이터는 v3, 배포된 서명 데이터 패치는 v4입니다. 최소 지원 버전은 Android 12L(API 32)입니다. 개발용 빈 에뮬레이터와 독립 시뮬레이터를 사용했습니다.

| 대상 | 실행과 결과 |
|---|---|
| Android 단위 검사 | Gradle testDebugUnitTest 17개 통과. 기존 이동 정책 6개, 데이터·모의 행동·예약 상태·추천·패치 검증 11개. |
| Android 빌드 | 일반·판매자 assembleRelease와 두 모듈의 lintDebug 완료. 두 APK의 서명과 zip 정렬 확인. |
| 서버 | 기존 검사를 포함한 Node 테스트 25개 통과. 정상 등록, 만료, 철회·복원, 잘못된 키, 다른 기기 바인딩, 승인 밖 발급 거절, 서명 재사용 방지 및 권한 분리 검사. 기존 서버에 버전 11 배포. |
| Android 12L / API 32 | 실제 2.0.0 앱의 업데이트 대화상자에서 서명된 2.0.1 APK를 내려받아 Android 설치를 승인했습니다. 업데이트 후 브롤러·반복·직접 지정 장비가 있는 예약을 보존했고, 새 앱에서 v4 데이터 패치도 적용했습니다. |
| Android 15 / API 35 | 실제 1.0.2 위에 일반 APK를 설치하고 기존 브롤러·반복 설정·추가 키 보존 확인. 데이터 패치 설치·재시작, 별도 톱니바퀴와 빠른 설정 수정·저장, 연습 화면, 예약 편집·취소, 먼 페이지의 노리 자동 픽, 잠금·누락·중복 방지, 슬롯별 자동·수동 장비, Discord·개발자 표시, 운영 서버의 미승인 판매자 거절까지 9개 흐름 통과. |
| 원본 인증서 | 1.0.2 및 일반 2.0.1 APK 서명 SHA-256은 75fbedd4009cc3f38872ef4b2d12646a7b0fef4eac40f2f6c42e4cfff2a66f7b. |
| 배포 파일 | 일반 APK 148443바이트, 판매자 APK 45774바이트. 공개 GitHub Release의 파일 4개를 다시 내려받아 로컬에서 확인한 파일과 바이트 해시가 일치함을 확인했습니다. |

[Android 12L 앱 내 APK 업데이트 로그](https://github.com/jungeunchan0806/irisai-updates/actions/runs/44538971215) · [Android 15 실행 로그](https://github.com/jungeunchan0806/irisai-updates/actions/runs/44538087392) · [화면 캡처와 검사 결과 ZIP](https://github.com/jungeunchan0806/irisai-updates/actions/runs/44538087392/artifacts/14537865572)

운영 서버에서는 미가입 단말의 미등록 응답과 판매자 미승인 응답을 확인했습니다. 정상·만료·철회 키의 발급과 검증은 테스트 데이터베이스에서 수행했습니다. 사용자 삼성 실물 기기에서의 검사는 수행하지 않았습니다.
