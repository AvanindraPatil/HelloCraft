@echo off
rem Builds and starts the real bridge on the default mapping and pipe names. Leave this window open while playing.
call "%~dp0build_bridge.bat" || exit /b 1
"%~dp0build\hn_bridge.exe"
