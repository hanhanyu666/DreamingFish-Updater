; 梦鱼更新管理安装程序钩子。

; 0.2.2 及更早的版本以 DreamingFishAdminDesktop 的名称安装。安装前先静默卸载这份旧程序，
; 避免“应用和功能”里出现两个条目。旧版本的数据不在安装目录里，卸载它不会动项目和连接记录。
!macro NSIS_HOOK_PREINSTALL
  Push $R0
  Push $R1
  ReadRegStr $R0 HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\DreamingFishAdminDesktop" "InstallLocation"
  StrCpy $R1 $R0 1
  ${If} $R1 == '"'
    StrCpy $R0 $R0 "" 1
    StrCpy $R0 $R0 -1
  ${EndIf}
  ${If} $R0 != ""
  ${AndIf} ${FileExists} "$R0\uninstall.exe"
    DetailPrint "正在移除旧版 DreamingFishAdminDesktop：$R0"
    ExecWait '"$R0\uninstall.exe" /S _?=$R0'
    Delete "$R0\uninstall.exe"
    RMDir "$R0"
    DeleteRegKey HKCU "Software\dreamingfish\DreamingFishAdminDesktop"
  ${EndIf}
  Pop $R1
  Pop $R0
!macroend

; 卸载页的勾选框原本会删除整个用户数据目录，旧版本的默认管理数据（项目、签名密钥、发布记录）就在那里。
; 这里改为只清理界面缓存，并清掉勾选状态，让后面的默认删除不再执行。
!macro NSIS_HOOK_PREUNINSTALL
  ${If} $DeleteAppDataCheckboxState = 1
  ${AndIf} $UpdateMode <> 1
    RMDir /r "$INSTDIR\data\webview"
    RMDir /r "$INSTDIR\data\webviews"
    RMDir /r "$LOCALAPPDATA\${BUNDLEID}\EBWebView"
    RMDir /r "$LOCALAPPDATA\${BUNDLEID}\webview"
    RMDir /r "$LOCALAPPDATA\${BUNDLEID}\webviews"
  ${EndIf}
  StrCpy $DeleteAppDataCheckboxState 0
!macroend
