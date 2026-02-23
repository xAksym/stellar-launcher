@echo off
echo ======================================
echo   StellarLauncher - Budowanie
echo ======================================
echo.

REM Sprawdz czy Node.js jest zainstalowany
where node >nul 2>&1
if errorlevel 1 (
    echo [BLAD] Node.js nie jest zainstalowany!
    echo Pobierz z: https://nodejs.org
    pause
    exit /b 1
)

REM Sprawdz czy Java JDK jest zainstalowana
where javac >nul 2>&1
if errorlevel 1 (
    echo [BLAD] Java JDK nie jest zainstalowana lub nie ma jej w PATH!
    echo Pobierz JDK 21 z: https://adoptium.net
    pause
    exit /b 1
)

REM Usun stary folder dist
if exist "dist\" (
    echo Usuwam stary folder dist...
    rmdir /s /q "dist"
    echo Folder dist usuniety.
    echo.
)

echo Kompilowanie i budowanie .exe...
echo.
call node build.js exe
if errorlevel 1 (
    echo.
    echo [BLAD] Budowanie nie powiodlo sie!
    pause
    exit /b 1
)

echo.
echo ======================================
echo   Budowanie zakonczone!
echo ======================================
echo.
echo Aplikacja znajduje sie w folderze: dist\StellarLauncher\
echo.
echo Pliki:
dir /b "dist\StellarLauncher\" 2>nul
echo.
pause