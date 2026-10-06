@echo off
rem Builds fake_host + fake_mc, runs them as two separate processes on a private mapping.
setlocal
call "%~dp0..\tools\vcvars.bat" || exit /b 1
set B=%~dp0build
if not exist "%B%" mkdir "%B%"
cl /nologo /std:c++20 /EHsc /O2 /W4 /Fo"%B%\\" /Fe"%B%\fake_mc.exe"   "%~dp0tools\fake_mc.cpp"   || exit /b 1
cl /nologo /std:c++20 /EHsc /O2 /W4 /Fo"%B%\\" /Fe"%B%\fake_host.exe" "%~dp0tools\fake_host.cpp" || exit /b 1
set MAPNAME=Local\HelloNeighborMC_twoproc_%RANDOM%
start "fake_mc" /b "%B%\fake_mc.exe" %MAPNAME%
"%B%\fake_host.exe" %MAPNAME% 6
set RC=%ERRORLEVEL%
rem fake_mc exits by itself ~2 s after the host heartbeat stops.
timeout /t 4 /nobreak >nul
exit /b %RC%
