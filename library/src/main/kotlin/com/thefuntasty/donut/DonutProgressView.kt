package com.thefuntasty.donut

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import com.thefuntasty.donut.extensions.sumByFloat

/*
Ideas:
- tooling in layout editor (testing data)
- turn on / off corner path effect with configurable number of sides
 */
class DonutProgressView @JvmOverloads constructor(
    context: Context,
    private val attrs: AttributeSet? = null,
    private val defStyleAttr: Int = 0,
    private val defStyleRes: Int = 0
) : View(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        private const val ANIM_DURATION_MS = 1000L // TODO parametrize
        private const val INTERPOLATOR_FACTOR = 1.5f // TODO parametrize
    }

    private var w = 0
    private var h = 0
    private var xPadd = 0f
    private var yPadd = 0f

    private var radius = 0f
    private var centerX = 0f
    private var centerY = 0f

    /**
     * Percentage of progress shown for all lines.
     *
     * Eg. when one line has 50% of total graph length,
     * setting this to 0.5f will result in that line being animated to 25% of total graph length.
     */
    var masterProgress: Float = 1f
        set(value) {
            if (value !in (0f..1f)) {
                return
            }

            field = value

            bgLine.masterProgress = value
            lines.forEach { it.masterProgress = value }
            invalidate()
        }

    /**
     * Stroke width of all lines in pixels.
     */
    var strokeWidth = 10f
        set(value) {
            field = value

            bgLine.lineStrokeWidth = value
            lines.forEach { it.lineStrokeWidth = value }
            updateLinesRadius()
            invalidate()
        }

    /**
     * Maximum value of sum of all entries in view, after which
     * all lines start to resize proportionally to amounts in their entry categories.
     */
    var cap: Float = 10f
        set(value) {
            field = value
            resolveState()
        }

    /**
     * Color of background line.
     */
    @ColorInt
    var bgLineColor: Int = 0
        set(value) {
            field = value
            bgLine.lineColor = value
            invalidate()
        }

    /**
     * Size of gap opening in degrees.
     */
    var gapWidthDegrees: Float = 45f
        set(value) {
            field = value

            bgLine.gapWidthDegrees = value
            lines.forEach { it.gapWidthDegrees = value }
            invalidate()
        }

    /**
     * Angle in degrees, at which the gap will be displayed.
     */
    var gapAngleDegrees: Float = 270f
        set(value) {
            field = value

            bgLine.gapAngleDegrees = value
            lines.forEach { it.gapAngleDegrees = value }
            invalidate()
        }

    private val data = mutableListOf<DonutProgressEntry>()
    private val lines = mutableListOf<DonutProgressLine>()
    private var animatorSet: AnimatorSet? = null

    private val bgLine = DonutProgressLine(
        category = "_bg",
        _radius = radius,
        _lineColor = bgLineColor,
        _lineStrokeWidth = strokeWidth,
        _masterProgress = masterProgress,
        _length = 1f,
        _gapWidthDegrees = gapWidthDegrees,
        _gapAngleDegrees = gapAngleDegrees
    )

    init {
        obtainAttributes()
    }

    @SuppressLint("Recycle")
    private fun obtainAttributes() {
        context.obtainStyledAttributes(
            attrs,
            R.styleable.DonutProgressView,
            defStyleAttr,
            defStyleRes
        ).use {
            strokeWidth = it.getDimensionPixelSize(
                R.styleable.DonutProgressView_donut_strokeWidth,
                dpToPx(10)
            ).toFloat()
            bgLineColor =
                it.getColor(
                    R.styleable.DonutProgressView_donut_bgLineColor,
                    ContextCompat.getColor(
                        context,
                        R.color.grey
                    )
                )

            gapWidthDegrees = it.getFloat(R.styleable.DonutProgressView_donut_gapWidth, 45f)
            gapAngleDegrees = it.getFloat(R.styleable.DonutProgressView_donut_gapAngle, 270f)
        }
    }

    /**
     * Returns current data.
     */
    fun getData() = data.toList()

    /**
     * TODO
     */
    fun setColor(category: String, @ColorInt color: Int) {

    }

    /**
     * Increases amount in [category] by specified [amount].
     *
     * If [category] does not yet exist in the view, new progress line with specified [color] will be created. If there is no
     * color defined, progress line will have default color. (TODO app's accent color).
     *
     * If [category] already exists and [color] is specified, it's progress line will be updated with that color.
     */
    fun addAmount(category: String, amount: Float, @ColorInt color: Int? = null) {
        require(amount >= 0f) { "Provided amount is less than zero" }

        if (hasEntriesForCategory(category)) {
            val entryIndex = data.indexOfFirst { it.category == category }
            val newColor = color ?: data[entryIndex].color

            data[entryIndex] = data[entryIndex].copy(
                amount = data[entryIndex].amount + amount,
                color = newColor
            )

            lines.first { it.category == category }.lineColor = newColor
            submitData(data)
        } else {
            val newEntry = DonutProgressEntry(
                category = category,
                amount = amount,
                color = color ?: ContextCompat.getColor(context, R.color.data_color_default)
            )

            submitData(data + newEntry)
        }
    }

    /**
     * Decreases amount in [category] by specified [amount].
     * If resulting amount is equal to, or less than zero, it's corresponding progress line will be removed from view.
     */
    fun removeAmount(category: String, amount: Float) {
        require(amount >= 0f) { "Provided amount is less than zero" }

        if (hasEntriesForCategory(category)) {
            val entryIndex = data.indexOfFirst { it.category == category }
            val resultAmount = data[entryIndex].amount - amount
            if (resultAmount <= 0f) {
                data.removeAt(entryIndex)
            } else {
                data[entryIndex] = data[entryIndex].copy(amount = data[entryIndex].amount - amount)
            }

            submitData(data)
        }
    }

    /**
     * Sets [amount] for specified [category].
     * If amount is equal, or less than zero, it's corresponding progress line will be removed from view.
     */
    fun setAmount(category: String, amount: Float) {
        if (hasEntriesForCategory(category)) {
            val entryIndex = data.indexOfFirst { it.category == category }
            if (amount <= 0f) {
                data.removeAt(entryIndex)
            } else {
                data[entryIndex] = data[entryIndex].copy(amount = amount)
            }

            submitData(data)
        }
    }

    /**
     * Submits new [entries] to the view.
     *
     * New progress line will be created for each non-existent entry category and view will be animated to new state.
     * Entries with the same category will be internally merged into single entry.
     * Additionally, existing lines with no entry category in new data set will be removed when animation completes.
     */
    fun submitData(entries: List<DonutProgressEntry>) {
        val mergedEntries = entries
            .filter { it.amount >= 0f }
            .groupBy { it.category }
            .flatMap {
                listOf(
                    DonutProgressEntry(
                        category = it.value.first().category,
                        amount = it.value.sumByFloat { it.amount },
                        color = it.value.first().color
                    )
                )
            }

        mergedEntries
            .groupBy { it.category }
            .forEach { kvp ->
                if (hasEntriesForCategory(kvp.key).not()) {
                    lines.add(
                        index = 0,
                        element = DonutProgressLine(
                            category = kvp.value[0].category,
                            _radius = radius,
                            _lineColor = kvp.value[0].color,
                            _lineStrokeWidth = strokeWidth,
                            _masterProgress = masterProgress,
                            _length = 0f,
                            _gapWidthDegrees = gapWidthDegrees,
                            _gapAngleDegrees = gapAngleDegrees
                        )
                    )
                }
            }

        this.data.apply {
            clear()
            addAll(mergedEntries)
        }

        resolveState()
    }

    fun clear() = submitData(listOf())

    private fun resolveState() {
        animatorSet?.cancel()
        animatorSet = AnimatorSet()

        val amounts = lines.map { getAmountForCategory(it.category) }
        val totalAmount = data.sumByFloat { it.amount }

        val drawPercentages = amounts.mapIndexed { index, _ ->
            if (totalAmount > cap) {
                getDrawAmountForLine(amounts, index) / totalAmount
            } else {
                getDrawAmountForLine(amounts, index) / cap
            }
        }

        drawPercentages.forEachIndexed { index, newPercentage ->
            val line = lines[index]
            val animator = animateLine(line, newPercentage) {
                if (!hasEntriesForCategory(line.category)) {
                    removeLine(line)
                }
            }

            animatorSet?.play(animator)
        }

        animatorSet?.start()
    }

    private fun getAmountForCategory(category: String): Float {
        return data.filter { it.category == category }.sumByFloat { it.amount }
    }

    private fun getDrawAmountForLine(amounts: List<Float>, index: Int): Float {
        if (index >= amounts.size) {
            return 0f
        }

        val thisLine = amounts[index]
        val previousLine = getDrawAmountForLine(amounts, index + 1) // Length of line above this one

        return thisLine + previousLine
    }

    private fun hasEntriesForCategory(category: String) =
        data.indexOfFirst { it.category == category } > -1

    private fun animateLine(
        line: DonutProgressLine,
        to: Float,
        animationEnded: (() -> Unit)? = null
    ): ValueAnimator {
        return ValueAnimator.ofFloat(line.length, to).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator(INTERPOLATOR_FACTOR)
            addUpdateListener {
                line.length = it.animatedValue as Float
                invalidate()
            }

            doOnEnd {
                animationEnded?.invoke()
            }
        }
    }

    private fun removeLine(line: DonutProgressLine) {
        lines.remove(line)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        this.w = w
        this.h = h

        this.xPadd = (paddingLeft + paddingRight).toFloat()
        this.yPadd = (paddingTop + paddingBottom).toFloat()

        this.centerX = w / 2f
        this.centerY = h / 2f

        updateLinesRadius()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val originalWidth = MeasureSpec.getSize(widthMeasureSpec)

        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(originalWidth, MeasureSpec.EXACTLY)
        )
    }

    private fun updateLinesRadius() {
        val ww = w.toFloat() - xPadd
        val hh = h.toFloat() - yPadd
        this.radius = Math.min(ww, hh) / 2f - strokeWidth / 2f

        bgLine.radius = radius
        lines.forEach { it.radius = radius }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.translate(centerX, centerY)

        bgLine.draw(canvas)
        lines.forEach { it.draw(canvas) }
    }

    // region helpers

    private fun dpToPx(dp: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        resources.displayMetrics
    ).toInt()

    // endregion
}
