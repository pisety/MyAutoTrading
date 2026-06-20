import aiohttp
import asyncio
import logging
import json
import time
from .base_broker import BaseBroker

logger = logging.getLogger(__name__)

class KiwoomBroker(BaseBroker):
    def __init__(self, app_key: str, app_secret: str, account_number: str, is_mock: bool = True):
        self.app_key = app_key
        self.app_secret = app_secret
        self.account_number = account_number
        self.is_mock = is_mock
        
        # 키움증권 REST API Base URL 
        # (추후 공식 가이드 및 사용자 발급 정보에 맞춰 URL 변경 필요)
        self.base_url = "https://openapi.kiwoom.com" if not is_mock else "https://openapi.kiwoom.com/v1/mock"
        
        self.access_token = None
        self.token_expired_at = 0
        
        self.ws = None
        self.ws_session = None

    async def _get_access_token(self):
        if time.time() < self.token_expired_at and self.access_token:
            return self.access_token
            
        url = f"{self.base_url}/oauth2/token"
        headers = {"Content-Type": "application/json;charset=UTF-8"}
        data = {
            "grant_type": "client_credentials",
            "appkey": self.app_key,
            "appsecretkey": self.app_secret
        }
        
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(url, headers=headers, json=data) as resp:
                    if resp.status == 200:
                        res = await resp.json()
                        self.access_token = res.get("access_token")
                        expires_in = res.get("expires_in", 86400)
                        self.token_expired_at = time.time() + expires_in - 300
                        logger.info("Kiwoom Access Token 발급 성공")
                        return self.access_token
                    else:
                        text = await resp.text()
                        logger.error(f"Kiwoom Token Error: {text}")
                        return None
        except Exception as e:
            logger.error(f"Kiwoom token exception: {e}")
            return None

    def _get_headers(self, token: str, tr_id: str = ""):
        return {
            "Content-Type": "application/json;charset=UTF-8",
            "Authorization": f"Bearer {token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret,
            "tr_id": tr_id
        }

    async def ensure_valid_token(self) -> bool:
        token = await self._get_access_token()
        return token is not None

    async def get_balance(self) -> tuple:
        # 반환 규격: (total_balance: float, current_yield: float, holdings: list)
        token = await self._get_access_token()
        if not token:
            return (0.0, 0.0, [])
            
        # TODO: 실제 키움 API 엔드포인트 및 응답 파싱 로직 구현
        # 현재는 아키텍처 뼈대이므로 기본값 반환
        return (0.0, 0.0, [])

    async def get_holdings(self) -> dict:
        # 반환 규격: {"005930": {"qty": 10, "avg_price": 50000}, ...}
        token = await self._get_access_token()
        if not token:
            return {}
            
        # TODO: 실제 키움 API 엔드포인트 구현
        return {}

    async def execute_order(self, symbol: str, side: str, qty: int, price: float, allow_extended_hours: bool) -> tuple:
        token = await self._get_access_token()
        if not token:
            return False, "인증 토큰 발급 실패"
            
        # TODO: 실제 키움 주문 전송 API 구현
        logger.info(f"[Kiwoom] 주문 전송 - {symbol} {side} {qty}주 @ {price}")
        return True, ""

    async def fetch_rest_current_prices(self, symbols: list) -> dict:
        # Fallback 시세 조회
        return {}

    async def connect_websocket(self, symbols: list, on_message_callback):
        # TODO: 키움증권 실시간 웹소켓 연동 (호가/체결 데이터 수신)
        logger.info(f"[Kiwoom] 웹소켓 연결 준비 - 종목: {symbols}")
        pass

    async def close_websocket(self):
        if self.ws:
            await self.ws.close()
            self.ws = None
        if self.ws_session:
            await self.ws_session.close()
            self.ws_session = None
