@echo off
rem Builds and runs protocol tests with MSVC. Output goes to protocol\build\.
call "%~dp0..\tools\vcvars.bat" || exit /b 1
if not exist "%~dp0build" mkdir "%~dp0build"
cl /nologo /std:c++20 /EHsc /O2 /W4 /Fo"%~dp0build\\" /Fe"%~dp0build\layout_test.exe" "%~dp0tests\layout_test.cpp" || exit /b 1
"%~dp0build\layout_test.exe"
cl /nologo /std:c++20 /EHsc /O2 /W4 /Fo"%~dp0build\\" /Fe"%~dp0build\overlay_test.exe" "%~dp0tests\overlay_test.cpp" || exit /b 1
"%~dp0build\overlay_test.exe"
