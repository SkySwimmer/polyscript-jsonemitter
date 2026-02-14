@echo off

setlocal EnableDelayedExpansion
set "dirpath=%~dp0"

if NOT EXIST "%dirpath%\plugins" mkdir "%dirpath%\plugins"

SET libs=
for %%i in (*.jar) do SET libs=!libs!;%%i
for /r "%dirpath%\libs" %%i in (*.jar) do SET libs=!libs!;%%i
for /r "%dirpath%\plugins" %%i in (*.jar) do SET libs=!libs!;%%i
SET libs=%libs:~1%

java -cp "%libs%" %*
exit %ERRORLEVEL%
