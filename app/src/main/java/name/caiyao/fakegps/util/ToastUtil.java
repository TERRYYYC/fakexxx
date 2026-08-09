package name.caiyao.fakegps.util;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

public class ToastUtil {

    private static Toast mToast;

    private static Handler mHandler = new Handler();
    private static Runnable r = new Runnable() {
        public void run() {
            if (mToast != null) {
                mToast.cancel();
                mToast = null;
            }
        }
    };

    public static void showShortToast(Context context, String message) {
        TextView text = new TextView(context);
        text.setText(message);
        text.setBackgroundColor(Color.BLACK);
        text.setPadding(10, 10, 10, 10);

        if (mToast != null) {
            mHandler.postDelayed(r, 0);
        } else {
            mToast = new Toast(context);
            mToast.setDuration(Toast.LENGTH_SHORT);
            mToast.setGravity(Gravity.BOTTOM, 0, 150);
            mToast.setView(text);
        }

        mHandler.postDelayed(r, 1000);
        mToast.show();
    }

    public static void show(Context context, String info) {
        Toast.makeText(context, info, Toast.LENGTH_SHORT).show();
    }

    public static void showerror(Context context, int rCode) {
        Toast.makeText(context, "Error code: " + rCode, Toast.LENGTH_LONG).show();
        Log.e("FakeGPS", "Error code: " + rCode);
    }
}
