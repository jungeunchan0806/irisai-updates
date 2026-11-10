# PylaAI Windows + IrisAI 게임 로직 (0.1.0)

Windows 10/11 x64용 실행·연결 패키지입니다. PylaAI `9b13d0f`를 기반으로 IrisAI 0.0.9 소스의 오토골, 자동 플레이 전략, 벽 탈출과 라운드 재개 로직을 이식했습니다. Mac 앱이나 Sparkle 업데이트 파일을 실행하지 않습니다.

## 처음 실행

1. ZIP 전체를 쓰기 가능한 폴더에 풉니다. 예: `C:\Users\사용자\PylaAI-Windows`. ZIP 안에서 직접 실행하지 마세요.
2. BlueStacks 5 또는 LDPlayer 9를 설치하고 브롤스타즈에 로그인합니다. 게임 언어는 English, 기본 컨트롤 배치를 사용하세요. 권장 해상도는 1920×1080입니다.
3. **Start-Windows.bat**를 실행합니다. Python 3.11 x64가 없으면 Windows의 winget으로 사용자 계정에 설치하고, 전용 `.venv`에 필요한 라이브러리를 준비합니다. 최초 설치에는 인터넷이 필요합니다. winget이 없는 PC는 Python 3.11 x64를 한 번 설치해야 합니다.
4. 에뮬레이터 감지 → 지원되는 ADB 설정 활성화 → 에뮬레이터 실행 → Android 부팅 확인 → ADB 연결 → 브롤스타즈 실행 → Pyla 화면 순서로 진행합니다.
5. 대기열에 브롤러를 추가하고 플레이 방식을 선택합니다. `Iris Aggressive Balanced` 등 `iris_` 전략이 이식본입니다. 일반 **Start**는 자동 플레이, **오토골 시작**은 브롤볼 자동 플레이를 시작합니다. 실행 중에도 오토골을 켜고 끌 수 있습니다.

이 배포는 Python을 자동 설치하는 소스 실행 패키지이며, Python이 내장된 단독 EXE는 아닙니다. 실제 Windows PC/에뮬레이터에서의 설치·경기 검증은 아직 하지 못했습니다.

## 에뮬레이터 지원 범위

| 제품 | 설치 감지·실행·ADB 연결 | ADB 자동 활성화 |
|---|---|---|
| BlueStacks 5 | 레지스트리/설치 폴더, 인스턴스별 설정 포트 | 에뮬레이터가 종료되어 있고 알려진 `bluestacks.conf` 형식일 때 |
| LDPlayer 9 | 설치 폴더/레지스트리, `leidian*.config` 인스턴스 | 종료 상태의 기존 `basicSettings.adbDebug` 키를 로컬 연결(1)로 변경 |
| MuMu/Nox | 알려진 경로 또는 직접 지정한 실행 파일 | 기본 ADB 연결을 시도; 꺼져 있거나 지원하지 않는 버전이면 설정에서 한 번 활성화 |

ADB 설정을 바꾸기 전 원본 파일을 같은 위치의 `.pyla-시간.bak`에 보관합니다. 실행 중인 에뮬레이터의 설정 파일은 수정하지 않습니다. ADB가 꺼진 채 실행 중이면 에뮬레이터를 종료하고 다시 Start-Windows.bat를 실행하세요. 쓰기 권한이 없는 설치는 해당 에뮬레이터의 설정 화면에서 ADB를 켜야 합니다.

이미 연결된 로컬 에뮬레이터가 하나면 이를 사용합니다. 여러 대가 켜져 있으면 임의로 제어하지 않고 General의 **Emulator Port**를 지정하도록 안내합니다. 0은 자동이며, 예전 기본값 5037은 자동으로 해석합니다. 5037 자체는 ADB 서버 포트여서 기기 연결 대상으로 쓰지 않습니다.

다른 드라이브의 설치가 감지되지 않으면 **에뮬레이터 경로**에 `HD-Player.exe`, `ldconsole.exe`, `Nox.exe`, `MuMuPlayer.exe`의 전체 경로를 입력하세요. BlueStacks는 `Pie64` 같은 인스턴스 이름, LDPlayer는 `0` 같은 번호도 지정할 수 있습니다. 연결이 끊어졌을 때는 사용하던 기기 포트에만 재연결하며 전역 ADB 서버를 종료하지 않습니다.

## 게임 기능

- IrisAI의 브롤볼 화면 인식 자료와 오토골 코드를 포함합니다. 축구공 점수 아이콘으로 브롤볼을 확인한 뒤 공을 찾고 획득·운반·조준 슛합니다. 다른 모드에서는 일반 플레이 전략으로 돌아갑니다.
- 일반 플레이 전략 9개, 벽·구석·골기둥 회피, 라운드 시작/득점 뒤 조이스틱 재입력을 이식했습니다.
- 자기 골대, 상대가 더 가까운 공, 가려진 공, 막힌 슛 경로는 테스트로 검사합니다. 실제 승률이나 자동 득점을 보장하는 기능은 아닙니다.
- 기존 Windows Pyla의 ONNX 모델과 추론 경로를 사용합니다. 전달받은 Mac 런타임·GUI·업데이터는 포함하지 않았습니다.
- 초기 설정의 게임 패키지는 `com.supercell.brawlstars`입니다. 다른 패키지는 General에서 지정하세요.

## 자동 패치

Windows 전용 피드: `https://raw.githubusercontent.com/jungeunchan0806/irisai-updates/windows-updates/windows-manifest.json`

Start-Windows.bat는 앱을 켜기 전에 이 피드에서 바뀐 코드·화면 자료만 받아 SHA-256을 확인합니다. 다운로드 또는 적용 실패 시 설치된 버전으로 시작하고, 중간에 끊긴 적용은 다음 시작 때 원복합니다. 앱이 실행 중이면 수동 패치도 잠금으로 거부합니다. 실행 중인 경기를 업데이트 때문에 재시작하지 않습니다.

- `cfg/`, 사용자 대기열, 모델, 로그는 이 코드 패치의 교체 대상이 아닙니다.
- 자동 업데이트도 변경된 파일은 내려받습니다. 사용자가 매번 새 ZIP을 받아 덮어쓸 필요가 없다는 뜻입니다.
- 직접 수정한 코드가 발견되면 덮어쓰지 않고 오류를 표시합니다. 백업은 `.pyla-backups`에 남습니다.
- 피드는 HTTPS와 파일 체크섬을 사용합니다. 이 Windows 소스 패치는 macOS Sparkle 서명이나 Windows Authenticode 서명 패키지가 아닙니다.
- 자동 업데이트를 끄려면 루트의 `update-settings.json`을 `{"auto_update": false}`로 저장합니다.
- 다른 신뢰하는 피드를 등록하려면 `Update-Windows.bat --manifest https://서버/windows-manifest.json`을 실행합니다.
- Mac의 `appcast.xml`과 Windows 피드는 별개입니다. Windows 업데이트 배포에 기존 Mac 릴리스의 latest 주소를 사용하지 마세요.

기존 **소스 설치본**에는 `Apply-Windows-Patch.bat`를 실행해 `main.py`와 `cfg`가 있는 폴더를 고를 수 있습니다. 다른 버전/수정된 코드/컴파일된 EXE 설치본에는 적용을 거부할 수 있습니다. 이때는 전체 Windows ZIP을 별도 폴더에 설치하세요.

## 유튜브 학습 상태

이 버전의 모델은 유튜브 강의로 추가 학습된 모델이 아닙니다. 현재 작업 환경에서는 YouTube 검색 요청이 HTTP 403으로 막혔고 학습한 영상은 0개입니다. 영상 다운로드만으로 이동·공략을 학습했다고 표시하지 않았습니다. 영상 접근, 학습 목표·라벨과 검증 데이터가 마련되어야 별도 학습이 가능합니다.

## 문제 확인과 검증

- 연결 오류: `Diagnose-Windows.bat` → `logs/windows-diagnostics.json`
- 시작 로그: `logs/windows-startup.log`
- 의존성 재설치: `Setup-Windows.bat`
- 로직 검사: `python -m pytest -q tests`

검사는 Linux에서 실행한 Windows API 모의 테스트와 실제 제공된 경기 화면/ONNX 모델 검사입니다. Windows 설치·GPU·실제 에뮬레이터 터치와 경기 결과는 현장 검증이 필요합니다.

원본: https://github.com/PylaAI/PylaAI 및 https://github.com/rarebinary/IrisAI . 전달받은 수정 소스: https://github.com/jungeunchan0806/irisai-updates/releases/tag/v0.0.9 . 라이선스는 LICENSE와 IRIS_LICENSE.txt를 확인하세요. 출처와 원본 해시는 pyla_addons/iris_source_provenance.json에 기록했습니다.

## 다음 패치 제작

소스를 수정한 뒤 테스트를 통과시키고 `python -m pyla_addons.build_patch --version 0.1.1 --release-url https://raw.githubusercontent.com/jungeunchan0806/irisai-updates/windows-updates`로 만듭니다. `patch-release/patch-*`와 `windows-manifest.json`을 `windows-updates` 브랜치에 함께 커밋합니다. 파일명에 내용 해시가 포함되므로 이전 payload는 남겨두어도 됩니다. 다음 앱 실행 때 자동으로 받습니다. 버전은 숫자 세 자리 형식이며 설치된 버전보다 낮은 패치는 거부합니다. Mac 릴리스와 main 브랜치를 Windows 업데이트 배포에 사용하지 않습니다.
