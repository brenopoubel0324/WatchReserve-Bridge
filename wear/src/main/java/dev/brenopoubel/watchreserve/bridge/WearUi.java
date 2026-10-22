package dev.brenopoubel.watchreserve.bridge;

import android.app.Activity;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Coluna circular rolável, com alvos de toque de pelo menos 48 dp. */
final class WearUi {
    final Activity activity;
    final LinearLayout column;
    final ScrollView scroll;

    WearUi(Activity activity, String title) {
        this.activity = activity;
        scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(24), dp(28), dp(24), dp(40));
        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        scroll.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_SCROLL) return false;
            float axis = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (axis == 0) axis = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            scroll.scrollBy(0, (int) (-axis * dp(40)));
            return true;
        });
        scroll.setFocusableInTouchMode(true);
        activity.setContentView(scroll);
        scroll.requestFocus();
        text(title, 17, Color.rgb(142, 227, 209));
    }

    TextView text(String text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(0, dp(4), 0, dp(10));
        column.addView(view);
        return view;
    }

    TextView body(String text) { return text(text, 13, Color.LTGRAY); }

    Button button(String text, View.OnClickListener action) {
        Button view = new Button(activity);
        view.setText(text);
        view.setTextSize(13);
        view.setAllCaps(false);
        view.setSingleLine(false);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x338EE3D1), background, null));
        int[][] states = {new int[]{android.R.attr.state_enabled}, new int[]{}};
        view.setBackgroundTintList(new ColorStateList(states, new int[]{0xFF243638, 0xFF1A2022}));
        view.setTextColor(new ColorStateList(states, new int[]{0xFFE3F4F1, 0xFF6E7A78}));
        view.setOnClickListener(action);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(8);
        column.addView(view, layout);
        return view;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
