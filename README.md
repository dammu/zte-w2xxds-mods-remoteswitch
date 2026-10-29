# zte-w2xxds-mods-remoteswitch

An LSPosed module for the ZTE W2xxDS cloud-PC tablet (Android 13) that turns the keyboard **F9** key into a manager for remote-desktop, streaming and cloud-PC clients. Opening a client switches the tablet into the vendor's "cloud computer mode" (system shortcuts such as Alt+Tab go to the remote PC); tapping F9 again returns to the tablet. Tested only on the **W203DS** (China Mobile edition). Requires root + LSPosed.

Companion module: [zte-w2xxds-mods-clonelabel](https://github.com/dammu/zte-w2xxds-mods-clonelabel) (profile names on launcher icons).

---

中兴 W2xxDS 平板的 LSPosed 模块：把键盘 **F9** 接管为远程客户端管理器。

- F9 唤起悬浮UI，列出各个用户资料里的远程桌面 / 串流 / 云电脑客户端（自动发现 + 手动添加）。
- 打开客户端时切到原厂"云电脑模式"，Alt+Tab 等快捷键直达远程电脑；远程模式下短按 F9 回平板，长按 F9 换会话。
- 拦掉原厂强制进运营商云电脑 app 的流程（包括开机自动进入）。
- 设置页在 LSPosed 模块页点"打开"，或在悬浮UI 里按 S。界面中英双语。

LSPosed 作用域：`com.zte.usmartlauncher`、`cm.komect.aqb.android.hjqbind`。只在 W203DS（中国移动版）上测试过，其它型号的运营商包名可能不同。

## 构建与安装

需要 Android SDK（`build-tools;36.0.0`、`platforms;android-36`，`ANDROID_HOME`）、JDK 17（`JAVA_HOME`）、PowerShell 7。不用 Gradle。

```powershell
pwsh -File build.ps1    # → remoteswitch.apk；首次构建生成 debug.keystore，请保留
adb install -r -t --no-incremental --user 0 remoteswitch.apk
```

在 LSPosed 里启用模块、勾选两个作用域，然后重启平板（或 `pwsh -File deploy.ps1 -NoBuild`：安装并重启作用域进程）。

## 回滚

在 LSPosed 里关闭模块。若之后仍卡在远程模式：

```shell
adb shell settings put secure default_home com.zte.mifavor.launcher
adb shell cmd package set-home-activity com.zte.mifavor.launcher/com.android.launcher3.uioverrides.QuickstepLauncher
adb shell settings put secure remoteswitch_mode tablet
```

不要冻结 `com.zte.usmartlauncher` 或 HJQBIND：F9 的系统调用依赖它们，前者不在会让系统软重启。

## 许可

MIT，见 [LICENSE](LICENSE)。`libs/api-82.jar` 是 Xposed Framework API（rovo89，Apache-2.0），只在编译期使用。个人项目，按原样提供，与中兴、中国移动无关。
