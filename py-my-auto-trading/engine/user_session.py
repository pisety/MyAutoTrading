import time
import asyncio
import logging
import aiohttp
import re
from collections import deque
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, List

from brokers.kis_broker import KISBroker
from brokers.kiwoom_broker import KiwoomBroker
from brokers.upbit_broker import UpbitBroker
from database.firebase_client import FirebaseClient
from models.schemas import Settings, AccountData, OrderCommand, ConditionalOrder, StopLossOrder
import crypto_utils

logger = logging.getLogger("UserSession")

class UserSession:
    def __init__(self, uid: str, fb_client: FirebaseClient, service_type: str = "stock"):
        self.uid = uid
        self.service_type = service_type
        self.fb = fb_client
        self.broker = None
        self.settings = Settings()
        self.account_data = AccountData()
        self.conditional_orders: Dict[str, ConditionalOrder] = {}
        self.stop_loss_orders: Dict[str, StopLossOrder] = {}
        
        self.stock_names: Dict[str, str] = {}
        
        self.price_data: Dict[str, float] = {}
        self.price_history: Dict[str, deque] = {}
        self.last_surge_buy_time: Dict[str, float] = {}
        
        self._main_task = None
        self._restart_task = None
        self._running = False
        self._loop = asyncio.get_event_loop()
        self._last_rest_price = 0.0

    @property
    def base_path(self):
        """Firebase 경로 기본 프리픽스: users/{uid}/{service_type}"""
        return f"users/{self.uid}/{self.service_type}"

    async def start(self):
        logger.info(f"Starting session for user: {self.uid}")
        self._running = True
        
        # Load initial config
        self._load_account_data()
        self._load_settings()
        
        # Register firebase listeners
        self.fb.listen(f"{self.base_path}/settings", self._on_settings_change)
        self.fb.listen(f"{self.base_path}/account_data", self._on_account_change)
        self.fb.listen(f"{self.base_path}/manual_orders", self._on_manual_order)
        self.fb.listen(f"{self.base_path}/conditional_orders", self._on_conditional_orders_change)
        self.fb.listen(f"{self.base_path}/stop_loss_orders", self._on_stop_loss_orders_change)
        
        self._setup_broker()
        
        # Start async loop
        self._main_task = asyncio.create_task(self._session_loop())

    async def stop(self):
        logger.info(f"Stopping session for user: {self.uid}")
        self._running = False
        if self._main_task:
            self._main_task.cancel()
        if self.broker:
            await self.broker.close_websocket()
        
        paths = ["settings", "account_data", "manual_orders", "conditional_orders", "stop_loss_orders"]
        for p in paths:
            self.fb.remove_listener(f"{self.base_path}/{p}")

    def _load_account_data(self):
        data = self.fb.get_data(f"{self.base_path}/account_data")
        if data:
            self.account_data = AccountData(**data)

    def _load_settings(self):
        data = self.fb.get_data(f"{self.base_path}/settings")
        if data:
            self.settings = Settings(**data)

    def _setup_broker(self):
        active_id = self.account_data.active_account_id
        if active_id and active_id in self.account_data.saved_accounts:
            acc = self.account_data.saved_accounts[active_id]
            if acc.broker == "KIS" or acc.broker == "한국투자":
                # 기존 브로커가 있으면 웹소켓 종료를 예약
                if self.broker:
                    self._loop.create_task(self.broker.close_websocket())
                    
                is_mock = getattr(acc, "is_mock", getattr(acc, "is_kis_mock", False))
                decrypted_key = crypto_utils.decrypt(acc.app_key).strip() if acc.app_key else ""
                decrypted_secret = crypto_utils.decrypt(acc.app_secret).strip() if acc.app_secret else ""
                self.broker = KISBroker(decrypted_key, decrypted_secret, acc.account_number, is_mock=is_mock, is_overseas=acc.is_overseas)
                logger.info(f"[{self.uid}] Broker configured: {acc.broker} ({active_id}) - Mock: {is_mock}, OS: {acc.is_overseas}")
                
                # 기존 가격 정보 복원 (장이 닫혀있을 때 UI 빈화면 방지)
                status_data = self.fb.get_data(f"{self.base_path}/status")
                if status_data and isinstance(status_data, dict) and "stock_prices" in status_data:
                    for sym, p in status_data["stock_prices"].items():
                        if sym in self.settings.watch_symbols:
                            self.price_data[sym] = float(p)
                        
                self._last_balance_check = 0.0 # Force immediate balance update
                self._restart_websocket()
            elif acc.broker == "키움" or acc.broker == "Kiwoom":
                if self.broker:
                    self._loop.create_task(self.broker.close_websocket())
                is_mock = getattr(acc, "is_mock", True)
                decrypted_key = crypto_utils.decrypt(acc.app_key).strip() if acc.app_key else ""
                decrypted_secret = crypto_utils.decrypt(acc.app_secret).strip() if acc.app_secret else ""
                
                self.broker = KiwoomBroker(decrypted_key, decrypted_secret, acc.account_number, is_mock=is_mock)
                logger.info(f"[{self.uid}] Broker configured: {acc.broker} ({active_id}) - Mock: {is_mock}")
                
                # 기존 가격 정보 복원
                status_data = self.fb.get_data(f"{self.base_path}/status")
                if status_data and isinstance(status_data, dict) and "stock_prices" in status_data:
                    for sym, p in status_data["stock_prices"].items():
                        if sym in self.settings.watch_symbols:
                            self.price_data[sym] = float(p)
                            
                self._last_balance_check = 0.0
                self._restart_websocket()
            elif acc.broker in ("업비트", "Upbit"):
                if self.broker:
                    self._loop.create_task(self.broker.close_websocket())
                decrypted_key = crypto_utils.decrypt(acc.app_key).strip() if acc.app_key else ""
                decrypted_secret = crypto_utils.decrypt(acc.app_secret).strip() if acc.app_secret else ""
                self.broker = UpbitBroker(decrypted_key, decrypted_secret)
                logger.info(f"[{self.uid}] Broker configured: {acc.broker} ({active_id})")

                # 기존 가격 정보 복원
                status_data = self.fb.get_data(f"{self.base_path}/status")
                if status_data and isinstance(status_data, dict) and "stock_prices" in status_data:
                    for sym, p in status_data["stock_prices"].items():
                        if sym in self.settings.watch_symbols:
                            self.price_data[sym] = float(p)

                self._last_balance_check = 0.0
                self._restart_websocket()
            else:
                logger.info(f"[{self.uid}] Unsupported broker selected: {acc.broker}")
                if self.broker:
                    self._loop.create_task(self.broker.close_websocket())
                    self.broker = None
                
                # Clear Firebase status so UI knows it's inactive
                self.price_data.clear()
                
                self.fb.update_data(f"{self.base_path}/status", {
                    "total_balance": 0.0,
                    "current_yield": 0.0,
                    "holdings": {},
                    "stock_prices": {}
                })

    def _restart_websocket(self):
        if self.broker:
            if self._restart_task and not self._restart_task.done():
                self._restart_task.cancel()
            self._restart_task = self._loop.create_task(self._do_restart_websocket())

    async def _do_restart_websocket(self):
        await self.broker.close_websocket()
        symbols = [s for s, v in self.settings.watch_symbols.items() if v.enabled]
            
        if symbols:
            await self.broker.connect_websocket(symbols, self._on_ws_price)

    def _on_ws_price(self, symbol: str, price: float):
        self.price_data[symbol] = price
        if symbol not in self.price_history:
            self.price_history[symbol] = deque(maxlen=120)
        self.price_history[symbol].append((time.time(), price))

    def _on_settings_change(self, event):
        if event.data is not None:
            # To avoid thread issues, schedule on loop
            self._loop.call_soon_threadsafe(self._handle_settings_update)
            
    def _handle_settings_update(self):
        self._load_settings()
        
        # 삭제된 감시종목에 대한 유령 캐시 지우기
        removed_symbols = [s for s in self.price_data.keys() if s not in self.settings.watch_symbols]
        for s in removed_symbols:
            self.price_data.pop(s, None)
            
        self._last_rest_price = 0.0
        self._restart_websocket()
            
    def _on_account_change(self, event):
        self._loop.call_soon_threadsafe(self._handle_account_update)

    def _handle_account_update(self):
        self._load_account_data()
        self._setup_broker()

    def _on_manual_order(self, event):
        data = event.data
        if isinstance(data, dict):
            cmd = data.get("command", "NONE")
            if cmd in ["BUY", "SELL"]:
                sym = data.get("symbol")
                qty = float(data.get("quantity", 1))
                price = float(data.get("price", 0.0))
                asyncio.run_coroutine_threadsafe(self._execute_manual_order(cmd, sym, qty, price), self._loop)

    async def _execute_manual_order(self, cmd, sym, qty, price):
        if not self.broker:
            return
        
        # 사용자가 입력한 가격이 없거나 0이면 현재가 사용
        if not price or price <= 0.0:
            price = self.price_data.get(sym)
            
        if not price or price <= 0.0:
            logger.warning(f"[{self.uid}] Cannot execute manual order: No price data for {sym}")
            return
            
        success, err_msg = await self.broker.execute_order(sym, cmd, qty, price, True)
        if success:
            logger.info(f"[{self.uid}] Manual order executed: {cmd} {sym} {qty}")
            self.fb.send_push_notification(self.uid, "수동 주문 체결", f"[{sym}] {cmd} {qty} 주문이 체결되었습니다.")
            self.fb.update_data(f"{self.base_path}/manual_orders", {
                "command": "NONE",
                "status": "SUCCESS",
                "error_message": ""
            })
        else:
            self.fb.update_data(f"{self.base_path}/manual_orders", {
                "command": "NONE",
                "status": "FAILED",
                "error_message": err_msg
            })

    def _on_conditional_orders_change(self, event):
        self._loop.call_soon_threadsafe(self._handle_conditional_update)

    def _handle_conditional_update(self):
        data = self.fb.get_data(f"{self.base_path}/conditional_orders")
        if isinstance(data, dict):
            self.conditional_orders = {k: ConditionalOrder(**v) for k, v in data.items() if v.get("status") == "PENDING"}

    def _on_stop_loss_orders_change(self, event):
        self._loop.call_soon_threadsafe(self._handle_stop_loss_update)

    def _handle_stop_loss_update(self):
        data = self.fb.get_data(f"{self.base_path}/stop_loss_orders")
        if isinstance(data, dict):
            self.stop_loss_orders = {k: StopLossOrder(**v) for k, v in data.items() if v.get("status") == "PENDING"}

    async def _fetch_stock_name(self, symbol: str) -> str:
        # 업비트 브로커인 경우 코인 이름 매핑 사용
        if isinstance(self.broker, UpbitBroker):
            return UpbitBroker.get_coin_name(symbol)
        if self.broker and self.broker.is_overseas:
            return symbol
        try:
            async with aiohttp.ClientSession() as session:
                url = f"https://finance.naver.com/item/main.naver?code={symbol.strip()}"
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=3)) as res:
                    text = await res.text()
                    match = re.search(r'<title>(.*?)\s*:\s*(?:네이버|Npay)', text)
                    if match:
                        return match.group(1).strip()
        except Exception as e:
            logger.debug(f"Failed to fetch stock name for {symbol}: {e}")
        return symbol

    async def _session_loop(self):
        last_sync = 0.0
        self._last_balance_check = 0.0
        
        while self._running:
            now = time.time()
            
            if now - last_sync >= 1.0:
                kst = timezone(timedelta(hours=9))
                self.fb.set_data(f"{self.base_path}/status/stock_prices", self.price_data)
                if self.stock_names:
                    self.fb.set_data(f"{self.base_path}/status/stock_names", self.stock_names)
                self.fb.set_data(f"{self.base_path}/status/last_updated", datetime.now(kst).isoformat())
                last_sync = now
                
            if self.broker and now - self._last_balance_check > 30.0:
                bal, yld, holdings = await self.broker.get_balance()
                status_update = {}
                if bal is not None:
                    status_update["total_balance"] = float(bal)
                    status_update["current_yield"] = float(yld) if yld else 0.0
                    
                    holdings_data = {}
                    if holdings:
                        for h in holdings:
                            holdings_data[h["symbol"]] = {
                                "symbol": h["symbol"],
                                "quantity": float(h["quantity"]),
                                "purchase_avg_price": float(h["purchase_avg_price"]),
                                "current_price": float(h["current_price"]),
                                "profit_loss_amount": float(h["profit_loss_amount"]),
                                "profit_loss_rate": float(h["profit_loss_rate"]),
                                "name": h.get("name", "")
                            }
                    status_update["holdings"] = holdings_data
                    status_update["last_updated"] = time.time()
                    status_update["auth_error"] = ""
                else:
                    status_update["total_balance"] = 0.0
                    status_update["current_yield"] = 0.0
                    status_update["holdings"] = {}
                    status_update["last_updated"] = time.time()
                    status_update["auth_error"] = "API 키 인증에 실패했습니다. 키를 확인해 주세요."

                self.fb.update_data(f"{self.base_path}/status", status_update)
                self._last_balance_check = now
                
            if self.broker and (now - self._last_rest_price >= 30.0):
                symbols = [s for s, v in self.settings.watch_symbols.items() if v.enabled]
                stale_symbols = []
                for sym in symbols:
                    hist = self.price_history.get(sym)
                    if not hist or (now - hist[-1][0] > 25):
                        stale_symbols.append(sym)
                
                if stale_symbols:
                    rest_prices = await self.broker.fetch_rest_current_prices(stale_symbols)
                    for sym, price in rest_prices.items():
                        self._on_ws_price(sym, price)
                            
                self._last_rest_price = now
                    
                # Sync missing stock names for watch list and surge configs
                all_symbols_for_names = set(symbols)
                if self.settings.surge_trading.enabled:
                    all_symbols_for_names.update(self.settings.surge_trading.per_symbol_config.keys())
                for sym in all_symbols_for_names:
                    if sym not in self.stock_names:
                        name = await self._fetch_stock_name(sym)
                        self.stock_names[sym] = name
                
            if self.settings.is_auto_trading and self.broker:
                await self._check_conditional_orders()
                await self._check_stop_loss()
                await self._check_surge_trading()

            await asyncio.sleep(0.5)

    async def _check_conditional_orders(self):
        to_remove = []
        for oid, order in self.conditional_orders.items():
            price = self.price_data.get(order.symbol)
            if not price: continue
            
            triggered = (order.side == "BUY" and price <= order.target_price) or \
                        (order.side == "SELL" and price >= order.target_price)
                        
            if triggered:
                # 목표가 도달 시 시장가(0.0)로 전송하여 즉시 체결 유도
                success, _ = await self.broker.execute_order(order.symbol, order.side, order.quantity, 0.0, order.allow_extended_hours)
                if success:
                    self.fb.send_push_notification(self.uid, "조건부 주문 체결", f"[{order.symbol}] {order.side} {order.quantity} 주문이 체결되었습니다.")
                    self.fb.update_data(f"{self.base_path}/conditional_orders/{oid}", {
                        "status": "EXECUTED",
                        "executed_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                        "executed_price": price
                    })
                    to_remove.append(oid)
                    
        for oid in to_remove:
            self.conditional_orders.pop(oid, None)

    async def _check_stop_loss(self):
        to_remove = []
        for oid, order in self.stop_loss_orders.items():
            price = self.price_data.get(order.symbol)
            if not price: continue
            
            if price <= order.trigger_price:
                qty = 1.0
                status_data = self.fb.get_data(f"{self.base_path}/status")
                if status_data and "holdings" in status_data:
                    holding = status_data["holdings"].get(order.symbol)
                    if holding:
                        total_qty = float(holding["quantity"])
                        qty = total_qty * (order.volume_percent / 100.0)
                        
                if qty > 0:
                    # 손절은 무조건 시장가(0.0)로 전송하여 즉시 체결 유도
                    success, _ = await self.broker.execute_order(order.symbol, "SELL", qty, 0.0, order.allow_extended_hours)
                    if success:
                        self.fb.send_push_notification(self.uid, "자동 손절매 체결", f"[{order.symbol}] SELL {qty} 주문이 체결되었습니다.")
                        self.fb.update_data(f"{self.base_path}/stop_loss_orders/{oid}", {
                            "status": "EXECUTED",
                            "executed_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                            "executed_price": price
                        })
                        to_remove.append(oid)
                    
        for oid in to_remove:
            self.stop_loss_orders.pop(oid, None)

    async def _check_surge_trading(self):
        cfg = self.settings.surge_trading
        if not cfg.enabled: return
        
        for sym, hist in self.price_history.items():
            if len(hist) < 2: continue
            
            hist_list = list(hist)
            latest_ts, latest_p = hist_list[-1]
            if sym not in cfg.per_symbol_config: continue
            
            configs = cfg.per_symbol_config[sym]
            if not isinstance(configs, list):
                # Fallback if old data format is somehow loaded
                configs = [configs]
                
            for i, sym_cfg in enumerate(configs):
                if sym_cfg.buy_quantity <= 0: continue
                
                cutoff_ts = latest_ts - sym_cfg.time_window_seconds
                
                past_p = None
                for ts, p in reversed(hist_list):
                    if ts <= cutoff_ts:
                        past_p = p
                        break
                        
                if not past_p: continue
                
                surge_pct = (latest_p - past_p) / past_p * 100.0
                if surge_pct >= sym_cfg.surge_percent_threshold:
                    last_buy = self.last_surge_buy_time.get(sym, 0)
                    if time.time() - last_buy > 60: # 60s cooldown
                        logger.info(f"[{self.uid}] Surge detected on {sym} ({sym_cfg.time_window_seconds}s, {sym_cfg.surge_percent_threshold}%): {surge_pct:.2f}%. Triggering BUY order.")
                        self._loop.create_task(self._execute_surge_buy(sym, sym_cfg.buy_quantity, latest_p, cfg.allow_extended_hours, i))
                        # Only trigger once per symbol per cycle, break out of config loop to respect cooldown
                        break

    async def _execute_surge_buy(self, symbol, qty, price, allow_extended, config_idx=None):
        success, _ = await self.broker.execute_order(symbol, "BUY", qty, price, allow_extended)
        if success:
            self.fb.send_push_notification(self.uid, "급등주 매수 체결", f"[{symbol}] BUY {qty} 주문이 체결되었습니다.")
            self.last_surge_buy_time[symbol] = time.time()
            
            if config_idx is not None:
                cfg = self.settings.surge_trading
                if symbol in cfg.per_symbol_config:
                    configs = cfg.per_symbol_config[symbol]
                    if 0 <= config_idx < len(configs):
                        del configs[config_idx]
                        dumped = [c.dict() for c in configs]
                        self.fb.set_data(f"{self.base_path}/settings/surge_trading/per_symbol_config/{symbol}", dumped)
                        
            # Wait a bit before checking balance to allow exchange to process the order
            await asyncio.sleep(2.0)
            self._last_balance_check = 0.0
