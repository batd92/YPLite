package com.yplite.ai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;
import java.util.List;

public class OverlayView extends View {

    private List<BoundingBox> results;
    private Paint boxPaint = new Paint();
    private Paint textBackgroundPaint = new Paint();
    private Paint textPaint = new Paint();
    private Rect bounds = new Rect();

    private static final int BOUNDING_RECT_TEXT_PADDING = 8;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    public void clear() {
        results = null;
        textPaint.reset();
        textBackgroundPaint.reset();
        boxPaint.reset();
        invalidate();
        initPaints();
    }

    private void initPaints() {
        textBackgroundPaint.setColor(Color.BLACK);
        textBackgroundPaint.setStyle(Paint.Style.FILL);
        textBackgroundPaint.setTextSize(50f);

        textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(50f);

        boxPaint.setColor(ContextCompat.getColor(getContext(), R.color.bounding_box_color));
        boxPaint.setStrokeWidth(8F);
        boxPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (results != null) {
            for (BoundingBox result : results) {
                float left = result.getX1() * getWidth();
                float top = result.getY1() * getHeight();
                float right = result.getX2() * getWidth();
                float bottom = result.getY2() * getHeight();

                canvas.drawRect(left, top, right, bottom, boxPaint);
                String drawableText = result.getClsName();

                textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length(), bounds);
                int textWidth = bounds.width();
                int textHeight = bounds.height();
                canvas.drawRect(
                        left,
                        top,
                        left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                        top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                        textBackgroundPaint
                );
                canvas.drawText(drawableText, left, top + bounds.height(), textPaint);
            }
        }
    }

    public void setResults(List<BoundingBox> boundingBoxes) {
        results = boundingBoxes;
        invalidate();
    }
}