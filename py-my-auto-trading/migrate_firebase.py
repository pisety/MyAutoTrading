"""
Firebase 데이터 마이그레이션 스크립트

기존 구조:  users/{uid}/settings, status, account_data, ...
새 구조:    users/{uid}/stock/settings, status, account_data, ...
            users/{uid}/crypto/settings, status, account_data, ...

기존 루트 레벨 데이터를 users/{uid}/stock/ 하위로 복사하고,
users/{uid}/crypto/ 하위에 빈 기본값을 생성합니다.
"""

import os
import sys
import json
import logging
from dotenv import load_dotenv

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("migrate_firebase")

load_dotenv()

import firebase_admin
from firebase_admin import credentials, db

# ── 설정 ─────────────────────────────────────────────────────────
DB_URL = os.getenv("FIREBASE_DB_URL", "")
CERT_PATH = os.getenv("FIREBASE_CERT_PATH", "firebase-adminsdk.json")

NODES_TO_MIGRATE = [
    "account_data",
    "settings",
    "status",
    "manual_orders",
    "conditional_orders",
    "stop_loss_orders",
]

CRYPTO_DEFAULTS = {
    "settings": {
        "is_auto_trading": False,
        "target_profit": 10.0,
        "watch_symbols": {},
        "surge_trading": {"enabled": False, "allow_extended_hours": False, "per_symbol_config": {}},
        "memo": "",
    },
    "status": {
        "total_balance": 0.0,
        "current_yield": 0.0,
        "holdings": {},
        "stock_prices": {},
        "last_updated": "",
    },
}


def init_firebase():
    if not DB_URL:
        logger.error("FIREBASE_DB_URL 환경변수가 설정되지 않았습니다.")
        sys.exit(1)
    if not os.path.exists(CERT_PATH):
        logger.error(f"Firebase 인증서를 찾을 수 없습니다: {CERT_PATH}")
        sys.exit(1)
    cred = credentials.Certificate(CERT_PATH)
    firebase_admin.initialize_app(cred, {"databaseURL": DB_URL})
    logger.info("Firebase 초기화 완료")


def get_all_uids():
    users = db.reference("users").get(shallow=True)
    if not users:
        logger.warning("users 노드가 비어 있습니다.")
        return []
    return list(users.keys())


def migrate_user(uid: str, delete_old: bool = False):
    logger.info(f"── 마이그레이션 시작: {uid} ──")

    root_ref = db.reference(f"users/{uid}")
    user_data = root_ref.get()
    if not user_data or not isinstance(user_data, dict):
        logger.warning(f"  [{uid}] 데이터 없음, 건너뜁니다.")
        return

    # 이미 마이그레이션 된 경우 확인
    if "stock" in user_data and isinstance(user_data["stock"], dict):
        logger.info(f"  [{uid}] 이미 'stock' 하위 노드가 존재합니다. 건너뜁니다.")
        return

    # 1) 기존 데이터를 stock/ 하위로 복사
    stock_data = {}
    for node in NODES_TO_MIGRATE:
        if node in user_data:
            stock_data[node] = user_data[node]
            logger.info(f"  [{uid}] {node} → stock/{node}")

    if stock_data:
        db.reference(f"users/{uid}/stock").update(stock_data)
        logger.info(f"  [{uid}] stock 하위 노드 생성 완료 ({len(stock_data)}개 노드)")

    # 2) crypto/ 하위에 빈 기본값 생성
    db.reference(f"users/{uid}/crypto").update(CRYPTO_DEFAULTS)
    logger.info(f"  [{uid}] crypto 하위 기본값 생성 완료")

    # 3) 선택적으로 루트 레벨 데이터 삭제
    if delete_old:
        for node in NODES_TO_MIGRATE:
            if node in user_data:
                db.reference(f"users/{uid}/{node}").delete()
                logger.info(f"  [{uid}] 기존 루트 노드 삭제: {node}")

    logger.info(f"  [{uid}] ✅ 마이그레이션 완료")


def main():
    init_firebase()

    uids = get_all_uids()
    if not uids:
        logger.info("마이그레이션할 사용자가 없습니다.")
        return

    logger.info(f"총 {len(uids)}명의 사용자를 마이그레이션합니다: {uids}")

    # 기존 루트 데이터 삭제 여부 확인
    delete_old = False
    if "--delete-old" in sys.argv:
        delete_old = True
        logger.warning("⚠️  --delete-old 플래그 활성화: 마이그레이션 후 기존 루트 데이터를 삭제합니다.")
    else:
        print("\n기존 루트 레벨 데이터를 삭제하려면 --delete-old 플래그를 추가하세요.")
        print("현재는 복사만 수행합니다.\n")

    confirm = input(f"총 {len(uids)}명의 사용자 데이터를 마이그레이션합니다. 계속하시겠습니까? (y/N): ")
    if confirm.strip().lower() != "y":
        logger.info("마이그레이션이 취소되었습니다.")
        return

    for uid in uids:
        try:
            migrate_user(uid, delete_old=delete_old)
        except Exception as e:
            logger.error(f"  [{uid}] 마이그레이션 실패: {e}")

    logger.info("🎉 모든 사용자 마이그레이션이 완료되었습니다.")


if __name__ == "__main__":
    main()
