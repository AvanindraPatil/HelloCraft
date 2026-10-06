@echo off
rem Builds the C++ artifacts the Java tests need: build\layout.txt and build\fake_host.exe.
setlocal
call "%~dp0..\tools\vcvars.bat" || exit /b 1
set B=%~dp0build
if not exist "%B%" mkdir "%B%"
cl /nologo /std:c++20 /EHsc /O2 /W4 /D_CRT_SECURE_NO_WARNINGS /Fo"%B%\\" /Fe"%B%\layout_dump.exe" "%~dp0tools\layout_dump.cpp" || exit /b 1
"%B%\layout_dump.exe" > "%B%\layout.txt" || exit /b 1
cl /nologo /std:c++20 /EHsc /O2 /W4 /D_CRT_SECURE_NO_WARNINGS /Fo"%B%\\" /Fe"%B%\fake_host.exe" "%~dp0tools\fake_host.cpp" || exit /b 1
echo java deps ready
