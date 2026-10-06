@echo off
rem Sets up the MSVC x64 build environment for the native builds (hn_gfx, protocol tools and tests).
rem Finds Visual Studio with vswhere (installed with every Visual Studio / Build Tools since 2017).
rem Override: set VCVARS64 to the full path of vcvars64.bat.
if defined VCVARS64 goto :run
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
    echo vcvars: vswhere.exe not found. Install Visual Studio or the Build Tools with "Desktop development with C++",
    echo        or set VCVARS64 to the full path of vcvars64.bat.
    exit /b 1
)
set "VSDIR="
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSDIR=%%i"
if not defined VSDIR (
    echo vcvars: no Visual Studio with the C++ x64 tools found. Install "Desktop development with C++",
    echo        or set VCVARS64 to the full path of vcvars64.bat.
    exit /b 1
)
set "VCVARS64=%VSDIR%\VC\Auxiliary\Build\vcvars64.bat"
:run
if not exist "%VCVARS64%" ( echo vcvars: "%VCVARS64%" does not exist & exit /b 1 )
call "%VCVARS64%" >nul 2>nul
