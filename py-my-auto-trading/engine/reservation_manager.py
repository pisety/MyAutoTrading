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
                
                # 2. 16시 00분 정각 체크 (16:00:00 ~ 16:00:05 사이 실행, 당일 1회 한정)
                if now.hour == 16 and now.minute == 0 and now.second <= 5:
                    if self._last_1600_date != today_str:
                        self._last_1600_date = today_str
                        logger.info(f"[{today_str} 16:00:00] 장전시간외 예약매수 스케줄러 트리거!")
                        await self.execute_reservations_1600()
                
                # 3. 08시 00분 정각 체크 (08:00:00 ~ 08:00:05 사이 실행, 당일 1회 한정)
                if now.hour == 8 and now.minute == 0 and now.second <= 5:
                    if self._last_0800_date != today_str:
                        self._last_0800_date = today_str
                        logger.info(f"[{today_str} 08:00:00] 미국 증시 자동 판단 및 취소 스케줄러 트리거!")
                        await self.evaluate_and_cancel_0800()
                
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
                            order_no=order_no, symbol=symbol, qty=qty, ord_dvsn="01"
                        )
                        if c_succ:
                            self.fb.set_data(f"{path}/{order_id}/status", "취소됨")
                        else:
                            self.fb.set_data(f"{path}/{order_id}/status", f"취소실패({c_msg})")
                    else:
                        self.fb.set_data(f"{path}/{order_id}/status", "취소됨")
                    self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)

    async def process_pending_new_orders(self):
        """주말/휴일 또는 평일 16시 이후~익일 아침 7시 이전에 신규 등록된 '대기중' 주문을 즉시 증권사 예약주문으로 발송"""
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
                
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict): continue
            
            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                status = order.get("status", "")
                if status == "대기중" or (status.startswith("08시 유지됨") and not order.get("order_no")):
                    symbol = order.get("symbol", "")
                    qty = float(order.get("quantity", 0))
                    if not symbol or qty <= 0: continue
                    
                    logger.info(f"[{session.uid}] 신규/대기 예약매수 즉시 발사: {symbol} {qty}주 (ORD_DVSN: 01)")
                    if not session.broker: continue
                    
                    success, msg = await session.broker.execute_order(
                        symbol=symbol, side="BUY", qty=qty, price=0.0, ord_dvsn="01"
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
                            self.fb.set_data(f"{path}/{order_id}/status", "16시 전송완료")
                        if odno:
                            self.fb.set_data(f"{path}/{order_id}/order_no", odno)
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        logger.info(f"[{session.uid}] 신규 예약매수 즉시 전송성공! (ODNO: {odno})")
                    else:
                        self.fb.set_data(f"{path}/{order_id}/status", f"전송실패({msg_text})")
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        logger.warning(f"[{session.uid}] 신규 예약매수 즉시 전송실패: {msg_text}")
                        self.fb.send_push_notification(
                            session.uid,
                            "⚠️ 예약매수 주문 접수 실패",
                            f"[{symbol}] {qty}주 예약접수 실패: {msg_text}"
                        )

    async def execute_reservations_1600(self):
        """매일 16:00:00.050에 등록된 장전시간외종가 예약매수 발사"""
        for session_key, session in getattr(self.user_manager, "sessions", {}).items():
            if not self._is_valid_kis_domestic_session(session):
                continue
                
            path = f"{session.account_path}/reservation_orders"
            orders = self.fb.get_data(path)
            if not isinstance(orders, dict):
                continue
                
            for order_id, order in orders.items():
                if not isinstance(order, dict): continue
                # 매일 16시 정각에 대기중이거나 이전일 유지/자동취소/실패 상태였던 주문들을 재발사 (수동 취소됨 제외)
                if status in ["대기중", "유지됨", "08시 유지됨", "08시 자동취소됨", "16시 전송완료"] or "실패" in status:
                    symbol = order.get("symbol", "")
                    qty = float(order.get("quantity", 0))
                    if not symbol or qty <= 0: continue
                    
                    logger.info(f"[{session.uid}] 16시 예약매수 발사: {symbol} {qty}주 (ORD_DVSN: 01)")
                    if not session.broker:
                        continue
                        
                    success, msg = await session.broker.execute_order(
                        symbol=symbol, side="BUY", qty=qty, price=0.0, ord_dvsn="01"
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
                        self.fb.set_data(f"{path}/{order_id}/status", "16시 전송완료")
                        if odno:
                            self.fb.set_data(f"{path}/{order_id}/order_no", odno)
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        logger.info(f"[{session.uid}] 예약매수 전송성공! (ODNO: {odno})")
                    else:
                        self.fb.set_data(f"{path}/{order_id}/status", f"전송실패({msg_text})")
                        self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
                        logger.warning(f"[{session.uid}] 예약매수 전송실패: {msg_text}")
                        self.fb.send_push_notification(
                            session.uid,
                            "⚠️ 예약매수 주문 접수 실패",
                            f"[{symbol}] {qty}주 예약접수 실패: {msg_text}"
                        )

    async def evaluate_and_cancel_0800(self):
        """매일 08:00:00에 미국 증시 자동판단 ON인 주문들 평균 시세 검증 및 자동 취소"""
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
                if status in ["16시 전송완료", "대기중", "유지됨"]:
                    symbol = order.get("symbol", "")
                    name = order.get("name", symbol)
                    qty = int(float(order.get("quantity", 0)))
                    order_no = order.get("order_no", "")
                    threshold = float(order.get("auto_cancel_threshold", 1.0))
                    
                    logger.info(f"[{session.uid}] 08시 미국 증시 자동판단 시작: {name}({symbol}) [기준값: {threshold}% 미만 시 취소]")
                    avg_rate, _ = await analyze_etf_overnight_return(symbol if symbol else name)
                    
                    now_ts = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S")
                    if avg_rate < threshold:
                        logger.info(f"[{session.uid}] {name} 산술평균 등락률({avg_rate:.2f}%) < 기준값({threshold}%) ➔ 자동 취소 실행!")
                        if order_no and session.broker:
                            c_succ, c_msg = await session.broker.cancel_domestic_order(
                                order_no=order_no, symbol=symbol, qty=qty, ord_dvsn="01"
                            )
                            if c_succ:
                                self.fb.set_data(f"{path}/{order_id}/status", f"08시 자동취소됨 (평균 {avg_rate:.2f}%)")
                            else:
                                self.fb.set_data(f"{path}/{order_id}/status", f"취소요청실패({c_msg}) (평균 {avg_rate:.2f}%)")
                        else:
                            self.fb.set_data(f"{path}/{order_id}/status", f"08시 자동취소됨 (평균 {avg_rate:.2f}%)")
                        self.fb.send_push_notification(
                            session.uid,
                            "🇺🇸 08시 미국 증시 자동 취소 발동",
                            f"[{name}({symbol})] 산술평균({avg_rate:.2f}%) < 기준치({threshold}%) ➔ 예약매수 자동 취소됨!"
                        )
                    else:
                        logger.info(f"[{session.uid}] {name} 산술평균 등락률({avg_rate:.2f}%) >= 기준값({threshold}%) ➔ 주문 유지!")
                        self.fb.set_data(f"{path}/{order_id}/status", f"08시 유지됨 (평균 {avg_rate:.2f}%)")
                    
                    self.fb.set_data(f"{path}/{order_id}/last_updated", now_ts)
