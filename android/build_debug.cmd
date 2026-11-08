@echo off
set JAVA_HOME=C:\AndroidDev\jdk-17.0.2
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d E:\0workspace\dshnext\Marches\GAKJ\weilie\android
call C:\AndroidDev\gradle-8.9\bin\gradle.bat :app:compileDebugKotlin --console=plain
