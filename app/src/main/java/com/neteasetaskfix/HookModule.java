package com.neteasetaskfix;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 任务修复模块 - 网易云音乐/荣耀音乐
 * 功能全部默认开启：
 * 1. 播放页作为独立任务根，按Home不跳回主页
 * 2. 播放页返回时启动主页
 * 3. 播放页返回键需按两次
 * 4. 启动应用直接进入播放页
 */
@SuppressWarnings({"StaticFieldLeak", "Convert2Lambda"})
public class HookModule implements IXposedHookLoadPackage {

    private static final String NETEASE_PKG = "com.netease.cloudmusic";
    private static final String HONOR_PKG = "com.hihonor.cloudmusic";
    private static final String PLAYER_ACT_REL = "com.netease.cloudmusic.activity.PlayerActivity";
    private static final String MAIN_ACT_REL = "com.netease.cloudmusic.activity.MainActivity";
    
    private static volatile Activity playerAct = null;
    private static volatile boolean processingFlag = false;
    private static volatile long lastBackTime = 0;
    private static volatile boolean directPlayerTriggered = false;
    
    // 当前 Hook 的目标包名（每个进程独立）
    private String targetPkg = null;

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        targetPkg = lpp.packageName;
        
        boolean isTarget = NETEASE_PKG.equals(targetPkg) || HONOR_PKG.equals(targetPkg);
        if (!isTarget || lpp.processName.contains(":")) return;

        XposedBridge.log("NetEaseTaskFix: Hooking " + targetPkg);

        try {
            Class<?> actClass = XposedHelpers.findClass("android.app.Activity", lpp.classLoader);

            // PlayerActivity onResume
            XposedHelpers.findAndHookMethod(actClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    // 双重校验：类名 + 包名
                    if (a.getClass().getName().contains("PlayerActivity") && 
                        isTargetPackage(a)) {
                        playerAct = a;
                    }
                }
            });

            // PlayerActivity finish - 启动主页
            XposedHelpers.findAndHookMethod(actClass, "finish", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (processingFlag) return;
                    Activity a = (Activity) param.thisObject;
                    
                    // 双重校验
                    if (!a.getClass().getName().contains("PlayerActivity")) return;
                    if (!isTargetPackage(a)) return;
                    
                    processingFlag = true;
                    
                    String currentPkg = a.getPackageName();
                    Intent intent = new Intent();
                    intent.setClassName(currentPkg, MAIN_ACT_REL);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    a.startActivity(intent);
                    
                    param.setResult(null);
                    processingFlag = false;
                }
            });

            // 返回键拦截
            XposedHelpers.findAndHookMethod(actClass, "dispatchKeyEvent", android.view.KeyEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    String name = a.getClass().getName();
                    android.view.KeyEvent e = (android.view.KeyEvent) param.args[0];
                    
                    if (e.getAction() != android.view.KeyEvent.ACTION_UP) return;
                    if (e.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return;
                    
                    // 双重校验
                    if (!name.contains("PlayerActivity")) return;
                    if (!isTargetPackage(a)) return;
                    
                    long now = System.currentTimeMillis();
                    if (now - lastBackTime > 3000) {
                        showTip(a, "再按一次返回");
                        lastBackTime = now;
                        param.setResult(Boolean.TRUE);
                    }
                }
            });

            // 主页获得焦点
            XposedHelpers.findAndHookMethod(actClass, "onWindowFocusChanged", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    boolean focus = (boolean) param.args[0];
                    Activity a = (Activity) param.thisObject;
                    String name = a.getClass().getName();
                    
                    if (!focus) return;
                    
                    // 双重校验：必须是目标应用的主页相关 Activity
                    if (!name.contains("MainActivity") && !name.contains("IconChangeAlias")) return;
                    if (!isTargetPackage(a)) return;
                    
                    // 关闭播放页
                    if (playerAct != null) {
                        final Activity p = playerAct;
                        playerAct = null;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    processingFlag = true;
                                    p.finish();
                                } finally {
                                    processingFlag = false;
                                }
                            }
                        });
                    }
                    
                    // 直接进入播放页
                    if (!directPlayerTriggered) {
                        directPlayerTriggered = true;
                        final Activity mainAct = a;
                        final String currentPkg = a.getPackageName();
                        
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                showTip(mainAct, "5秒后自动进入播放页");
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        Intent intent = new Intent();
                                        intent.setClassName(currentPkg, PLAYER_ACT_REL);
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        mainAct.startActivity(intent);
                                    }
                                }, 5000);
                            }
                        }, 5000);
                    }
                }
            });

            // Activity 启动拦截
            Class<?> inst = XposedHelpers.findClass("android.app.Instrumentation", lpp.classLoader);
            XposedBridge.hookAllMethods(inst, "execStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (!(arg instanceof Intent intent)) continue;
                        if (intent.getComponent() == null) continue;
                        String cls = intent.getComponent().getClassName();
                        
                        if (cls.contains("PlayerActivity")) {
                            int flags = intent.getFlags();
                            flags |= Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
                            flags &= ~(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                            intent.setFlags(flags);
                        }
                        break;
                    }
                }
            });

        } catch (Throwable e) {
            XposedBridge.log("NetEaseTaskFix Error: " + e.getMessage());
        }
    }
    
    /**
     * 双重校验：判断 Activity 是否属于目标应用
     */
    private boolean isTargetPackage(Activity a) {
        String pkg = a.getPackageName();
        return NETEASE_PKG.equals(pkg) || HONOR_PKG.equals(pkg);
    }
    
    /**
     * 显示提示（Toast + 悬浮文字双重保障）
     */
    private void showTip(final Activity activity, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (activity == null || activity.isFinishing()) return;
                if (!isTargetPackage(activity)) return;  // 再次校验
                
                // 方式1: Toast
                try {
                    android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
                
                // 方式2: 直接在 Activity 的 DecorView 上添加文字
                try {
                    ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
                    
                    TextView tipView = new TextView(activity);
                    tipView.setText(msg);
                    tipView.setTextColor(Color.WHITE);
                    tipView.setTextSize(18);
                    tipView.setPadding(50, 30, 50, 30);
                    tipView.setBackgroundColor(0xDD000000);
                    
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    );
                    params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                    params.topMargin = 200;
                    
                    decorView.addView(tipView, params);
                    
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                decorView.removeView(tipView);
                            } catch (Exception ignored) {}
                        }
                    }, 2000);
                    
                } catch (Exception e) {
                    XposedBridge.log("NetEaseTaskFix TipView Error: " + e.getMessage());
                }
            }
        });
    }
}
