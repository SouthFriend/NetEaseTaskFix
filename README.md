# NetEase Task Fix

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![LSPosed](https://img.shields.io/badge/Platform-LSPosed-green.svg)](https://github.com/LSPosed/LSPosed)

一个用于修复网易云音乐/荣耀音乐在车机画中画模式下异常行为的 Xposed 模块。

---

## 目录

- [背景与问题](#背景与问题)
- [车机桌面画中画坑机制](#车机桌面画中画坑机制)
- [开发历程](#开发历程)
  - [第一阶段：简单FLAG方案](#第一阶段简单flag方案)
  - [第二阶段：让播放页成为根](#第二阶段让播放页成为根)
  - [第三阶段：预加载主页的尝试](#第三阶段预加载主页的尝试)
  - [第四阶段：最终方案](#第四阶段最终方案)
- [最终实现原理](#最终实现原理)
- [安装与使用](#安装与使用)
- [技术细节](#技术细节)
- [致谢](#致谢)

---

## 背景与问题

某些车机系统提供了"画中画坑"功能——桌面可以预留位置，自动显示指定应用的界面。这在车载场景下非常实用，比如导航时可以在桌面一角显示音乐播放界面。

然而，网易云音乐（以及基于相同代码的荣耀音乐）在车机画中画模式下存在严重问题：

| 问题 | 描述 |
|------|------|
| Home键跳回主页 | 在画中画模式下，按Home键或切回桌面时，播放页会自动跳回应用主页 |
| 下滑退出应用 | 用户下滑关闭播放页时，整个应用会退出，而不是回到主页 |
| 单任务显示 | 最近任务列表中只显示一个窗口，无法独立管理播放页 |

这些问题导致用户体验极差：想切歌得重新打开应用，想保留播放页在画中画坑里却总是跳走。

---

## 车机桌面画中画坑机制

在深入了解问题之前，需要先理解车机桌面的特殊机制：

```
┌─────────────────────────────────────────────────────┐
│                    车机桌面                           │
│  ┌─────────────┐    ┌─────────────┐                 │
│  │  画中画坑1   │    │  画中画坑2   │    其他区域...   │
│  │  (自动显示)  │    │  (自动显示)  │                 │
│  └─────────────┘    └─────────────┘                 │
└─────────────────────────────────────────────────────┘
```

**核心机制**：
1. 桌面预留"坑位"，自动拉起指定应用的**根Activity**
2. 当应用进程死亡时，桌面会自动重新启动应用
3. 如果播放页作为根Activity，桌面会持续保持播放页显示
4. 如果主页是根Activity，桌面会不断尝试拉起主页

**关键代码**（车机桌面实现逻辑）：
```xml
<!-- 桌面配置中的画中画坑定义 -->
<com.car.desktop.PiPWindow>
    <targetPackage>com.netease.cloudmusic</targetPackage>
    <autoLaunch>true</autoLaunch>  <!-- 自动启动应用 -->
    <showRootActivity>true</showRootActivity>  <!-- 显示根Activity -->
</com.car.desktop.PiPWindow>
```

这就是问题的根源：桌面总是去找根Activity，而网易云音乐的根Activity是主页。当用户从主页进入播放页后，桌面画中画坑仍然指向主页，导致各种异常行为。

---

## 开发历程

### 第一阶段：简单FLAG方案

**思路**：在播放页启动时添加特殊FLAG，让它独立于主页。

**尝试的Hook**：
```java
// Hook PlayerActivity.onCreate
XposedHelpers.findAndHookMethod(playerClass, "onCreate", Bundle.class, new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        Activity activity = (Activity) param.thisObject;
        // 尝试修改Task属性
        activity.setTaskDescription(...);
    }
});
```

**尝试的FLAG组合**：
- `FLAG_ACTIVITY_NEW_TASK` - 新任务启动
- `FLAG_ACTIVITY_MULTIPLE_TASK` - 允许多任务
- `FLAG_ACTIVITY_LAUNCH_ADJACENT` - 相邻启动（分屏）

**结果**：❌ **失败**

**原因分析**：
1. 网易云音乐内部对Activity启动有严格控制
2. 主页和播放页共享同一个TaskAffinity
3. 简单的FLAG无法改变它们在同一任务栈的事实

---

### 第二阶段：让播放页成为根

**思路**：使用 `FLAG_ACTIVITY_CLEAR_TASK` 清空任务栈，让播放页成为新的根。

**实现**：
```java
// Hook Instrumentation.execStartActivity
XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        Intent intent = getIntentFromArgs(param.args);
        if (intent.getComponent().getClassName().contains("PlayerActivity")) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
    }
});
```

**结果**：✅ **部分成功**

**效果**：
- ✅ 播放页成功成为独立任务栈的根
- ✅ 按Home键不再跳回主页
- ✅ 最近任务列表显示两个独立窗口

**新问题**：
- ❌ 播放页返回时，整个应用退出（因为主页被清掉了）
- ❌ 车机桌面检测到应用"死亡"后会重新启动，显示启动页（约1.5秒）

---

### 第三阶段：预加载主页的尝试

**问题**：播放页返回时主页需要重新加载，有明显的停顿和启动页广告。

**尝试方案A - 提前启动主页**：
```java
// 播放页onResume后，延迟启动主页
new Handler().postDelayed(() -> {
    Intent main = new Intent();
    main.setComponent(new ComponentName(pkg, "MainActivity"));
    main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(main);
}, 500);

// 然后再把播放页切到前台
new Handler().postDelayed(() -> {
    Intent player = new Intent();
    player.setComponent(new ComponentName(pkg, "PlayerActivity"));
    player.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    context.startActivity(player);
}, 600);
```

**结果**：❌ **失败 - 死循环**

主页启动后成为根，播放页被切到后台。车机桌面检测到根是主页，又去拉主页...导致主页和播放页来回切换。

**尝试方案B - 主页后台加载**：
```java
// 主页启动时立即移到后台
XposedHelpers.findAndHookMethod(mainClass, "onResume", new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        Activity main = (Activity) param.thisObject;
        main.moveTaskToBack(true);
        // 设置窗口透明、不可触摸
        main.getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        );
    }
});
```

**结果**：❌ **失败 - 闪退桌面**

主页被移到后台后，系统认为没有可见Activity，将整个任务栈切到后台，导致闪回桌面。

**结论**：预加载方案在Android Activity生命周期限制下不可行。

---

### 第四阶段：最终方案

**核心思路**：既然预加载不行，那就优化返回时的体验。

**实现逻辑**：

```
用户在播放页按返回/下滑
        ↓
    拦截finish()调用
        ↓
    先启动主页(MainActivity)
        ↓
    主页获得焦点
        ↓
    再finish()播放页
        ↓
    用户看到的是：播放页 → 主页（平滑过渡）
```

**关键代码**：

```java
// 1. 拦截播放页的finish()
XposedHelpers.findAndHookMethod(Activity.class, "finish", new XC_MethodHook() {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        Activity a = (Activity) param.thisObject;
        if (!a.getClass().getName().contains("PlayerActivity")) return;
        
        // 拦截finish，改为启动主页
        Intent i = new Intent();
        i.setComponent(new ComponentName(pkg, MAIN_ACT));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        a.startActivity(i);
        
        param.setResult(null);  // 阻止原finish执行
    }
});

// 2. 主页获得焦点后关闭播放页
XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged", boolean.class, new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        boolean hasFocus = (boolean) param.args[0];
        Activity a = (Activity) param.thisObject;
        
        if (!hasFocus) return;
        if (!a.getClass().getName().contains("MainActivity")) return;
        
        // 主页获得焦点，延迟关闭播放页
        if (playerActivity != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                playerActivity.finish();
            });
        }
    }
});
```

**为什么用 `onWindowFocusChanged` 而不是 `onResume`？**

`onResume` 只表示Activity进入恢复状态，但窗口可能还没有完全显示。`onWindowFocusChanged(true)` 才表示窗口真正获得焦点并显示在屏幕上，这时关闭播放页用户看到的是平滑的过渡。

**结果**：✅ **成功！**

---

## 最终实现原理

### 1. 播放页独立任务栈

```java
// Hook Activity启动，为PlayerActivity添加特殊FLAG
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
```

使播放页成为新任务栈的根，与主页处于不同的任务栈。

```
原状态：
┌─────────────────────┐
│ Task #1             │
│ ┌─────────────────┐ │
│ │ MainActivity    │ │  ← 根
│ └─────────────────┘ │
│ ┌─────────────────┐ │
│ │ PlayerActivity  │ │
│ └─────────────────┘ │
└─────────────────────┘

Hook后：
┌─────────────────────┐     ┌─────────────────────┐
│ Task #1             │     │ Task #2             │
│ ┌─────────────────┐ │     │ ┌─────────────────┐ │
│ │ MainActivity    │ │     │ │ PlayerActivity  │ │  ← 根
│ └─────────────────┘ │     │ └─────────────────┘ │
│      (后台)         │     │      (前台)         │
└─────────────────────┘     └─────────────────────┘
```

### 2. 播放页返回启动主页

```
用户操作：按返回/下滑关闭播放页
              ↓
    ┌─────────────────────┐
    │ PlayerActivity.finish() │ ← 被Hook拦截
    └─────────────────────┘
              ↓
    启动 MainActivity (NEW_TASK | CLEAR_TOP)
              ↓
    ┌─────────────────────┐
    │ MainActivity 显示   │
    └─────────────────────┘
              ↓
    onWindowFocusChanged(true)
              ↓
    ┌─────────────────────┐
    │ PlayerActivity.finish() │ ← 这次真的finish
    └─────────────────────┘
              ↓
    用户看到：播放页 → 主页（平滑过渡）
```

### 3. 返回键双击拦截

```java
// 播放页按返回需按两次
if (name.contains("PlayerActivity")) {
    long now = System.currentTimeMillis();
    if (now - lastBackTime > 3000) {
        toast("再按一次返回");
        lastBackTime = now;
        param.setResult(Boolean.TRUE);  // 拦截
    }
    // 3秒内第二次不拦截，放行
}
```

### 4. 自动进入播放页

```java
// 主页首次获得焦点后，延迟启动播放页
if (!directPlayerTriggered) {
    directPlayerTriggered = true;
    new Handler().postDelayed(() -> {
        Intent i = new Intent();
        i.setComponent(new ComponentName(pkg, PLAYER_ACT));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(i);
    }, 2000);
}
```

---

## 安装与使用

### 前置要求

- Android 设备
- 已安装 [LSPosed](https://github.com/LSPosed/LSPosed)（API 101）
- Root 权限

### 安装步骤

1. 克隆并编译：
   ```bash
   git clone https://github.com/SouthFriend/NetEaseTaskFix.git
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

### 验证

打开系统「最近任务」列表，应该看到两个独立的任务卡片：
- 网易云音乐（主页）
- 网易云音乐（播放页）

---

## 技术细节

### 支持的应用

| 应用 | 包名 | 播放页 | 主页 |
|------|------|--------|------|
| 网易云音乐 | `com.netease.cloudmusic` | `PlayerActivity` | `MainActivity` |
| 荣耀音乐 | `com.hihonor.cloudmusic` | `PlayerActivity` | `MainActivity` |

### Hook点清单

| Hook点 | 作用 |
|--------|------|
| `Activity.onCreate` | 获取Context |
| `Activity.onResume` | 记录当前播放页实例 |
| `Activity.finish` | 拦截播放页finish，改为启动主页 |
| `Activity.dispatchKeyEvent` | 拦截返回键，实现双击退出 |
| `Activity.onWindowFocusChanged` | 主页获得焦点后关闭播放页、自动进入播放页 |
| `Instrumentation.execStartActivity` | 为PlayerActivity添加特殊FLAG |

### 关键FLAG

```java
// 让Activity成为新任务的根
FLAG_ACTIVITY_NEW_TASK      = 0x10000000
FLAG_ACTIVITY_CLEAR_TASK    = 0x00080000

// 清除可能影响任务栈的FLAG
~FLAG_ACTIVITY_NO_HISTORY
~FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
```

### 已知限制

1. **仅支持网易云音乐和荣耀音乐** - 其他音乐APP的Activity结构可能不同
2. **需要LSPosed API 101** - 未在其他Xposed框架上测试
3. **部分车机可能有特殊行为** - 不同车机厂商的画中画实现可能有差异

---

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) - 现代化的 Xposed 框架
- [Xposed](https://github.com/rovo89/Xposed) - 原始 Xposed 框架
- 所有参与测试和反馈的用户

---

## License

[MIT License](LICENSE)

## 作者

[南棚柚](https://github.com/SouthFriend)
