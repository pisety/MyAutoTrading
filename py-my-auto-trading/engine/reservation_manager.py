import asyncio
import logging
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, List, Optional
from database.firebase_client import FirebaseClient
from engine.etf_analyzer import analyze_etf_overnight_return

logger = logging.getLogger("ReservationManager")

class ReservationManager:
    def __init__(self, fb_client: FirebaseClient, user_manager: Any):
        self.fb = fb_client
        self.user_manager = user_manager
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self._last_1600_date: Optional[str] = None
        self._last_0800_date: Optional[str] = None
        self._last_0840_date: Optional[str] = None
        self._last_0500_date: Optional[str] = None
        self._last_1530_date: Optional[str] = None

    async def start(self):
        logger.info("ReservationManager scheduler started.")
        self._running = True
        self._task = asyncio.create_task(self._scheduler_loop())

    async def stop(self):
        logger.info("ReservationManager scheduler stopping.")
        self._running = False
        if self._task:
            self._task.cancel()

    def _is_valid_kis_domestic_session(self, session) -> bool:
        if not session or not getattr(session, "_running", False) or getattr(session, "service_type", "") != "stock":
            return False
        broker = getattr(session, "broker", None)
        if not broker or broker.__class__.__name__ != "KISBroker":
            return False
        if getattr(broker, "is_mock", False) or getattr(broker, "is_overseas", False):
            return False
        return True

    def _is_kis_order_server_open(self, now: datetime) -> bool:
        """KIS 국내주식 주문/예약주문 서버 운영 시간 여부 확인"""
        weekday = now.weekday()  # 0:월 ~ 6:일
        time_val = now.hour * 100 + now.minute

        # 일요일(6) 전일 휴장 (비영업일/서버 점검)
        if weekday == 6:
            return False
            
        # 토요일(5) 07:30 이후 휴장 (금요일 밤~새벽 07:30 까지만 야간 예약접수 가능)
        if weekday == 5:
            return time_val < 730

        # 평일 (월~금)
        # 오전 일일정산/점검 시간(07:30 ~ 08:20) 및 장마감 정산 시간(15:30 ~ 16:00) 제외
        if (730 <= time_val < 820) or (1530 <= time_val < 1600):
            return False
            
        return True

    async def _reset_weekend_failed_orders(self):
        """주말/휴장 시간에 '장운영일자' 또는 '장개시 전' 오류로 실패한 주문을 다시 '대기중'으로 복구하여 영업일에 자동 접수되도록 처리"""
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue

            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict): continue

            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                status = order.get("status", "")
                if "장운영일자" in status or "장개시 전" in status:
                    symbol = order.get("symbol", "")
                    logger.info(f"[{session.uid}] 주말/휴장시간 오류 주문({symbol}) ➔ '대기중'으로 복구")
                    self.fb.set_data(f"{path}/{order_id}/status", "대기중")

    async def _scheduler_loop(self):
        KST = timezone(timedelta(hours=9))
        while self._running:
            try:
                now = datetime.now(KST)
                today_str = now.strftime("%Y-%m-%d")
                
                # 1. 사용자 수동 취소 요청 처리
                await self.process_manual_cancels()
                
                # 1-1. 증권사 주문 서버가 오픈된 시간대에 신규 '대기중' 주문을 즉시 증권사 접수
                #      서버가 닫힌 주말/휴장시간에는 접수하지 않고 '대기중'으로 유지하며 기존 오류 건을 복구
                if self._is_kis_order_server_open(now):
                    await self.process_pending_new_orders()
                else:
                    await self._reset_weekend_failed_orders()
                
                # 2. 08시 20분 정각 체크 (08:20:00.050 정밀 트리거, 당일 1회 한정) - 장전시간외 종가 대기열 일괄 접수
                if now.hour == 8 and now.minute == 20 and now.second <= 5:
                    if self._last_1600_date != today_str:
                        self._last_1600_date = today_str
                        # 증권사 서버 오픈(08:20:00.000) 전에 도착하여 '장운영시간이 아닙니다' 에러로 튕기는 것을 방지하기 위해 50ms(0.05초) 대기 후 정밀 발사
                        if now.second == 0 and now.microsecond < 50000:
                            await asyncio.sleep((50000 - now.microsecond) / 1000000.0)
                        logger.info(f"[{today_str} 08:20:00.050] 장전시간외 종가 대기열 선점 스케줄러 정밀 트리거!")
                        await self.execute_reservations_0820()
                
                # 3. 08시 00분 정각 체크 (08:00:00 ~ 08:00:05 사이 실행, 당일 1회 한정)
                if now.hour == 8 and now.minute == 0 and now.second <= 5:
                    if self._last_0800_date != today_str:
                        self._last_0800_date = today_str
                        logger.info(f"[{today_str} 08:00:00] 미국 증시 자동 판단 및 취소 스케줄러 트리거!")
                        await self.evaluate_and_cancel_0800(today_str)

                # 3-1. 05시 00분 정각 체크 (05:00:00 ~ 05:00:05 사이 실행, 당일 1회 한정)
                if now.hour == 5 and now.minute == 0 and now.second <= 5:
                    if self._last_0500_date != today_str:
                        self._last_0500_date = today_str
                        logger.info(f"[{today_str} 05:00:00] 미국 증시 정규장 마감 스냅샷 스케줄러 트리거!")
                        await self.snapshot_us_market_close_0500(today_str)
                        
                # 3-2. 15시 30분 정각 체크 (15:30:00 ~ 15:30:05 사이 실행, 당일 1회 한정)
                if now.hour == 15 and now.minute == 30 and now.second <= 5:
                    if self._last_1530_date != today_str:
                        self._last_1530_date = today_str
                        logger.info(f"[{today_str} 15:30:00] 미국 주식 데이마켓 스냅샷 스케줄러 트리거!")
                        await self.snapshot_us_day_market_1530(today_str)

                # 4. 08시 30분 ~ 08시 39분: 10초 주기 장전시간외 종가매수 실시간 체결 알림 검사
                if now.hour == 8 and 30 <= now.minute < 40 and now.second % 10 == 0:
                    await self.check_0830_0840_fill_status()

                # 5. 08시 40분 정각 체크 (08:40:00 ~ 08:40:05 사이 실행, 당일 1회 한정) - 미체결 마감 알림
                if now.hour == 8 and now.minute == 40 and now.second <= 5:
                    if self._last_0840_date != today_str:
                        self._last_0840_date = today_str
                        logger.info(f"[{today_str} 08:40:00] 장전시간외 종가 미체결 마감 스케줄러 트리거!")
                        await self.check_0840_unfilled_status()

                
            except Exception as e:
                logger.error(f"ReservationManager loop error: {e}", exc_info=True)
            
            await asyncio.sleep(1.0)

    async def process_manual_cancels(self):
        """사용자가 앱에서 [주문 취소] 버튼을 눌러 status가 '취소요청'이 된 주문 즉시 취소 처리"""
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
            
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict): continue
            
            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                if order.get("status") == "취소요청":
                    symbol = order.get("symbol", "")
                    qty = int(float(order.get("quantity", 0)))
                    order_no = order.get("order_no", "")
                    logger.info(f"[{session.uid}] 사용자 수동 예약주문 취소 요청 감지: {symbol} (주문번호: {order_no})")
                    
                    now_ts = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S")
                    if order_no and session.broker:
                        c_succ, c_msg = await session.broker.cancel_domestic_order(
                            order_no=order_no, symbol=symbol, qty=qty, ord_dvsn="05"
                        )
                        if c_succ:
                            self.fb.set_data(f"{path}/{order_id}/status", "취소됨")
                        else:
                            self.fb.set_data(f"{path}/{order_id}/status", f"취소실패({c_msg})")
                    else:
                        self.fb.set_data(f"{path}/{order_id}/status", "취소됨")
                    self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)

    async def process_pending_new_orders(self):
        """주말/휴일 또는 평일 08:40 이후~익일 08:20 이전에 신규 등록된 '대기중' 주문을 관리 및 08:20 접수 준비"""
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
                
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict): continue
            
            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                status = order.get("status", "")
                
                # 기존 16시 전송완료 레거시 상태명 자동 마이그레이션 및 대기중 주문 처리
                if status == "16시 전송완료":
                    now_kst = datetime.now(timezone(timedelta(hours=9)))
                    if order.get("order_no"):
                        self.fb.set_data(f"{path}/{order_id}/status", "08:20 접수완료")
                        logger.info(f"[{session.uid}] 레거시 상태명 자동 변환: 16시 전송완료 ➔ 08:20 접수완료 (ODNO: {order.get('order_no')})")
                    else:
                        self.fb.set_data(f"{path}/{order_id}/status", "08:20 전송대기")
                        logger.info(f"[{session.uid}] 레거시 상태명 자동 변환: 16시 전송완료 ➔ 08:20 전송대기")
                    continue
                
                if status == "대기중" or (status.startswith("08시 유지됨") and not order.get("order_no")):
                    symbol = order.get("symbol", "")
                    qty = float(order.get("quantity", 0))
                    if not symbol or qty <= 0: continue
                    
                    now_kst = datetime.now(timezone(timedelta(hours=9)))
                    # 08:20 ~ 08:39 사이 신규 등록 시 즉시 증권사 전송 (대기열 접수 또는 실시간 체결)
                    is_0820_window = (now_kst.hour == 8 and 20 <= now_kst.minute <= 39)
                    if not is_0820_window:
                        if status == "대기중":
                            self.fb.set_data(f"{path}/{order_id}/status", "08:20 전송대기")
                            self.fb.set_data(f"{path}/{order_id}/last_updated", now_kst.strftime("%Y-%m-%d %H:%M:%S"))
                            logger.info(f"[{session.uid}] 장전시간외 매수 주문({symbol}) 접수 완료: 아침 08:20 장전시간외 대기열 전송 대기 중")
                        continue
                    
                    logger.info(f"[{session.uid}] 08:20~08:40 신규 예약매수 즉시 발사: {symbol} {qty}주 (ORD_DVSN: 05)")
                    if not session.broker: continue
                    
                    success, msg = await session.broker.execute_order(
                        symbol=symbol, side="BUY", qty=qty, price=0.0, ord_dvsn="05"
                    )
                    odno = ""
                    if "|" in msg:
                        msg_parts = msg.split("|")
                        odno = msg_parts[1].strip()
                        msg_text = msg_parts[0].strip()
                    else:
                        msg_text = msg
                        
                    now_ts = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S")
                    if success:
                        if status == "대기중":
                            self.fb.set_data(f"{path}/{order_id}/status", "08:20 접수완료")
                        if odno:
                            self.fb.set_data(f"{path}/{order_id}/order_no", odno)
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        logger.info(f"[{session.uid}] 08:20~08:40 신규 예약매수 즉시 전송성공! (ODNO: {odno})")
                        self.fb.send_push_notification(
                            session.uid,
                            "🚀 장전시간외 매수 주문 전송 완료",
                            f"[{symbol}] {qty}주 한국투자증권 전송 성공! (주문번호: {odno})"
                        )
                    else:
                        self.fb.set_data(f"{path}/{order_id}/status", f"전송실패({msg_text})")
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        logger.warning(f"[{session.uid}] 08:20~08:40 신규 예약매수 즉시 전송실패: {msg_text}")
                        self.fb.send_push_notification(
                            session.uid,
                            "⚠️ 예약매수 주문 접수 실패",
                            f"[{symbol}] {qty}주 예약접수 실패: {msg_text}"
                        )

    def _get_etf_constituents_map(self) -> dict:
        cmap = self.fb.get_data("system/etf_constituents")
        if isinstance(cmap, dict) and cmap:
            return cmap
        from engine.etf_analyzer import ETF_CONSTITUENTS_MAP
        return ETF_CONSTITUENTS_MAP

    async def _get_target_reservation_etfs(self) -> set:
        target_etfs = set()
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict): continue
            for order in orders.values():
                if isinstance(order, dict) and order.get("auto_cancel_enabled", False):
                    status = order.get("status", "")
                    if status in ["16시 전송완료", "08:20 전송대기", "08:20 접수완료", "대기중", "유지됨"] or "08시 유지됨" in status or "08시 자동취소됨" in status or "실패" in status:
                        target_etfs.add(order.get("symbol", order.get("name", "")))
        return target_etfs

    async def snapshot_us_market_close_0500(self, today_str: str):
        from engine.etf_analyzer import get_constituents_for_etf, fetch_stock_rate
        target_etfs = await self._get_target_reservation_etfs()
        if not target_etfs: return
        
        cmap = self._get_etf_constituents_map()
        all_tickers = set()
        for etf in target_etfs:
            if not etf: continue
            all_tickers.update(get_constituents_for_etf(etf, constituents_map=cmap))
            
        if not all_tickers: return
            
        logger.info(f"[05:00 Snapshot] Fetching close prices for {len(all_tickers)} US stocks...")
        tasks = [fetch_stock_rate(t) for t in all_tickers]
        results = await asyncio.gather(*tasks)
        
        snapshot = {}
        for r in results:
            if r["success"] and r["price"] > 0:
                snapshot[r["symbol"]] = r["price"]
                
        if snapshot:
            self.fb.set_data(f"system/us_snapshot/{today_str}_0500", snapshot)
            logger.info(f"[05:00 Snapshot] Saved {len(snapshot)} prices to Firebase.")

    async def snapshot_us_day_market_1530(self, today_str: str):
        snapshot_0500 = self.fb.get_data(f"system/us_snapshot/{today_str}_0500")
        if not isinstance(snapshot_0500, dict): snapshot_0500 = {}
            
        overseas_broker = None
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            broker = getattr(session, "broker", None)
            if broker and getattr(broker, "is_overseas", False) and not getattr(broker, "is_mock", False):
                overseas_broker = broker
                break
        if not overseas_broker:
            for session_key, session in getattr(self.user_manager, "sessions", {}).items():
                broker = getattr(session, "broker", None)
                if broker and getattr(broker, "is_overseas", False):
                    overseas_broker = broker
                    break

        from engine.etf_analyzer import get_constituents_for_etf
        target_etfs = await self._get_target_reservation_etfs()
        cmap = self._get_etf_constituents_map()
        all_tickers = set()
        for etf in target_etfs:
            if not etf: continue
            all_tickers.update(get_constituents_for_etf(etf, constituents_map=cmap))
            
        tickers = list(all_tickers)
        final_snapshot = {}
        
        if overseas_broker and tickers:
            logger.info(f"[15:30 Snapshot] Fetching day market prices via KIS for {len(tickers)} stocks...")
            kis_prices = await overseas_broker.fetch_rest_current_prices(tickers)
            for t in tickers:
                if t in kis_prices:
                    price = kis_prices[t]
                    if isinstance(price, tuple): price = price[0]
                    if price > 0:
                        final_snapshot[t] = price
                        
        for t in tickers:
            if t not in final_snapshot and t in snapshot_0500:
                final_snapshot[t] = snapshot_0500[t]
                
        if final_snapshot:
            self.fb.set_data(f"system/us_snapshot/{today_str}_base_price", final_snapshot)
            logger.info(f"[15:30 Snapshot] Saved base prices to Firebase (Fallback applied).")

    async def execute_reservations_0820(self):
        """매일 아침 08:20:00.050에 등록된 장전시간외종가 예약매수 증권사 대기열 발사 (ORD_DVSN: 05)"""
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
                
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict):
                continue
                
            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                status = order.get("status", "")
                if "취소" in status:
                    continue
                
                # 매일 아침 08:20 정각에 대기중이거나 이전일 유지/실패 상태였던 주문들을 대기열로 발사
                if status in ["대기중", "유지됨", "16시 전송완료", "08:20 전송대기"] or "08시 유지됨" in status or "전송실패" in status:
                    symbol = order.get("symbol", "")
                    qty = float(order.get("quantity", 0))
                    if not symbol or qty <= 0: continue
                    
                    logger.info(f"[{session.uid}] 08:20 장전시간외 대기열 선점 매수 발사: {symbol} {qty}주 (ORD_DVSN: 05)")
                    if not session.broker:
                        continue
                        
                    success, msg = await session.broker.execute_order(
                        symbol=symbol, side="BUY", qty=qty, price=0.0, ord_dvsn="05"
                    )
                    
                    odno = ""
                    if "|" in msg:
                        msg_parts = msg.split("|")
                        odno = msg_parts[1].strip()
                        msg_text = msg_parts[0].strip()
                    else:
                        msg_text = msg
                        
                    now_ts = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S")
                    if success:
                        self.fb.set_data(f"{path}/{order_id}/status", "08:20 접수완료")
                        if odno:
                            self.fb.set_data(f"{path}/{order_id}/order_no", odno)
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        logger.info(f"[{session.uid}] 장전시간외 대기열 전송성공! (ODNO: {odno})")
                        self.fb.send_push_notification(
                            session.uid,
                            "🚀 08:20 장전시간외 대기열 선점 완료",
                            f"[{symbol}] {qty}주 한국투자증권 대기열 접수 성공! (주문번호: {odno})"
                        )
                    else:
                        self.fb.set_data(f"{path}/{order_id}/status", f"전송실패({msg_text})")
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        logger.warning(f"[{session.uid}] 장전시간외 대기열 전송실패: {msg_text}")
                        self.fb.send_push_notification(
                            session.uid,
                            "⚠️ 08:20 장전시간외 대기열 매수 발송 실패",
                            f"[{symbol}] {qty}주 장전시간외 대기열 발송 실패: {msg_text}"
                        )

    async def evaluate_and_cancel_0800(self, today_str: str = None):
        """매일 08:00:00에 미국 증시 자동판단 ON인 주문들 평균 시세 검증 및 자동 취소"""
        if not today_str:
            KST = timezone(timedelta(hours=9))
            now = datetime.now(KST)
            today_str = now.strftime("%Y-%m-%d")
        else:
            now = datetime.strptime(today_str, "%Y-%m-%d")
            
        base_prices = None
        for i in range(1, 6):
            prev_date = (now - timedelta(days=i)).strftime("%Y-%m-%d")
            bp = self.fb.get_data(f"system/us_snapshot/{prev_date}_base_price")
            if isinstance(bp, dict):
                base_prices = bp
                break
                
        if not isinstance(base_prices, dict):
            base_prices = self.fb.get_data(f"system/us_snapshot/{today_str}_0500")
            
        if not isinstance(base_prices, dict):
            base_prices = None

        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
            
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict):
                continue
                
            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                if not order.get("auto_cancel_enabled", False):
                    continue
                
                status = order.get("status", "")
                if status in ["16시 전송완료", "08:20 전송대기", "08:20 접수완료", "대기중", "유지됨"] or "08시 유지됨" in status or "08시 자동취소됨" in status or "실패" in status:
                    symbol = order.get("symbol", "")
                    name = order.get("name", symbol)
                    qty = int(float(order.get("quantity", 0)))
                    order_no = order.get("order_no", "")
                    threshold = float(order.get("auto_cancel_threshold", 1.0))
                    
                    logger.info(f"[{session.uid}] 08시 미국 증시 자동판단 시작: {name}({symbol}) [기준값: {threshold}% 미만 시 취소]")
                    cmap = self._get_etf_constituents_map()
                    avg_rate, _ = await analyze_etf_overnight_return(symbol if symbol else name, base_prices=base_prices, constituents_map=cmap)
                    
                    now_ts = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S")
                    if avg_rate < threshold:
                        logger.info(f"[{session.uid}] {name} 산술평균 등락률({avg_rate:.2f}%) < 기준값({threshold}%) ➔ 자동 취소 실행 (Firebase 상태 업데이트)!")
                        self.fb.set_data(f"{path}/{order_id}/status", f"08시 자동취소됨 (평균 {avg_rate:.2f}%)")
                        self.fb.send_push_notification(
                            session.uid,
                            "🇺🇸 08시 미국 증시 자동 취소 발동",
                            f"[{name}({symbol})] 산술평균({avg_rate:.2f}%) < 기준치({threshold}%) ➔ 예약매수 자동 취소됨!"
                        )
                        await asyncio.sleep(1) # 푸시 알림 가드 타임
                    else:
                        logger.info(f"[{session.uid}] {name} 산술평균 등락률({avg_rate:.2f}%) >= 기준값({threshold}%) ➔ 주문 유지!")
                        self.fb.set_data(f"{path}/{order_id}/status", f"08시 유지됨 (평균 {avg_rate:.2f}%)")
                        self.fb.send_push_notification(
                            session.uid,
                            "🇺🇸 08시 미국 증시 예약매수 유지",
                            f"[{name}({symbol})] 산술평균({avg_rate:.2f}%) ≥ 기준치({threshold}%) ➔ 예약매수 정상 유지(08:20 전송 예정)!"
                        )
                        await asyncio.sleep(1) # 푸시 알림 가드 타임
                    
                    self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)

    async def check_0830_0840_fill_status(self):
        """08:30 ~ 08:39 사이에 전송완료된 장전시간외 주문들의 실시간 체결 여부를 조회하여 체결 완료 알림 발송"""
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
            
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict):
                continue
                
            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                status = order.get("status", "")
                if status in ["16시 전송완료", "08:20 접수완료"]:
                    order_no = order.get("order_no", "")
                    symbol = order.get("symbol", "")
                    name = order.get("name", symbol)
                    qty = int(float(order.get("quantity", 0)))
                    
                    if order_no and session.broker and hasattr(session.broker, "inquire_order_fill_status"):
                        tot_ccld_qty, rmnd_qty = await session.broker.inquire_order_fill_status(order_no)
                        if tot_ccld_qty > 0 and (tot_ccld_qty >= qty or rmnd_qty == 0):
                            now_ts = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S")
                            self.fb.set_data(f"{path}/{order_id}/status", "08시 장전체결완료")
                            self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                            logger.info(f"[{session.uid}] 장전시간외 종가매수 실시간 체결 확인: {name}({symbol}) {tot_ccld_qty}주")
                            self.fb.send_push_notification(
                                session.uid,
                                "🎉 장전시간외 종가매수 체결 완료",
                                f"[{name}] {tot_ccld_qty}주가 장전시간외종가로 체결되었습니다! (주문번호: {order_no})"
                            )

    async def check_0840_unfilled_status(self):
        """08:40 마감 시점에 여전히 전송완료(미체결) 상태인 주문들을 최종 조회하여 미체결 마감 또는 체결 알림 발송"""
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
            
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict):
                continue
                
            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                status = order.get("status", "")
                if status in ["16시 전송완료", "08:20 접수완료"]:
                    order_no = order.get("order_no", "")
                    symbol = order.get("symbol", "")
                    name = order.get("name", symbol)
                    qty = int(float(order.get("quantity", 0)))
                    now_ts = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S")
                    
                    if order_no and session.broker and hasattr(session.broker, "inquire_order_fill_status"):
                        tot_ccld_qty, rmnd_qty = await session.broker.inquire_order_fill_status(order_no)
                        if tot_ccld_qty > 0 and (tot_ccld_qty >= qty or rmnd_qty == 0):
                            self.fb.set_data(f"{path}/{order_id}/status", "08시 장전체결완료")
                            self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                            logger.info(f"[{session.uid}] 장전시간외 종가매수 마감 시 체결 확인: {name}({symbol}) {tot_ccld_qty}주")
                            self.fb.send_push_notification(
                                session.uid,
                                "🎉 장전시간외 종가매수 체결 완료",
                                f"[{name}] {tot_ccld_qty}주가 장전시간외종가로 체결되었습니다! (주문번호: {order_no})"
                            )
                        else:
                            self.fb.set_data(f"{path}/{order_id}/status", "08시 장전미체결")
                            self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                            logger.info(f"[{session.uid}] 장전시간외 종가매수 미체결 마감: {name}({symbol}) {qty}주")
                            self.fb.send_push_notification(
                                session.uid,
                                "⏰ 08:40 장전시간외 매수 미체결 알림",
                                f"[{name}] {qty}주가 08:40 마감까지 체결되지 않아 자동 마감되었습니다. (주문번호: {order_no})"
                            )
                    else:
                        # API 미지원 시 기본 처리
                        self.fb.set_data(f"{path}/{order_id}/status", "08시 장전미체결")
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        self.fb.send_push_notification(
                            session.uid,
                            "⏰ 08:40 장전시간외 매수 미체결 알림",
                            f"[{name}] {qty}주가 08:40 마감 시점까지 체결되지 않아 마감되었습니다."
                        )

