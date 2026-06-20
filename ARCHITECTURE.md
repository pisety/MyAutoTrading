# MyAutoTrading 아키텍처 및 세부 구조 명세 (Architecture & Features)

이 문서는 개인용 주식/가상화폐 자동매매 시스템의 전체 아키텍처와 세부 데이터 흐름, 핵심 로직을 설명합니다. **향후 시스템 수정 시 본 문서를 항상 최신 상태로 유지해야 합니다.**

---

## 1. 시스템 전체 아키텍처 (System Architecture)

본 시스템은 **Android App (클라이언트)** 과 **Python Engine (백엔드 서버)** 이 완전히 분리되어 있으며, 그 사이를 **Firebase Realtime Database**가 중계하는 구조를 가집니다.

```mermaid
flowchart TD
    subgraph Client ["📱 Android App (Kotlin/Compose)"]
        UI[UI: Jetpack Compose]
        VM[ViewModel: StateFlow]
        FB_Client[Firebase RTDB Client]
    end

    subgraph Database ["☁️ Firebase Realtime Database"]
        RTDB[(Realtime Database\nJSON Tree)]
    end

    subgraph Backend ["🐍 Python Trading Engine (Asyncio)"]
        FB_Server[Firebase Admin SDK]
        Watcher[Surge Monitor (급등 감시)]
        Broker_KR[한국투자증권 API]
        Broker_Crypto[Upbit API]
    end

    %% 데이터 흐름
    UI <-->|State/Intent| VM
    VM <-->|Read/Write| FB_Client
    FB_Client <==>|Realtime Sync| RTDB
    RTDB <==>|Event Listener| FB_Server
    FB_Server -->|Update Config| Watcher
    
    Watcher <-->|Fetch Price & Order| Broker_KR
    Watcher <-->|Fetch Price & Order| Broker_Crypto
```

### 1.1 핵심 데이터 흐름 (Data Flow)
1. **설정 변경 (App ➡️ Firebase ➡️ Python):** 
   - 사용자가 앱에서 '감시 종목 추가', '매수 조건(시간/퍼센트/수량) 변경', '자동매매 ON/OFF' 버튼을 누릅니다.
   - 앱은 해당 내용을 JSON 포맷으로 직렬화하여 Firebase에 덮어씁니다.
   - Python 엔진은 Firebase의 `db.reference('/settings').listen()` 리스너를 통해 변경 이벤트를 실시간(0.1초 이내)으로 수신하고 내부 설정값(`user_session.py`)을 갱신합니다.
2. **시세 및 계좌 반영 (Python ➡️ Firebase ➡️ App):**
   - Python 엔진의 백그라운드 태스크가 업비트/한국투자증권 API를 지속적으로 호출하여 현재 가격, 총 자산, 보유 주식 수 등을 가져옵니다.
   - 주기적으로 Firebase의 `/status` 경로에 덮어씁니다.
   - Android 앱은 `/status` 경로의 `addValueEventListener`를 통해 상태를 즉시 넘겨받아 화면(Compose)을 다시 그립니다(Recomposition).

---

## 2. 주요 기능 명세 (Features)

### 2.1 다중 급등주 감시 및 매매 (Surge Trading Monitor)
- **개념:** 특정 종목이 `T초` 동안 `P%` 이상 급등했을 때, 봇이 즉시 지정된 수량(`Q`)만큼 시장가(또는 지정가)로 매수하는 기능입니다.
- **다중 감시 지원:** 한 종목(예: 비트코인)에 대해 `[60초/3%/0.01개]`, `[10초/1%/0.05개]` 등 여러 개의 조건(Rule)을 동시에 걸어둘 수 있습니다.
- **연속 매수 방지 (Cooldown):** 급등이 연속으로 일어날 때 수량이 중복해서 매수되는 것을 방지하기 위해, 한 번 매수가 체결되면 해당 감시 조건은 일정 시간(또는 일시정지 상태) 동안 쿨다운에 들어갑니다.

### 2.2 수동 주문 (Manual Ordering)
- 앱에서 버튼을 눌러 즉각적으로 매수/매도/손절 주문을 서버로 쏘아 올립니다.
- Firebase의 `/commands` 경로에 주문 데이터(종목, 가격, 수량, 종류)가 Push되며, Python 서버가 이 경로를 리스닝하다가 새로운 명령이 들어오면 증권사 API를 통해 실제 주문을 실행하고 로그를 남긴 뒤 해당 명령을 데이터베이스에서 삭제(소모)합니다.

---

## 3. 폴더 및 파일 구조 (Directory Structure)

### 3.1 Android App (`AutoTradingApp/`)
- **UI 계층 (`MainActivity.kt` 등):** Jetpack Compose로 전면 작성되어 있습니다. `StockTradingScreen`, `CryptoTradingScreen` 등의 컴포저블(Composable) 함수들로 화면이 구성됩니다.
- **상태 관리 (`TradingViewModel.kt`):** `MutableStateFlow`를 이용해 화면의 상태를 관리하고, 사용자의 입력을 받아 Firebase 저장 로직을 호출합니다.
- **데이터 모델 (`TradingModels.kt`):** Firebase와 통신하기 위한 데이터 클래스들이 정의되어 있습니다. Kotlin Serialization이나 Gson 등의 역할을 직접 담당합니다. (예: `SurgeTradingConfig`, `StockStatus` 등)

### 3.2 Python Engine (`py-my-auto-trading/`)
- **`my-auto-trading.py` (엔트리 포인트):** 메인 실행 파일이며, 로거(Logger) 세팅 및 모든 백그라운드 루프(asyncio.gather)를 실행시킵니다.
- **`user_session.py`:** Firebase와의 통신을 전담하며, 상태(Status)를 업데이트하거나 앱에서 넘어오는 명령(Commands)과 설정(Settings)을 콜백으로 받아 처리합니다.
- **`surge_monitor.py`:** 가격 변동 이력을 메모리(deque)에 저장하고, 각 종목별로 사용자가 설정한 다중 급등 조건이 만족되었는지 계산하는 핵심 트레이딩 알고리즘입니다.
- **`koreainvestment.py` / `upbit.py`:** 실제 증권사(한국투자증권)와 코인 거래소(Upbit) API와 통신하여 주문을 넣거나 호가를 조회하는 래퍼(Wrapper) 모듈입니다.
- **`schemas.py`:** Python 측 데이터 모델 클래스들(Pydantic 또는 Dataclass)이 정의되어 있으며, Android의 `TradingModels.kt`와 구조가 1:1로 매칭되어야 합니다.

---

## 4. 향후 확장 및 유지보수 가이드
- **새로운 설정 필드 추가 시:** 안드로이드의 `TradingModels.kt`와 파이썬의 `schemas.py` 양쪽에 동시에 새로운 변수(Property)를 추가해야 Firebase 직렬화/역직렬화 시 에러가 나지 않습니다.
- **키 관리:** 절대 GitHub나 외부에 `keys.json`, `google-services.json`의 원본 데이터를 올리지 마세요. 템플릿 파일 구조만 유지합니다.
