import sys
import asyncio
sys.path.append('.')
from database.firebase_client import FirebaseClient
from brokers.kis_broker import KISBroker
import crypto_utils
import json

async def test():
    fb = FirebaseClient('https://my-auto-trading-9deb3-default-rtdb.asia-southeast1.firebasedatabase.app', 'serviceAccountKey.json')
    fb.initialize()
    data = fb.get_data('users/test_user/stock/account_data')
    acc = data["saved_accounts"]["한국투자_MOCK_KR"]
    
    decrypted_key = crypto_utils.decrypt(acc["app_key"]).strip()
    decrypted_secret = crypto_utils.decrypt(acc["app_secret"]).strip()
    
    broker = KISBroker(decrypted_key, decrypted_secret, acc["account_number"], is_mock=True, is_overseas=False)
    
    # We will simulate get_balance() to see the raw output
    import aiohttp
    await broker.ensure_valid_token()
    
    headers = {
        "content-type": "application/json",
        "authorization": f"Bearer {broker.access_token}",
        "appkey": broker.app_key,
        "appsecret": broker.app_secret,
        "tr_id": "VTTC8434R"
    }
    
    params = {
        "CANO": broker.account_number,
        "ACNT_PRDT_CD": broker.acnt_prdt_cd,
        "AFHR_FLPR_YN": "N",
        "OFL_YN": "",
        "INQR_DVSN": "02",
        "UNPR_DVSN": "01",
        "FUND_STTL_ICLD_YN": "N",
        "FNCG_AMT_AUTO_RDPT_YN": "N",
        "PRCS_DVSN": "00",
        "CTX_AREA_FK100": "",
        "CTX_AREA_NK100": ""
    }
    
    url = f"{broker.base_url}/uapi/domestic-stock/v1/trading/inquire-balance"
    async with aiohttp.ClientSession() as session:
        async with session.get(url, headers=headers, params=params) as res:
            res_data = await res.json()
            print("Status Code:", res.status)
            print("Response:", json.dumps(res_data, indent=2, ensure_ascii=False))

asyncio.run(test())
