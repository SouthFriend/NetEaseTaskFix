package com.neteasetaskfix;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

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
public class HookModule implements IXposedHookLoadPackage {

    private static final String NETEASE_PKG = "com.netease.cloudmusic";
    private static final String HONOR_PKG = "com.hihonor.cloudmusic";
    private static final String PLAYER_ACT = "com.netease.cloudmusic.activity.PlayerActivity";
    private static final String MAIN_ACT = "com.netease.cloudmusic.activity.MainActivity";
    
    private static final ThreadLocal<Boolean> processing = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile Activity playerAct = null;
    private static volatile Context ctx = null;
    private static volatile String pkg = null;
    private static volatile long lastBackTime = 0;
    private static volatile boolean directPlayerTriggered = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        pkg = lpp.packageName;
        
        boolean isTarget = NETEASE_PKG.equals(pkg) || HONOR_PKG.equals(pkg);
        if (!isTarget || lpp.processName.contains(":")) return;

        try {
            Class<?> actClass = XposedHelpers.findClass("android.app.Activity", lpp.classLoader);

            // 获取 Context
            XposedHelpers.findAndHookMethod(actClass, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ctx = ((Activity) param.thisObject).getApplicationContext();
                }
            });

            // PlayerActivity onResume
            XposedHelpers.findAndHookMethod(actClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    if (a.getClass().getName().contains("PlayerActivity")) {
                        playerAct = a;
                    }
                }
            });

            // PlayerActivity finish - 启动主页
            XposedHelpers.findAndHookMethod(actClass, "finish", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (processing.get()) return;
                    Activity a = (Activity) param.thisObject;
                    if (!a.getClass().getName().contains("PlayerActivity")) return;
                    
                    processing.set(Boolean.TRUE);
                    
                    Intent i = new Intent();
                    i.setComponent(new ComponentName(pkg, MAIN_ACT));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    a.startActivity(i);
                    
                    param.setResult(null);
                    processing.set(Boolean.FALSE);
                }
            });

            // 返回键拦截 - 在 dispatchKeyEvent 里处理
            XposedHelpers.findAndHookMethod(actClass, "dispatchKeyEvent", android.view.KeyEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    String name = a.getClass().getName();
                    android.view.KeyEvent e = (android.view.KeyEvent) param.args[0];
                    
                    // 只处理 UP 事件
                    if (e.getAction() != android.view.KeyEvent.ACTION_UP) return;
                    if (e.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return;
                    
                    // 播放页返回拦截 - 需按两次
                    if (name.contains("PlayerActivity")) {
                        long now = System.currentTimeMillis();
                        if (now - lastBackTime > 3000) {
                            // 第一次拦截
                            toast("再按一次返回");
                            lastBackTime = now;
                            param.setResult(Boolean.TRUE);
                        }
                        // 3秒内第二次放行
                    }
                }
            });

            // 主页获得焦点 - 关闭播放页 + 直接进入播放页
            XposedHelpers.findAndHookMethod(actClass, "onWindowFocusChanged", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    boolean focus = (boolean) param.args[0];
                    Activity a = (Activity) param.thisObject;
                    String name = a.getClass().getName();
                    
                    if (!focus) return;
                    if (!name.contains("MainActivity") && !name.contains("IconChangeAlias")) return;
                    
                    // 关闭播放页
                    if (playerAct != null) {
                        Activity p = playerAct;
                        playerAct = null;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                processing.set(Boolean.TRUE);
                                p.finish();
                            } finally {
                                processing.set(Boolean.FALSE);
                            }
                        });
                    }
                    
                    // 直接进入播放页功能 - 仅在首次启动时触发
                    if (!directPlayerTriggered) {
                        directPlayerTriggered = true;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (ctx != null) {
                                Intent i = new Intent();
                                i.setComponent(new ComponentName(pkg, PLAYER_ACT));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                ctx.startActivity(i);
                            }
                        }, 2000);
                    }
                }
            });

            // Activity 启动拦截 - 播放页加 Flags
            Class<?> inst = XposedHelpers.findClass("android.app.Instrumentation", lpp.classLoader);
            XposedBridge.hookAllMethods(inst, "execStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (!(arg instanceof Intent)) continue;
                        Intent i = (Intent) arg;
                        if (i.getComponent() == null) continue;
                        String cls = i.getComponent().getClassName();
                        
                        if (cls.contains("PlayerActivity")) {
                            int f = i.getFlags();
                            f |= Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
                            f &= ~(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                            i.setFlags(f);
                        }
                        break;
                    }
                }
            });

        } catch (Throwable e) {
            XposedBridge.log("NetEaseTaskFix Error: " + e.getMessage());
        }
    }
    
    private void toast(String msg) {
        if (ctx == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }
}
