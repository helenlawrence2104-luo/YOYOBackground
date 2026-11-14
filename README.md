# YOYOBackground

荣耀 YOYO 后台执行 LSPosed 实验模块。

在手机前台继续播放视频、音乐或运行其他应用时，尝试将 YOYO 发起的应用操作放到不可见的离屏显示，并处理唤醒覆盖层、媒体静音和锁屏任务策略。

当前版本 **0.5-experimental**，包名 `local.yoyobg.module`。这是针对特定手机环境验证的实验实现，不保证所有应用和所有执行入口兼容。

## 已验证环境

| 项目 | 环境 |
| --- | --- |
| 手机 | HONOR PTP-AN10 |
| 系统报告 | Android 17 / API 37，MagicOS 11.0.0.117 |
| 权限环境 | KernelSU + Zygisk + LSPosed |
| 荣耀 YOYO | `com.hihonor.magicvoice` / **90.10.35.036** |
| 微信 | 8.0.80 |

YOYO 版本不匹配时不会安装执行钩子。APK 最低安装版本为 Android 14，不代表已测试其他系统。

## 功能与验证状态

- 用户确认唤醒界面不再干扰前台操作。
- 用户确认媒体声音正常，同时 YOYO 能正常听懂并回答。
- 用户确认锁屏打开计算器没有要求解锁。
- 用户确认 0.5 锁屏微信发送测试没有要求解锁，消息已发出。
- 微信发送确认使用内部自动语音应答：匹配明确的发送确认后提交一次“确认发送”PCM，不从扬声器播放，不模仿用户声线。

以上是本次设备与测试流程的结果，不代表所有游戏的持续触摸、所有耳机、全部联系人场景或每次会话均已通过。曾观察到会话切换导致内部应答中止；不会自动反复重试。详细边界见 [验证记录](docs/VALIDATION.md)。

## 安装与启用

1. 从 [Releases](https://github.com/helenlawrence2104-luo/YOYOBackground/releases) 下载 `YOYOBackground-v0.5.apk`，安装到已具备 LSPosed 的设备。
2. 在 LSPosed 中启用 **YOYO 后台执行实验**，作用域只勾选 **荣耀 YOYO / com.hihonor.magicvoice**。
3. 通过 ADB 配置总开关和分项开关：

```sh
adb shell settings put global yoyo_background_lab_enabled 1
adb shell settings put global yoyo_bg_keep_media 1
adb shell settings put global yoyo_bg_lock_work 1
adb shell settings put global yoyo_bg_wechat_auto_send 1
```

4. 重启 YOYO 的进程，或自行重启手机，使 LSPosed 加载模块。Windows 可运行 `tools/restart-yoyo.ps1`，需要手机已有 root。
5. 先验证唤醒、媒体声音和打开计算器，再自行选择文件传输助手测试发送。

手机上的控制应用可以启用/暂停**总开关**，按钮修改配置时需要允许该控制应用使用 root。分项开关按上述 ADB 命令配置。

## 暂停与卸载

```sh
adb shell settings put global yoyo_background_lab_enabled 0
```

将任一分项设置为 `0` 可单独关闭。已有后台任务可能需要重启 YOYO 才释放。在 LSPosed 中禁用并卸载模块即可移除，不需要清除 YOYO 或微信数据。

## 锁屏与后台执行方式

离屏显示不是悬浮窗或分屏，不在手机主屏绘制。模块尝试设置独立焦点及不抢顶层焦点标记。

锁屏功能处理的是 YOYO 内部的任务策略，不保存或输入设备密码，不改系统 Keyguard。只在 `UserManager.isUserUnlocked()` 为 true 时放行，即手机重启后已完成过首次解锁。其他应用自身的认证仍可能阻止任务。

同一应用的现有任务可能被移动到后台；不能保证用户和助手同时独立操作同一应用实例。

## 构建

Windows PowerShell 7、JDK 21、Android SDK（platform 35 或以上、Build Tools 35 或以上）、Xposed API 82 编译依赖。

```powershell
./build.ps1 -SdkRoot 'C:/Android/Sdk' -JavaHome 'C:/Program Files/Java/jdk-21' -XposedApiPath 'C:/dependencies/api-82.jar'
```

脚本输出 `build/YOYOBackground-v0.5.apk`，使用本地新建的开发签名。**自行构建的签名与本次 Release APK 不同，不能直接覆盖安装原签名版本。** 发布 APK 的原签名私钥不包含在仓库中。

Xposed API 82 仅用于编译，不打包进 APK；脚本校验依赖 SHA256。依赖用法见 [Xposed 官方 API 指南](https://github.com/rovo89/XposedBridge/wiki/Using-the-Xposed-Framework-API)。

## 资源来源

`module/assets/confirm_send.pcm` 是 Microsoft Huihui Desktop 系统 TTS 生成的内部指令音频，16 kHz / 16-bit / mono，内容“确认发送”，不是用户录音。仓库不包含 YOYO/微信 APK、反编译源码、设备原始日志、联系人、消息内容或签名私钥。
