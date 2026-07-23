package com.halolink.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Locale;

public class HaloView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final ValueAnimator animator;
    private String state = "SEARCHING";
    private String label = "Searching...";
    private float phase = 0f;
    private long statusChangedAt = System.currentTimeMillis();

    public HaloView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(Color.BLACK);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        subTextPaint.setColor(Color.rgb(130, 138, 148));
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        subTextPaint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setStatus(String newState, String newLabel) {
        String normalized = newState == null ? "READY" : newState.toUpperCase(Locale.ROOT);
        if (!normalized.equals(state)) statusChangedAt = System.currentTimeMillis();
        state = normalized;
        label = newLabel == null || newLabel.isEmpty() ? defaultLabel(normalized) : newLabel;
        invalidate();
    }

    private String defaultLabel(String value) {
        switch (value) {
            case "THINKING": return "Thinking...";
            case "WORKING": return "Working...";
            case "STREAMING": return "Responding...";
            case "LISTENING": return "Listening...";
            case "COMPLETED": return "Completed!";
            case "ERROR": return "Error";
            case "CONNECTING": return "Connecting...";
            case "SEARCHING": return "Searching...";
            default: return "Ready";
        }
    }

    private int colorForState() {
        switch (state) {
            case "THINKING": return Color.rgb(255, 70, 70);
            case "WORKING": return Color.rgb(255, 139, 51);
            case "STREAMING": return Color.rgb(255, 85, 70);
            case "LISTENING": return Color.rgb(63, 156, 255);
            case "COMPLETED": return Color.rgb(74, 232, 104);
            case "ERROR": return Color.rgb(213, 82, 235);
            case "READY": return Color.rgb(70, 190, 100);
            default: return Color.rgb(190, 198, 208);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        float w = getWidth();
        float h = getHeight();
        float min = Math.min(w, h);

        // Tiny, slow movement reduces prolonged OLED pixel stress while remaining invisible in normal use.
        double burnPhase = System.currentTimeMillis() / 120000.0;
        float burnX = (float) Math.sin(burnPhase) * 3f;
        float burnY = (float) Math.cos(burnPhase * 0.83) * 3f;
        float cx = w / 2f + burnX;
        float cy = h / 2f + burnY;
        float radius = min * 0.31f;
        float stroke = Math.max(10f, min * 0.022f);
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        int color = colorForState();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(null);
        paint.setMaskFilter(null);

        // Dim base ring.
        paint.setColor(withAlpha(color, state.equals("READY") ? 95 : 55));
        canvas.drawOval(oval, paint);

        float pulse = 0.64f + 0.36f * (float) Math.sin(phase * Math.PI * 2);
        float rotation = phase * 360f - 90f;
        int brightAlpha = 235;
        if (state.equals("THINKING") || state.equals("LISTENING")) brightAlpha = (int) (135 + 110 * pulse);
        if (state.equals("ERROR")) brightAlpha = ((int) (phase * 8) % 2 == 0) ? 250 : 85;
        if (state.equals("READY")) brightAlpha = 75;

        paint.setColor(withAlpha(color, brightAlpha));
        paint.setMaskFilter(new BlurMaskFilter(stroke * 1.2f, BlurMaskFilter.Blur.NORMAL));
        if (isRotating()) {
            paint.setShader(new SweepGradient(cx, cy,
                    new int[]{Color.TRANSPARENT, withAlpha(color, 110), color, Color.TRANSPARENT},
                    new float[]{0f, .35f, .72f, 1f}));
            canvas.save();
            canvas.rotate(rotation, cx, cy);
            canvas.drawArc(oval, 0, 125, false, paint);
            canvas.restore();
            paint.setShader(null);
        } else {
            canvas.drawOval(oval, paint);
        }

        paint.setMaskFilter(null);
        paint.setStrokeWidth(Math.max(5f, stroke * .48f));
        paint.setColor(withAlpha(color, 245));
        if (isRotating()) {
            canvas.drawArc(oval, rotation, 82, false, paint);
        } else {
            canvas.drawOval(oval, paint);
        }

        if (state.equals("LISTENING")) drawListeningWaves(canvas, cx, cy - radius * .18f, color, stroke);
        if (state.equals("COMPLETED")) drawCheck(canvas, cx, cy - radius * .20f, radius, color, stroke);
        if (state.equals("ERROR")) drawErrorMark(canvas, cx, cy - radius * .20f, radius, color, stroke);

        textPaint.setTextSize(Math.max(36f, min * 0.095f));
        float textY = cy + radius * .20f;
        canvas.drawText(label, cx, textY, textPaint);

        subTextPaint.setTextSize(Math.max(18f, min * 0.037f));
        String sub = subLabel();
        if (!sub.isEmpty()) canvas.drawText(sub, cx, textY + min * .075f, subTextPaint);
    }

    private boolean isRotating() {
        return state.equals("SEARCHING") || state.equals("CONNECTING") ||
                state.equals("WORKING") || state.equals("STREAMING");
    }

    private String subLabel() {
        switch (state) {
            case "SEARCHING": return "Looking for your Mac";
            case "CONNECTING": return "Connecting on local network";
            case "READY": return "Connected";
            case "THINKING": return "ChatGPT is thinking";
            case "WORKING": return "A tool is running";
            case "STREAMING": return "Response in progress";
            case "LISTENING": return "Voice input active";
            case "COMPLETED": return "Response ready";
            case "ERROR": return "Check the browser";
            default: return "";
        }
    }

    private void drawCheck(Canvas canvas, float cx, float cy, float radius, int color, float stroke) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(stroke * .62f);
        paint.setColor(color);
        paint.setShadowLayer(stroke * 1.5f, 0, 0, color);
        checkPath.reset();
        checkPath.moveTo(cx - radius * .13f, cy);
        checkPath.lineTo(cx - radius * .035f, cy + radius * .095f);
        checkPath.lineTo(cx + radius * .17f, cy - radius * .12f);
        canvas.drawPath(checkPath, paint);
        paint.clearShadowLayer();
    }

    private void drawErrorMark(Canvas canvas, float cx, float cy, float radius, int color, float stroke) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke * .55f);
        paint.setColor(color);
        canvas.drawLine(cx, cy - radius * .13f, cx, cy + radius * .055f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy + radius * .14f, stroke * .28f, paint);
    }

    private void drawListeningWaves(Canvas canvas, float cx, float cy, int color, float stroke) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke * .30f);
        paint.setColor(color);
        float base = stroke * 1.4f;
        for (int i = -3; i <= 3; i++) {
            float dynamic = (float) (0.45 + 0.55 * Math.abs(Math.sin(phase * Math.PI * 2 + i * .7)));
            float height = base * (1.3f + (3 - Math.abs(i)) * .55f) * dynamic;
            float x = cx + i * stroke * .52f;
            canvas.drawLine(x, cy - height / 2, x, cy + height / 2, paint);
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }
}
