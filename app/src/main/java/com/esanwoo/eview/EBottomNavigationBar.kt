package com.esanwoo.eview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MenuInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.viewpager2.widget.ViewPager2
import com.airbnb.lottie.LottieAnimationView
import java.io.InputStream

typealias OnItemSelected = (index: Int) -> Unit

@SuppressLint("RestrictedApi")
class EBottomNavigationBar(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
    ViewGroup(context, attrs, defStyleAttr) {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    private val TAG = "LottieBottomNavigationB"
    private val titles = ArrayList<String>()
    private val originalIcons = ArrayList<Drawable>()
    private val itemTvs = ArrayList<TextView>()
    private val itemIvs = ArrayList<LottieAnimationView>()

    private val ivTvMargin = 0      //icon和title之间的间距
    private var normalColor: Int
    private var selectedColor: Int
    private val defaultSelectedColor: Int
    private var iconSize: Float
    private var showTopBoundary: Boolean
    private var itemGradient: Boolean   //item是否渐变效果
    private var iconScaleable = true    //todo 该属性加入xml属性配置
    private var iconScaleValue = 1.5f     //todo 该属性加入xml属性配置

    //    private var needIconChangeColor = true
    private var isLottieIcon: Boolean = false
    private var isOriginalIcons = true

    private var vpPositionOffset = 0f
    private var iconMeasureSize = 0
    private var selectedIndex = 0

    private var topBoundaryPaint: Paint
    private val bezierPath by lazy { Path() }

    private var onItemSelected: OnItemSelected? = null
    private var viewPager: ViewPager2? = null

    /**
     * @see pageChangeByBnv
     *  页面切换是否来自于点击底部导航栏
     *  该字段的作用是防止以下现象：
     *      选中导航栏的某一项需要vp自动切换到对应的页面，滑动vp切换页面时也需要bnv自动选中到对应的一项，
     *  所以需要设置bnv的OnItemSelectedListener，在其中调用切换vp页面的代码；设置vp的OnPageChangeCallback，在其中设置选中bnv项的代码，
     *  这就会出现选中导航栏某一项后会触发bnv的OnItemSelected，其中的切换vp页面操作会触发vp的OnPageChangeCallback，而在OnPageChangeCallback
     *  中的选中bnv项的操作又会触发bnv的OnItemSelected，导致循环触发监听事件的问题
     *      所以需要在bnv的OnItemSelected中判断pageChangeByBnv若为true，说明是选中bnv项触发的，则需要切换vp的页面，若为false，说明是
     *  滑动vp触发OnPageChangeCallback后在其中设置选中bnv项的操作时触发的，那么此时vp页面已经通过滑动切换完了，就不需要再做切换vp页面的操作了
     *      同理在vp的OnPageChangeCallback中也要判断pageChangeByBnv是否为true
     */
    private var pageChangeByBnv = true

    init {
        defaultSelectedColor = context.getSystemPrimaryColor() ?: Color.parseColor("#1aad19")

        val typeArray = context.obtainStyledAttributes(attrs, R.styleable.EBottomNavigationBar)
        normalColor = typeArray.getColor(
            R.styleable.EBottomNavigationBar_itemTextNormalColor,
            Color.DKGRAY
        )
        selectedColor = typeArray.getColor(
            R.styleable.EBottomNavigationBar_itemTextSelectedColor,
            defaultSelectedColor
        )
        showTopBoundary =
            typeArray.getBoolean(R.styleable.EBottomNavigationBar_showTopBoundary, true)
        itemGradient =
            typeArray.getBoolean(R.styleable.EBottomNavigationBar_itemGradient, true)
        iconSize = typeArray.getDimension(
            R.styleable.EBottomNavigationBar_iconSize,
            LayoutParams.WRAP_CONTENT.toFloat()
        )

        if (background == null) setBackgroundColor(Color.WHITE)

        topBoundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = selectedColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        if (typeArray.hasValue(R.styleable.EBottomNavigationBar_menu)) {
            val menu = MenuBuilder(context)
            MenuInflater(context).inflate(
                typeArray.getResourceId(
                    R.styleable.EBottomNavigationBar_menu,
                    0
                ), menu
            )
            if (menu.size() > 0) {
                for (i in 0 until menu.size()) {
                    menu.getItem(i).let {
                        addItem(it.title.toString(), it.icon!!)
                    }
                }
                setSelected(0, true)
            }
        }
        typeArray.recycle()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.actionMasked == MotionEvent.ACTION_UP) {
            val index = event.x / (measuredWidth / itemIvs.size)
            index.toInt().also {
                if (it != selectedIndex) setSelected(it, true)
            }
        }
        return true
    }

    //绘制topBoundary线的凸起时计算专用，这里不能使用selectedIndex，因为onPageSelected方法的特性会导致selectedIndex在vp的page还未滚动完毕时就更新，
    //更新后继续使用vpPositionOffset计算就不对了
    var vpPageScrolledPosition = 0
    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (showTopBoundary)
            if (isOriginalIcons || !iconScaleable)
                canvas?.drawLine(
                    0f,
                    0f,
                    measuredWidth.toFloat(),
                    1f,
                    topBoundaryPaint
                )
            else {
                val iv = itemIvs[vpPageScrolledPosition]
                // TODO: 优化凸起效果
                bezierPath.reset()
                //offset：凸起起点的偏移量。因为topBoundary线的凸起要随着page的滚动而移动，也就是凸起的起点要加上偏移量，值为（当前选中item起点距下一个item起点的距离）* vpPositionOffset
                val offset =
                    (if (vpPageScrolledPosition == itemTvs.size - 1) 0 else itemIvs[vpPageScrolledPosition + 1].left - iv.left) * vpPositionOffset
                val bezierStart = (iv.left.toFloat() - iv.measuredWidth / 2) + offset
                bezierPath.lineTo(bezierStart, 0f)
                val bezierControlY = -(iv.measuredHeight * iconScaleValue - iv.bottom) - 50
                bezierPath.quadTo(
                    iv.left + iv.measuredWidth / 2f + offset,
                    bezierControlY,
                    iv.right + iv.measuredWidth / 2f + offset,
                    0f
                )
                bezierPath.lineTo(measuredWidth.toFloat(), 0f)
                canvas?.drawPath(bezierPath, topBoundaryPaint)
            }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val myWidthMode = MeasureSpec.getMode(widthMeasureSpec)
        val myHeightMode = MeasureSpec.getMode(heightMeasureSpec)
        val myWidthSize = MeasureSpec.getSize(widthMeasureSpec)
        val myHeightSize = MeasureSpec.getSize(heightMeasureSpec)
        var usedWidth = 0
        var usedHeight = 0
        for (index in 0 until childCount step 2) {
            val childIv = getChildAt(index)
            val childTv = getChildAt(index + 1)
            val childIvWidthMeasureSpec: Int
            val childIvHeightMeasureSpec: Int
            val childTvWidthMeasureSpec: Int
            val childTvHeightMeasureSpec: Int
            //测量iv
            when (childIv.layoutParams.width) {
                LayoutParams.MATCH_PARENT -> {
                    childIvWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        myWidthSize - usedWidth,
                        MeasureSpec.EXACTLY
                    )
                }

                LayoutParams.WRAP_CONTENT -> {
                    childIvWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        myWidthSize - usedWidth,
                        MeasureSpec.AT_MOST
                    )
                }

                else -> childIvWidthMeasureSpec =
                    MeasureSpec.makeMeasureSpec(childIv.layoutParams.width, MeasureSpec.EXACTLY)
            }
            when (childIv.layoutParams.height) {
                LayoutParams.MATCH_PARENT -> {
                    childIvHeightMeasureSpec =
                        MeasureSpec.makeMeasureSpec(myHeightSize, MeasureSpec.EXACTLY)
                }

                LayoutParams.WRAP_CONTENT -> {
                    childIvHeightMeasureSpec =
                        MeasureSpec.makeMeasureSpec(myHeightSize, MeasureSpec.AT_MOST)
                }

                else -> childIvHeightMeasureSpec =
                    MeasureSpec.makeMeasureSpec(childIv.layoutParams.height, MeasureSpec.EXACTLY)
            }
            childIv.measure(childIvWidthMeasureSpec, childIvHeightMeasureSpec)


            //测量tv
            when (childTv.layoutParams.width) {
                LayoutParams.MATCH_PARENT -> {
                    childTvWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        myWidthSize - usedWidth,
                        MeasureSpec.EXACTLY
                    )
                }

                LayoutParams.WRAP_CONTENT -> {
                    childTvWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        myWidthSize - usedWidth,
                        MeasureSpec.AT_MOST
                    )
                }

                else -> childTvWidthMeasureSpec =
                    MeasureSpec.makeMeasureSpec(childTv.layoutParams.width, MeasureSpec.EXACTLY)
            }

            when (childTv.layoutParams.height) {
                LayoutParams.MATCH_PARENT -> {
                    childTvHeightMeasureSpec =
                        MeasureSpec.makeMeasureSpec(
                            myHeightSize - childIv.measuredHeight - ivTvMargin,
                            MeasureSpec.EXACTLY
                        )
                }

                LayoutParams.WRAP_CONTENT -> {
                    childTvHeightMeasureSpec =
                        MeasureSpec.makeMeasureSpec(
                            myHeightSize - childIv.measuredHeight - ivTvMargin,
                            MeasureSpec.AT_MOST
                        )
                }

                else -> childTvHeightMeasureSpec =
                    MeasureSpec.makeMeasureSpec(childTv.layoutParams.height, MeasureSpec.EXACTLY)
            }
            childTv.measure(childTvWidthMeasureSpec, childTvHeightMeasureSpec)
            usedWidth += Math.max(
                childIv.measuredWidth,
                childTv.measuredWidth
            )  //每次累加的已用宽度为tv和iv中的大值
            usedHeight =
                Math.max(usedHeight, childIv.measuredHeight + childTv.measuredHeight + ivTvMargin)
        }
        setMeasuredDimension(
            if (myWidthMode == MeasureSpec.EXACTLY) myWidthSize else usedWidth,
            if (myHeightMode == MeasureSpec.EXACTLY) myHeightSize else usedHeight
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val itemWidth = measuredWidth.toFloat() / childCount * 2
        for (i in 0 until childCount step 2) {
            val childIv = getChildAt(i)
            val childTv = getChildAt(i + 1)

            var left = itemWidth * (i / 2) + (itemWidth - childIv.measuredWidth) / 2
            var top =
                (measuredHeight - (childIv.measuredHeight + childTv.measuredHeight + ivTvMargin)) / 2
            var right = left + childIv.measuredWidth
            var bottom = top + childIv.measuredHeight
            childIv.layout(left.toInt(), top, right.toInt(), bottom)


            left = itemWidth * (i / 2) + (itemWidth - childTv.measuredWidth) / 2
            top = childIv.bottom + ivTvMargin
            right = left + childTv.measuredWidth
            bottom = top + childTv.measuredHeight
            childTv.layout(
                left.toInt(),
                top,
                right.toInt(),
                bottom
            ) // NOTE: 2023/7/18 这里注意要用measuredWidth，因为获取的height为0
        }
        iconMeasureSize = getChildAt(0).measuredHeight

        //如果icon可以缩放，那么可能出现放大后超出顶部的情况
        // 为了显示超出顶部的内容需要设置父view的clipChildren为false。如果往上找一个不行就用下面的while循环一层层往上设置
        var v = parent as? ViewGroup
        if (v != null) v.clipChildren = false
//            while (v != null) {
//                v.clipChildren = false
//                v = v.parent as? ViewGroup
//            }
    }

    fun bindViewPager(viewPager2: ViewPager2) {
        requireNotNull(viewPager2) { "viewpager can't be null" }
        viewPager = viewPager2
        viewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                if (pageChangeByBnv)
                    return
                if (position < itemTvs.size - 1) {
                    calTransitionColor(
                        selectedColor,
                        normalColor,
                        positionOffset
                    ).run {
                        if (isOriginalIcons) itemIvs[position].drawable.setTint(this)
                        itemTvs[position].setTextColor(this)
                    }
                    calTransitionColor(
                        normalColor,
                        selectedColor,
                        positionOffset
                    ).run {
                        if (isOriginalIcons) itemIvs[position + 1].drawable.setTint(this)
                        itemTvs[position + 1].setTextColor(this)
                    }
                    if (!isOriginalIcons) {
                        //图标缩放
                        itemIvs[position].scaleX =
                            iconScaleValue - (iconScaleValue - 1) * positionOffset
                        itemIvs[position].scaleY =
                            iconScaleValue - (iconScaleValue - 1) * positionOffset
                        itemIvs[position + 1].scaleX = 1 + (iconScaleValue - 1) * positionOffset
                        itemIvs[position + 1].scaleY = 1 + (iconScaleValue - 1) * positionOffset

                        //由于图标放大后不能侵占文字的空间，所以在放大的同时要往上移动，反之缩小的时候要移回原位
                        itemIvs[position].translationY =
                            -(iconScaleValue - 1) * iconMeasureSize / 2 * (1 - positionOffset)
                        itemIvs[position + 1].translationY =
                            -(iconScaleValue - 1) * iconMeasureSize / 2 * positionOffset
                    }
                    vpPageScrolledPosition = position
                    vpPositionOffset = positionOffset
                    invalidate()
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING)
                    pageChangeByBnv = false
            }

            override fun onPageSelected(position: Int) {
                // NOTE: 该方法不是page滚动完毕才调用，而是当手指抬起的瞬间，vp根据此时的惯性以及位置计算出page是否足以翻页，
                //  如果构成翻页则滚动至下一页并调用该方法，否则恢复至本页初始位置且不调用该方法
                if (pageChangeByBnv)
                    return
                if (isLottieIcon) itemIvs[position].playAnimation()
                updateSelected(position, true)
            }
        })
    }

    private fun updateSelected(index: Int, callOnItemSelected: Boolean) {
        invalidate()
        if (pageChangeByBnv) viewPager?.setCurrentItem(index, false)
        if (callOnItemSelected) onItemSelected?.invoke(index)
        selectedIndex = index
    }

    fun setOnItemSelectedListener(onItemSelected: OnItemSelected) {
        this.onItemSelected = onItemSelected
    }

    fun setSeledted(index: Int) {
        if (index == selectedIndex)
            return
        setSelected(index, true)
    }

    /**
     * @param index Int
     * @param callOnItemSelected Boolean    是否触发item选中事件，由内部皮肤图标切换逻辑调用该方法时传false，外部用户调用该方法传true
     */
    private fun setSelected(index: Int, callOnItemSelected: Boolean) {
        vpPageScrolledPosition =
            index  //点击item切换时需要将vpPageScrolledPosition更新，否则onDraw时顶部边界线的凸起位置不会变
        pageChangeByBnv = true
        if (!isOriginalIcons) {
            itemIvs[selectedIndex].scaleX = 1.0f
            itemIvs[selectedIndex].scaleY = 1.0f
            itemIvs[index].scaleX = iconScaleValue
            itemIvs[index].scaleY = iconScaleValue
            itemIvs[selectedIndex].translationY = 0f
            itemIvs[index].translationY = -(iconScaleValue - 1) * iconMeasureSize / 2
            if (isLottieIcon) itemIvs[index].playAnimation()
        } else {
            itemIvs[selectedIndex].drawable.setTint(normalColor)
            itemIvs[index].drawable.setTint(selectedColor)
        }
        itemTvs[selectedIndex].setTextColor(normalColor)
        itemTvs[index].setTextColor(selectedColor)

        updateSelected(index, callOnItemSelected)
    }

    fun getSelectedIndex() = selectedIndex

    private fun replaceItem() {
        // TODO: replace item's title and icon
    }

    fun addItem(title: String, icon: Drawable) {
        LottieAnimationView(context).apply {
            layoutParams =
                LayoutParams(iconSize.toInt(), iconSize.toInt())
            setImageDrawable(icon)
        }.also {
            addView(it)
            itemIvs.add(it)
        }

        TextView(context).apply {
            layoutParams =
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setText(title)
            setTextColor(normalColor)
        }.also {
            addView(it)
            itemTvs.add(it)
        }
        titles.add(title)
        originalIcons.add(icon)
    }

    fun setNormalcolor(color: Int) {
        normalColor = color
        updateColor(false)
    }

    fun setSelectedcolor(color: Int) {
        selectedColor = color
        updateColor(true)
    }

    fun enableIconGradientEffect(enable: Boolean) {
        // TODO: 图标是否开启渐变效果
        itemGradient = enable
    }

    /**
     * @param selected Boolean 设置的是否是选中状态颜色，如果是则只需将选中状态的颜色更新即可，否则需遍历item将非选中状态的颜色更新
     */
    private fun updateColor(selected: Boolean) {
        topBoundaryPaint.color = selectedColor
        invalidate()  //这个是为了刷新导航栏顶部的线的颜色
        if (selected) {
            if (isOriginalIcons) itemIvs[selectedIndex].drawable.setTint(selectedColor)
            itemTvs[selectedIndex].setTextColor(selectedColor)
        } else {
            for (i in 0 until itemTvs.size) {
                if (i == selectedIndex)
                    continue
                if (isOriginalIcons) itemIvs[i].drawable.setTint(normalColor)
                itemTvs[i].setTextColor(normalColor)
            }
        }
    }

    fun modifyLottieIcons(lotties: ArrayList<InputStream>) {
        // TODO: 返回从lottie动画中提取的主色调颜色
        for (i in 0 until lotties.size) {
            itemIvs[i].run {
                setImageDrawable(null)
                setAnimation(lotties[i], null)
                progress = 1f
            }
        }
        originalIcons[selectedIndex].setTint(normalColor)   //防止出现如下问题：比如默认图标选中2，切换为皮肤图标，选中5，再换回默认图标，这时2和5的图标都为选中色
        isLottieIcon = true
        isOriginalIcons = false
        setSelected(selectedIndex, false)
    }

    fun modifyLottieIconsFromRawId(lottiesRawIds: ArrayList<Int?>) {
        // TODO: 返回从lottie动画中提取的主色调颜色
        for (i in 0 until lottiesRawIds.size) {
            lottiesRawIds[i]?.let {
                itemIvs[i].run {
                    setImageDrawable(null)
                    setAnimation(it)
                    progress = 1f
                }
            }
        }
        originalIcons[selectedIndex].setTint(normalColor)   //防止出现如下问题：比如默认图标选中2，切换为皮肤图标，选中5，再换回默认图标，这时2和5的图标都为选中色
        isLottieIcon = true
        isOriginalIcons = false
        setSelected(selectedIndex, false)
    }

    private fun modifyIcon(icon: Drawable, index: Int) {
        // TODO: 修改单个item的图标待完善
        itemIvs[index].setImageDrawable(icon)
        isOriginalIcons = false
    }

    fun modifyStyleIconsFromDrawable(iconDrawables: ArrayList<Drawable>) {
        for (i in 0 until iconDrawables.size) {
            itemIvs[i].run {
                if (isLottieIcon) cancelAnimation()
                setImageDrawable(iconDrawables[i])
            }
        }
        originalIcons[selectedIndex].setTint(normalColor)
        isLottieIcon = false
        isOriginalIcons = false
        setSelected(selectedIndex, false)
    }

    fun modifyStyleIconsFromResId(iconResIds: ArrayList<Int>) {
        val drawables = ArrayList<Drawable>()
        iconResIds.forEach {
            drawables.add(context.resources.getDrawable(it, null))
        }
        modifyStyleIconsFromDrawable(drawables)
    }

    /**
     * 还原成xml中设置的menu中的图标
     */
    fun restoreMenuIcon() {
        if (!isOriginalIcons) {
            for (i in 0 until itemTvs.size) {
                itemIvs[i].run {
                    if (isLottieIcon) cancelAnimation()
                    setImageDrawable(originalIcons[i])
                }
            }
            itemIvs[selectedIndex].scaleX = 1.0f
            itemIvs[selectedIndex].scaleY = 1.0f
            itemIvs[selectedIndex].translationY = 0f
            isLottieIcon = false
            isOriginalIcons = true
//            setSelected(selectedIndex, false)
        }
    }

    fun recycle() {
        viewPager = null
    }
}

fun Context.getSystemPrimaryColor(): Int? {
    val typedValue = TypedValue()
    if (theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true))
        return typedValue.data
    else return null
}

private fun calTransitionColor(startColor: Int, endColor: Int, percent: Float): Int {
    val startHsv = FloatArray(3)
    val endHsv = FloatArray(3)
    val outHsv = FloatArray(3)
    Color.colorToHSV(startColor, startHsv)
    Color.colorToHSV(endColor, endHsv)

    // 计算当前动画完成度（percent）所对应的颜色值
    if (endHsv[0] - startHsv[0] > 180) {
        endHsv[0].let {
            endHsv.set(0, it - 360f)
        }
    } else if (endHsv[0] - startHsv[0] < -180) {
        endHsv[0].let {
            endHsv.set(0, it + 360f)
        }
    }
    outHsv[0] = startHsv[0] + (endHsv[0] - startHsv[0]) * percent
    if (outHsv[0] > 360) {
        outHsv[0].let {
            outHsv.set(0, it - 360)
        }
    } else if (outHsv[0] < 0) {
        outHsv[0].let {
            outHsv.set(0, it + 360)
        }
    }
    outHsv[1] = startHsv[1] + (endHsv[1] - startHsv[1]) * percent
    outHsv[2] = startHsv[2] + (endHsv[2] - startHsv[2]) * percent

    // 计算当前动画完成度（fraction）所对应的透明度
    val alpha =
        startColor shr 24 + ((endColor shr 24 - startColor shr 24) * percent).toInt()

    return Color.HSVToColor(alpha, outHsv)
}