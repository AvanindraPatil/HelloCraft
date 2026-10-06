@echo off
rem Builds hn_bridge.exe (x64, release, static runtime) with MSVC into protocol\build, or into the folder given as the
rem first argument (the running bridge locks protocol\build\hn_bridge.exe).
setlocal
call "%~dp0..\tools\vcvars.bat" || exit /b 1
set B=%~1
if "%B%"=="" set B=%~dp0build
if not exist "%B%" mkdir "%B%"
cl /nologo /std:c++20 /EHsc /O2 /W4 /MT /D_CRT_SECURE_NO_WARNINGS /Fo"%B%\\" /Fe"%B%\hn_bridge.exe" "%~dp0tools\hn_bridge.cpp" || exit /b 1
echo built %B%\hn_bridge.exe
