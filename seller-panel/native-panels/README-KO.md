# 판매자·관리자 Android 2.2.0 빌드

판매자 앱은 기존 기기 인증 코드와 패키지를 유지하고 내장 화면에 HWID 수정·추가·연결 해제, 정지·해제, 완전 삭제를 반영합니다. 관리자 앱은 기존 웹 패널을 열며 해당 기능은 서버에 이미 배포되어 있습니다. 두 앱의 버전 코드는 22000입니다.

기존 패널의 서명 인증서 SHA-256은 `11c5f03c3f98613d9e230a35382c3af592b83826f3092d2c2c306bc873dcc71f`입니다. 게임 APK의 공개 AOSP 서명과 다릅니다. 서명 개인키는 저장소·현재 작업 환경에 없으며, 다른 키로 서명한 앱은 기존 패널 위에 설치할 수 없습니다. 기존 판매자 앱을 지우면 기기 인증 정보가 사라질 수 있으므로 삭제하지 마세요.

준비 및 ZIP·매니페스트 검사:

```sh
python native-panels/build.py --sdk-tools /path/to/android-sdk/build-tools/35.0.0 --out /path/to/prepared --prepare-only
```

원래 서명 환경에서 최종 APK 만들기:

```sh
python native-panels/build.py --sdk-tools /path/to/android-sdk/build-tools/35.0.0 --out /path/to/release --keystore /private/original.jks --alias original-alias --password-file /private/password.txt
```

PKCS#8 키·X.509 인증서를 사용한다면 keystore 옵션 대신 `--key /private/original.pk8 --cert /private/original.x509.pem`을 지정합니다. 결과 서명이 기존 인증서와 다르면 배포 APK 생성을 중단합니다. 비밀키·비밀번호는 GitHub에 올리지 않습니다.

`.unsigned.apk`는 준비 산출물이며 휴대폰에 설치하는 최종 APK가 아닙니다. 실제 계정의 키를 수정·삭제하는 자동 테스트는 수행하지 않습니다.
