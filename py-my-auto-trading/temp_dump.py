import os
import sys
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from database.firebase_client import FirebaseClient
import json

fb = FirebaseClient("https://my-auto-trading-9deb3-default-rtdb.asia-southeast1.firebasedatabase.app/", 
                    "oracle/key/firebase-adminsdk.json")
fb.initialize()
data = fb.get_data("users/test_user/crypto/status/holdings")
print("Crypto Holdings:", json.dumps(data, indent=2, ensure_ascii=False))
