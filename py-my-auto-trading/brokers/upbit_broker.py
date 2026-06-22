import jwt
import uuid
import json
import hashlib
import logging
import asyncio
import aiohttp
import websockets
from urllib.parse import urlencode, unquote
from typing import Dict, Optional, Tuple, List, Callable

from brokers.base_broker import BaseBroker

logger = logging.getLogger("UpbitBroker")


class UpbitBroker(BaseBroker):
    """업비트 암호화폐 거래소 브로커 (JWT per-request 인증)"""

    COIN_NAMES = {
        "BTC": "비트코인", "ETH": "이더리움", "XRP": "리플", "SOL": "솔라나",
        "DOGE": "도지코인", "ADA": "에이다", "AVAX": "아발란체", "DOT": "폴카닷",
        "MATIC": "폴리곤", "LINK": "체인링크", "TRX": "트론", "ATOM": "코스모스",
        "ETC": "이더리움클래식", "BCH": "비트코인캐시", "XLM": "스텔라루멘",
        "SAND": "샌드박스", "MANA": "디센트럴랜드", "AXS": "액시인피니티",
        "EOS": "이오스", "SHIB": "시바이누",
    }

    def __init__(self, access_key: str, secret_key: str):
        super().__init__(access_key, secret_key, account_number="", is_mock=False)
        self.is_overseas = False
        self.base_url = "https://api.upbit.com/v1"
        self.ws_url = "wss://api.upbit.com/websocket/v1"
        self.ws_task = None
        self.ws_connection = None
        self._on_message_callback = None
        self._running_ws = False

    # ──────────────────────── JWT helper ────────────────────────

    def _create_jwt(self, query_params: Optional[dict] = None) -> str:
        """Upbit API 호출용 JWT 토큰을 생성한다. query_params가 있으면 query_hash를 포함."""
        payload = {
            "access_key": self.app_key,
            "nonce": str(uuid.uuid4()),
        }
        if query_params:
            query_string = unquote(urlencode(query_params, doseq=True)).encode()
            m = hashlib.sha512()
            m.update(query_string)
            payload["query_hash"] = m.hexdigest()
            payload["query_hash_alg"] = "SHA512"
        return jwt.encode(payload, self.app_secret)

    def _auth_headers(self, query_params: Optional[dict] = None) -> dict:
        token = self._create_jwt(query_params)
        return {"Authorization": f"Bearer {token}"}

    # ──────────────────────── Coin names ────────────────────────

    @classmethod
    def get_coin_name(cls, market_code: str) -> str:
        """'KRW-BTC' → '비트코인'. 매핑에 없으면 통화 코드를 그대로 반환."""
        currency = market_code.split("-")[-1] if "-" in market_code else market_code
        return cls.COIN_NAMES.get(currency.upper(), currency)

    # ──────────────────────── BaseBroker 구현 ────────────────────────

    async def ensure_valid_token(self) -> bool:
        """JWT 인증이 유효한지 확인 (GET /v1/accounts 호출)."""
        try:
            async with aiohttp.ClientSession() as session:
                url = f"{self.base_url}/accounts"
                async with session.get(url, headers=self._auth_headers(), timeout=aiohttp.ClientTimeout(total=10)) as res:
                    if res.status == 200:
                        data = await res.json()
                        if isinstance(data, list):
                            return True
                    else:
                        body = await res.text()
                        logger.warning(f"Upbit auth check failed ({res.status}): {body}")
        except Exception as e:
            logger.error(f"Upbit auth check error: {e}")
        return False

    async def get_balance(self) -> Tuple[Optional[float], Optional[float], Optional[List[Dict]]]:
        """업비트 계좌 잔고 조회. (total_balance, yield_rate, holdings) 반환."""
        try:
            async with aiohttp.ClientSession() as session:
                url = f"{self.base_url}/accounts"
                async with session.get(url, headers=self._auth_headers(), timeout=aiohttp.ClientTimeout(total=10)) as res:
                    if res.status != 200:
                        logger.error(f"Upbit balance error ({res.status}): {await res.text()}")
                        return None, None, None
                    accounts = await res.json()

            if not isinstance(accounts, list):
                return None, None, None

            krw_balance = 0.0
            coin_entries = []

            for entry in accounts:
                currency = entry.get("currency", "")
                balance = float(entry.get("balance", 0))
                locked = float(entry.get("locked", 0))
                avg_buy_price = float(entry.get("avg_buy_price", 0))

                if currency == "KRW":
                    krw_balance = balance + locked
                elif balance > 0 or locked > 0:
                    coin_entries.append({
                        "currency": currency,
                        "balance": balance,
                        "locked": locked,
                        "avg_buy_price": avg_buy_price,
                        "total_qty": balance + locked,
                    })

            # Fetch current prices for held coins
            holdings = []
            total_purchase = 0.0
            total_eval = 0.0

            if coin_entries:
                markets = [f"KRW-{c['currency']}" for c in coin_entries]
                current_prices = await self._fetch_ticker_prices(markets)

                for c in coin_entries:
                    market = f"KRW-{c['currency']}"
                    
                    # 상장폐지되거나 원화 마켓에서 지원하지 않아 현재가를 알 수 없는 코인은 완전히 제외
                    if market not in current_prices:
                        continue
                        
                    current_price = current_prices[market]
                    qty = c["total_qty"]
                    avg_price = c["avg_buy_price"]

                    purchase_amount = avg_price * qty
                    eval_amount = current_price * qty
                    pnl_amount = eval_amount - purchase_amount
                    pnl_rate = (pnl_amount / purchase_amount * 100) if purchase_amount > 0 else 0.0

                    total_purchase += purchase_amount
                    total_eval += eval_amount

                    holdings.append({
                        "symbol": market,
                        "name": self.get_coin_name(market),
                        "quantity": qty,
                        "purchase_avg_price": avg_price,
                        "current_price": current_price,
                        "profit_loss_rate": round(pnl_rate, 2),
                        "profit_loss_amount": round(pnl_amount, 2),
                    })

            total_balance = krw_balance + total_eval
            overall_yield = ((total_eval - total_purchase) / total_purchase * 100) if total_purchase > 0 else 0.0

            return round(total_balance, 2), round(overall_yield, 2), holdings

        except Exception as e:
            logger.error(f"Upbit get_balance error: {e}")
            return None, None, None

    async def _fetch_ticker_prices(self, markets: List[str]) -> Dict[str, float]:
        """내부 헬퍼: GET /v1/ticker 로 여러 마켓의 현재가를 한 번에 조회. 상장폐지 코인 포함 시 404 에러 폴백 처리."""
        if not markets:
            return {}
        
        prices = {}
        try:
            async with aiohttp.ClientSession() as session:
                params = {"markets": ",".join(markets)}
                url = f"{self.base_url}/ticker"
                async with session.get(url, params=params, timeout=aiohttp.ClientTimeout(total=10)) as res:
                    if res.status == 200:
                        data = await res.json()
                        return {item["market"]: float(item["trade_price"]) for item in data}
                    elif res.status == 404:
                        logger.warning("Upbit ticker bulk fetch 404. Falling back to individual fetch to skip delisted coins.")
                        for m in markets:
                            try:
                                async with session.get(url, params={"markets": m}, timeout=aiohttp.ClientTimeout(total=5)) as single_res:
                                    if single_res.status == 200:
                                        data = await single_res.json()
                                        if data and len(data) > 0:
                                            prices[data[0]["market"]] = float(data[0]["trade_price"])
                            except Exception as ex:
                                logger.debug(f"Skip market {m}: {ex}")
                        return prices
                    else:
                        logger.warning(f"Upbit ticker error ({res.status}): {await res.text()}")
        except Exception as e:
            logger.error(f"Upbit ticker fetch error: {e}")
        return prices

    async def execute_order(self, symbol: str, side: str, qty: float, price: float, allow_extended_hours: bool) -> Tuple[bool, str]:
        """
        업비트 주문 실행.
        - side: "BUY" → bid, "SELL" → ask
        - 시장가 매수: ord_type='price', price=총 KRW 금액
        - 시장가 매도: ord_type='market', volume=수량
        - 지정가:     ord_type='limit', price=가격, volume=수량
        """
        upbit_side = "bid" if side == "BUY" else "ask"

        if price > 0.0:
            # 지정가 주문
            params = {
                "market": symbol,
                "side": upbit_side,
                "ord_type": "limit",
                "price": str(price),
                "volume": str(qty),
            }
        else:
            # 시장가 주문
            if side == "BUY":
                # 시장가 매수: 총 KRW 금액 지정 (qty를 사용하여 총 구매 금액 산정. 또는 호출 시 qty 자리에 KRW 금액을 보냈을 수 있음)
                # 현재 엔진 로직에서 시장가 주문 시 price=0.0 으로 오면 총 KRW 금액을 알기 어려움.
                # 임시로 price가 0.0일 때 qty 자체를 총 KRW 금액으로 간주함. (UI에서 시장가 주문 기능과 맞추기 위함)
                params = {
                    "market": symbol,
                    "side": upbit_side,
                    "ord_type": "price",
                    "price": str(qty),
                }
            else:
                # 시장가 매도: 수량 지정
                params = {
                    "market": symbol,
                    "side": upbit_side,
                    "ord_type": "market",
                    "volume": str(qty),
                }

        try:
            async with aiohttp.ClientSession() as session:
                url = f"{self.base_url}/orders"
                headers = {
                    **self._auth_headers(params),
                    "Content-Type": "application/json",
                }
                async with session.post(url, headers=headers, json=params, timeout=aiohttp.ClientTimeout(total=10)) as res:
                    data = await res.json()
                    if res.status in (200, 201):
                        logger.info(f"Upbit order success: {side} {symbol} qty={qty} price={price}")
                        return True, "주문 성공"
                    else:
                        err_name = data.get("error", {}).get("name", "unknown")
                        err_msg = data.get("error", {}).get("message", str(data))
                        logger.warning(f"Upbit order failed: {err_name} - {err_msg}")
                        return False, f"주문 실패: {err_msg}"
        except Exception as e:
            logger.error(f"Upbit order exception: {e}")
            return False, f"시스템 오류: {e}"

    async def fetch_rest_current_prices(self, symbols: List[str]) -> Dict[str, float]:
        """REST API 기반 시세 조회. symbols는 'KRW-BTC' 형식 리스트."""
        if not symbols:
            return {}
        prices = await self._fetch_ticker_prices(symbols)
        return prices

    # ──────────────────────── WebSocket ────────────────────────

    async def connect_websocket(self, symbols: List[str], on_message_callback: Callable[[str, float], None]):
        """업비트 WebSocket 시세 구독 (인증 불필요)."""
        self._on_message_callback = on_message_callback
        self._running_ws = True

        # symbols는 'KRW-BTC' 형식이어야 함
        codes = [s.strip() for s in symbols if s.strip()]
        if not codes:
            logger.warning("Upbit WS: No symbols to subscribe.")
            return

        async def _ws_loop():
            while self._running_ws:
                try:
                    async with websockets.connect(self.ws_url, ping_interval=30) as ws:
                        self.ws_connection = ws
                        logger.info(f"Upbit WebSocket connected. Subscribing to {len(codes)} markets.")

                        subscribe_msg = json.dumps([
                            {"ticket": str(uuid.uuid4())},
                            {"type": "ticker", "codes": codes},
                            {"format": "DEFAULT"},
                        ])
                        await ws.send(subscribe_msg)

                        async for message in ws:
                            if not self._running_ws:
                                break
                            try:
                                if isinstance(message, bytes):
                                    data = json.loads(message.decode("utf-8"))
                                elif isinstance(message, str):
                                    data = json.loads(message)
                                else:
                                    continue

                                code = data.get("code")
                                trade_price = data.get("trade_price")
                                if code and trade_price is not None and self._on_message_callback:
                                    self._on_message_callback(code, float(trade_price))
                            except (json.JSONDecodeError, KeyError) as e:
                                logger.debug(f"Upbit WS parse error: {e}")

                except websockets.exceptions.ConnectionClosed:
                    logger.warning("Upbit WebSocket connection closed. Reconnecting in 3s...")
                    self.ws_connection = None
                    await asyncio.sleep(3)
                except Exception as e:
                    logger.error(f"Upbit WebSocket error: {e}")
                    self.ws_connection = None
                    await asyncio.sleep(3)

        self.ws_task = asyncio.create_task(_ws_loop())

    async def close_websocket(self):
        """WebSocket 연결 종료."""
        self._running_ws = False
        if self.ws_connection:
            try:
                await self.ws_connection.close()
            except Exception:
                pass
            self.ws_connection = None
        if self.ws_task:
            self.ws_task.cancel()
            self.ws_task = None
