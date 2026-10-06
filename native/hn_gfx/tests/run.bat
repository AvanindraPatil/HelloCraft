@echo off
rem Builds the harness and runs it against build\hn_gfx.dll (build that first with ..\build.bat).
setlocal
call "%~dp0..\..\..\tools\vcvars.bat" || exit /b 1
set B=%~dp0..\build
cl /nologo /std:c++20 /EHsc /O2 /W3 /Fo"%B%\\" /Fe"%B%\harness.exe" "%~dp0harness.cpp" user32.lib || exit /b 1
pushd "%B%"
"%B%\harness.exe" "%B%\hn_gfx.dll"
set RC=%ERRORLEVEL%
echo ---- hn_gfx.log ----
type "%B%\hn_gfx.log"
popd
exit /b %RC%
