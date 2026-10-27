package app.shell;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

/** Мелкие помощники для программного UI. */
final class U {
    private U() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView tv(Context c, String t, float sp, int color) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    static GradientDrawable box(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static void round(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View x, Outline o) {
                o.setRoundRect(0, 0, x.getWidth(), x.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    static TextView center(TextView v) {
        v.setGravity(Gravity.CENTER);
        return v;
    }
}
