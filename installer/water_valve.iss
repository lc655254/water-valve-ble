#define MyAppName "蓝牙水库"
#define MyAppVersion "1.0.11"
#define MyAppPublisher "lc655254"
#define MyAppURL "https://github.com/lc655254/water-valve-ble"
#define MyAppExeName "蓝牙水库安全版.exe"

[Setup]
AppId={{A3A47703-5436-4ACA-BC5D-8AB79BF8A66D}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}/releases
DefaultDirName={localappdata}\Programs\蓝牙水库
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=Output
OutputBaseFilename=蓝牙水库_Setup_v{#MyAppVersion}
SetupIconFile=..\assets\app.ico
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible
MinVersion=10.0
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}
LicenseFile=LICENSE.txt
CloseApplications=yes
RestartApplications=no
SetupLogging=yes
VersionInfoVersion=1.0.11.0
VersionInfoProductName={#MyAppName}
VersionInfoDescription={#MyAppName} 安装程序
VersionInfoCompany={#MyAppPublisher}

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务："; Flags: checkedonce

[Files]
Source: "..\dist\蓝牙水库安全版\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "config.json,ui_prefs.json,*.log,valve_session.json,valve_heartbeat,valve_guard.pid,webview2\*"

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\set_folder_icon.bat"; Flags: runhidden waituntilterminated
Filename: "{app}\{#MyAppExeName}"; Description: "启动 {#MyAppName}"; Flags: nowait postinstall skipifsilent

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "WaterValveGuard"; Flags: dontcreatekey uninsdeletevalue

[UninstallDelete]
Type: filesandordirs; Name: "{app}\webview2"
Type: files; Name: "{app}\*.log"
Type: files; Name: "{app}\valve_session.json"
Type: files; Name: "{app}\valve_heartbeat"
Type: files; Name: "{app}\valve_guard.pid"

[Code]
function ValveBusy(): Boolean;
var
  S: AnsiString;
  J: String;
begin
  Result := False;
  J := ExpandConstant('{app}\valve_session.json');
  if FileExists(J) and LoadStringFromFile(J, S) then
  begin
    if (Pos('"state":"open"', S) > 0) or (Pos('"state": "open"', S) > 0) or
       (Pos('"state":"opening"', S) > 0) or (Pos('"state": "opening"', S) > 0) or
       (Pos('"state":"closing"', S) > 0) or (Pos('"state": "closing"', S) > 0) then
      Result := True;
  end;
  if not Result then
  begin
    J := ExpandConstant('{localappdata}\蓝牙水库\valve_session.json');
    if FileExists(J) and LoadStringFromFile(J, S) then
    begin
      if (Pos('"state":"open"', S) > 0) or (Pos('"state": "open"', S) > 0) or
         (Pos('"state":"opening"', S) > 0) or (Pos('"state": "opening"', S) > 0) or
         (Pos('"state":"closing"', S) > 0) or (Pos('"state": "closing"', S) > 0) then
        Result := True;
    end;
  end;
end;

function AppProcessExists(): Boolean;
var
  R: Integer;
begin
  R := 0;
  Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
       '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "if (Get-Process -Name ''蓝牙水库安全版'' -ErrorAction SilentlyContinue) { exit 1 } else { exit 0 }"',
       '', SW_HIDE, ewWaitUntilTerminated, R);
  Result := (R = 1);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  R: Integer;
begin
  Result := '';
  if ValveBusy() then
  begin
    Result := '水阀还在放水/结算，请先在程序里关水，再安装更新。';
    Exit;
  end;
  Exec(ExpandConstant('{sys}\taskkill.exe'),
       '/F /T /IM "蓝牙水库安全版.exe"', '', SW_HIDE,
       ewWaitUntilTerminated, R);
  if AppProcessExists() then
  begin
    ShellExec('runas', ExpandConstant('{sys}\taskkill.exe'),
              '/F /T /IM "蓝牙水库安全版.exe"', '', SW_HIDE,
              ewWaitUntilTerminated, R);
    if AppProcessExists() then
    begin
      Result := '检测到以管理员身份运行的旧进程，无法自动结束。' +
                '请以管理员身份打开任务管理器结束“蓝牙水库安全版.exe”，或重启电脑后再安装。';
      Exit;
    end;
  end;
end;

function InitializeUninstall(): Boolean;
var
  R: Integer;
begin
  Result := True;
  if ValveBusy() then
  begin
    MsgBox('水阀还在放水/结算，请先在程序里关水，再卸载。', mbError, MB_OK);
    Result := False;
    Exit;
  end;
  Exec(ExpandConstant('{sys}\taskkill.exe'),
       '/F /T /IM "蓝牙水库安全版.exe"', '', SW_HIDE,
       ewWaitUntilTerminated, R);
  if AppProcessExists() then
  begin
    ShellExec('runas', ExpandConstant('{sys}\taskkill.exe'),
              '/F /T /IM "蓝牙水库安全版.exe"', '', SW_HIDE,
              ewWaitUntilTerminated, R);
    if AppProcessExists() then
    begin
      MsgBox('检测到以管理员身份运行的旧进程。请先以管理员身份结束“蓝牙水库安全版.exe”，再卸载。',
             mbError, MB_OK);
      Result := False;
    end;
  end;
end;





