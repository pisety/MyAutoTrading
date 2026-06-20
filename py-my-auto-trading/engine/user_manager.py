import asyncio
import logging
from typing import Dict
from database.firebase_client import FirebaseClient
from engine.user_session import UserSession

logger = logging.getLogger("UserManager")

class UserManager:
    def __init__(self, fb_client: FirebaseClient):
        self.fb = fb_client
        self.sessions: Dict[str, UserSession] = {}
        self._loop = asyncio.get_event_loop()

    def start_listening(self):
        logger.info("UserManager started listening for new users.")
        # Listen to the root of 'users' to detect new or removed users dynamically
        self.fb.listen("users", self._on_users_change)

    def _on_users_change(self, event):
        if event.path == "/":
            self._loop.call_soon_threadsafe(self._sync_sessions, event.data)
        else:
            parts = [p for p in event.path.split('/') if p]
            if parts:
                uid = parts[0]
                if event.data is None:
                    # Node was deleted, so we must stop and remove both sessions
                    for stype in ("stock", "crypto"):
                        key = f"{uid}_{stype}"
                        if key in self.sessions:
                            session = self.sessions.pop(key)
                            asyncio.run_coroutine_threadsafe(session.stop(), self._loop)
                else:
                    self._loop.call_soon_threadsafe(self._ensure_session, uid)

    def _sync_sessions(self, users_data):
        if not isinstance(users_data, dict):
            return

        # Derive set of currently active UIDs from composite session keys
        current_uids = set()
        for key in self.sessions:
            uid = key.rsplit("_", 1)[0]
            current_uids.add(uid)

        target_uids = set(users_data.keys())

        # Start new sessions
        for uid in target_uids - current_uids:
            self._ensure_session(uid)

        # Stop removed sessions
        for uid in current_uids - target_uids:
            logger.info(f"User removed: {uid}")
            for stype in ("stock", "crypto"):
                key = f"{uid}_{stype}"
                session = self.sessions.pop(key, None)
                if session:
                    asyncio.create_task(session.stop())

    def _ensure_session(self, uid: str):
        for stype in ("stock", "crypto"):
            key = f"{uid}_{stype}"
            if key not in self.sessions:
                logger.info(f"New user detected, creating {stype} session: {uid}")
                session = UserSession(uid, self.fb, service_type=stype)
                self.sessions[key] = session
                asyncio.create_task(session.start())

    async def stop_all(self):
        for session in self.sessions.values():
            await session.stop()
        self.sessions.clear()

