# 라이선스 패널 소스

기존 Sites 프로젝트의 2026-11-17 소스이며 버전 10으로 배포되었습니다. 운영 환경 변수, 서명 비밀키, 실제 구매자 데이터는 포함하지 않았습니다. `npm run install:ci`, `mkdir -p .sites-runtime`, `node --test tests/server.test.mjs`, `npx tsc --noEmit`, `npm run build`로 검증합니다.

판매자 앱 내장 화면 변경본은 `seller-client/panel.html`에 있습니다. 기존 판매자 APK/EXE를 새로 빌드하려면 원래 빌드 프로젝트가 필요합니다. 현재 `public/downloads`는 이전 배포본입니다.

키 삭제는 구매자 레코드를 실제 삭제합니다. 감사 기록의 내부 ID/작업명은 유지됩니다. 클라이언트는 보통 1분 안에 정지를 반영하며 오프라인 상태에서는 기존 인증 임대가 최대 5분 남을 수 있습니다.

구매자 HWID 수정 API `update_device`는 관리자와 소유 판매자에게 제공됩니다. 메모도 수정할 수 있으며 기존 키·만료일·정지 상태는 유지됩니다. 미사용 기간형 키의 첫 기기 연결에서만 기간을 시작합니다.
