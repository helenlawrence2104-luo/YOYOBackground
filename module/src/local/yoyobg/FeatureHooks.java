package local.yoyobg;

import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.os.UserManager;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** User-requested optional behaviors, scoped to the YOYO process by BackgroundHook. */
final class FeatureHooks {
    private static final Map<Object, Boolean> confirmed = Collections.synchronizedMap(new WeakHashMap<>());
    private static Application app() {
        return (Application) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
    }
    private static boolean on(String key) {
        try {
            Application app = app();
            return app != null && Settings.Global.getInt(app.getContentResolver(), "yoyo_background_lab_enabled", 0) == 1
                    && Settings.Global.getInt(app.getContentResolver(), key, 0) == 1;
        } catch (Throwable e) { return false; }
    }
    private static boolean unlockedData() {
        try { return ((UserManager) app().getSystemService(Context.USER_SERVICE)).isUserUnlocked(); }
        catch (Throwable e) { return false; }
    }
    private static void log(String s) { XposedBridge.log("YOYOBackgroundLab: " + s); }
    static void install(ClassLoader loader) {
        installAudio(loader);
        installLock(loader);
        installWechat(loader);
    }
    private static void installAudio(ClassLoader loader) {
        try {
            Class<?> volume = XposedHelpers.findClass("com.hihonor.magicvoice.phonebase.util.VolumeUtil", loader);
            XC_MethodHook noMute = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (on("yoyo_bg_keep_media")) { log("Prevented YOYO media mute/unmute"); p.setResult(null); }
                }
            };
            XposedBridge.hookAllMethods(volume, "setMute", noMute);
            XposedBridge.hookAllMethods(volume, "setUnMute", noMute);
            XposedBridge.hookAllMethods(AudioManager.class, "adjustStreamVolume", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!on("yoyo_bg_keep_media") || p.args.length < 2) return;
                    int stream = (Integer) p.args[0], direction = (Integer) p.args[1];
                    if (stream == AudioManager.STREAM_MUSIC && (direction == AudioManager.ADJUST_MUTE
                            || direction == AudioManager.ADJUST_UNMUTE || direction == AudioManager.ADJUST_TOGGLE_MUTE)) {
                        log("Prevented direct YOYO media mute adjustment"); p.setResult(null);
                    }
                }
            });
            // Return immediate local success to YOYO without asking AudioService to interrupt media.
            // This deliberately does not represent an actual grant of system audio focus.
            for (Method method : AudioManager.class.getDeclaredMethods()) {
                if (method.getName().equals("requestAudioFocus") && method.getReturnType() == int.class) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (on("yoyo_bg_keep_media")) {
                                log("Skipped system audio-focus request; local immediate-success compatibility response");
                                p.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                            }
                        }
                    });
                }
            }
            Class<?> window = XposedHelpers.findClass("com.hihonor.awareness.agent.interactive.UIAgentWindowManager", loader);
            XposedBridge.hookAllMethods(window, "setVolumeByPidStream", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (on("yoyo_bg_keep_media")) { log("Skipped assistant per-app volume override"); p.setResult(true); }
                }
            });
            log("v0.4 audio hooks installed");
        } catch (Throwable e) { log("Audio hooks incomplete: " + e); }
    }
    private static void installLock(ClassLoader loader) {
        try {
            Class<?> interceptor = XposedHelpers.findClass("com.hihonor.magicvoice.phoneaction.interceptor.impl.ScreenLockInterceptor", loader);
            XposedBridge.hookAllMethods(interceptor, "shouldIntercept", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (on("yoyo_bg_lock_work") && unlockedData()) {
                        Class<?> result = XposedHelpers.findClass(
                                "com.hihonor.magicvoice.phoneaction.interceptor.MessageInterceptor$InterceptResult", loader);
                        p.setResult(XposedHelpers.callStaticMethod(result, "pass"));
                        log("Passed YOYO lock-screen policy; device keyguard unchanged");
                    }
                }
            });
            Class<?> keyguard = XposedHelpers.findClass("com.hihonor.magicvoice.phonebase.util.KeyguardUtil", loader);
            XposedBridge.hookAllMethods(keyguard, "isKeyguardLocked", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (on("yoyo_bg_lock_work") && unlockedData()) p.setResult(false);
                }
            });
            // This separate application-control policy explicitly requires unlocking for WeChat.
            Class<?> appControl = XposedHelpers.findClass(
                    "com.hihonor.magicvoice.business.manager.AppCtrlUnlockScreenManager", loader);
            XposedBridge.hookAllMethods(appControl, "needUnlockScreen", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (on("yoyo_bg_lock_work") && unlockedData()) {
                        log("Skipped YOYO app-control unlock requirement; system lock unchanged");
                        p.setResult(false);
                    }
                }
            });
            // Returning null is the native 'no unlock directive needed' contract of this method.
            Class<?> unlockDirectives = XposedHelpers.findClass(
                    "com.hihonor.magicvoice.phoneaction.intentionhandler.unlockscreen.UnlockScreenDirectiveHelper", loader);
            XposedBridge.hookAllMethods(unlockDirectives, "detectUnlockScreenDirective", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (on("yoyo_bg_lock_work") && unlockedData()) {
                        log("Suppressed YOYO generated unlock step; system lock unchanged");
                        p.setResult(null);
                    }
                }
            });
            log("v0.5 lock-policy hooks installed");
        } catch (Throwable e) { log("Lock-policy hooks incomplete: " + e); }
    }
    private static void installWechat(ClassLoader loader) {
        try {
            Class<?> wechat = XposedHelpers.findClass("com.hihonor.magicvoice.business.wechatControl.WeVoiceControl", loader);
            XposedHelpers.findAndHookMethod(wechat, "invokeVoiceCallback", String.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!on("yoyo_bg_wechat_auto_send") || Boolean.TRUE.equals(p.args[1])) return;
                    String answer = (String) p.args[0];
                    if (answer == null) return;
                    String tail = answer.substring(Math.max(0, answer.length() - 70)).replaceAll("\\s", "");
                    // Only an explicit message-send confirmation from an active WeChat voice session.
                    boolean asksSend = tail.matches("(?s).*(确认发送|确定发送|是否发送|是否要发送|要发送吗|发送吗|发送给.{1,20}吗|可以发送了吗).*" );
                    if (!asksSend || tail.matches("(?s).*(转账|付款|支付|红包|验证码|授权登录).*")) return;
                    Object input = XposedHelpers.getObjectField(p.thisObject, "curVoiceInput");
                    if (input == null || XposedHelpers.getObjectField(p.thisObject, "voiceControlSession") == null) return;
                    synchronized (confirmed) {
                        if (confirmed.put(input, true) != null) return;
                    }
                    Object control = p.thisObject;
                    // This is a synthetic internal reply, not a recording or imitation of the user.
                    p.setResult(null);
                    log("WeChat send-confirmation response recognized; queued one internal confirmation");
                    new Thread(() -> reply(control, input), "yoyo-wechat-confirm").start();
                }
            });
            log("v0.4 WeChat confirmation hook installed");
        } catch (Throwable e) { log("WeChat confirmation hook incomplete: " + e); }
    }
    private static void reply(Object control, Object input) {
        try {
            byte[] pcm;
            try (InputStream in = app().getPackageManager().getResourcesForApplication("local.yoyobg.module")
                    .getAssets().open("confirm_send.pcm"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                pcm = out.toByteArray();
            }
            if (pcm.length < 3200 || (pcm.length & 1) != 0) throw new IllegalStateException("Invalid PCM asset");
            Object waiting = XposedHelpers.getObjectField(control, "waitingStatus");
            Object waitingForWechat = XposedHelpers.getStaticObjectField(waiting.getClass(), "WAITING_WE_CHAT");
            XposedHelpers.setObjectField(control, "waitingStatus", waitingForWechat);
            for (int offset = 0; offset < pcm.length; offset += 640) {
                if (!on("yoyo_bg_wechat_auto_send") || XposedHelpers.getObjectField(control, "curVoiceInput") != input)
                    throw new IllegalStateException("Confirmation cancelled or session changed");
                byte[] frame = java.util.Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + 640));
                Object accepted = XposedHelpers.callMethod(input, "sendVoice", frame);
                if (Boolean.FALSE.equals(accepted)) throw new IllegalStateException("WeChat rejected voice data");
                Thread.sleep(20);
            }
            XposedHelpers.callMethod(control, "endSend");
            log("Internal confirmation audio submitted; actual delivery is not verified");
        } catch (Throwable e) { log("Internal confirmation failed; no automatic retry: " + e); }
    }
}
