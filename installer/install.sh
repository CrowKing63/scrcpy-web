#!/bin/bash

# ============================================================
# SCRCPY-Web Wireless Installer for Mac/Linux
# ============================================================

# Set script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ADB_MAC="$SCRIPT_DIR/adb/adb_mac"
ADB_LINUX="$SCRIPT_DIR/adb/adb_linux"
APK_PATH=$(ls "$SCRIPT_DIR"/*.apk 2>/dev/null | head -n 1)

# Detect OS and set ADB path
if [[ "$OSTYPE" == "darwin"* ]]; then
    # MacOS
    if [ -f "$ADB_MAC" ]; then
        ADB="$ADB_MAC"
        chmod +x "$ADB"
    else
        ADB="adb"
    fi
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    # Linux
    if [ -f "$ADB_LINUX" ]; then
        ADB="$ADB_LINUX"
        chmod +x "$ADB"
    else
        ADB="adb"
    fi
else
    ADB="adb"
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
GRAY='\033[0;90m'
NC='\033[0m' # No Color

# ============================================================
# HELPERS
# ============================================================

show_header() {
    local step=$1
    local total=$2
    local title=$3
    clear
    echo -e "${CYAN}============================================================${NC}"
    if [ "$step" -gt 0 ]; then
        echo -e "  ${WHITE}[$L_STEP $step $L_OF $total]  $title${NC}"
    else
        echo -e "  ${WHITE}$title${NC}"
    fi
    echo -e "${CYAN}============================================================${NC}"
    echo ""
}

pause_continue() {
    echo ""
    echo -e "  ${GRAY}$L_CONTINUE${NC}"
    read -r
}

ask_yes_no() {
    local question=$1
    while true; do
        echo -e "  $question"
        echo -e "  $L_YESNO"
        echo ""
        read -p "  [Y/N]: " ans
        case $ans in
            [Yy]* ) return 0;;
            [Nn]* ) return 1;;
        esac
    done
}

# ============================================================
# LANGUAGE STRINGS
# ============================================================

select_language() {
    clear
    echo -e "${CYAN}============================================================${NC}"
    echo -e "  ${WHITE}SCRCPY-Web Installer${NC}"
    echo -e "  ${GRAY}Language / 언어 / 言語 / 语言 / Idioma${NC}"
    echo -e "${CYAN}============================================================${NC}"
    echo ""
    echo "  [1] English"
    echo "  [2] 한국어  (Korean)"
    echo "  [3] 日本語  (Japanese)"
    echo "  [4] 简体中文 (Chinese Simplified)"
    echo "  [5] Español  (Spanish)"
    echo ""
    
    while true; do
        read -p "  Select [1-5]: " choice
        case $choice in
            1) LANG="en"; break;;
            2) LANG="ko"; break;;
            3) LANG="ja"; break;;
            4) LANG="zh"; break;;
            5) LANG="es"; break;;
        esac
    done
}

# Load strings based on language
load_strings() {
    case $LANG in
        ko)
            L_TITLE="SCRCPY-Web 무선 설치 프로그램"
            L_WELCOME1="이 프로그램은 Wi-Fi를 통해 Android 폰에"
            L_WELCOME2="SCRCPY-Web을 설치합니다. USB 케이블 불필요."
            L_STEPS="순서: 개발자 옵션 > 무선 디버깅 > 페어링 > 연결 > 설치"
            L_STEP="단계"; L_OF="/"
            L_CONTINUE="ENTER를 눌러 계속하세요..."
            L_S1_TITLE="개발자 옵션 활성화"
            L_S1_1="Android 폰에서:  설정 > 휴대폰 정보  로 이동하세요."
            L_S1_2="'빌드 번호'를 빠르게 7번 탭하세요."
            L_S1_3="'개발자가 되었습니다!' 메시지가 표시됩니다."
            L_S1_4="설정 > 개발자 옵션으로 돌아가 켜져 있는지 확인하세요."
            L_S2_TITLE="무선 디버깅 활성화"
            L_S2_1="개발자 옵션에서 '무선 디버깅'을 탭하세요."
            L_S2_2="'무선 디버깅' 토글을 켜세요."
            L_S2_3="해당 화면의 IP 주소와 포트를 확인하세요 (4단계에서 필요)."
            L_PAIRED_Q="이 PC가 이미 폰과 페어링되어 있나요?"
            L_YESNO="[Y] 예   [N] 아니오"
            L_S3_TITLE="기기 페어링"
            L_S3_1="무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 탭하세요."
            L_S3_2="6자리 코드와 임시 IP:포트가 표시됩니다."
            L_PAIR_IP="페어링 IP:포트 (예: 192.168.1.5:37425): "
            L_PAIR_CODE="6자리 페어링 코드: "
            L_PAIRING="페어링 중..."
            L_PAIR_OK="페어링 성공!"
            L_PAIR_FAIL="페어링 실패. IP:포트와 코드를 확인 후 다시 시도하세요."
            L_S4_TITLE="기기 연결"
            L_S4_1="무선 디버깅 메인 화면에서 IP 주소와 포트를 확인하세요."
            L_S4_2="(예: 192.168.1.5:38417)"
            L_CONNECT_IP="연결 IP:포트 (예: 192.168.1.5:38417): "
            L_CONNECTING="연결 중..."
            L_CONNECT_OK="연결됨!"
            L_CONNECT_FAIL="연결 실패. 폰과 PC가 같은 Wi-Fi에 있는지 확인하세요."
            L_S5_TITLE="SCRCPY-Web 설치"
            L_INSTALLING="APK 설치 중, 잠시 기다려주세요..."
            L_INSTALL_OK="설치 완료! SCRCPY-Web이 폰에 설치되었습니다."
            L_INSTALL_SIGFIX="서명 불일치 감지. 이전 버전을 제거하고 재설치합니다..."
            L_INSTALL_FAIL="설치 실패. 위 오류 내용을 확인하세요."
            L_DONE_TITLE="설치 완료!"
            L_DONE1="SCRCPY-Web이 설치되었습니다."
            L_DONE2="폰에서 앱을 열고 화면의 안내를 따르세요."
            L_DONE3="그런 다음 브라우저에서 앱에 표시된 IP로 접속하세요."
            L_DONE4="예시: http://192.168.1.5:8080"
            L_RETRY="다시 시도하시겠습니까? [Y/N]: "
            L_QUIT="ENTER를 눌러 종료하세요."
            L_ADB_MISSING="오류: adb를 찾을 수 없습니다. 'brew install android-platform-tools'로 설치하거나 바이너리를 확인하세요."
            L_APK_MISSING="오류: APK 파일이 없습니다. 설치 패키지를 다시 다운로드하세요."
            ;;
        ja)
            L_TITLE="SCRCPY-Web ワ이어레스인스톨러"
            L_WELCOME1="이 프로그램은 Wi-Fi를 통해 Android 폰에"
            L_WELCOME2="SCRCPY-Web을 설치합니다. USB 케이블 불필요."
            L_STEPS="手順: 開発者オプション > ワイヤレスデバッグ > ペアリング > 接続 > インストール"
            L_STEP="ステップ"; L_OF="/"
            L_CONTINUE="Enterキーを押して続行..."
            L_S1_TITLE="開発者オプションを有効にする"
            L_S1_1="Androidスマホで:  設定 > 端末情報  を開きます."
            L_S1_2="'ビル드番号'를 7回素早くタップします."
            L_S1_3="'開発者になりました！' と表示されます."
            L_S1_4="設定 > 開発者オプション に戻り、オンになっていることを確認します."
            L_S2_TITLE="ワイヤレスデバッグを有効にする"
            L_S2_1="開発者オプションで「ワイヤレスデバッグ」をタップします."
            L_S2_2="'ワイヤレスデバッグ' トグルをオンにします."
            L_S2_3="その画面に表示されるIPアドレスとポートを確認します."
            L_PAIRED_Q="このPCはすでにデバイスとペアリングされていますか？"
            L_YESNO="[Y] はい   [N] いいえ"
            L_S3_TITLE="デバイスのペアリング"
            L_S3_1="ワイヤレスデバッグ画面で「ペアリングコードでデバイスをペアリング」をタップします."
            L_S3_2="6桁のコードと一時的なIP:ポートが表示されます."
            L_PAIR_IP="ペアリングIP:ポート (例: 192.168.1.5:37425): "
            L_PAIR_CODE="6桁のペアリングコード: "
            L_PAIRING="ペアリング中..."
            L_PAIR_OK="ペアリング成功！"
            L_PAIR_FAIL="ペアリング失敗。IP:ポートとコードを確認して再試行してください。"
            L_S4_TITLE="デバイスに接続"
            L_S4_1="ワイヤレスデバッグのメイン画面でIPアドレスとポートを確認します."
            L_S4_2="(例: 192.168.1.5:38417)"
            L_CONNECT_IP="接続IP:ポート (例: 192.168.1.5:38417): "
            L_CONNECTING="接続中..."
            L_CONNECT_OK="接続しました！"
            L_CONNECT_FAIL="接続失敗。スマホとPCが同じWi-Fiにいることを確認してください。"
            L_S5_TITLE="SCRCPY-Webのインストール"
            L_INSTALLING="APKをインストール中, しばらくお待ちください..."
            L_INSTALL_OK="インストール完了！SCRCPY-WebがAndroidスマホにインストールされました。"
            L_INSTALL_SIGFIX="署名の不一致を検出. 以前のバージョンを削除して再インストールします..."
            L_INSTALL_FAIL="インストール失敗. 上記のエラーを確認してください。"
            L_DONE_TITLE="セットアップ完了！"
            L_DONE1="SCRCPY-Webがインストールされました。"
            L_DONE2="スマホでアプリを開き、画面の指示に従ってください。"
            L_DONE3="その後、ブラウザでアプリに表示されたIPを開いてください。"
            L_DONE4="例: http://192.168.1.5:8080"
            L_RETRY="再試行しますか？ [Y/N]: "
            L_QUIT="Enterキーを押して終了します。"
            L_ADB_MISSING="エラー: adbが見つかりません。"
            L_APK_MISSING="エラー: APKファイルが見つかりません。"
            ;;
        zh)
            L_TITLE="SCRCPY-Web 无线安装程序"
            L_WELCOME1="本安装程序将通过Wi-Fi将SCRCPY-Web"
            L_WELCOME2="安装到您的Android手机. 无需USB数据线."
            L_STEPS="步骤: 开发者选项 > 无线调试 > 配对 > 连接 > 安装"
            L_STEP="步骤"; L_OF="/"
            L_CONTINUE="按ENTER继续..."
            L_S1_TITLE="启用开发者选项"
            L_S1_1="在Android手机上打开:  设置 > 关于手机"
            L_S1_2="找到「版本号」并快速点击7次."
            L_S1_3="您将看到:「您已处于开发者模式！」"
            L_S1_4="返回 设置 > 开发者选项，确认已开启."
            L_S2_TITLE="启用无线调试"
            L_S2_1="在开发者选项中点击「无线调试」。"
            L_S2_2="开启「无线调试」开关."
            L_S2_3="记下该界面显示的IP地址和端口."
            L_PAIRED_Q="此电脑是否已与您的手机配对过？"
            L_YESNO="[Y] 是   [N] 否"
            L_S3_TITLE="配对设备"
            L_S3_1="在无线调试界面中点击「使用配对码配对设备」。"
            L_S3_2="将显示6位配对码和临时IP:端口."
            L_PAIR_IP="手机显示的配对IP:端口 (例: 192.168.1.5:37425): "
            L_PAIR_CODE="6位配对码: "
            L_PAIRING="配对中..."
            L_PAIR_OK="配对成功！"
            L_PAIR_FAIL="配对失败. 请检查IP:端口和配对码后重试."
            L_S4_TITLE="连接设备"
            L_S4_1="在无线调试主界面确认IP地址和端口."
            L_S4_2="(例: 192.168.1.5:38417)"
            L_CONNECT_IP="手机显示的连接IP:端口 (例: 192.168.1.5:38417): "
            L_CONNECTING="连接中..."
            L_CONNECT_OK="已连接！"
            L_CONNECT_FAIL="连接失败. 请确保手机和电脑在同一Wi-Fi网络."
            L_S5_TITLE="安装SCRCPY-Web"
            L_INSTALLING="正在安装APK，请稍候..."
            L_INSTALL_OK="安装完成！SCRCPY-Web已安装到您的手机."
            L_INSTALL_SIGFIX="检测到签名不匹配，正在删除旧版本并重新安装..."
            L_INSTALL_FAIL="安装失败. 请查看上方错误信息."
            L_DONE_TITLE="设置完成！"
            L_DONE1="SCRCPY-Web已安装。"
            L_DONE2="在手机上打开应用并按照屏幕提示操作。"
            L_DONE3="然后在浏览器中访问应用显示的IP地址。"
            L_DONE4="示例: http://192.168.1.5:8080"
            L_RETRY="重试？[Y/N]: "
            L_QUIT="按ENTER退出。"
            L_ADB_MISSING="错误: 找不到adb."
            L_APK_MISSING="错误: 找不到APK文件。"
            ;;
        es)
            L_TITLE="Instalador Inalámbrico SCRCPY-Web"
            L_WELCOME1="Este instalador conecta a su teléfono Android vía Wi-Fi"
            L_WELCOME2="e instala SCRCPY-Web. Sin cable USB."
            L_STEPS="Pasos: Opciones de desarrollador > Depuración inalambrica > Emparejar > Conectar > Instalar"
            L_STEP="Paso"; L_OF="de"
            L_CONTINUE="Presione ENTER para continuar..."
            L_S1_TITLE="Activar Opciones de Desarrollador"
            L_S1_1="En su teléfono Android abra:  Ajustes > Acerca del teléfono"
            L_S1_2="Busque 'Número de compilación' y tóquelo 7 veces rápidamente."
            L_S1_3="Verá: '¡Ahora eres desarrollador!'"
            L_S1_4="Vuelva a Ajustes > Opciones de desarrollador y confirme que está ACTIVADO."
            L_S2_TITLE="Activar Depuración Inalámbrica"
            L_S2_1="En Opciones de desarrollador, toque 'Depuración inalámbrica'."
            L_S2_2="Active el interruptor 'Depuración inalámbrica'."
            L_S2_3="Anote la IP y puerto que aparece en esa pantalla."
            L_PAIRED_Q="¿Este PC ya está emparejado con su teléfono?"
            L_YESNO="[Y] Sí   [N] No"
            L_S3_TITLE="Emparejar Dispositivo"
            L_S3_1="Dentro de Depuración inalámbrica, toque 'Emparejar dispositivo con código'."
            L_S3_2="Aparecerá un código de 6 dígitos y una IP:puerto temporal."
            L_PAIR_IP="IP:puerto de emparejamiento (ej: 192.168.1.5:37425): "
            L_PAIR_CODE="Código de emparejamiento de 6 dígitos: "
            L_PAIRING="Emparejando..."
            L_PAIR_OK="¡Emparejamiento exitoso!"
            L_PAIR_FAIL="Error al emparejar. Verifique la IP:puerto y el código."
            L_S4_TITLE="Conectar Dispositivo"
            L_S4_1="En la pantalla PRINCIPAL de Depuración inalámbrica, note la IP y puerto."
            L_S4_2="(ej: 192.168.1.5:38417)"
            L_CONNECT_IP="IP:puerto de conexión (ej: 192.168.1.5:38417): "
            L_CONNECTING="Conectando..."
            L_CONNECT_OK="¡Conectado!"
            L_CONNECT_FAIL="Error de conexión. Asegúrese de estar en la misma red Wi-Fi."
            L_S5_TITLE="Instalar SCRCPY-Web"
            L_INSTALLING="Instalando APK, espere unos segundos..."
            L_INSTALL_OK="¡Instalación completa! SCRCPY-Web ya está en su teléfono."
            L_INSTALL_SIGFIX="Conflicto de firma detectado. Eliminando versión anterior..."
            L_INSTALL_FAIL="Error en la instalación. Vea el detalle de error arriba."
            L_DONE_TITLE="¡Configuración Completa!"
            L_DONE1="SCRCPY-Web ha sido instalado."
            L_DONE2="Abra la app en su teléfono y siga las instrucciones."
            L_DONE3="Luego abra un navegador y vaya a la IP que muestra la app."
            L_DONE4="Ejemplo: http://192.168.1.5:8080"
            L_RETRY="¿Reintentar? [Y/N]: "
            L_QUIT="Presione ENTER para salir."
            L_ADB_MISSING="ERROR: No se encontró adb."
            L_APK_MISSING="ERROR: No se encontró el archivo APK."
            ;;
        *)
            L_TITLE="SCRCPY-Web Wireless Installer"
            L_WELCOME1="This installer connects to your Android phone over Wi-Fi"
            L_WELCOME2="and installs SCRCPY-Web. No USB cable required."
            L_STEPS="Steps: Developer Options > Wireless Debugging > Pair > Connect > Install"
            L_STEP="Step"; L_OF="of"
            L_CONTINUE="Press ENTER to continue..."
            L_S1_TITLE="Enable Developer Options"
            L_S1_1="On your Android phone, open:  Settings > About phone"
            L_S1_2="Find 'Build number' and tap it 7 times rapidly."
            L_S1_3="You will see: 'You are now a developer!'"
            L_S1_4="Go to Settings > Developer options and confirm it is ON."
            L_S2_TITLE="Enable Wireless Debugging"
            L_S2_1="In Developer options, tap 'Wireless debugging'."
            L_S2_2="Turn the 'Wireless debugging' toggle ON."
            L_S2_3="Note the IP address and port shown (needed in Step 4)."
            L_PAIRED_Q="Is this PC already paired with your phone?"
            L_YESNO="[Y] Yes   [N] No"
            L_S3_TITLE="Pair Device"
            L_S3_1="Inside Wireless debugging, tap 'Pair device with pairing code'."
            L_S3_2="A 6-digit code and a temporary IP:port will appear."
            L_PAIR_IP="Pairing IP:port (e.g. 192.168.1.5:37425): "
            L_PAIR_CODE="6-digit pairing code: "
            L_PAIRING="Pairing..."
            L_PAIR_OK="Pairing successful!"
            L_PAIR_FAIL="Pairing failed. Check the IP:port and code, then try again."
            L_S4_TITLE="Connect Device"
            L_S4_1="On the MAIN Wireless debugging screen, note the IP and port."
            L_S4_2="(e.g. 192.168.1.5:38417)"
            L_CONNECT_IP="Connect IP:port (e.g. 192.168.1.5:38417): "
            L_CONNECTING="Connecting..."
            L_CONNECT_OK="Connected!"
            L_CONNECT_FAIL="Connection failed. Make sure phone and PC are on the same Wi-Fi."
            L_S5_TITLE="Install SCRCPY-Web"
            L_INSTALLING="Installing APK, please wait..."
            L_INSTALL_OK="Installation complete! SCRCPY-Web is now on your phone."
            L_INSTALL_SIGFIX="Signature mismatch detected. Removing previous version and retrying..."
            L_INSTALL_FAIL="Installation failed. See error detail above."
            L_DONE_TITLE="Setup Complete!"
            L_DONE1="SCRCPY-Web is installed."
            L_DONE2="Open the app on your phone and follow the on-screen prompts."
            L_DONE3="Then open a browser and go to the IP address shown in the app."
            L_DONE4="Example: http://192.168.1.5:8080"
            L_RETRY="Retry? [Y/N]: "
            L_QUIT="Press ENTER to exit."
            L_ADB_MISSING="ERROR: adb not found. Please install adb or provide a binary."
            L_APK_MISSING="ERROR: No APK file found."
            ;;
    esac
}

# ============================================================
# MAIN LOOP
# ============================================================

select_language
load_strings

# Pre-flight checks
if ! command -v "$ADB" &> /dev/null; then
    show_header 0 0 "ERROR"
    echo -e "  ${RED}$L_ADB_MISSING${NC}"
    echo ""
    read -p "  $L_QUIT"
    exit 1
fi

if [ -z "$APK_PATH" ]; then
    show_header 0 0 "ERROR"
    echo -e "  ${RED}$L_APK_MISSING${NC}"
    echo ""
    read -p "  $L_QUIT"
    exit 1
fi

# Welcome
show_header 0 5 "$L_TITLE"
echo -e "  $L_WELCOME1"
echo -e "  $L_WELCOME2"
echo ""
echo -e "  ${GRAY}$L_STEPS${NC}"
pause_continue

# Step 1
show_header 1 5 "$L_S1_TITLE"
echo -e "  1. $L_S1_1"
echo -e "  2. $L_S1_2"
echo -e "  3. $L_S1_3"
echo -e "  4. $L_S1_4"
pause_continue

# Step 2
show_header 2 5 "$L_S2_TITLE"
echo -e "  1. $L_S2_1"
echo -e "  2. $L_S2_2"
echo -e "  3. $L_S2_3"
pause_continue

# Step 3
show_header 3 5 "$L_S3_TITLE"
if ! ask_yes_no "$L_PAIRED_Q"; then
    while true; do
        show_header 3 5 "$L_S3_TITLE"
        echo -e "  1. $L_S3_1"
        echo -e "  2. $L_S3_2"
        echo ""
        read -p "  $L_PAIR_IP" pairAddr
        read -p "  $L_PAIR_CODE" pairCode
        echo ""
        echo -e "  ${YELLOW}$L_PAIRING${NC}"
        "$ADB" pair "$pairAddr" "$pairCode"
        if [ $? -eq 0 ]; then
            echo -e "  ${GREEN}$L_PAIR_OK${NC}"
            pause_continue
            break
        else
            echo ""
            echo -e "  ${RED}$L_PAIR_FAIL${NC}"
            pause_continue
        fi
    done
fi

# Step 4
while true; do
    show_header 4 5 "$L_S4_TITLE"
    echo -e "  $L_S4_1"
    echo -e "  $L_S4_2"
    echo ""
    read -p "  $L_CONNECT_IP" connectAddr
    echo ""
    echo -e "  ${YELLOW}$L_CONNECTING${NC}"
    output=$("$ADB" connect "$connectAddr" 2>&1)
    echo "  $output"
    if [[ $output == *"connected"* ]]; then
        echo -e "  ${GREEN}$L_CONNECT_OK${NC}"
        pause_continue
        break
    else
        echo ""
        echo -e "  ${RED}$L_CONNECT_FAIL${NC}"
        pause_continue
    fi
done

# Step 5
while true; do
    show_header 5 5 "$L_S5_TITLE"
    echo -e "  ${YELLOW}$L_INSTALLING${NC}"
    echo ""
    
    output=$("$ADB" install -r "$APK_PATH" 2>&1)
    rc=$?
    echo "  $output"
    
    if [ $rc -eq 0 ]; then
        echo ""
        echo -e "  ${GREEN}$L_INSTALL_OK${NC}"
        break
    fi
    
    if [[ $output == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
        echo ""
        echo -e "  ${YELLOW}$L_INSTALL_SIGFIX${NC}"
        "$ADB" uninstall "com.scrcpyweb" &> /dev/null
        output2=$("$ADB" install "$APK_PATH" 2>&1)
        rc2=$?
        echo "  $output2"
        if [ $rc2 -eq 0 ]; then
            echo ""
            echo -e "  ${GREEN}$L_INSTALL_OK${NC}"
            break
        fi
    fi
    
    echo ""
    echo -e "  ${RED}$L_INSTALL_FAIL${NC}"
    echo ""
    read -p "  $L_RETRY" retry
    if [[ $retry != [Yy]* ]]; then break; fi
done

# Done
show_header 0 5 "$L_DONE_TITLE"
echo -e "  ${GREEN}$L_DONE1${NC}"
echo -e "  $L_DONE2"
echo -e "  $L_DONE3"
echo -e "  ${CYAN}$L_DONE4${NC}"
echo ""
read -p "  $L_QUIT"
