import os
import asyncio
import logging
from dotenv import load_dotenv

# Load env vars before importing other modules
load_dotenv()

from database.firebase_client import FirebaseClient
from engine.user_manager import UserManager

# Logging Setup
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger("Main")

async def main():
    logger.info("Starting Global Quant Trading Multi-User Engine")
    load_dotenv()
    
    db_url = os.getenv("FIREBASE_DB_URL")
    cert_path = os.getenv("FIREBASE_CREDENTIALS_PATH", "serviceAccountKey.json")
    
    if not db_url:
        logger.error("FIREBASE_DB_URL not found in environment.")
        return

    # 1. Initialize Firebase Client
    fb_client = FirebaseClient(db_url, cert_path)
    fb_client.initialize()

    # 2. Initialize UserManager
    user_manager = UserManager(fb_client)
    user_manager.start_listening()

    # 3. Keep Main Loop Running
    try:
        while True:
            await asyncio.sleep(3600)
    except asyncio.CancelledError:
        logger.info("Engine shutting down...")
    finally:
        await user_manager.stop_all()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Engine stopped by user.")
