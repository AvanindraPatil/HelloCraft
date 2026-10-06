@echo off
rem Builds hn_gfx.dll (x64, release) with MSVC into native\hn_gfx\build\.
rem Deploy: copy build\hn_gfx.dll to <HN>\HelloNeighbor\Binaries\Win64\ue4ss\Mods\HnGfx\dlls\main.dll
rem MinHook (BSD-2, native\third_party\minhook) provides the code hooks used for scene-depth tracking.
setlocal
call "%~dp0..\..\tools\vcvars.bat" || exit /b 1
set B=%~dp0build
set MH=%~dp0..\third_party\minhook\src
if not exist "%B%" mkdir "%B%"
cl /nologo /c /O2 /W3 /MT /Fo"%B%\\" "%MH%\hook.c" "%MH%\buffer.c" "%MH%\trampoline.c" "%MH%\hde\hde64.c" || exit /b 1
cl /nologo /std:c++20 /EHsc /O2 /W4 /MT /LD /D_CRT_SECURE_NO_WARNINGS /Fo"%B%\\" /Fe"%B%\hn_gfx.dll" "%~dp0hn_gfx.cpp" "%~dp0hn_input.cpp" "%~dp0hn_world.cpp" "%B%\hook.obj" "%B%\buffer.obj" "%B%\trampoline.obj" "%B%\hde64.obj" user32.lib || exit /b 1
echo built %B%\hn_gfx.dll
