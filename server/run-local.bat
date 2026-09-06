@echo off
title Bookkeeping Sync Server - http://192.168.0.2:8080
cd /d "%~dp0"

rem ---- Guard: refuse to start if port 8080 is already listening ----
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
if %errorlevel%==0 (
    echo [ERROR] Port 8080 is already in use. Another server instance is running.
    echo         Close that window first, then run this script again.
    echo.
    pause
    exit /b 1
)

echo ============================================================
echo   Bookkeeping Sync Server
echo.
echo   URL       : http://192.168.0.2:8080
echo   Database  : H2 file  server\data\bookkeeping.mv.db
echo.
echo   Email verification links are printed in THIS window.
echo   Close this window to stop the server.
echo.
echo   Admin page: http://192.168.0.2:8080/admin/   (backups in serverackups)
echo   If the LAN IP of this PC changes, update the address in
echo   the App sync center AND the app.base-url line below.
echo ============================================================
echo.

java -jar build\libs\bookkeeping-sync-server-3.1.0.jar --spring.datasource.url="jdbc:h2:file:./data/bookkeeping;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_ON_EXIT=FALSE" --app.base-url="http://192.168.0.2:8080"

echo.
echo Server exited.
pause
