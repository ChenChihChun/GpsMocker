package com.gpsmock.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class UIHelper {

    // Color Palette - Modern Dark Theme
    public static final int BG_PRIMARY = Color.parseColor("#0F1923");
    public static final int BG_CARD = Color.parseColor("#1A2733");
    public static final int BG_INPUT = Color.parseColor("#1E2D3D");
    public static final int BG_TOP_BAR = Color.parseColor("#162330");

    public static final int ACCENT_BLUE = Color.parseColor("#4FC3F7");
    public static final int ACCENT_GREEN = Color.parseColor("#66BB6A");
    public static final int ACCENT_RED = Color.parseColor("#EF5350");

    public static final int TEXT_PRIMARY = Color.parseColor("#ECEFF1");
    public static final int TEXT_SECONDARY = Color.parseColor("#90A4AE");
    public static final int TEXT_HINT = Color.parseColor("#546E7A");

    public static final int DIVIDER = Color.parseColor("#263238");

    public static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    public static GradientDrawable roundRect(int color, int radiusDp, Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(ctx, radiusDp));
        return gd;
    }

    public static GradientDrawable roundRectStroke(int fillColor, int strokeColor, int radiusDp, int strokeDp, Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(ctx, radiusDp));
        gd.setStroke(dp(ctx, strokeDp), strokeColor);
        return gd;
    }

    public static LinearLayout pageRoot(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_PRIMARY);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    public static LinearLayout topBar(Context ctx, String titleText) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG_TOP_BAR);
        int p = dp(ctx, 20);
        bar.setPadding(p, dp(ctx, 18), p, dp(ctx, 18));
        bar.setElevation(dp(ctx, 4));

        TextView title = new TextView(ctx);
        title.setText(titleText);
        title.setTextSize(22);
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.02f);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(title);
        return bar;
    }

    public static Button primaryButton(Context ctx, String text) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setAllCaps(false);
        btn.setLetterSpacing(0.03f);
        btn.setBackground(roundRect(ACCENT_GREEN, 14, ctx));
        btn.setStateListAnimator(null);
        btn.setElevation(dp(ctx, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 54));
        lp.setMargins(0, dp(ctx, 10), 0, dp(ctx, 10));
        btn.setLayoutParams(lp);
        return btn;
    }

    public static EditText styledInput(Context ctx, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(TEXT_HINT);
        et.setTextColor(TEXT_PRIMARY);
        et.setTextSize(16);
        et.setBackground(roundRectStroke(BG_INPUT, Color.parseColor("#2E4050"), 14, 1, ctx));
        int pad = dp(ctx, 16);
        et.setPadding(pad, dp(ctx, 14), pad, dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, 6), 0, dp(ctx, 6));
        et.setLayoutParams(lp);
        return et;
    }

    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(BG_CARD, 16, ctx));
        int pad = dp(ctx, 18);
        card.setPadding(pad, pad, pad, pad);
        card.setElevation(dp(ctx, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, 6), 0, dp(ctx, 6));
        card.setLayoutParams(lp);
        return card;
    }

    public static LinearLayout sectionHeader(Context ctx, String text) {
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.setPadding(dp(ctx, 4), dp(ctx, 16), dp(ctx, 4), dp(ctx, 10));

        View line = new View(ctx);
        line.setBackgroundColor(ACCENT_GREEN);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 16));
        lineLp.setMargins(0, 0, dp(ctx, 10), 0);
        line.setLayoutParams(lineLp);

        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(TEXT_SECONDARY);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setLetterSpacing(0.1f);

        wrapper.addView(line);
        wrapper.addView(label);
        return wrapper;
    }

    public static Button smallButton(Context ctx, String text, int color) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(13);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setBackground(roundRectStroke(Color.TRANSPARENT, color, 10, 1, ctx));
        btn.setStateListAnimator(null);
        btn.setElevation(0);
        int pad = dp(ctx, 12);
        btn.setPadding(pad, 0, pad, 0);
        btn.setMinimumWidth(0);
        btn.setMinWidth(0);
        return btn;
    }
}
