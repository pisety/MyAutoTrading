import asyncio
import aiohttp
import json
import base64
import jwt
import uuid
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
    app_key = decrypt("ENC:J7IJMWWXq9Yuy80nL4jy4fLUtu5j6Qvn3y6b33MAkZ6RY0AWRTA3V66HwdbxjDOsiT1sSzj/Klbs9TBtSrqZfQ==").strip()
    app_secret = decrypt("ENC:Oss57LlnYd6SbZax65zxHha5UmQ2C+2DCrwPucLYDreyS3YGgD89KaT7nFZjk8VpftWLzMlEynY97to8i8wGYg==").strip()
    
    payload = {
        'access_key': app_key,
        'nonce': str(uuid.uuid4())
    }
    jwt_token = jwt.encode(payload, app_secret, algorithm='HS256')
    headers = {'Authorization': f'Bearer {jwt_token}'}
    
    async with aiohttp.ClientSession() as session:
        async with session.get('https://api.upbit.com/v1/accounts', headers=headers) as res:
            data = await res.json()
            print(json.dumps(data, indent=2))

asyncio.run(main())
