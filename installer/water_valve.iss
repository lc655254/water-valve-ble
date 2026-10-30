#define MyAppName "蓝牙水库"
#define MyAppVersion "1.0.1"
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
VersionInfoVersion=1.0.1.0
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
Filename: "{app}\{#MyAppExeName}"; Description: "启动 {#MyAppName}"; Flags: nowait postinstall skipifsilent

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "WaterValveGuard"; Flags: dontcreatekey uninsdeletevalue

[UninstallDelete]
Type: filesandordirs; Name: "{app}\webview2"
Type: files; Name: "{app}\*.log"
Type: files; Name: "{app}\valve_session.json"
Type: files; Name: "{app}\valve_heartbeat"
Type: files; Name: "{app}\valve_guard.pid"
