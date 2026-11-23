import json
from pathlib import Path
r=json.loads(Path('bshack-game-launch/result.json').read_text())
c=json.loads(Path('bridgecheck-results/checks.json').read_text())
print('''## 오토골 단독판 2.2.5-static · 실기기 미검증 빌드

설치 파일: **Iris-AutoGoal-2.2.5-no-update-no-afk.apk** (약 2 GB)

- 자동패치 다운로드·적용·복구와 업데이트 메뉴 삭제. 이전에 저장된 패치도 읽지 않습니다.
- Anti AFK가 추가되기 전 단독 엔진 사용. Anti AFK 및 연결 상태에 따른 별도 입력 보류 기능 없음.
- 투명 메뉴 버튼은 상단 중앙. 큰 UI 열기·닫기, UI 숨기기, Discord 링크, HWID 인증 유지.
- 기존 단독판과 동일한 수정 APK 인증서 사용. Supercell 공식 서명이 아닙니다.
- 기존 패키지 ID·설정 저장소·게임 데이터와 네이티브 라이브러리 보존.

자동 업데이트를 제거했으므로 이번 APK를 직접 설치해야 합니다. 같은 서명의 기존 단독판에는 덮어쓰기 설치하세요. 서명 오류가 나면 기존 앱을 삭제하지 말고 서명/설치 오류를 확인하세요.

## 실제 검증 범위
''')
print(f"독립 Android UI 검사 {len(c)}개 통과: 실제 DEX 로딩, 인증 결과 전달, 오토골 ON/OFF 전달, 중앙 메뉴, 이전 패치 무시 및 반복 재시작.")
print(f"Android API {r.get('android_api')}, 에뮬레이터 ABI {r.get('abi')}.")
for kind in ['baseline','candidate']:
 d=r.get(kind,{})
 print(f"- {kind}: APK 설치={d.get('installed',False)}, 30초 프로세스 유지={d.get('alive_30s',False)}, UI 감지={d.get('ui_visible','미검사')}; 오류={d.get('error','없음')}")
print('\n게임 시작 검사 결과: 기존판과 새 APK 모두 ARM 변환 계층을 사용하는 API 35 에뮬레이터에서 libosu.so 초기화 중 동일한 SIGABRT로 종료했습니다. 두 APK 설치는 성공했지만 게임 시작 검사는 실패했습니다. 라이브러리 오류를 해결했다고 주장하지 않습니다.')
print('\n실제 삼성 기기, 실서버 로비 진입, 친선전 자동 득점은 검증하지 않았습니다. UI 검사 통과는 실제 경기 동작을 보증하지 않습니다. 게임 기반 버전은 기존 70.648입니다. 테스트 결과와 제약은 Actions 아티팩트를 참고하세요.')

print('\n[실행 로그·검사 아티팩트](https://github.com/jungeunchan0806/irisai-updates/actions/runs/45138443253) · [수정 소스](https://github.com/jungeunchan0806/irisai-updates/tree/android-autogoal-static-225)')
