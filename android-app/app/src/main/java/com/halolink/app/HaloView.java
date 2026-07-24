package com.halolink.app;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.util.Locale;

public class HaloView extends View {
    private static final long OLED_SHIFT_INTERVAL_MS = 120_000L;
    private static final long ANIMATION_CYCLE_MS = 2_200L;
    private static final float[] SWEEP_POSITIONS = {0f, .35f, .72f, 1f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringOval = new RectF();
    private final int[] sweepColors = new int[4];
    private BlurMaskFilter glowFilter;
    private SweepGradient sweepGradient;
    private float cachedStroke = -1f;
    private float cachedCenterX = Float.NaN;
    private float cachedCenterY = Float.NaN;
    private int cachedGradientColor = Color.TRANSPARENT;
    private String state = "SEARCHING";
    private String label = "Searching...";
    private float phase = 0f;
    private boolean animationsEnabled = true;
    private boolean attached = false;
    private boolean minimalIdleMode = false;
    private long animationEpochMs = SystemClock.elapsedRealtime();

    private final Runnable animationFrameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldAnimate()) return;
            long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - animationEpochMs);
            phase = (elapsedMs % ANIMATION_CYCLE_MS) / (float) ANIMATION_CYCLE_MS;
            invalidate();
            long frameDelayMs = EnergyPolicy.animationFrameDelayMs(state);
            if (frameDelayMs > 0L) postDelayed(this, frameDelayMs);
        }
    };

    private final Runnable oledShiftRunnable = new Runnable() {
        @Override
        public void run() {
            if (!attached || !animationsEnabled || shouldAnimate()) return;
            invalidate();
            postDelayed(this, OLED_SHIFT_INTERVAL_MS);
        }
    };

    public HaloView(Context context) {
        super(context);
        // BlurMaskFilter is hardware accelerated on the Mate 20 Pro/API 29.
        // Keep the compatibility fallback only for Android 8.x devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        setBackgroundColor(Color.BLACK);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        subTextPaint.setColor(Color.rgb(130, 138, 148));
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        subTextPaint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
    }

    public boolean setStatus(String newState, String newLabel) {
        String normalized = newState == null ? "READY" : newState.toUpperCase(Locale.ROOT);
        String resolvedLabel = newLabel == null || newLabel.isEmpty()
                ? defaultLabel(normalized) : newLabel;
        boolean exitMinimalMode = minimalIdleMode
                && !EnergyPolicy.supportsMinimalDisplay(normalized);
        if (normalized.equals(state) && resolvedLabel.equals(label) && !exitMinimalMode) return false;
        state = normalized;
        label = resolvedLabel;
        if (exitMinimalMode) minimalIdleMode = false;
        updateAnimationPolicy();
        invalidate();
        return true;
    }

    public String getState() {
        return state;
    }

    public void setAnimationsEnabled(boolean enabled) {
        if (animationsEnabled == enabled) return;
        animationsEnabled = enabled;
        updateAnimationPolicy();
    }

    public boolean setMinimalIdleMode(boolean enabled) {
        if (minimalIdleMode == enabled) return false;
        minimalIdleMode = enabled;
        updateAnimationPolicy();
        invalidate();
        return true;
    }

    public boolean isMinimalIdleMode() {
        return minimalIdleMode;
    }

    private boolean shouldAnimate() {
        return attached
                && animationsEnabled
                && !minimalIdleMode
                && EnergyPolicy.isAnimatedState(state);
    }

    private void updateAnimationPolicy() {
        removeCallbacks(animationFrameRunnable);
        removeCallbacks(oledShiftRunnable);
        if (shouldAnimate()) {
            post(animationFrameRunnable);
        } else {
            if (attached && animationsEnabled) {
                postDelayed(oledShiftRunnable, OLED_SHIFT_INTERVAL_MS);
            }
        }
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

        // Shift only once per interval. Static states redraw on the same cadence,
        // while animated states reuse one cached gradient between shifts.
        long burnStep = System.currentTimeMillis() / OLED_SHIFT_INTERVAL_MS;
        double burnPhase = burnStep * 0.91;
        float burnX = (float) Math.sin(burnPhase) * 3f;
        float burnY = (float) Math.cos(burnPhase * 0.83) * 3f;
        float cx = w / 2f + burnX;
        float cy = h / 2f + burnY;
        int color = colorForState();

        if (minimalIdleMode) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setMaskFilter(null);
            paint.setColor(withAlpha(color, 220));
            canvas.drawCircle(cx, cy, Math.max(5f, min * .018f), paint);
            return;
        }

        float radius = min * 0.31f;
        float stroke = Math.max(10f, min * 0.022f);
        ringOval.set(cx - radius, cy - radius, cx + radius, cy + radius);
        updateEffectCache(cx, cy, stroke, color);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(null);
        paint.setMaskFilter(null);

        // Dim base ring.
        paint.setColor(withAlpha(color, state.equals("READY") ? 95 : 55));
        canvas.drawOval(ringOval, paint);

        float pulse = 0.64f + 0.36f * (float) Math.sin(phase * Math.PI * 2);
        float rotation = phase * 360f - 90f;
        int brightAlpha = 235;
        if (state.equals("THINKING") || state.equals("LISTENING")) brightAlpha = (int) (135 + 110 * pulse);
        if (state.equals("ERROR")) brightAlpha = ((int) (phase * 8) % 2 == 0) ? 250 : 85;
        if (state.equals("READY")) brightAlpha = 75;

        paint.setColor(withAlpha(color, brightAlpha));
        paint.setMaskFilter(glowFilter);
        if (isRotating()) {
            paint.setShader(sweepGradient);
            canvas.save();
            canvas.rotate(rotation, cx, cy);
            canvas.drawArc(ringOval, 0, 125, false, paint);
            canvas.restore();
            paint.setShader(null);
        } else {
            canvas.drawOval(ringOval, paint);
        }

        paint.setMaskFilter(null);
        paint.setStrokeWidth(Math.max(5f, stroke * .48f));
        paint.setColor(withAlpha(color, 245));
        if (isRotating()) {
            canvas.drawArc(ringOval, rotation, 82, false, paint);
        } else {
            canvas.drawOval(ringOval, paint);
        }

        // Symbols use the upper half of the halo so they never collide with the two text lines.
        if (state.equals("LISTENING")) drawListeningWaves(canvas, cx, cy - radius * .42f, color, stroke);
        if (state.equals("ERROR")) drawErrorMark(canvas, cx, cy - radius * .42f, radius, color, stroke);

        String displayLabel = fitText(textPaint, label, Math.max(36f, min * 0.095f),
                36f, radius * 1.60f);
        // Keep both lines clear of the lower arc, even when a label is longer than usual.
        float textY = cy + radius * .035f;
        canvas.drawText(displayLabel, cx, textY, textPaint);

        String sub = subLabel();
        if (!sub.isEmpty()) {
            String displaySub = fitText(subTextPaint, sub, Math.max(18f, min * .037f),
                    18f, radius * 1.52f);
            float subTextY = textY + Math.min(min * .065f, radius * .22f);
            canvas.drawText(displaySub, cx, subTextY, subTextPaint);
        }
    }

    private String fitText(Paint target, String value, float preferredSize, float minimumSize,
            float maxWidth) {
        target.setTextSize(preferredSize);
        float width = target.measureText(value);
        if (width > maxWidth) {
            target.setTextSize(Math.max(minimumSize, preferredSize * maxWidth / width));
        }
        if (target.measureText(value) <= maxWidth) return value;
        String ellipsis = "…";
        int end = target.breakText(value, 0, value.length(), true,
                Math.max(0f, maxWidth - target.measureText(ellipsis)), null);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return end > 0 ? value.substring(0, end) + ellipsis : ellipsis;
    }

    private void updateEffectCache(float cx, float cy, float stroke, int color) {
        if (Math.abs(cachedStroke - stroke) > 0.01f || glowFilter == null) {
            cachedStroke = stroke;
            glowFilter = new BlurMaskFilter(stroke * 1.2f, BlurMaskFilter.Blur.NORMAL);
        }
        if (sweepGradient == null
                || cachedGradientColor != color
                || Math.abs(cachedCenterX - cx) > 0.01f
                || Math.abs(cachedCenterY - cy) > 0.01f) {
            cachedCenterX = cx;
            cachedCenterY = cy;
            cachedGradientColor = color;
            sweepColors[0] = Color.TRANSPARENT;
            sweepColors[1] = withAlpha(color, 110);
            sweepColors[2] = color;
            sweepColors[3] = Color.TRANSPARENT;
            sweepGradient = new SweepGradient(cx, cy, sweepColors, SWEEP_POSITIONS);
        }
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
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateAnimationPolicy();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(animationFrameRunnable);
        removeCallbacks(oledShiftRunnable);
        super.onDetachedFromWindow();
    }
}
