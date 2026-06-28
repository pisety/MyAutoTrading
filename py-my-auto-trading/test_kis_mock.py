import asyncio
import aiohttp
import json
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

def decrypt(encrypted_text: str) -> str:
    key = "my_super_secret_key_123456789012"
    if not encrypted_text.startswith("ENC:"): return encrypted_text
    data = base64.b64decode(encrypted_text[4:])
    iv = data[:16]
    cipher = AES.new(key.encode('utf-8'), AES.MODE_CBC, iv)
    return unpad(cipher.decrypt(data[16:]), AES.block_size).decode('utf-8')

async def main():
    app_key = decrypt("ENC:Z2zl/gEg+UeeBjjOxflFv5hg5PvOvP5gfYiTEQQq+naegvjVB0H88Q/grUDD3Jpczse4iUkd321Ip99AP1NBeQ==")
    app_secret = decrypt("ENC:VPGUrMmjCKaj3MBPprPXAf67pDn8fgkkff8c6vBdBOCLVLaWuCpcQeo5E7iK9/AHFRB0sKC4Wy0LzLKHGQNKJWEQ1BUGjMalhWCyZa42GWx0qPWAAlYd9Fmz5KNmwIeZdc15MLhZEZAfu5LZe2LsYtQP7H6H/T8Osg+Jqg9PhrwPUD0R6zTg8C/hx3WDbDpmi3ckoT3FjqMSHwYB4p44CFVThJ6nxb4pArm6toAGfnQpd0odSysJEAOb/K068wpq2o48BOH5D7QeUkwT6Lx6Jg==")
    
    body = {"grant_type": "client_credentials", "appkey": app_key, "appsecret": app_secret}
    async with aiohttp.ClientSession() as session:
        url = "https://openapivts.koreainvestment.com:29443/oauth2/tokenP"
        async with session.post(url, json=body) as res:
            token_data = await res.json()
            token = token_data.get("access_token")
            
        params = {
            "CANO": "50191727",
            "ACNT_PRDT_CD": "01",
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
        headers = {
            "content-type": "application/json",
            "authorization": f"Bearer " + token,
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": "VTTC8434R"
        }
        
        async with session.get("https://openapivts.koreainvestment.com:29443/uapi/domestic-stock/v1/trading/inquire-balance", headers=headers, params=params) as res:
            data = await res.json()
            print(json.dumps(data, indent=2, ensure_ascii=False))

asyncio.run(main())
