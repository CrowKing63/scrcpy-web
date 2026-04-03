#Requires -Version 5.0
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AdbPath   = Join-Path $ScriptDir "adb\adb.exe"
$ApkPath   = Get-ChildItem -Path $ScriptDir -Filter "*.apk" | Select-Object -First 1 -ExpandProperty FullName

# ============================================================
# LANGUAGE STRINGS
# ============================================================
$Strings = @{
    en = @{
        Title        = "SCRCPY-Web Wireless Installer"
        Welcome1     = "This installer connects to your Android phone over Wi-Fi"
        Welcome2     = "and installs SCRCPY-Web. No USB cable required."
        WelcomeSteps = "Steps: Developer Options > Wireless Debugging > Pair > Connect > Install"
        Step         = "Step"; Of = "of"
        Continue     = "Press ENTER to continue..."
        S1Title      = "Enable Developer Options"
        S1_1         = "On your Android phone, open:  Settings > About phone"
        S1_2         = "Find 'Build number' and tap it 7 times rapidly."
        S1_3         = "You will see: 'You are now a developer!'"
        S1_4         = "Go to Settings > Developer options and confirm it is ON."
        S2Title      = "Enable Wireless Debugging"
        S2_1         = "In Developer options, tap 'Wireless debugging'."
        S2_2         = "Turn the 'Wireless debugging' toggle ON."
        S2_3         = "Note the IP address and port shown (needed in Step 4)."
        PairedQ      = "Is this PC already paired with your phone? (from a previous install)"
        YesNo        = "[Y] Yes   [N] No"
        S3Title      = "Pair Device"
        S3_1         = "Inside Wireless debugging, tap 'Pair device with pairing code'."
        S3_2         = "A 6-digit code and a temporary IP:port will appear."
        PairIP       = "Pairing IP:port shown on phone (e.g. 192.168.1.5:37425): "
        PairCode     = "6-digit pairing code: "
        Pairing      = "Pairing..."
        PairOK       = "Pairing successful!"
        PairFail     = "Pairing failed. Check the IP:port and code, then try again."
        S4Title      = "Connect Device"
        S4_1         = "On the MAIN Wireless debugging screen (not the pairing popup),"
        S4_2         = "note the IP address and port (e.g. 192.168.1.5:38417)."
        ConnectIP    = "Connect IP:port shown on phone (e.g. 192.168.1.5:38417): "
        Connecting   = "Connecting..."
        ConnectOK    = "Connected!"
        ConnectFail  = "Connection failed. Make sure phone and PC are on the same Wi-Fi."
        S5Title      = "Install SCRCPY-Web"
        Installing   = "Installing APK, please wait..."
        InstallOK    = "Installation complete! SCRCPY-Web is now on your phone."
        InstallSigFix = "Signature mismatch detected. Removing previous version and retrying..."
        InstallFail  = "Installation failed. See error detail above."
        DoneTitle    = "Setup Complete!"
        Done1        = "SCRCPY-Web is installed."
        Done2        = "Open the app on your phone and follow the on-screen prompts."
        Done3        = "Then open a browser and go to the IP address shown in the app."
        Done4        = "Example: http://192.168.1.5:8080"
        Retry        = "Retry? [Y/N]: "
        Quit         = "Press ENTER to exit."
        AdbMissing   = "ERROR: adb\adb.exe not found. Re-download the installer package."
        ApkMissing   = "ERROR: No APK file found. Re-download the installer package."
    }
    ko = @{
        Title        = "SCRCPY-Web 무선 설치 프로그램"
        Welcome1     = "이 프로그램은 Wi-Fi를 통해 Android 폰에"
        Welcome2     = "SCRCPY-Web을 설치합니다. USB 케이블 불필요."
        WelcomeSteps = "순서: 개발자 옵션 > 무선 디버깅 > 페어링 > 연결 > 설치"
        Step         = "단계"; Of = "/"
        Continue     = "ENTER를 눌러 계속하세요..."
        S1Title      = "개발자 옵션 활성화"
        S1_1         = "Android 폰에서:  설정 > 휴대폰 정보  로 이동하세요."
        S1_2         = "'빌드 번호'를 빠르게 7번 탭하세요."
        S1_3         = "'개발자가 되었습니다!' 메시지가 표시됩니다."
        S1_4         = "설정 > 개발자 옵션으로 돌아가 켜져 있는지 확인하세요."
        S2Title      = "무선 디버깅 활성화"
        S2_1         = "개발자 옵션에서 '무선 디버깅'을 탭하세요."
        S2_2         = "'무선 디버깅' 토글을 켜세요."
        S2_3         = "해당 화면의 IP 주소와 포트를 확인하세요 (4단계에서 필요)."
        PairedQ      = "이 PC가 이미 폰과 페어링되어 있나요? (이전 설치에서)"
        YesNo        = "[Y] 예   [N] 아니오"
        S3Title      = "기기 페어링"
        S3_1         = "무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 탭하세요."
        S3_2         = "6자리 코드와 임시 IP:포트가 표시됩니다."
        PairIP       = "폰에 표시된 페어링 IP:포트 (예: 192.168.1.5:37425): "
        PairCode     = "6자리 페어링 코드: "
        Pairing      = "페어링 중..."
        PairOK       = "페어링 성공!"
        PairFail     = "페어링 실패. IP:포트와 코드를 확인 후 다시 시도하세요."
        S4Title      = "기기 연결"
        S4_1         = "무선 디버깅 메인 화면 (페어링 팝업 아님) 에서"
        S4_2         = "IP 주소와 포트를 확인하세요 (예: 192.168.1.5:38417)."
        ConnectIP    = "폰에 표시된 연결 IP:포트 (예: 192.168.1.5:38417): "
        Connecting   = "연결 중..."
        ConnectOK    = "연결됨!"
        ConnectFail  = "연결 실패. 폰과 PC가 같은 Wi-Fi에 있는지 확인하세요."
        S5Title      = "SCRCPY-Web 설치"
        Installing   = "APK 설치 중, 잠시 기다려주세요..."
        InstallOK    = "설치 완료! SCRCPY-Web이 폰에 설치되었습니다."
        InstallSigFix = "서명 불일치 감지. 이전 버전을 제거하고 재설치합니다..."
        InstallFail  = "설치 실패. 위 오류 내용을 확인하세요."
        DoneTitle    = "설치 완료!"
        Done1        = "SCRCPY-Web이 설치되었습니다."
        Done2        = "폰에서 앱을 열고 화면의 안내를 따르세요."
        Done3        = "그런 다음 브라우저에서 앱에 표시된 IP로 접속하세요."
        Done4        = "예시: http://192.168.1.5:8080"
        Retry        = "다시 시도하시겠습니까? [Y/N]: "
        Quit         = "ENTER를 눌러 종료하세요."
        AdbMissing   = "오류: adb\adb.exe를 찾을 수 없습니다. 설치 패키지를 다시 다운로드하세요."
        ApkMissing   = "오류: APK 파일이 없습니다. 설치 패키지를 다시 다운로드하세요."
    }
    ja = @{
        Title        = "SCRCPY-Web ワイヤレスインストーラー"
        Welcome1     = "このインストーラーはWi-Fi経由でAndroidスマホに"
        Welcome2     = "SCRCPY-Webをインストールします。USBケーブル不要。"
        WelcomeSteps = "手順: 開発者オプション > ワイヤレスデバッグ > ペアリング > 接続 > インストール"
        Step         = "ステップ"; Of = "/"
        Continue     = "Enterキーを押して続行..."
        S1Title      = "開発者オプションを有効にする"
        S1_1         = "Androidスマホで:  設定 > 端末情報  を開きます。"
        S1_2         = "'ビルド番号'を7回素早くタップします。"
        S1_3         = "'開発者になりました！' と表示されます。"
        S1_4         = "設定 > 開発者オプション に戻り、オンになっていることを確認します。"
        S2Title      = "ワイヤレスデバッグを有効にする"
        S2_1         = "開発者オプションで「ワイヤレスデバッグ」をタップします。"
        S2_2         = "'ワイヤレスデバッグ' トグルをオンにします。"
        S2_3         = "その画面に表示されるIPアドレスとポートを確認します（手順4で使用）。"
        PairedQ      = "このPCはすでにデバイスとペアリングされていますか？（以前のインストールから）"
        YesNo        = "[Y] はい   [N] いいえ"
        S3Title      = "デバイスのペアリング"
        S3_1         = "ワイヤレスデバッグ画面で「ペアリングコードでデバイスをペアリング」をタップします。"
        S3_2         = "6桁のコードと一時的なIP:ポートが表示されます。"
        PairIP       = "スマホに表示されたペアリングIP:ポート (例: 192.168.1.5:37425): "
        PairCode     = "6桁のペアリングコード: "
        Pairing      = "ペアリング中..."
        PairOK       = "ペアリング成功！"
        PairFail     = "ペアリング失敗。IP:ポートとコードを確認して再試行してください。"
        S4Title      = "デバイスに接続"
        S4_1         = "ワイヤレスデバッグのメイン画面（ペアリングポップアップではなく）で"
        S4_2         = "IPアドレスとポートを確認します（例: 192.168.1.5:38417）。"
        ConnectIP    = "スマホに表示された接続IP:ポート (例: 192.168.1.5:38417): "
        Connecting   = "接続中..."
        ConnectOK    = "接続しました！"
        ConnectFail  = "接続失敗。スマホとPCが同じWi-Fiにいることを確認してください。"
        S5Title      = "SCRCPY-Webのインストール"
        Installing   = "APKをインストール中、しばらくお待ちください..."
        InstallOK    = "インストール完了！SCRCPY-WebがAndroidスマホにインストールされました。"
        InstallSigFix = "署名の不一致を検出。以前のバージョンを削除して再インストールします..."
        InstallFail  = "インストール失敗。上記のエラーを確認してください。"
        DoneTitle    = "セットアップ完了！"
        Done1        = "SCRCPY-Webがインストールされました。"
        Done2        = "スマホでアプリを開き、画面の指示に従ってください。"
        Done3        = "その後、ブラウザでアプリに表示されたIPを開いてください。"
        Done4        = "例: http://192.168.1.5:8080"
        Retry        = "再試行しますか？ [Y/N]: "
        Quit         = "Enterキーを押して終了します。"
        AdbMissing   = "エラー: adb\adb.exeが見つかりません。インストールパッケージを再ダウンロードしてください。"
        ApkMissing   = "エラー: APKファイルが見つかりません。インストールパッケージを再ダウンロードしてください。"
    }
    zh = @{
        Title        = "SCRCPY-Web 无线安装程序"
        Welcome1     = "本安装程序将通过Wi-Fi将SCRCPY-Web"
        Welcome2     = "安装到您的Android手机。无需USB数据线。"
        WelcomeSteps = "步骤: 开发者选项 > 无线调试 > 配对 > 连接 > 安装"
        Step         = "步骤"; Of = "/"
        Continue     = "按ENTER继续..."
        S1Title      = "启用开发者选项"
        S1_1         = "在Android手机上打开:  设置 > 关于手机"
        S1_2         = "找到「版本号」并快速点击7次。"
        S1_3         = "您将看到:「您已处于开发者模式！」"
        S1_4         = "返回 设置 > 开发者选项，确认已开启。"
        S2Title      = "启用无线调试"
        S2_1         = "在开发者选项中点击「无线调试」。"
        S2_2         = "开启「无线调试」开关。"
        S2_3         = "记下该界面显示的IP地址和端口（步骤4需要）。"
        PairedQ      = "此电脑是否已与您的手机配对过？（来自之前的安装）"
        YesNo        = "[Y] 是   [N] 否"
        S3Title      = "配对设备"
        S3_1         = "在无线调试界面中点击「使用配对码配对设备」。"
        S3_2         = "将显示6位配对码和临时IP:端口。"
        PairIP       = "手机显示的配对IP:端口 (例: 192.168.1.5:37425): "
        PairCode     = "6位配对码: "
        Pairing      = "配对中..."
        PairOK       = "配对成功！"
        PairFail     = "配对失败。请检查IP:端口和配对码后重试。"
        S4Title      = "连接设备"
        S4_1         = "在无线调试主界面（不是配对弹窗）中"
        S4_2         = "确认IP地址和端口（例: 192.168.1.5:38417）。"
        ConnectIP    = "手机显示的连接IP:端口 (例: 192.168.1.5:38417): "
        Connecting   = "连接中..."
        ConnectOK    = "已连接！"
        ConnectFail  = "连接失败。请确保手机和电脑在同一Wi-Fi网络。"
        S5Title      = "安装SCRCPY-Web"
        Installing   = "正在安装APK，请稍候..."
        InstallOK    = "安装完成！SCRCPY-Web已安装到您的手机。"
        InstallSigFix = "检测到签名不匹配，正在删除旧版本并重新安装..."
        InstallFail  = "安装失败。请查看上方错误信息。"
        DoneTitle    = "设置完成！"
        Done1        = "SCRCPY-Web已安装。"
        Done2        = "在手机上打开应用并按照屏幕提示操作。"
        Done3        = "然后在浏览器中访问应用显示的IP地址。"
        Done4        = "示例: http://192.168.1.5:8080"
        Retry        = "重试？[Y/N]: "
        Quit         = "按ENTER退出。"
        AdbMissing   = "错误: 找不到adb\adb.exe。请重新下载安装包。"
        ApkMissing   = "错误: 找不到APK文件。请重新下载安装包。"
    }
    es = @{
        Title        = "Instalador Inalambrico SCRCPY-Web"
        Welcome1     = "Este instalador conecta a su telefono Android via Wi-Fi"
        Welcome2     = "e instala SCRCPY-Web. Sin cable USB."
        WelcomeSteps = "Pasos: Opciones de desarrollador > Depuracion inalambrica > Emparejar > Conectar > Instalar"
        Step         = "Paso"; Of = "de"
        Continue     = "Presione ENTER para continuar..."
        S1Title      = "Activar Opciones de Desarrollador"
        S1_1         = "En su telefono Android abra:  Ajustes > Acerca del telefono"
        S1_2         = "Busque 'Numero de compilacion' y toquelo 7 veces rapidamente."
        S1_3         = "Vera: 'Ahora eres desarrollador!'"
        S1_4         = "Vuelva a Ajustes > Opciones de desarrollador y confirme que esta ACTIVADO."
        S2Title      = "Activar Depuracion Inalambrica"
        S2_1         = "En Opciones de desarrollador, toque 'Depuracion inalambrica'."
        S2_2         = "Active el interruptor 'Depuracion inalambrica'."
        S2_3         = "Anote la IP y puerto que aparece en esa pantalla (se usara en el Paso 4)."
        PairedQ      = "Este PC ya esta emparejado con su telefono? (de una instalacion anterior)"
        YesNo        = "[Y] Si   [N] No"
        S3Title      = "Emparejar Dispositivo"
        S3_1         = "Dentro de Depuracion inalambrica, toque 'Emparejar dispositivo con codigo'."
        S3_2         = "Aparecera un codigo de 6 digitos y una IP:puerto temporal."
        PairIP       = "IP:puerto de emparejamiento del telefono (ej: 192.168.1.5:37425): "
        PairCode     = "Codigo de emparejamiento de 6 digitos: "
        Pairing      = "Emparejando..."
        PairOK       = "Emparejamiento exitoso!"
        PairFail     = "Error al emparejar. Verifique la IP:puerto y el codigo."
        S4Title      = "Conectar Dispositivo"
        S4_1         = "En la pantalla PRINCIPAL de Depuracion inalambrica (no el popup),"
        S4_2         = "anote la IP y puerto (ej: 192.168.1.5:38417)."
        ConnectIP    = "IP:puerto de conexion del telefono (ej: 192.168.1.5:38417): "
        Connecting   = "Conectando..."
        ConnectOK    = "Conectado!"
        ConnectFail  = "Error de conexion. Asegurese de que el telefono y PC esten en la misma red Wi-Fi."
        S5Title      = "Instalar SCRCPY-Web"
        Installing   = "Instalando APK, espere unos segundos..."
        InstallOK    = "Instalacion completa! SCRCPY-Web ya esta en su telefono."
        InstallSigFix = "Conflicto de firma detectado. Eliminando version anterior y reintentando..."
        InstallFail  = "Error en la instalacion. Vea el detalle de error arriba."
        DoneTitle    = "Configuracion Completa!"
        Done1        = "SCRCPY-Web ha sido instalado."
        Done2        = "Abra la app en su telefono y siga las instrucciones en pantalla."
        Done3        = "Luego abra un navegador y vaya a la IP que muestra la app."
        Done4        = "Ejemplo: http://192.168.1.5:8080"
        Retry        = "Reintentar? [Y/N]: "
        Quit         = "Presione ENTER para salir."
        AdbMissing   = "ERROR: No se encontro adb\adb.exe. Descargue de nuevo el paquete instalador."
        ApkMissing   = "ERROR: No se encontro el archivo APK. Descargue de nuevo el paquete instalador."
    }
}

# ============================================================
# HELPERS
# ============================================================
function Show-Header($step, $total, $title) {
    Clear-Host
    Write-Host ("=" * 60) -ForegroundColor Cyan
    if ($step -gt 0) {
        Write-Host "  [$($s.Step) $step $($s.Of) $total]  $title" -ForegroundColor White
    } else {
        Write-Host "  $title" -ForegroundColor White
    }
    Write-Host ("=" * 60) -ForegroundColor Cyan
    Write-Host ""
}

function Pause-Continue {
    Write-Host ""
    Write-Host "  $($s.Continue)" -ForegroundColor DarkGray
    $null = Read-Host
}

function Ask-YesNo($question) {
    while ($true) {
        Write-Host "  $question"
        Write-Host "  $($s.YesNo)"
        Write-Host ""
        $ans = (Read-Host "  [Y/N]").Trim().ToUpper()
        if ($ans -eq "Y") { return $true }
        if ($ans -eq "N") { return $false }
    }
}

function Run-Adb {
    param([string[]]$Args)
    & $AdbPath @Args
    return $LASTEXITCODE
}

# ============================================================
# LANGUAGE SELECTION
# ============================================================
function Select-Language {
    Clear-Host
    Write-Host ("=" * 60) -ForegroundColor Cyan
    Write-Host "  SCRCPY-Web Installer" -ForegroundColor White
    Write-Host "  Language / 언어 / 言語 / 语言 / Idioma" -ForegroundColor Gray
    Write-Host ("=" * 60) -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  [1] English"
    Write-Host "  [2] 한국어  (Korean)"
    Write-Host "  [3] 日本語  (Japanese)"
    Write-Host "  [4] 简体中文 (Chinese Simplified)"
    Write-Host "  [5] Español  (Spanish)"
    Write-Host ""
    while ($true) {
        $choice = (Read-Host "  Select [1-5]").Trim()
        switch ($choice) {
            "1" { return "en" }
            "2" { return "ko" }
            "3" { return "ja" }
            "4" { return "zh" }
            "5" { return "es" }
        }
    }
}

# ============================================================
# MAIN
# ============================================================
$lang = Select-Language
$s    = $Strings[$lang]

# Pre-flight checks
if (-not (Test-Path $AdbPath)) {
    Clear-Host
    Write-Host ""
    Write-Host "  $($s.AdbMissing)" -ForegroundColor Red
    Write-Host ""
    Read-Host "  $($s.Quit)"
    exit 1
}
if (-not $ApkPath) {
    Clear-Host
    Write-Host ""
    Write-Host "  $($s.ApkMissing)" -ForegroundColor Red
    Write-Host ""
    Read-Host "  $($s.Quit)"
    exit 1
}

# Welcome
Show-Header 0 5 $s.Title
Write-Host "  $($s.Welcome1)"
Write-Host "  $($s.Welcome2)"
Write-Host ""
Write-Host "  $($s.WelcomeSteps)" -ForegroundColor DarkGray
Pause-Continue

# Step 1 — Developer Options
Show-Header 1 5 $s.S1Title
Write-Host "  1. $($s.S1_1)"
Write-Host "  2. $($s.S1_2)"
Write-Host "  3. $($s.S1_3)"
Write-Host "  4. $($s.S1_4)"
Pause-Continue

# Step 2 — Wireless Debugging
Show-Header 2 5 $s.S2Title
Write-Host "  1. $($s.S2_1)"
Write-Host "  2. $($s.S2_2)"
Write-Host "  3. $($s.S2_3)"
Pause-Continue

# Step 3 — Pair
Show-Header 3 5 $s.S3Title
$alreadyPaired = Ask-YesNo $s.PairedQ

if (-not $alreadyPaired) {
    while ($true) {
        Show-Header 3 5 $s.S3Title
        Write-Host "  1. $($s.S3_1)"
        Write-Host "  2. $($s.S3_2)"
        Write-Host ""
        $pairAddr = (Read-Host "  $($s.PairIP)").Trim()
        $pairCode = (Read-Host "  $($s.PairCode)").Trim()
        Write-Host ""
        Write-Host "  $($s.Pairing)" -ForegroundColor Yellow
        $rc = Run-Adb @("pair", $pairAddr, $pairCode)
        if ($rc -eq 0) {
            Write-Host "  $($s.PairOK)" -ForegroundColor Green
            Pause-Continue
            break
        } else {
            Write-Host ""
            Write-Host "  $($s.PairFail)" -ForegroundColor Red
            Pause-Continue
        }
    }
}

# Step 4 — Connect
while ($true) {
    Show-Header 4 5 $s.S4Title
    Write-Host "  $($s.S4_1)"
    Write-Host "  $($s.S4_2)"
    Write-Host ""
    $connectAddr = (Read-Host "  $($s.ConnectIP)").Trim()
    Write-Host ""
    Write-Host "  $($s.Connecting)" -ForegroundColor Yellow
    $output = & $AdbPath connect $connectAddr 2>&1
    Write-Host "  $output"
    if ($output -match "connected") {
        Write-Host "  $($s.ConnectOK)" -ForegroundColor Green
        Pause-Continue
        break
    } else {
        Write-Host ""
        Write-Host "  $($s.ConnectFail)" -ForegroundColor Red
        Pause-Continue
    }
}

# Step 5 — Install
while ($true) {
    Show-Header 5 5 $s.S5Title
    Write-Host "  $($s.Installing)" -ForegroundColor Yellow
    Write-Host ""

    $output = & $AdbPath install -r $ApkPath 2>&1
    $rc = $LASTEXITCODE
    $outputStr = $output -join "`n"
    Write-Host ($output | ForEach-Object { "  $_" })

    if ($rc -eq 0) {
        Write-Host ""
        Write-Host "  $($s.InstallOK)" -ForegroundColor Green
        break
    }

    # Signature mismatch: uninstall old version and retry once
    if ($outputStr -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        Write-Host ""
        Write-Host "  $($s.InstallSigFix)" -ForegroundColor Yellow
        & $AdbPath uninstall "com.scrcpyweb" | Out-Null
        $output2 = & $AdbPath install $ApkPath 2>&1
        $rc2 = $LASTEXITCODE
        Write-Host ($output2 | ForEach-Object { "  $_" })
        if ($rc2 -eq 0) {
            Write-Host ""
            Write-Host "  $($s.InstallOK)" -ForegroundColor Green
            break
        }
    }

    Write-Host ""
    Write-Host "  $($s.InstallFail)" -ForegroundColor Red
    Write-Host ""
    $retry = (Read-Host "  $($s.Retry)").Trim().ToUpper()
    if ($retry -ne "Y") { break }
}

# Done
Show-Header 0 5 $s.DoneTitle
Write-Host "  $($s.Done1)" -ForegroundColor Green
Write-Host "  $($s.Done2)"
Write-Host "  $($s.Done3)"
Write-Host "  $($s.Done4)" -ForegroundColor Cyan
Write-Host ""
Read-Host "  $($s.Quit)"
