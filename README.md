# NetEase Task Fix

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![LSPosed](https://img.shields.io/badge/Platform-LSPosed-green.svg)](https://github.com/LSPosed/LSPosed)

一个用于修复网易云音乐/荣耀音乐在车机画中画模式下异常行为的 Xposed 模块。

## 背景

在某些车机系统中，桌面提供了"画中画坑"功能，可以自动显示应用的界面。但网易云音乐在画中画模式下存在以下问题：

1. 按Home键或切回桌面时，播放页会跳回主页
2. 下滑关闭播放页时，整个应用会退出
3. 最近任务列表中只显示一个窗口

## 功能

本模块通过 Hook Activity 启动流程，实现以下功能：

- ✅ **独立任务栈**：播放页作为独立任务的根Activity，按Home不会跳回主页
- ✅ **平滑返回**：播放页返回时自动启动主页，不会退出整个应用
- ✅ **返回键拦截**：播放页需按两次返回键才能退出（防止误触）
- ✅ **自动进入播放页**：启动应用后自动进入播放页
- ✅ **多应用支持**：支持网易云音乐和荣耀音乐

## 原理

### 1. 播放页独立任务栈

Hook `Instrumentation.execStartActivity`，在 `PlayerActivity` 启动时添加：

```java
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
```

使播放页成为新任务栈的根Activity，与主页处于不同的任务栈。

### 2. 播放页返回启动主页

Hook `Activity.finish()`，在播放页调用 `finish()` 时：

1. 拦截原 `finish()` 调用
2. 启动主页 `MainActivity`
3. 主页获得焦点后关闭播放页

### 3. 返回键拦截

Hook `Activity.dispatchKeyEvent()`，在播放页按下返回键时：

- 第一次：显示Toast提示"再按一次返回"
- 3秒内第二次：放行，正常返回

### 4. 自动进入播放页

Hook `Activity.onWindowFocusChanged()`，在主页首次获得焦点后：

- 延迟2秒启动播放页（作为独立任务根）

## 安装

### 前置要求

- Android 设备
- 已安装 [LSPosed](https://github.com/LSPosed/LSPosed)（API 101）
- Root 权限

### 步骤

1. 克隆项目并编译：
   ```bash
   git clone https://github.com/你的用户名/NetEaseTaskFix.git
   cd NetEaseTaskFix
   ./gradlew assembleDebug
   ```

2. 安装 APK：
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. 在 LSPosed 管理器中：
   - 启用「任务修复」模块
   - 作用域勾选「网易云音乐」和/或「荣耀音乐」

4. 强制停止目标应用，重新打开即可生效

## 验证

打开系统「最近任务」列表，你应该能看到两个独立的任务卡片：
- 网易云音乐（主页）
- 网易云音乐（播放页）

## 已知限制

- 仅支持网易云音乐和荣耀音乐（包名分别为 `com.netease.cloudmusic` 和 `com.hihonor.cloudmusic`）
- 仅在 LSPosed API 101 环境下测试通过
- 部分车机系统可能有特殊行为，需要针对性适配

## 开发

### 项目结构

```
NetEaseTaskFix/
├── app/
│   └── src/main/
│       ├── java/com/neteasetaskfix/
│       │   └── HookModule.java    # 核心Hook逻辑
│       ├── assets/
│       │   └── xposed_init        # Xposed入口声明
│       └── res/
│           └── values/
│               └── arrays.xml     # 作用域配置
├── build.gradle.kts
└── README.md
```

### 编译

```bash
./gradlew assembleDebug
```

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) - 现代化的 Xposed 框架
- [Xposed](https://github.com/rovo89/Xposed) - 原始 Xposed 框架

## License

[MIT License](LICENSE)

## 作者

[南棚柚](https://github.com/你的用户名)
