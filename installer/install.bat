@echo off
setlocal EnableDelayedExpansion
chcp 65001 > nul
title SCRCPY-Web Installer

:: ============================================================
:: SCRCPY-Web Wireless Installer
:: Guides the user through enabling wireless debugging on
:: Android and installing the APK via portable ADB.
:: ============================================================

set "SCRIPT_DIR=%~dp0"
set "ADB=%SCRIPT_DIR%adb\adb.exe"
set "APK="

for %%f in ("%SCRIPT_DIR%*.apk") do (
    if not defined APK set "APK=%%f"
)

:: ============================================================
:: LANGUAGE SELECTION
:: ============================================================
:lang_select
cls
echo ============================================================
echo   SCRCPY-Web Installer
echo   Language / 언어 / 言語 / 语言 / Idioma
echo ============================================================
echo.
echo   [1] English
echo   [2] 한국어  (Korean)
echo   [3] 日本語  (Japanese)
echo   [4] 简体中文 (Chinese Simplified)
echo   [5] Español  (Spanish)
echo.
set /p "LANG_CHOICE=Select [1-5]: "

if "%LANG_CHOICE%"=="1" set "LANG=en" & goto load_strings
if "%LANG_CHOICE%"=="2" set "LANG=ko" & goto load_strings
if "%LANG_CHOICE%"=="3" set "LANG=ja" & goto load_strings
if "%LANG_CHOICE%"=="4" set "LANG=zh" & goto load_strings
if "%LANG_CHOICE%"=="5" set "LANG=es" & goto load_strings
goto lang_select

:load_strings
if "%LANG%"=="en" call :strings_en
if "%LANG%"=="ko" call :strings_ko
if "%LANG%"=="ja" call :strings_ja
if "%LANG%"=="zh" call :strings_zh
if "%LANG%"=="es" call :strings_es
goto check_adb

:: ============================================================
:: STRINGS — English
:: ============================================================
:strings_en
set "S_STEP=Step"
set "S_OF=of"
set "S_PRESS_ENTER=  Press ENTER to continue..."
set "S_STEP1_TITLE=Enable Developer Options"
set "S_STEP1_1=On your Android phone, open:  Settings > About phone"
set "S_STEP1_2=Find 'Build number' and tap it 7 times rapidly."
set "S_STEP1_3=You will see: 'You are now a developer!'"
set "S_STEP1_4=Go back to Settings > Developer options and confirm it is ON."
set "S_STEP2_TITLE=Enable Wireless Debugging"
set "S_STEP2_1=In Developer options, tap 'Wireless debugging'."
set "S_STEP2_2=Turn the 'Wireless debugging' toggle ON."
set "S_STEP2_3=Note the IP address and port shown on that screen (you'll need it in Step 4)."
set "S_ALREADY_PAIRED=Is this PC already paired with your phone? (from a previous install)"
set "S_YES_NO=[Y] Yes   [N] No"
set "S_STEP3_TITLE=Pair Device"
set "S_STEP3_1=Inside Wireless debugging, tap 'Pair device with pairing code'."
set "S_STEP3_2=A 6-digit code and a temporary IP:port will appear."
set "S_ENTER_PAIR_IP=  Pairing IP:port shown on phone (e.g. 192.168.1.5:37425): "
set "S_ENTER_PAIR_CODE=  6-digit pairing code: "
set "S_PAIRING=  Pairing..."
set "S_PAIR_OK=  Pairing successful!"
set "S_PAIR_FAIL=  Pairing failed. Check the IP:port and code, then try again."
set "S_STEP4_TITLE=Connect Device"
set "S_STEP4_1=On the MAIN Wireless debugging screen (not the pairing popup),"
set "S_STEP4_2=note the IP address and port (e.g. 192.168.1.5:38417)."
set "S_ENTER_CONNECT=  Connect IP:port shown on phone (e.g. 192.168.1.5:38417): "
set "S_CONNECTING=  Connecting..."
set "S_CONNECT_OK=  Connected!"
set "S_CONNECT_FAIL=  Connection failed. Make sure phone and PC are on the same Wi-Fi."
set "S_STEP5_TITLE=Install SCRCPY-Web"
set "S_INSTALLING=  Installing APK — this may take a few seconds..."
set "S_INSTALL_OK=  Installation complete! SCRCPY-Web is now on your phone."
set "S_INSTALL_FAIL=  Installation failed. Try running this file as Administrator."
set "S_DONE_TITLE=Setup Complete!"
set "S_DONE_1=  SCRCPY-Web is installed."
set "S_DONE_2=  Open the app on your phone and follow the on-screen prompts."
set "S_DONE_3=  Then open a browser and go to the IP address shown in the app."
set "S_DONE_4=  Example: http://192.168.1.5:8080"
set "S_RETRY=  Retry? [Y/N]: "
set "S_QUIT=  Press any key to exit."
set "S_ADB_MISSING=  ERROR: adb\adb.exe not found. Re-download the installer package."
set "S_APK_MISSING=  ERROR: No APK file found. Re-download the installer package."
set "S_WELCOME_LINE1=  This installer will connect to your Android phone over Wi-Fi"
set "S_WELCOME_LINE2=  and install SCRCPY-Web — no USB cable required."
set "S_WELCOME_STEPS=  Steps: Developer Options > Wireless Debugging > Pair > Connect > Install"
goto :eof

:: ============================================================
:: STRINGS — Korean
:: ============================================================
:strings_ko
set "S_STEP=단계"
set "S_OF=/"
set "S_PRESS_ENTER=  ENTER를 눌러 계속하세요..."
set "S_STEP1_TITLE=개발자 옵션 활성화"
set "S_STEP1_1=Android 폰에서:  설정 > 휴대폰 정보  로 이동하세요."
set "S_STEP1_2='빌드 번호'를 빠르게 7번 탭하세요."
set "S_STEP1_3='개발자가 되었습니다!' 메시지가 표시됩니다."
set "S_STEP1_4=설정 > 개발자 옵션으로 돌아가 켜져 있는지 확인하세요."
set "S_STEP2_TITLE=무선 디버깅 활성화"
set "S_STEP2_1=개발자 옵션에서 '무선 디버깅'을 탭하세요."
set "S_STEP2_2='무선 디버깅' 토글을 켜세요."
set "S_STEP2_3=해당 화면의 IP 주소와 포트를 확인하세요 (4단계에서 필요합니다)."
set "S_ALREADY_PAIRED=이 PC가 이미 폰과 페어링되어 있나요? (이전 설치에서)"
set "S_YES_NO=[Y] 예   [N] 아니오"
set "S_STEP3_TITLE=기기 페어링"
set "S_STEP3_1=무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 탭하세요."
set "S_STEP3_2=6자리 코드와 임시 IP:포트가 표시됩니다."
set "S_ENTER_PAIR_IP=  폰에 표시된 페어링 IP:포트 (예: 192.168.1.5:37425): "
set "S_ENTER_PAIR_CODE=  6자리 페어링 코드: "
set "S_PAIRING=  페어링 중..."
set "S_PAIR_OK=  페어링 성공!"
set "S_PAIR_FAIL=  페어링 실패. IP:포트와 코드를 확인 후 다시 시도하세요."
set "S_STEP4_TITLE=기기 연결"
set "S_STEP4_1=무선 디버깅 메인 화면 (페어링 팝업 아님) 에서"
set "S_STEP4_2=IP 주소와 포트를 확인하세요 (예: 192.168.1.5:38417)."
set "S_ENTER_CONNECT=  폰에 표시된 연결 IP:포트 (예: 192.168.1.5:38417): "
set "S_CONNECTING=  연결 중..."
set "S_CONNECT_OK=  연결됨!"
set "S_CONNECT_FAIL=  연결 실패. 폰과 PC가 같은 Wi-Fi에 있는지 확인하세요."
set "S_STEP5_TITLE=SCRCPY-Web 설치"
set "S_INSTALLING=  APK 설치 중 — 잠시 기다려주세요..."
set "S_INSTALL_OK=  설치 완료! SCRCPY-Web이 폰에 설치되었습니다."
set "S_INSTALL_FAIL=  설치 실패. 이 파일을 관리자 권한으로 실행해 보세요."
set "S_DONE_TITLE=설치 완료!"
set "S_DONE_1=  SCRCPY-Web이 설치되었습니다."
set "S_DONE_2=  폰에서 앱을 열고 화면의 안내를 따르세요."
set "S_DONE_3=  그런 다음 브라우저에서 앱에 표시된 IP로 접속하세요."
set "S_DONE_4=  예시: http://192.168.1.5:8080"
set "S_RETRY=  다시 시도하시겠습니까? [Y/N]: "
set "S_QUIT=  아무 키나 눌러 종료하세요."
set "S_ADB_MISSING=  오류: adb\adb.exe를 찾을 수 없습니다. 설치 패키지를 다시 다운로드하세요."
set "S_APK_MISSING=  오류: APK 파일이 없습니다. 설치 패키지를 다시 다운로드하세요."
set "S_WELCOME_LINE1=  이 프로그램은 Wi-Fi를 통해 Android 폰에"
set "S_WELCOME_LINE2=  SCRCPY-Web을 설치합니다 — USB 케이블 불필요."
set "S_WELCOME_STEPS=  순서: 개발자 옵션 > 무선 디버깅 > 페어링 > 연결 > 설치"
goto :eof

:: ============================================================
:: STRINGS — Japanese
:: ============================================================
:strings_ja
set "S_STEP=ステップ"
set "S_OF=/"
set "S_PRESS_ENTER=  Enterキーを押して続行..."
set "S_STEP1_TITLE=開発者オプションを有効にする"
set "S_STEP1_1=Androidスマホで:  設定 > 端末情報  を開きます。"
set "S_STEP1_2='ビルド番号'を7回素早くタップします。"
set "S_STEP1_3='開発者になりました！' と表示されます。"
set "S_STEP1_4=設定 > 開発者オプション に戻り、オンになっていることを確認します。"
set "S_STEP2_TITLE=ワイヤレスデバッグを有効にする"
set "S_STEP2_1=開発者オプションで「ワイヤレスデバッグ」をタップします。"
set "S_STEP2_2='ワイヤレスデバッグ' トグルをオンにします。"
set "S_STEP2_3=その画面に表示されるIPアドレスとポートを確認します (手順4で使用)。"
set "S_ALREADY_PAIRED=このPCはすでにデバイスとペアリングされていますか？(以前のインストールから)"
set "S_YES_NO=[Y] はい   [N] いいえ"
set "S_STEP3_TITLE=デバイスのペアリング"
set "S_STEP3_1=ワイヤレスデバッグ画面で「ペアリングコードでデバイスをペアリング」をタップします。"
set "S_STEP3_2=6桁のコードと一時的なIP:ポートが表示されます。"
set "S_ENTER_PAIR_IP=  スマホに表示されたペアリングIP:ポート (例: 192.168.1.5:37425): "
set "S_ENTER_PAIR_CODE=  6桁のペアリングコード: "
set "S_PAIRING=  ペアリング中..."
set "S_PAIR_OK=  ペアリング成功！"
set "S_PAIR_FAIL=  ペアリング失敗。IP:ポートとコードを確認して再試行してください。"
set "S_STEP4_TITLE=デバイスに接続"
set "S_STEP4_1=ワイヤレスデバッグのメイン画面 (ペアリングポップアップではなく) で"
set "S_STEP4_2=IPアドレスとポートを確認します (例: 192.168.1.5:38417)。"
set "S_ENTER_CONNECT=  スマホに表示された接続IP:ポート (例: 192.168.1.5:38417): "
set "S_CONNECTING=  接続中..."
set "S_CONNECT_OK=  接続しました！"
set "S_CONNECT_FAIL=  接続失敗。スマホとPCが同じWi-Fiにいることを確認してください。"
set "S_STEP5_TITLE=SCRCPY-Webのインストール"
set "S_INSTALLING=  APKをインストール中 — しばらくお待ちください..."
set "S_INSTALL_OK=  インストール完了！SCRCPY-WebがAndroidスマホにインストールされました。"
set "S_INSTALL_FAIL=  インストール失敗。管理者として実行してみてください。"
set "S_DONE_TITLE=セットアップ完了！"
set "S_DONE_1=  SCRCPY-Webがインストールされました。"
set "S_DONE_2=  スマホでアプリを開き、画面の指示に従ってください。"
set "S_DONE_3=  その後、ブラウザでアプリに表示されたIPを開いてください。"
set "S_DONE_4=  例: http://192.168.1.5:8080"
set "S_RETRY=  再試行しますか？ [Y/N]: "
set "S_QUIT=  任意のキーを押して終了します。"
set "S_ADB_MISSING=  エラー: adb\adb.exeが見つかりません。インストールパッケージを再ダウンロードしてください。"
set "S_APK_MISSING=  エラー: APKファイルが見つかりません。インストールパッケージを再ダウンロードしてください。"
set "S_WELCOME_LINE1=  このインストーラーはWi-Fi経由でAndroidスマホに"
set "S_WELCOME_LINE2=  SCRCPY-Webをインストールします — USBケーブル不要。"
set "S_WELCOME_STEPS=  手順: 開発者オプション > ワイヤレスデバッグ > ペアリング > 接続 > インストール"
goto :eof

:: ============================================================
:: STRINGS — Chinese Simplified
:: ============================================================
:strings_zh
set "S_STEP=步骤"
set "S_OF=/"
set "S_PRESS_ENTER=  按ENTER继续..."
set "S_STEP1_TITLE=启用开发者选项"
set "S_STEP1_1=在Android手机上打开:  设置 > 关于手机"
set "S_STEP1_2=找到「版本号」并快速点击7次。"
set "S_STEP1_3=您将看到:「您已处于开发者模式！」"
set "S_STEP1_4=返回 设置 > 开发者选项，确认已开启。"
set "S_STEP2_TITLE=启用无线调试"
set "S_STEP2_1=在开发者选项中点击「无线调试」。"
set "S_STEP2_2=开启「无线调试」开关。"
set "S_STEP2_3=记下该界面显示的IP地址和端口（步骤4需要）。"
set "S_ALREADY_PAIRED=此电脑是否已与您的手机配对过？（来自之前的安装）"
set "S_YES_NO=[Y] 是   [N] 否"
set "S_STEP3_TITLE=配对设备"
set "S_STEP3_1=在无线调试界面中点击「使用配对码配对设备」。"
set "S_STEP3_2=将显示6位配对码和临时IP:端口。"
set "S_ENTER_PAIR_IP=  手机显示的配对IP:端口 (例: 192.168.1.5:37425): "
set "S_ENTER_PAIR_CODE=  6位配对码: "
set "S_PAIRING=  配对中..."
set "S_PAIR_OK=  配对成功！"
set "S_PAIR_FAIL=  配对失败。请检查IP:端口和配对码后重试。"
set "S_STEP4_TITLE=连接设备"
set "S_STEP4_1=在无线调试主界面（不是配对弹窗）中"
set "S_STEP4_2=确认IP地址和端口（例: 192.168.1.5:38417）。"
set "S_ENTER_CONNECT=  手机显示的连接IP:端口 (例: 192.168.1.5:38417): "
set "S_CONNECTING=  连接中..."
set "S_CONNECT_OK=  已连接！"
set "S_CONNECT_FAIL=  连接失败。请确保手机和电脑在同一Wi-Fi网络。"
set "S_STEP5_TITLE=安装SCRCPY-Web"
set "S_INSTALLING=  正在安装APK，请稍候..."
set "S_INSTALL_OK=  安装完成！SCRCPY-Web已安装到您的手机。"
set "S_INSTALL_FAIL=  安装失败。请尝试以管理员身份运行此文件。"
set "S_DONE_TITLE=设置完成！"
set "S_DONE_1=  SCRCPY-Web已安装。"
set "S_DONE_2=  在手机上打开应用并按照屏幕提示操作。"
set "S_DONE_3=  然后在浏览器中访问应用显示的IP地址。"
set "S_DONE_4=  示例: http://192.168.1.5:8080"
set "S_RETRY=  重试？[Y/N]: "
set "S_QUIT=  按任意键退出。"
set "S_ADB_MISSING=  错误: 找不到adb\adb.exe。请重新下载安装包。"
set "S_APK_MISSING=  错误: 找不到APK文件。请重新下载安装包。"
set "S_WELCOME_LINE1=  本安装程序将通过Wi-Fi将SCRCPY-Web"
set "S_WELCOME_LINE2=  安装到您的Android手机 — 无需USB数据线。"
set "S_WELCOME_STEPS=  步骤: 开发者选项 > 无线调试 > 配对 > 连接 > 安装"
goto :eof

:: ============================================================
:: STRINGS — Spanish
:: ============================================================
:strings_es
set "S_STEP=Paso"
set "S_OF=de"
set "S_PRESS_ENTER=  Presione ENTER para continuar..."
set "S_STEP1_TITLE=Activar Opciones de Desarrollador"
set "S_STEP1_1=En su teléfono Android abra:  Ajustes > Acerca del teléfono"
set "S_STEP1_2=Busque 'Número de compilación' y tóquelo 7 veces rápidamente."
set "S_STEP1_3=Verá: '¡Ahora eres desarrollador!'"
set "S_STEP1_4=Vuelva a Ajustes > Opciones de desarrollador y confirme que está ACTIVADO."
set "S_STEP2_TITLE=Activar Depuración Inalámbrica"
set "S_STEP2_1=En Opciones de desarrollador, toque 'Depuración inalámbrica'."
set "S_STEP2_2=Active el interruptor 'Depuración inalámbrica'."
set "S_STEP2_3=Anote la IP y puerto que aparece en esa pantalla (se usará en el Paso 4)."
set "S_ALREADY_PAIRED=¿Este PC ya está emparejado con su teléfono? (de una instalación anterior)"
set "S_YES_NO=[Y] Sí   [N] No"
set "S_STEP3_TITLE=Emparejar Dispositivo"
set "S_STEP3_1=Dentro de Depuración inalámbrica, toque 'Emparejar dispositivo con código'."
set "S_STEP3_2=Aparecerá un código de 6 dígitos y una IP:puerto temporal."
set "S_ENTER_PAIR_IP=  IP:puerto de emparejamiento del teléfono (ej: 192.168.1.5:37425): "
set "S_ENTER_PAIR_CODE=  Código de emparejamiento de 6 dígitos: "
set "S_PAIRING=  Emparejando..."
set "S_PAIR_OK=  ¡Emparejamiento exitoso!"
set "S_PAIR_FAIL=  Error al emparejar. Verifique la IP:puerto y el código."
set "S_STEP4_TITLE=Conectar Dispositivo"
set "S_STEP4_1=En la pantalla PRINCIPAL de Depuración inalámbrica (no el popup de emparejamiento),"
set "S_STEP4_2=anote la IP y puerto (ej: 192.168.1.5:38417)."
set "S_ENTER_CONNECT=  IP:puerto de conexión del teléfono (ej: 192.168.1.5:38417): "
set "S_CONNECTING=  Conectando..."
set "S_CONNECT_OK=  ¡Conectado!"
set "S_CONNECT_FAIL=  Error de conexión. Asegúrese de que el teléfono y PC estén en la misma red Wi-Fi."
set "S_STEP5_TITLE=Instalar SCRCPY-Web"
set "S_INSTALLING=  Instalando APK — espere unos segundos..."
set "S_INSTALL_OK=  ¡Instalación completa! SCRCPY-Web ya está en su teléfono."
set "S_INSTALL_FAIL=  Error en la instalación. Intente ejecutar este archivo como Administrador."
set "S_DONE_TITLE=¡Configuración Completa!"
set "S_DONE_1=  SCRCPY-Web ha sido instalado."
set "S_DONE_2=  Abra la app en su teléfono y siga las instrucciones en pantalla."
set "S_DONE_3=  Luego abra un navegador y vaya a la IP que muestra la app."
set "S_DONE_4=  Ejemplo: http://192.168.1.5:8080"
set "S_RETRY=  ¿Reintentar? [Y/N]: "
set "S_QUIT=  Presione cualquier tecla para salir."
set "S_ADB_MISSING=  ERROR: No se encontró adb\adb.exe. Descargue de nuevo el paquete instalador."
set "S_APK_MISSING=  ERROR: No se encontró el archivo APK. Descargue de nuevo el paquete instalador."
set "S_WELCOME_LINE1=  Este instalador conecta a su teléfono Android vía Wi-Fi"
set "S_WELCOME_LINE2=  e instala SCRCPY-Web — sin cable USB."
set "S_WELCOME_STEPS=  Pasos: Opciones de desarrollador > Depuración inalámbrica > Emparejar > Conectar > Instalar"
goto :eof

:: ============================================================
:: PRE-FLIGHT CHECKS
:: ============================================================
:check_adb
if not exist "%ADB%" (
    cls
    echo.
    echo %S_ADB_MISSING%
    echo.
    pause
    exit /b 1
)

if not defined APK (
    cls
    echo.
    echo %S_APK_MISSING%
    echo.
    pause
    exit /b 1
)

:: ============================================================
:: WELCOME
:: ============================================================
:welcome
cls
echo ============================================================
echo   SCRCPY-Web Wireless Installer
echo ============================================================
echo.
echo %S_WELCOME_LINE1%
echo %S_WELCOME_LINE2%
echo.
echo %S_WELCOME_STEPS%
echo.
echo %S_PRESS_ENTER%
pause > nul

:: ============================================================
:: STEP 1 — Developer Options
:: ============================================================
:step1
cls
echo ============================================================
echo   [%S_STEP% 1 %S_OF% 5]  %S_STEP1_TITLE%
echo ============================================================
echo.
echo   1. %S_STEP1_1%
echo   2. %S_STEP1_2%
echo   3. %S_STEP1_3%
echo   4. %S_STEP1_4%
echo.
echo %S_PRESS_ENTER%
pause > nul

:: ============================================================
:: STEP 2 — Wireless Debugging
:: ============================================================
:step2
cls
echo ============================================================
echo   [%S_STEP% 2 %S_OF% 5]  %S_STEP2_TITLE%
echo ============================================================
echo.
echo   1. %S_STEP2_1%
echo   2. %S_STEP2_2%
echo   3. %S_STEP2_3%
echo.
echo %S_PRESS_ENTER%
pause > nul

:: ============================================================
:: STEP 3 — Pair Device (skip if already paired)
:: ============================================================
:step3_ask
cls
echo ============================================================
echo   [%S_STEP% 3 %S_OF% 5]  %S_STEP3_TITLE%
echo ============================================================
echo.
echo   %S_ALREADY_PAIRED%
echo   %S_YES_NO%
echo.
set /p "PAIRED_CHOICE=  [Y/N]: "
if /i "!PAIRED_CHOICE!"=="Y" goto step4
if /i "!PAIRED_CHOICE!"=="N" goto do_pair
goto step3_ask

:do_pair
cls
echo ============================================================
echo   [%S_STEP% 3 %S_OF% 5]  %S_STEP3_TITLE%
echo ============================================================
echo.
echo   1. %S_STEP3_1%
echo   2. %S_STEP3_2%
echo.
set /p "PAIR_ADDR=%S_ENTER_PAIR_IP%"
set /p "PAIR_CODE=%S_ENTER_PAIR_CODE%"
echo.
echo %S_PAIRING%
"%ADB%" pair %PAIR_ADDR% %PAIR_CODE%
if %errorlevel% neq 0 (
    echo.
    echo %S_PAIR_FAIL%
    echo.
    pause
    goto do_pair
)
echo.
echo %S_PAIR_OK%
echo.
echo %S_PRESS_ENTER%
pause > nul

:: ============================================================
:: STEP 4 — Connect
:: ============================================================
:step4
cls
echo ============================================================
echo   [%S_STEP% 4 %S_OF% 5]  %S_STEP4_TITLE%
echo ============================================================
echo.
echo   %S_STEP4_1%
echo   %S_STEP4_2%
echo.
set /p "CONNECT_ADDR=%S_ENTER_CONNECT%"
echo.
echo %S_CONNECTING%
for /f "tokens=*" %%o in ('"%ADB%" connect %CONNECT_ADDR% 2^>^&1') do (
    echo   %%o
    set "CONN_OUT=%%o"
)
echo !CONN_OUT! | findstr /i "connected" > nul
if errorlevel 1 (
    echo.
    echo %S_CONNECT_FAIL%
    echo.
    pause
    goto step4
)
echo.
echo %S_CONNECT_OK%
echo.
echo %S_PRESS_ENTER%
pause > nul

:: ============================================================
:: STEP 5 — Install APK
:: ============================================================
:step5
cls
echo ============================================================
echo   [%S_STEP% 5 %S_OF% 5]  %S_STEP5_TITLE%
echo ============================================================
echo.
echo %S_INSTALLING%
echo.
"%ADB%" install -r "%APK%"
if %errorlevel% neq 0 (
    echo.
    echo %S_INSTALL_FAIL%
    echo.
    set /p "RETRY_CHOICE=%S_RETRY%"
    if /i "!RETRY_CHOICE!"=="Y" goto step5
    goto end
)

:: ============================================================
:: DONE
:: ============================================================
:done
cls
echo ============================================================
echo   %S_DONE_TITLE%
echo ============================================================
echo.
echo %S_DONE_1%
echo %S_DONE_2%
echo %S_DONE_3%
echo %S_DONE_4%
echo.

:end
echo %S_QUIT%
pause > nul
exit /b 0
