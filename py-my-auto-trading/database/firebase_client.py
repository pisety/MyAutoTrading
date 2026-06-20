import os
import logging
from typing import Callable, Dict, Any
import firebase_admin
from firebase_admin import credentials, db

logger = logging.getLogger("FirebaseClient")

class FirebaseClient:
    def __init__(self, db_url: str, cert_path: str):
        self.db_url = db_url
        self.cert_path = cert_path
        self._app = None
        self._listeners: Dict[str, Any] = {}

    def initialize(self):
        if not os.path.exists(self.cert_path):
            logger.warning(f"Firebase cert_path {self.cert_path} not found. Attempting default credentials.")
            cred = credentials.ApplicationDefault()
        else:
            cred = credentials.Certificate(self.cert_path)
            
        try:
            self._app = firebase_admin.initialize_app(cred, {
                'databaseURL': self.db_url
            })
            logger.info("Firebase SDK Initialized successfully.")
        except Exception as e:
            if "already exists" in str(e):
                logger.info("Firebase app already initialized.")
            else:
                logger.error(f"Firebase initialization failed: {e}")
                raise

    def get_data(self, path: str) -> Any:
        try:
            return db.reference(path).get()
        except Exception as e:
            logger.error(f"Error getting data from {path}: {e}")
            return None

    def set_data(self, path: str, data: Any):
        try:
            db.reference(path).set(data)
        except Exception as e:
            logger.error(f"Error setting data to {path}: {e}")

    def update_data(self, path: str, data: dict):
        try:
            db.reference(path).update(data)
        except Exception as e:
            logger.error(f"Error updating data at {path}: {e}")

    def listen(self, path: str, callback: Callable[[Any], None]):
        """
        Firebase 리스너 등록. callback은 이벤트 발생 시 호출됩니다.
        """
        def wrapper(event):
            callback(event)

        try:
            ref = db.reference(path)
            listener = ref.listen(wrapper)
            self._listeners[path] = listener
            logger.info(f"Started listening to Firebase path: {path}")
        except Exception as e:
            logger.error(f"Failed to start listener on {path}: {e}")

    def remove_listener(self, path: str):
        listener = self._listeners.pop(path, None)
        if listener:
            try:
                listener.close()
                logger.info(f"Removed listener for path: {path}")
            except Exception as e:
                logger.error(f"Error closing listener for {path}: {e}")
