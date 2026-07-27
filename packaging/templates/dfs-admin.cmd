@echo off
setlocal
chcp 65001 >nul
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 -Dstdin.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Ddfs.home="%~dp0." -jar "%~dp0app\dfs-admin.jar" %*
exit /b %ERRORLEVEL%
