@echo off
rem Chain test: pipe_sim (stands in for Lua) -> hn_bridge -> shared memory -> fake_mc. Private names throughout.
rem Runs TWO Lua sessions back to back on one bridge: reusing disconnected pipe instances used to deadlock.
setlocal
call "%~dp0..\tools\vcvars.bat" || exit /b 1
set B=%~dp0build
if not exist "%B%" mkdir "%B%"
cl /nologo /std:c++20 /EHsc /O2 /W4 /D_CRT_SECURE_NO_WARNINGS /Fo"%B%\\" /Fe"%B%\hn_bridge.exe" "%~dp0tools\hn_bridge.cpp" || exit /b 1
cl /nologo /std:c++20 /EHsc /O2 /W4 /D_CRT_SECURE_NO_WARNINGS /Fo"%B%\\" /Fe"%B%\pipe_sim.exe"  "%~dp0tools\pipe_sim.cpp"  || exit /b 1
cl /nologo /std:c++20 /EHsc /O2 /W4 /D_CRT_SECURE_NO_WARNINGS /Fo"%B%\\" /Fe"%B%\fake_mc.exe"   "%~dp0tools\fake_mc.cpp"   || exit /b 1
set MAPNAME=Local\HelloNeighborMC_bridge_%RANDOM%
set PIPE=\\.\pipe\hnmc_test_%RANDOM%
start "bridge" /b "%B%\hn_bridge.exe" %MAPNAME% %PIPE% --sessions 2
start "fake_mc" /b "%B%\fake_mc.exe" %MAPNAME%
"%B%\pipe_sim.exe" %PIPE% 4
if errorlevel 1 exit /b 1
"%B%\pipe_sim.exe" %PIPE% 4
set RC=%ERRORLEVEL%
ping -n 5 127.0.0.1 >nul
exit /b %RC%
