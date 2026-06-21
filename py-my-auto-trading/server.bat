@echo off
setlocal enabledelayedexpansion

set "KEY_PATH=D:\01.SW\03.Tool\01.AndroidStudio\AndroidStudioProjects\my-auto-trading\oracle\key\ssh-key-2026-06-05.key"
set "SERVER=ubuntu@134.185.115.222"
set "BASE_DIR=D:\01.SW\03.Tool\01.AndroidStudio\AndroidStudioProjects\MyAutoTrading\py-my-auto-trading"

if "%~1"=="" goto :usage
if "%~1"=="deploy" goto :deploy
if "%~1"=="upload" goto :upload
if "%~1"=="restart" goto :restart
if "%~1"=="log" goto :log
if "%~1"=="ssh" goto :ssh_cmd
if "%~1"=="status" goto :status
goto :usage

:deploy
echo [Deploy] Uploading all files and restarting engine...
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%BASE_DIR%\brokers\*" "%SERVER%:~/brokers/"
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%BASE_DIR%\engine\*" "%SERVER%:~/engine/"
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%BASE_DIR%\database\*" "%SERVER%:~/database/"
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%BASE_DIR%\models\*" "%SERVER%:~/models/"
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%BASE_DIR%\start.sh" "%SERVER%:~/"
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%BASE_DIR%\my-auto-trading.py" "%SERVER%:~/"
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%BASE_DIR%\crypto_utils.py" "%SERVER%:~/"
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%BASE_DIR%\requirements.txt" "%SERVER%:~/"
ssh -o StrictHostKeyChecking=no -i "%KEY_PATH%" %SERVER% "chmod +x ~/start.sh && ~/start.sh"
echo [Deploy] Done!
goto :eof

:upload
if "%~2"=="" (
    echo [Error] Specify file path. Example: server.bat upload brokers\kiwoom_broker.py
    goto :eof
)
set "FILE_PATH=%BASE_DIR%\%~2"
for %%F in ("%FILE_PATH%") do set "FILE_NAME=%%~nxF"
echo %~2 | findstr /i /b "brokers\\" >nul
if not errorlevel 1 (
    set "REMOTE_PATH=~/brokers/!FILE_NAME!"
    goto :do_upload
)
echo %~2 | findstr /i /b "engine\\" >nul
if not errorlevel 1 (
    set "REMOTE_PATH=~/engine/!FILE_NAME!"
    goto :do_upload
)
echo %~2 | findstr /i /b "database\\" >nul
if not errorlevel 1 (
    set "REMOTE_PATH=~/database/!FILE_NAME!"
    goto :do_upload
)
echo %~2 | findstr /i /b "models\\" >nul
if not errorlevel 1 (
    set "REMOTE_PATH=~/models/!FILE_NAME!"
    goto :do_upload
)
set "REMOTE_PATH=~/!FILE_NAME!"
:do_upload
echo [Upload] %FILE_PATH% to !REMOTE_PATH!
scp -o StrictHostKeyChecking=no -i "%KEY_PATH%" "%FILE_PATH%" "%SERVER%:!REMOTE_PATH!"
echo [Upload] Done!
goto :eof

:restart
echo [Restart] Restarting engine...
ssh -o StrictHostKeyChecking=no -i "%KEY_PATH%" %SERVER% "chmod +x ~/start.sh && ~/start.sh"
echo [Restart] Done!
goto :eof

:log
set "LINES=30"
if not "%~2"=="" set "LINES=%~2"
echo [Log] Last %LINES% lines...
ssh -o StrictHostKeyChecking=no -i "%KEY_PATH%" %SERVER% "tail -n %LINES% ~/engine.log"
goto :eof

:ssh_cmd
set "CMD="
shift
:build_cmd
if "%~1"=="" goto :run_ssh
if defined CMD (set "CMD=!CMD! %~1") else (set "CMD=%~1")
shift
goto :build_cmd
:run_ssh
echo [SSH] Running: !CMD!
ssh -o StrictHostKeyChecking=no -i "%KEY_PATH%" %SERVER% "!CMD!"
goto :eof

:status
echo [Status] Checking engine process...
ssh -o StrictHostKeyChecking=no -i "%KEY_PATH%" %SERVER% "ps aux | grep my-auto-trading | grep -v grep; echo ---; uptime"
goto :eof

:usage
echo.
echo   Usage: server.bat [command] [args...]
echo.
echo   Commands:
echo     deploy              Upload all files + restart engine
echo     upload [filepath]   Upload single file (e.g. brokers\kiwoom_broker.py)
echo     restart             Restart engine only
echo     log [lines]         Show engine log (default 30 lines)
echo     ssh [command]       Run command on server
echo     status              Check engine process status
echo.
goto :eof
