import sys
sys.path.append('.')
from database.firebase_client import FirebaseClient
import json

fb = FirebaseClient('https://my-auto-trading-9deb3-default-rtdb.asia-southeast1.firebasedatabase.app', 'serviceAccountKey.json')
fb.initialize()

# Reset round to 0, clear holding_quantity, t_value, and last_exec_date for testing
data = fb.get_data('users/test_user/stock/accounts/한국투자_MOCK_OS/laore_status')
if data:
    for key, val in data.items():
        val['current_round'] = 0
        val['holding_quantity'] = 0.0
        val['t_value'] = 0.0
        val['last_exec_date'] = ""
    fb.set_data('users/test_user/stock/accounts/한국투자_MOCK_OS/laore_status', data)
    print("Laore status reset successfully.")
