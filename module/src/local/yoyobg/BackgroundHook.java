package local.yoyobg;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Version-pinned prototype; runtime acceptance requires an actual YOYO command. */
public final class BackgroundHook implements IXposedHookLoadPackage {
    private static final String PKG = "com.hihonor.magicvoice";
    private static final String VIR = "com.hihonor.awareness.agent.virdisplay.VirDisplayManager";
    private static final String WINDOW = "com.hihonor.awareness.agent.interactive.UIAgentWindowManager";
    private static final String BEAN = "com.hihonor.awareness.agent.bean.UIAgentInteractionBean";
    private static final String KEY = "yoyo_background_lab_enabled";
    private static final ThreadLocal<Boolean> initializing = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> routing = ThreadLocal.withInitial(() -> false);
    private static ClassLoader loader;

    private static void log(String text) {
        XposedBridge.log("YOYOBackgroundLab: " + text);
        android.util.Log.i("YOYOBackgroundLab", text);
    }
    private static Application app() {
        return (Application) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
    }
    private static boolean enabled() {
        try {
            Application application = app();
            return application != null && Settings.Global.getInt(application.getContentResolver(), KEY, 0) == 1;
        } catch (Throwable error) { return false; }
    }
    private static Object manager() {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass(VIR, loader), "getInstance");
    }
    private static int prepare(Object manager) {
        XposedHelpers.callMethod(manager, "querySupportVirtual");
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isSupportVirtual")))
            throw new IllegalStateException("Native background capability unavailable");
        XposedHelpers.callMethod(manager, "initVirtualDisplay");
        // getmDisplayId() deliberately returns 0 before a task is executing.
        // Preparation must check the actual created display, not that execution-only getter.
        int id = XposedHelpers.getIntField(manager, "mDisplayId");
        if (id <= 0 || !XposedHelpers.getBooleanField(manager, "isInitComplete"))
            throw new IllegalStateException("Native background display not ready");
        return id;
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!PKG.equals(param.packageName)) return;
        loader = param.classLoader;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Context context = (Context) p.args[0];
                    String version = context.getPackageManager().getPackageInfo(PKG, 0).versionName;
                    if (!"90.10.35.036".equals(version)) {
                        log("Version mismatch; hooks not installed: " + version); return;
                    }
                    install();
                    log("Hooks installed v0.5 in " + param.processName);
                } catch (Throwable e) { log("Install failed: " + e); }
            }
        });
    }
    private static void install() {
        Class<?> vir = XposedHelpers.findClass(VIR, loader);
        XposedHelpers.findAndHookMethod(vir, "queryCapsuleSwitch", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled()) p.setResult(false);
            }
        });
        XposedHelpers.findAndHookMethod(vir, "showWindow", android.graphics.Bitmap.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled()) p.setResult(null);
            }
        });
        XposedHelpers.findAndHookMethod(vir, "moveStacksToMainDisplay", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled()) { log("Suppressed task return to main display"); p.setResult(null); }
            }
        });
        XposedHelpers.findAndHookMethod(vir, "initImagerReader", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { initializing.set(enabled()); }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (enabled() && !XposedHelpers.getBooleanField(p.thisObject, "isInitComplete")) {
                        android.hardware.display.VirtualDisplay vd = (android.hardware.display.VirtualDisplay)
                                XposedHelpers.getObjectField(p.thisObject, "virtualDisplay");
                        if (vd == null) throw new IllegalStateException("Native display creation failed");
                        android.view.Display display = vd.getDisplay();
                        android.graphics.Point size = new android.graphics.Point(); display.getRealSize(size);
                        android.media.ImageReader reader = android.media.ImageReader.newInstance(size.x, size.y, 1, 3);
                        Object manager = p.thisObject;
                        reader.setOnImageAvailableListener(r -> {
                            android.media.Image image = r.acquireLatestImage();
                            if (image == null) return;
                            try {
                                if (Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isVirtualExecuting")))
                                    XposedHelpers.callMethod(manager, "imageReaderToBitmap", image);
                            } catch (Throwable error) { log("Background frame failed: " + error); }
                            finally { image.close(); }
                        }, new android.os.Handler(android.os.Looper.getMainLooper()));
                        vd.setSurface(reader.getSurface());
                        XposedHelpers.setObjectField(manager, "mImageReader", reader);
                        XposedHelpers.setObjectField(manager, "mVirtualSurface", reader.getSurface());
                        XposedHelpers.setObjectField(manager, "mDisplay", display);
                        XposedHelpers.setIntField(manager, "mDisplayId", display.getDisplayId());
                        XposedHelpers.setBooleanField(manager, "isInitComplete", true);
                        XposedHelpers.setBooleanField(manager, "isOpenvirtualSwitch", true);
                        try { XposedHelpers.callMethod(manager, "setAIEVDDisplayID", display.getDisplayId()); }
                        catch (Throwable error) { log("OEM display registration unavailable: " + error); }
                        log("Recovered standard display surface: " + display.getDisplayId());
                    }
                } catch (Throwable error) { log("Display recovery failed: " + error); }
                finally { initializing.set(false); }
            }
        });
        XposedHelpers.findAndHookMethod(vir, "isDefaultVirtual", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Native detection otherwise disables support after it creates its own display.
                if (enabled() && XposedHelpers.getBooleanField(p.thisObject, "isInitComplete")) p.setResult(true);
            }
        });
        for (Method method : DisplayManager.class.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("createVirtualDisplay") && types.length >= 6
                    && types[0] == String.class && types[5] == int.class) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (initializing.get()) {
                            p.args[5] = ((Integer) p.args[5]) | (1 << 6) | (1 << 14) | (1 << 16);
                            log("Added OWN_FOCUS and STEAL_TOP_FOCUS_DISABLED");
                        }
                    }
                });
            }
        }
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(BEAN, loader), "getExeInEVD", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled()) p.setResult("EVD_ONLY");
            }
        });
        // Disable only the framework voice-session window; speech callbacks still run.
        XposedHelpers.findAndHookMethod(android.service.voice.VoiceInteractionSession.class,
                "setUiEnabled", boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (enabled()) p.args[0] = false;
                    }
                });
        XposedBridge.hookAllMethods(android.service.voice.VoiceInteractionSession.class, "doShow", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled()) {
                    ((android.service.voice.VoiceInteractionSession) p.thisObject).setUiEnabled(false);
                    log("Voice session UI disabled");
                }
            }
        });
        // Some YOYO versions add their own voice overlay outside VoiceInteractionSession.
        Class<?> windowGlobal = XposedHelpers.findClass("android.view.WindowManagerGlobal", null);
        XC_MethodHook overlayHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled() || p.args.length < 2 || !(p.args[1] instanceof android.view.WindowManager.LayoutParams)) return;
                android.view.WindowManager.LayoutParams lp = (android.view.WindowManager.LayoutParams) p.args[1];
                if (lp.type == 2031 || lp.type == 2038 || lp.type == 2002 || lp.type == 2003 || lp.type == 2006) {
                    lp.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                    lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                    lp.alpha = 0f; lp.dimAmount = 0f; lp.width = 1; lp.height = 1;
                    log("Voice overlay made invisible and non-interactive; type=" + lp.type);
                }
            }
        };
        XposedBridge.hookAllMethods(windowGlobal, "addView", overlayHook);
        XposedBridge.hookAllMethods(windowGlobal, "updateViewLayout", overlayHook);
        XposedHelpers.findAndHookMethod(
                "com.hihonor.awareness.server.childprocess.helper.UIAgentVirDisplayHelper", loader,
                "initVirDisplay", XposedHelpers.findClass("com.hihonor.awareness.server.childprocess.UiAgentManagerProxy", loader),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled() || p.args[0] == null) return;
                        try {
                            // Follow the native initializer but prevent its cloud policy from selecting display 0.
                            XposedHelpers.setIntField(p.args[0], "uxVersion", 2);
                            XposedHelpers.setObjectField(p.args[0], "exeInEVD", "EVD_ONLY");
                        } catch (Throwable e) { log("Native initializer policy failed: " + e); }
                    }
                });
        XposedBridge.hookAllMethods(XposedHelpers.findClass(WINDOW, loader), "startActivity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled()) return;
                try {
                    Object manager = manager();
                    int display = prepare(manager);
                    routing.set(true);
                    boolean result = Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "startVirtualActivity", p.args[1]));
                    log("UIAgent launch on display " + display + ": " + result);
                    p.setResult(result);
                } catch (Throwable e) { log("Blocked foreground fallback: " + e); p.setResult(false); }
                finally { routing.set(false); }
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass(WINDOW, loader), "injectInputEventEx", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled()) return;
                try {
                    Object manager = manager();
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isVirtualExecuting"))) {
                        int display = (Integer) XposedHelpers.callMethod(manager, "getmDisplayId");
                        if (display <= 0) throw new IllegalStateException("Background input has no display");
                        XposedHelpers.callMethod(p.args[1], "setDisplayId", display);
                    }
                } catch (Throwable e) { log("Blocked unsafe input: " + e); p.setResult(false); }
            }
        });
        // Classic 'open app' commands may bypass the UIAgent launcher. Preserve the original intent and extras.
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled() || routing.get() || initializing.get()) return;
                int intentIndex = -1;
                for (int i = 0; i < p.args.length; i++) if (p.args[i] instanceof Intent) { intentIndex = i; break; }
                if (intentIndex < 0 || !(p.args[0] instanceof Context)) return;
                Context context = (Context) p.args[0];
                Intent intent = (Intent) p.args[intentIndex];
                ActivityInfo target = intent.resolveActivityInfo(context.getPackageManager(), 0);
                if (target == null || PKG.equals(target.packageName) || "local.yoyobg.module".equals(target.packageName)) return;
                try {
                    Object manager = manager();
                    int display = prepare(manager);
                    int bundleIndex = -1;
                    Class<?>[] types = ((Method) p.method).getParameterTypes();
                    for (int i = 0; i < types.length; i++) if (types[i] == android.os.Bundle.class) bundleIndex = i;
                    if (bundleIndex < 0) throw new IllegalStateException("Unsupported launch signature");
                    android.os.Bundle old = (android.os.Bundle) p.args[bundleIndex];
                    android.app.ActivityOptions options = old == null ? android.app.ActivityOptions.makeBasic() :
                            (android.app.ActivityOptions) XposedHelpers.callStaticMethod(android.app.ActivityOptions.class, "fromBundle", old);
                    if (options == null) options = android.app.ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(display);
                    p.args[bundleIndex] = options.toBundle();
                    p.setObjectExtra("backgroundManager", manager);
                    p.setObjectExtra("backgroundPackage", target.packageName);
                    log("Classic app launch redirected to display " + display);
                } catch (Throwable e) {
                    log("Blocked classic foreground fallback: " + e);
                    p.setResult(null);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object manager = p.getObjectExtra("backgroundManager");
                if (manager != null && !p.hasThrowable()) {
                    XposedHelpers.setObjectField(manager, "pkgName", p.getObjectExtra("backgroundPackage"));
                    XposedHelpers.setBooleanField(manager, "isVirtualExecuting", true);
                    log("Classic launch returned without exception; task location still requires verification");
                }
            }
        });
        FeatureHooks.install(loader);
    }
}
