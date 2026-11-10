# Windows 0.1.0 다운로드

**[Windows 전체 패키지 다운로드](https://raw.githubusercontent.com/jungeunchan0806/irisai-updates/windows-downloads/windows/PylaAI-Windows-0.1.0.zip)**

압축을 풀고 `Start-Windows.bat`를 실행하세요. 최초 실행에는 Python 3.11 x64와 필요한 라이브러리를 준비합니다. Python을 내장한 단독 EXE는 아닙니다.

- [기존 Pyla 소스 폴더용 자동 패치](https://raw.githubusercontent.com/jungeunchan0806/irisai-updates/windows-downloads/windows/PylaAI-Windows-AutoPatch.zip)
- [실행 안내와 지원 범위](PylaAI-Windows-Guide-KO.md)
- [검증 결과](PylaAI-Windows-validation.json)
- [파일 체크섬](PylaAI-Windows-SHA256SUMS.txt)
- [소스 변경분](PylaAI-Windows-source-changes.patch)

IrisAI 0.0.9의 자동 플레이 전략 9개·오토골·벽 회피·라운드 재개 로직을 Windows Pyla에 이식했습니다. 에뮬레이터 감지·실행·ADB 연결과 설정을 보존하는 자동 업데이트를 포함합니다. BlueStacks·LDPlayer의 ADB 자동 활성화는 에뮬레이터가 종료된 상태에서 지원합니다. MuMu·Nox는 초기 수동 ADB 설정이 필요할 수 있습니다.

Linux에서 96개 테스트, 32개 화면 하위 검사, 실제 ONNX 추론과 HTTPS 패치 51개 적용을 검증했습니다. 실제 Windows/에뮬레이터 검증은 아직이며 유튜브 추가 학습은 수행하지 않았습니다.

[원본 v0.0.9 릴리스](https://github.com/jungeunchan0806/irisai-updates/releases/tag/v0.0.9) · [Windows 자동 업데이트 채널](https://github.com/jungeunchan0806/irisai-updates/tree/windows-updates)
