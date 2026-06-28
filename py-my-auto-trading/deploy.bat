@echo off
echo ===================================================
echo   Oracle Cloud Backend Auto Deployment Script
echo ===================================================

set KEY_PATH=D:\01.SW\03.Tool\01.AndroidStudio\AndroidStudioProjects\my-auto-trading\oracle\key\ssh-key-2026-06-05.key
set SERVER=ubuntu@134.185.115.222
set BASE_DIR=D:\01.SW\03.Tool\01.AndroidStudio\AndroidStudioProjects\MyAutoTrading\py-my-auto-trading

echo.
echo [1/2] Uploading files to Oracle Cloud...
scp -i "%KEY_PATH%" "%BASE_DIR%\brokers\*" "%SERVER%:~/brokers/"
scp -i "%KEY_PATH%" "%BASE_DIR%\engine\*" "%SERVER%:~/engine/"
scp -i "%KEY_PATH%" "%BASE_DIR%\database\*" "%SERVER%:~/database/"
scp -i "%KEY_PATH%" "%BASE_DIR%\models\*" "%SERVER%:~/models/"
scp -i "%KEY_PATH%" "%BASE_DIR%\start.sh" "%SERVER%:~/"
scp -i "%KEY_PATH%" "%BASE_DIR%\my-auto-trading.py" "%SERVER%:~/"
scp -i "%KEY_PATH%" "%BASE_DIR%\crypto_utils.py" "%SERVER%:~/"
scp -i "%KEY_PATH%" "%BASE_DIR%\requirements.txt" "%SERVER%:~/"
echo.
echo [2/2] Restarting Python Engine on remote server...
ssh -i "%KEY_PATH%" "%SERVER%" "chmod +x ~/start.sh && ~/start.sh"

echo.
echo ===================================================
echo   Deployment completed successfully!
echo ===================================================


