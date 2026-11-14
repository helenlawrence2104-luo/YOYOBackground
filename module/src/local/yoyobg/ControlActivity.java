package local.yoyobg;
import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
public final class ControlActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setPadding(40,70,40,40);
        status = new TextView(this); status.setTextSize(20); view.addView(status);
        TextView info = new TextView(this);
        info.setText("实验版 0.5\n适配 YOYO 90.10.35.036\n\n作用域只选荣耀 YOYO。\n\n保留语音唤醒，尝试隐藏覆盖层、后台执行、不静音媒体、再次锁屏后继续任务。微信明确询问发送确认时，尝试通过其语音输入接口内部自动应答一次；不会播放该回复。\n\n尚需真机验收，不能保证所有入口有效。重启后首次解锁前不放行加密数据任务。\n\n暂停后，新指令恢复默认逻辑；已有后台任务可能需要重启 YOYO 释放。");
        info.setTextSize(16); info.setPadding(0,30,0,30); view.addView(info);
        for (int value : new int[]{1,0}) {
            Button button = new Button(this); button.setText(value == 1 ? "启用后台执行实验" : "暂停实验（恢复默认执行）");
            button.setOnClickListener(v -> new Thread(() -> {
                String result;
                try {
                    Process process = Runtime.getRuntime().exec(new String[]{"su","-c","settings put global yoyo_background_lab_enabled " + value});
                    result = process.waitFor() == 0 ? "设置已保存" : "需要允许本应用使用 root";
                } catch (Exception e) { result = e.toString(); }
                final String message = result;
                runOnUiThread(() -> { refresh(); android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show(); });
            }).start()); view.addView(button);
        }
        setContentView(view); refresh();
    }
    private void refresh() {
        boolean enabled=Settings.Global.getInt(getContentResolver(),"yoyo_background_lab_enabled",0)==1;
        status.setText("YOYO 后台执行实验\n开关："+(enabled?"启用":"暂停")+"\n模块是否加载请以 LSPosed 和日志为准");
    }
}


