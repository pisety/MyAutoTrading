# py-my-auto-trading (Python 자동매매 엔진)

본 프로젝트는 주식(한국투자증권)과 가상화폐(업비트)의 실시간 가격을 모니터링하고, 사용자가 설정한 조건에 따라 자동으로 매매를 수행하는 Python 기반 백엔드 엔진입니다.
Firebase Realtime Database와 연동되어 안드로이드 앱 클라이언트의 명령을 실시간으로 수신받아 동작합니다.

## 🚀 주요 기능

- **실시간 주가/코인 시세 모니터링:** 웹소켓 및 REST API를 통해 실시간 가격 정보를 가져옵니다.
- **급등 감시 로직:** N초 이내에 M% 이상 급등한 경우 시장가/지정가로 Q개 자동 매수하는 알고리즘을 지원합니다.
- **다중 감시 조건 처리:** 종목당 여러 개의 감시 조건을 동시에 병렬로 체크하고 조건 달성 시 즉각 매수합니다. (연속 매수 방지 쿨다운 적용)
- **Firebase 연동:** Firebase Realtime Database를 통해 클라이언트 앱과 양방향으로 설정 및 상태를 동기화합니다.

## 🛠 기술 스택

- **언어:** Python 3
- **비동기 처리:** `asyncio`
- **외부 API:** 한국투자증권 OpenAPI, Upbit API
- **데이터베이스:** Firebase Realtime Database (`firebase-admin`)

## ⚙️ 설치 및 실행 방법

1. **저장소 클론:**
   ```bash
   git clone <repository-url>
   ```
2. **패키지 설치:**
   ```bash
   pip install -r requirements.txt
   ```
3. **환경 설정 (보안 파일):**
   - API 키 및 비밀키 설정: `keys.json` 파일을 생성하여 거래소/증권사 API 키를 입력합니다. (템플릿: `keys_template.json` 참고)
   - Firebase Admin SDK 설정: `database/` 경로에 `google-services.json`을 배치합니다.
4. **엔진 실행:**
   ```bash
   python my-auto-trading.py
   ```

## ⚠️ 보안 주의 사항
- `keys.json` 및 `google-services.json` 파일, 클라우드 SSH 키 등은 절대 공개 저장소에 커밋하지 마세요. (본 저장소의 `.gitignore`에 추가되어 있습니다.)
