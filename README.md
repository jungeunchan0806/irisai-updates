# Iris Android 오토골 연구 작업

이 소스는 **브롤스타즈 친선전투에서 검증된 최종 APK가 아닙니다.** 원본 Iris 0.0.9의 공 인식·운반·슈팅 판단을 Android Java로 옮긴 코어와, 게임 프로세스 내부 입력 방식의 독립 실험을 포함합니다. 앱 설치만으로 다른 앱의 프로세스 안에서 실행할 권한이 생기지는 않습니다.

## 확인한 실행 방식

`inprocess/src/agent.js`는 허용된 대상 프로세스 안에서 Android `Activity.dispatchTouchEvent`를 호출합니다. 시스템 전체에 터치를 주입하거나, 브롤스타즈 네이티브 함수 주소를 추측해서 호출하지 않습니다. 실행에는 이미 허가된 Frida 연결 등 별도의 진입 조건이 필요합니다. 루팅을 수행하거나 게임 보호 기능을 변경하는 스크립트는 포함하지 않습니다.

이 방식이 사용자의 현재 브롤스타즈 버전에서 허용되고 정상 작동하는지는 확인되지 않았습니다. 공개된 다른 게임 후킹 프로젝트의 버전 호환 주장만으로 검증 완료 처리하지 않았으며, 재배포 금지 소스는 복사하지 않았습니다.

## 구성

- `app/src/main/java/com/irisai/autogoal/`: 공·골대 인식, ONNX 객체 탐지, 경로 탐색, 소유 판단, 입력 허용 상태.
- `app/src/test/`: 녹화 화면과 판단 로직 테스트. 실제 경기를 실행하는 테스트가 아닙니다.
- `inprocess/`: 독립 작성한 Frida 입력 모듈 및 명령 검증 테스트.
- `probe/`: 자체 입력 실험용 Android 앱. 브롤스타즈나 친선전투 시뮬레이터가 아닙니다.
- `tools/android_preflight.py`: 연결된 기기 및 설치된 게임 버전 확인. 기본 실행은 읽기 전용이며 루팅하지 않습니다.
- `tools/test_inprocess_lab.py`: 자체 실험 앱에만 연결하는 입력 통합 테스트.
- `investigation/`: 출처, 실제 검증 범위, 미실시 친선전투 점검 항목.

현재 오프라인 자동 테스트는 Java 30개, JavaScript 13개가 통과했습니다. JavaScript 스케줄링 테스트의 Android 인터페이스는 테스트 대역이므로 실제 안드로이드 호환성 검증으로 계산하지 않습니다.

메인 `app`의 설정 화면·화면 공유 서비스·최종 입력 백엔드 통합은 아직 완료되지 않았습니다. 매니페스트에 선언된 `MainActivity`, `OverlayService`, `GameAccessibility`는 이전 화면 공유 방식의 설계 흔적이며 실행 가능한 완성 앱으로 배포하지 않습니다.

## 빌드와 자동 테스트

JDK 17, Android SDK 35, Node.js 22 이상이 필요합니다. `ANDROID_HOME`을 SDK 경로로 설정하세요.

```sh
./gradlew :app:testDebugUnitTest :probe:assembleDebug
cd inprocess
npm ci
npm test
npm run build
```

Windows에서는 `gradlew.bat`을 사용합니다. 메인 앱의 출시 빌드와 실제 친선전투 검증은 별도 단계입니다. 키스토어·비밀번호·개인 로그는 소스에 포함하지 않습니다.

## 기기 확인

```sh
python tools/android_preflight.py --adb /path/to/adb --out device-report.json
```

여러 기기가 연결되어 있으면 `--serial`을 지정하세요. 이미 설치된 루트 관리자에서 권한을 확인하려면 명시적으로 `--check-root`를 사용할 수 있습니다. 이 옵션은 `su -c id`만 실행하며 기기를 루팅하지 않습니다.

## 내부 입력 실험 재현

자신이 관리하는 테스트 에뮬레이터에 `probe-debug.apk`와 공식 Frida 서버를 준비한 뒤, 같은 버전의 Python `frida` 패키지를 사용합니다. Frida 서버는 localhost에만 바인딩하세요.

```sh
python tools/test_inprocess_lab.py --adb /path/to/adb --serial emulator-5554
```

테스트는 대상 프로세스 내부에서 DOWN/MOVE/UP 전달, OFF 시 CANCEL, 재활성화 후 오래된 명령 거절, 다른 앱으로 전환했을 때의 거절, 연결 상태 갱신 중단 시 거절을 검사합니다. 이 결과를 브롤스타즈 호환성이나 친선전투 성공으로 해석하면 안 됩니다.

## 원본과 라이선스

오토골 로직과 모델·이미지는 사용자가 제공한 [IrisAI 0.0.9 소스](https://github.com/jungeunchan0806/irisai-updates/releases/tag/v0.0.9)에서 가져왔습니다. 원본 라이선스는 `IRIS_LICENSE.txt`, 자산 해시는 `app/src/main/assets/source.json`에 있습니다. Java 이식본에는 원본의 CC BY-NC 4.0 조건을 적용합니다. 사용 의존성의 라이선스는 각 패키지를 따릅니다.
