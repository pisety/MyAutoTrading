import sys
sys.path.append('.')
from database.firebase_client import FirebaseClient
import json

fb = FirebaseClient('https://my-auto-trading-9deb3-default-rtdb.asia-southeast1.firebasedatabase.app', 'serviceAccountKey.json')
fb.initialize()
data = fb.get_data('users/test_user/crypto')
print(json.dumps(data, ensure_ascii=False))
