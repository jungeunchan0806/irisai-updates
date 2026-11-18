# TheSeven 투명 메뉴 버튼

사용자가 제공한 `TheSeven_KoreanOnly.apk` 70.648 전용 수정입니다.

- 기존 ‘더세븐’ 버튼의 그림과 글자를 숨깁니다.
- 화면 **맨 위 가운데** 투명 영역을 누르면 원래 큰 메뉴가 열립니다.
- 같은 영역을 다시 누르면 닫힙니다. 기존 X 버튼도 유지합니다.
- 원래 메뉴의 탭·기능·문구는 유지합니다. Iris 오토골·라이선스·자동 업데이트는 추가하지 않습니다.

새 투명 영역은 너비 320, 높이 88에 원래 모듈의 화면 배율을 곱한 크기입니다.
열린 메뉴보다 나중에 터치 영역을 등록하여 메뉴를 연 상태에서도 닫을 수 있습니다.
기존 180ms 클릭 간격 제한은 유지합니다.

## 입력과 수정 범위

원본 APK SHA-256:
`309be672f46987a109f7eb8690c6b32b51e4fe6f24867c9566bb1ba145345a7b`

유일하게 변경하는 APK 내용은 `lib/arm64-v8a/libbrf.so`입니다.
매니페스트·DEX·게임 라이브러리·리소스의 내용은 모두 원본과 같아야 합니다.
APK ZIP 패딩과 서명 블록은 재포장·서명 과정에서 달라집니다.

원본 개인 서명키가 없어 사용자가 승인한 **별도 서명**을 사용합니다.
기존 원본 APK 위에 업데이트 설치할 수 없습니다. 서명키는 저장소에 포함하지 않습니다.
기존 Iris 패치 채널은 변경하지 않습니다.

## 빌드와 검증

Python 3.11 이상, Java 17, Android Build Tools 35.0.0 필요.

```sh
python -m pip install -r theseven/requirements.txt
python theseven/build.py original.apk unsigned.apk
```

보관 중인 개인 키로 `apksigner sign`을 실행합니다. 16KB ZIP 정렬을 유지하려면
`--alignment-preserved true --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true`
옵션을 사용하십시오. 비밀키·비밀번호를 저장소나 로그에 넣지 마십시오.

```sh
unzip -p original.apk lib/arm64-v8a/libbrf.so > original-libbrf.so
python theseven/test_native.py original-libbrf.so --report native-validation.json
python theseven/verify.py original.apk signed.apk --report apk-validation.json
apksigner verify --verbose --print-certs signed.apk
zipalign -c -P 16 4 signed.apk
```

ARM64 검사는 원래 메뉴 렌더러·클릭 처리 코드를 Unicorn에서 실행합니다.
그리기·메모리 할당·시계·문자열 포맷 등 플랫폼 경계만 대체합니다.
4개 화면 크기, 3개 메뉴 탭, 3개 벡터 용량 조건에서 투명 영역과 열기·닫기,
기존 메뉴 보존, 반복 회전·토글, 기존 X 버튼을 확인합니다.
로딩 화면 2개 경우도 원본과 비교합니다. 실제 휴대폰의 게임 실행 검사는 아닙니다.

배포 워크플로는 원본을 내려받아 해시를 검사하고 `patch.xdelta`로 서명 완료 APK를
복원한 뒤 모든 APK 항목 비교·ARM64 검사·서명·정렬 검사를 통과해야 업로드합니다.
이 차분 파일은 배포 재현용이며 앱 내부 자동 업데이트 기능이 아닙니다.
