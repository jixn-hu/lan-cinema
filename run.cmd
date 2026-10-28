@echo off
setlocal
cd /d "%~dp0"

set "PYTHON=.venv\Scripts\python.exe"

if not exist "%PYTHON%" (
    echo [1/3] Creating Python virtual environment...
    py -3 -m venv .venv
    if errorlevel 1 goto :error
)

echo [2/3] Installing required packages...
"%PYTHON%" -m pip install -r requirements.txt
if errorlevel 1 goto :error

echo [3/3] Starting File Browser at http://127.0.0.1:8000
start "" /b powershell -NoProfile -Command "Start-Sleep -Seconds 1; Start-Process 'http://127.0.0.1:8000'"
"%PYTHON%" serve.py 8000
goto :end

:error
echo.
echo Startup failed. Check that Python 3 is installed and available through the py command.
pause

:end
endlocal
