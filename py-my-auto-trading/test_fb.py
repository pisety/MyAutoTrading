import sys, os
from dotenv import load_dotenv
sys.path.append('/home/ubuntu')
import crypto_utils
from database.firebase_client import FirebaseClient

load_dotenv()
db_url = os.getenv("FIREBASE_DB_URL")
cert_path = os.getenv("FIREBASE_CREDENTIALS_PATH", "serviceAccountKey.json")

fb = FirebaseClient(db_url, cert_path)
fb.initialize()

for mode in ['한국투자_MOCK_KR', '한국투자_REAL_KR']:
    print(f"--- {mode} ---")
    data = fb.get_data(f"users/test_user/account_data/saved_accounts/{mode}")
    if data:
        enc1 = data.get('app_key', '')
        enc2 = data.get('app_secret', '')
        d1 = crypto_utils.decrypt(enc1).strip()
        d2 = crypto_utils.decrypt(enc2).strip()
        print("Key len:", len(d1))
        print("Secret len:", len(d2))
        print("Key:", d1[:5], "...", d1[-5:])
        print("Secret:", d2[:5], "...", d2[-5:])
    else:
        print("No data")
