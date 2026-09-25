@echo off
rem
rem Runs boson-director-cli from a directory holding bin\ and lib\: a Boson distribution, or target\dist.
rem
rem Java is the bundled JRE when there is one, then %JAVA_HOME%, then java on the PATH. JAVA_OPTS is
rem passed to it. Without a bundled JRE, Java 17 or later is required and is checked for.

setlocal

rem Resolve the directory holding this script, then its parent.
pushd "%~dp0.."
set "BASEDIR=%CD%"
popd

rem Each test is a statement of its own: a nested if inside a parenthesised block would need
rem delayed expansion to read the variable it just set. For the same reason there is no goto or
rem call here - this file has Unix line endings, which cmd.exe seeks through unreliably.
set "JAVA="
set "BUNDLED="
if exist "%BASEDIR%\jre\bin\java.exe" set "JAVA=%BASEDIR%\jre\bin\java.exe"
if exist "%BASEDIR%\jre\bin\java.exe" set "BUNDLED=1"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA set "JAVA=java"

rem With no bundled runtime this is the portable package, so the Java found above is the user's.
rem Check it: a too-old JVM otherwise fails with UnsupportedClassVersionError, which names a
rem bytecode level rather than the thing to fix. Java 8 reports 1.8.0_x, whose leading 1 correctly
rem compares as older than 17.
rem
rem The version goes through a file rather than through for /f ... in ('command'): that form runs the
rem command with cmd /c, which strips the first and the last quote from a command line holding more
rem than two - and a quoted java.exe followed by a quoted search term is exactly that. The version
rem line is found by its second word rather than taken to be the first line, because with
rem JAVA_TOOL_OPTIONS or _JAVA_OPTIONS set the JVM prints "Picked up ..." ahead of it.
set "JAVA_VER="
set "JAVA_VER_FILE=%TEMP%\boson-java-version-%RANDOM%.txt"
if not defined BUNDLED "%JAVA%" -version > "%JAVA_VER_FILE%" 2>&1
if not defined BUNDLED for /f "usebackq tokens=1-3" %%a in ("%JAVA_VER_FILE%") do if not defined JAVA_VER if /i "%%b"=="version" set "JAVA_VER=%%~c"
if not defined BUNDLED del "%JAVA_VER_FILE%" > nul 2>&1
if not defined BUNDLED if not defined JAVA_VER echo Error: no Java runtime found. 1>&2
if not defined BUNDLED if not defined JAVA_VER echo Hint: install Java 17 or later, or point JAVA_HOME at one. 1>&2
if not defined BUNDLED if not defined JAVA_VER exit /b 1
set "JAVA_MAJOR="
if defined JAVA_VER for /f "delims=." %%a in ("%JAVA_VER%") do set "JAVA_MAJOR=%%a"
if defined JAVA_MAJOR if %JAVA_MAJOR% LSS 17 echo Error: Boson needs Java 17 or later, but found Java %JAVA_MAJOR%. 1>&2
if defined JAVA_MAJOR if %JAVA_MAJOR% LSS 17 echo Hint: install Java 17 or later, or point JAVA_HOME at one. 1>&2
if defined JAVA_MAJOR if %JAVA_MAJOR% LSS 17 exit /b 1

rem The serial collector: a command runs briefly and needs no parallel GC threads to start.
"%JAVA%" -XX:+UseSerialGC %JAVA_OPTS% -cp "%BASEDIR%\lib\*" io.bosonnetwork.director.cli.BosonDirectorCli %*
exit /b %ERRORLEVEL%
