package com.example.k15puzzle

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_15puzzle.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.roundToLong

class Activity15Puzzle : AppCompatActivity() {

	enum class Mode {
		Game, GameOver, JustShuffled, PuzzleMatched
	}

	var tiles = arrayOfNulls<Button?>(0)
	var tileSize: Long = 0
	var tileSpacing: Long = 0
	var spaceX: Long = 0
	var spaceY: Long = 0

	var lastResizeTime: Long = 0
	var lastTapTime: Long = 0
	var closingAnimation = false
	var timeRemaining = 0
	var panelDebugMaximumHeight = 0
	var resizeCount = 0

	var handler = Handler()

	private val randomGen = Random()

	val maxMoveAniDuration = 150f
	val minMoveAniDuration = 1f

//	OnClickListener changed to OnTouchListener, for tiles move immediately after touch, instead of after release finger
//	var tileClickListener =
//		View.OnClickListener { sender -> OnTilePressed(sender) }

	var tileTouchListener = OnTouchListener { sender, event ->
		val value = super.onTouchEvent(event)
		if (event.action == MotionEvent.ACTION_DOWN) {
			onTilePressed(sender)
			return@OnTouchListener true
		}
		value
	}

	var linear: TimeInterpolator = LinearInterpolator()
	var inBack: TimeInterpolator = PathInterpolator(0.6f, -0.28f, 0.735f, 0.045f)
	var outBack: TimeInterpolator = PathInterpolator(0.175f, 0.885f, 0.32f, 1.275f)
	var outExpo: TimeInterpolator = PathInterpolator(0.19f, 1f, 0.22f, 1f)
	var panelDebugVisible = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_15puzzle)

		lastResizeTime =	System.currentTimeMillis() //To prevent resize on start on Android

		panelClient.viewTreeObserver.addOnGlobalLayoutListener { panelClientResize() }

// Logging has been added to debug animation delays, now commented out
//		Log.d("onCreate", "Thread.currentThread()=${Thread.currentThread()}")

		base = 4

		panelClient.setOnLongClickListener()  { panelClientOnLongClick(it) }
	}

	private var mode: Mode? = null
	set(value)
//	fun setMode(value: Mode)  // was changed from function to field setter
	{
		field = value
		if (mode == Mode.Game)
			handler.postDelayed(timerTimeRunnable, 1000)
		else
			handler.removeCallbacks(timerTimeRunnable)
	}


	fun buttonBaseOnClick(sender: View) {
		val senderButton = sender as Button
		val lBase = senderButton.text[0].toString().toInt()
		base = lBase
	}

	private var base = 0
	set(value)
//	fun setBase(value: Int)
	{
		if (value == base)
		{
			animateBaseNotChanged()
			return
		}
		mode = Mode.GameOver
		animateTilesDisappeare()
		field = value
		val delay = if (tiles.size > 0) (520 + 30L * tiles.size) else (200L)
		handler.postDelayed(timerCreateTilesRunnable, delay)
	}

	var timerCreateTilesRunnable = Runnable { timerCreateTilesTimer() }
	fun timerCreateTilesTimer() {
		createTiles()
		setMaxTime()
		animatePrepareBeforePlace()
		animatePlaceTilesFast()
	}

	fun createTiles() {
		for (i in tiles.indices)
		if (tiles[i] != null) {
			(tiles[i]!!.parent as ViewGroup).removeView(tiles[i])
			tiles[i] = null
		}
		tiles = arrayOfNulls(base * base)
		for (i in 0 until tiles.size - 1)
		if (tiles[i] == null) {
			val newTile = Button(this)

			newTile.setOnTouchListener(tileTouchListener)

			newTile.text = (i + 1).toString()

			val colorTileNormal1 = resources.getColor(R.color.colorTileNormal1)
			val colorTileNormal2 = resources.getColor(R.color.colorTileNormal2)

// Animating background of tile, one color of gradient
			val colorAnimation = ValueAnimator()
			colorAnimation.addUpdateListener { animator ->
				val color = animator.animatedValue as Int
				val gradientDrawable = newTile.background as GradientDrawable
				gradientDrawable.colors = intArrayOf(colorTileNormal1, color)

				newTile.setTag(R.id.tileCurColor, color)
			}

			newTile.setTag(R.id.tileColorAnimation, colorAnimation)
			newTile.setTag(R.id.tileCurColor, colorTileNormal2)

			val gradientDrawable = GradientDrawable(
				GradientDrawable.Orientation.TL_BR, intArrayOf(colorTileNormal1, colorTileNormal2))
			gradientDrawable.cornerRadius = 4f
			gradientDrawable.setStroke(8, resources.getColor(R.color.colorTileStroke))

			newTile.setBackground(gradientDrawable)

			newTile.layoutParams = ViewGroup.LayoutParams(100, 100)
			panelClient?.addView(newTile)

			tiles[i] = newTile
		}

		if (tiles[tiles.size - 1] != null)
			tiles[tiles.size - 1] = null
	}

	fun ind(row: Int, col: Int): Int {
		return row * base + col
	}


	fun actualPosition(tile: Button?): Int {
		for (i in tiles.indices)
			if (tiles[i] === tile) return i
		return 0
	}


	fun onTilePressed(sender: View)  = GlobalScope.async() {
		val senderTile = sender as Button
		if (mode == Mode.JustShuffled)
			mode = Mode.Game
		val wasMoved: Boolean =
			tryMoveTile(actualPosition(senderTile), maxMoveAniDuration, waitAnimationEnd = false).await()
		if (wasMoved)
			runOnUiThread {
				checkPuzzleMatched()
			}
	}


	fun tryMoveTile(tilePosition: Int, moveAniDuration: Float, waitAnimationEnd: Boolean):
			Deferred<Boolean> = GlobalScope.async() {

		fun moveTile(oldPosition: Int, newPosition: Int) = GlobalScope.async() {
			val temp = tiles[newPosition]
			tiles[newPosition] = tiles[oldPosition]
			tiles[oldPosition] = temp

			val tileNewPos = tiles[newPosition]
			if (tileNewPos != null)
				animateMoveTile(tileNewPos, moveAniDuration, waitAnimationEnd).await()
		}

		var wasMoved = false
		val colPressed = tilePosition % base
		val rowPressed = tilePosition / base
		for (row in 0 until base)
			if (tiles[ind(row, colPressed)] == null) {
				if (row > rowPressed) //Move tiles down
					for (rowToMove in row - 1 downTo rowPressed) {
						moveTile(ind(rowToMove, colPressed), ind(rowToMove + 1, colPressed)).await()
						wasMoved = true
					}
				if (rowPressed > row) //Move tiles up
					for (rowToMove in row + 1..rowPressed) {
						moveTile(ind(rowToMove, colPressed), ind(rowToMove - 1, colPressed)).await()
						wasMoved = true
					}
			}
		if (!wasMoved)
			for (col in 0 until base)
			if (tiles[ind(rowPressed, col)] == null) {
				if (col > colPressed) //Move tiles right
					for (colToMove in col - 1 downTo colPressed) {
						moveTile(ind(rowPressed, colToMove), ind(rowPressed, colToMove + 1)).await()
						wasMoved = true
					}
				if (colPressed > col) //Move tiles left
					for (colToMove in col + 1..colPressed) {
						moveTile(ind(rowPressed, colToMove), ind(rowPressed, colToMove - 1)).await()
						wasMoved = true
					}
			}

		wasMoved

	}


	fun animateMoveTile(tile: Button, moveAniDuration: Float, waitAnimationEnd: Boolean)
			: Deferred<Unit> {
		val actPos = actualPosition(tile)
		val newCol = actPos % base
		val newRow = actPos / base
		val offsetOnScaledTile = (tileSize - tile.layoutParams.width) / 2.0f
		val x = spaceX + Math.round(newCol * (tileSize + tileSpacing) + offsetOnScaledTile)
		val y = spaceY + Math.round(newRow * (tileSize + tileSpacing) + offsetOnScaledTile)

		runOnUiThread {
			if (moveAniDuration > 0) {
				tile.animate().translationX(x.toFloat()).translationY(y.toFloat())
					.setDuration(moveAniDuration.toLong()).setStartDelay(0).setInterpolator(outExpo)
					.start()
			} else {
				tile.translationX = x.toFloat()
				tile.translationY = y.toFloat()
			}
		}

		val deferred = CompletableDeferred<Unit>()

		if (waitAnimationEnd && (moveAniDuration > 0)) {
			tile.animate().withStartAction {
//				val curTime = System.currentTimeMillis()
//				val timeFromLastLog = curTime - lastLogTime
//				lastLogTime = curTime
//				Log.d("Shuffle", "Time=${sdf.format(Date(curTime))}; Diff=$timeFromLastLog; AniStart; moveAniDuration=$moveAniDuration;")
			}
			.withEndAction {
//				val curTime = System.currentTimeMillis()
//				val timeFromLastLog = curTime - lastLogTime
//				lastLogTime = curTime
//				Log.d("Shuffle", "Time=${sdf.format(Date(curTime))}; Diff=$timeFromLastLog; AniEnd  ; moveAniDuration=$moveAniDuration;")
				deferred.complete(Unit)
			}
		}
		else
			deferred.complete(Unit)

		return deferred
	}


	fun checkPuzzleMatched() {
		var puzzleMatched = true
		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val textNumber = tile.text.toString().toInt()
				if (textNumber - 1 != actualPosition(tiles[i])) {
					puzzleMatched = false
					break
				}
			}

		if (puzzleMatched && mode == Mode.Game) {
			mode = Mode.PuzzleMatched
			animatePuzzleMatched()
		}

		if (!puzzleMatched && (mode == Mode.PuzzleMatched || mode == Mode.JustShuffled)) {
			animateNormalizeTilesColor()
			if (mode == Mode.PuzzleMatched)
				mode = Mode.GameOver
		}
	}

// For logging
//	val sdf = SimpleDateFormat("HH:mm:ss.SSS")
//	var lastLogTime: Long = 0


	fun buttonShuffleOnClick(sender: View?) = GlobalScope.async() {

//		Log.d("Shuffle", "Thread.currentThread()=${Thread.currentThread()}")
//		Log.d("Shuffle", "Looper.getMainLooper().thread=${Looper.getMainLooper().thread}")

		runOnUiThread {
			animateNormalizeTilesColor()
		}
		var newI = 0
		val moveCount = tiles.size * tiles.size
		var moveAniDuration = maxMoveAniDuration

//		val timeShuffleStart = System.currentTimeMillis()
//		lastLogTime = timeShuffleStart
//		Log.d("Shuffle", "start. moveCount=$moveCount")

		for (i in 1..moveCount) {
			if (i <= 10)
				moveAniDuration = minMoveAniDuration + maxMoveAniDuration * (1 - i / 10.0f)
			if (i >= moveCount - 10)
				moveAniDuration = minMoveAniDuration + maxMoveAniDuration / 2 * (1 - (moveCount - i) / 10.0f)
			if (i > 20 && i < moveCount - 20)
				moveAniDuration =	if (i % 10 == 0) minMoveAniDuration else 0f

			var wasMoved: Boolean
//			var timeWasMoved = System.currentTimeMillis()
			do {
				newI = randomGen.nextInt(tiles.size)
				wasMoved = tryMoveTile(newI, moveAniDuration, true).await()

//				if (wasMoved) { // For debugging, commented out
//					val curTime = System.currentTimeMillis()
//					val MoveDuration = curTime - timeWasMoved
//					val timeFromLastLog = curTime - lastLogTime
//					Log.d("Shuffle", "Time=${sdf.format(Date(curTime))}; Diff=$timeFromLastLog; Move=$i; moveAniDuration=$moveAniDuration; MoveDuration=$MoveDuration;")
//					timeWasMoved = curTime
//					lastLogTime = curTime
//				}

			} while (!wasMoved)
		}

//		val timeShuffle_ms = System.currentTimeMillis() - timeShuffleStart
//		Log.d("Shuffle","End. duration=$timeShuffle_ms; OneMove=${timeShuffle_ms/moveCount.toFloat()}")

		runOnUiThread {
			setMaxTime()
			//  stopBlinkShuffle(); // Blinking of buttonShuffle is not realized
			mode = Mode.JustShuffled
			checkPuzzleMatched()
		}
	}

	var timerTimeRunnable = Runnable { timerTimeTimer() }
	fun timerTimeTimer() {
//		Log.d("Timer", "timerTimeTimer")
		timeRemaining = timeRemaining - 1
		val sec = timeRemaining % 60
		val min = timeRemaining / 60
		textTime.text = String.format("%1\$d:%2$02d", min, sec)
		if (timeRemaining == 0) {
			mode = Mode.GameOver
			animateTimeOver()
			//		startBlinkShuffle();
			return
		}

		if (timeRemaining <= 10)
			animateTimeRunningOut()

		if (mode == Mode.Game) {
			handler.postDelayed(timerTimeRunnable, 1000)
//			Log.d("Timer", "timerTime.postDelayed(timerTimeRunnable, 1000) in timerTimeTimer")
		}
	}

	fun setMaxTime() {
		timeRemaining = base * base * base * base / 20 * 10
		val sec = timeRemaining % 60
		val min = timeRemaining / 60
		textTime.text = String.format("%1\$d:%2$02d", min, sec)
	}


	fun panelClientResize() {
		handler.removeCallbacks(timerResizeRunnable)
		handler.postDelayed(timerResizeRunnable, 200)
	}


	var timerResizeRunnable = Runnable { timerResizeTimer() }
	fun timerResizeTimer() {
		handler.removeCallbacks(timerResizeRunnable)
		val timeFromLastResize_ms = System.currentTimeMillis() - lastResizeTime
		if (timeFromLastResize_ms > 1000)
		{
			animatePlaceTilesFast()
			lastResizeTime = System.currentTimeMillis()
		}
	}

	override fun onBackPressed() {
		if (!closingAnimation) {
			closingAnimation = true
			animateTilesDisappeare()
			return
		}
		finish()
	}

//-------------------------------   Animations   -----------------------------

	fun calcConsts() {
		val height = panelClient.measuredHeight
		val width = panelClient.measuredWidth
		if (height > width)
		{
			spaceX = (width / 20f).roundToLong()
			tileSize = ((width - spaceX * 2f) / base).roundToLong()
			spaceY = spaceX + ((height - width).toFloat() / 2f).roundToLong()
		} else
		{
			spaceY = (height / 20f).roundToLong()
			tileSize = ((height - spaceY * 2f) / base).roundToLong()
			spaceX = spaceY + ((width - height) / 2f).roundToLong()
		}
		tileSpacing = (tileSize * 0.06).roundToLong()
		tileSize = (tileSize * 0.94).roundToLong()
		spaceX = spaceX + (tileSpacing / 2f).roundToLong()
		spaceY = spaceY + (tileSpacing / 2f).roundToLong()
	}

	fun animatePlaceTilesFast() {
		calcConsts()
//		Log.d("Animate", "PlaceTilesFast")

		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val delay = 30L * i //delay for each tile is increasing
				val col = i % base
				val row = i / base
				val width = tile.layoutParams.width
				val height = tile.layoutParams.height
//				Log.d("Animate", String.format("width=%d, height=%d", width, height))
				val scaleX = tileSize.toFloat() / width
				val scaleY = tileSize.toFloat() / height
				val offsetOnScaledTile = (tileSize - width) / 2.0f
				val x = spaceX + Math.round(col * (width * scaleX + tileSpacing) + offsetOnScaledTile)
				val y = spaceY + Math.round(row * (height * scaleY + tileSpacing) + offsetOnScaledTile)

// Changed to animate each property separately, because they have different settings (e.g. delay)
				animateFloatDelay(tile, "scaleX", scaleX, 200, 200 + delay)
				animateFloatDelay(tile, "scaleY", scaleY, 200, 100 + delay)
				animateFloatDelay(tile, "translationX", x.toFloat(), 200, delay)
				animateFloatDelay(tile, "translationY", y.toFloat(), 100, delay)
			}
	}

	fun animateTilesDisappeare() {
		var lastTile: Button? = null
		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val delay = 30L * i
				val x = Math.round(tile.translationX + tileSize / 2.0)
				val y = Math.round(tile.translationY + tileSize).toLong()
// Changed to animate all properties together, because they have same settings (e.g. duration, delay, interpolator)
				tile.animate().scaleX(0.1f).scaleY(0.1f)
					.rotation(45.0f).alpha(0f)
					.translationX(x.toFloat()).translationY(y.toFloat())
					.setDuration(400).setStartDelay(delay).setInterpolator(inBack)
				lastTile = tile
			}

//		Log.d("closingAnimation", " = " + ((Boolean)closingAnimation).toString() +
//				" lastTile =" + ((lastTile == null)? "null": lastTile.toString())
//		);
		if (closingAnimation && lastTile != null)
			lastTile.animate().withEndAction {
				finish()
			}
	}

	fun animatePrepareBeforePlace() {
		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val scaleX = tileSize.toFloat() / tile.layoutParams.width
				val scaleY = tileSize.toFloat() / tile.layoutParams.height
				val col = i % base
				val row = i / base
				val x = spaceX + Math.round(col * (tile.layoutParams.width * scaleX + tileSpacing))
				val y = spaceY + Math.round(row * (tile.layoutParams.height * scaleY + tileSpacing))
				tile.scaleX = 0.5f
				tile.scaleY = 0.5f
				tile.alpha = 0f
				tile.rotation = 45.0f
				tile.translationX = x + Math.round(tileSize / 2.0).toFloat()
				tile.translationY = y + tileSize.toFloat()
			}

		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val delay = 30L * i

//				tile.animate().rotation(0).alpha(1)
//						.setDuration(200).setStartDelay(delay).setInterpolator(linear);
				animateFloatDelay(tile, "rotation", 0f, 400, delay)
				animateFloatDelay(tile, "alpha", 1f, 400, 100 + delay)
			}
	}

	fun animateBaseNotChanged() {
		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val delay = 30L * i
				val origScaleX = tile.scaleX
				val origScaleY = tile.scaleY
				animateFloatDelay(tile, "scaleX", origScaleX / 2.0f, 300, delay, inBack)
				animateFloatDelay(tile, "scaleY", origScaleY / 2.0f, 300, delay, inBack)
				animateFloatDelay(tile, "scaleX", origScaleX, 300, 350 + delay, outBack)
				animateFloatDelay(tile, "scaleY", origScaleY, 300, 350 + delay, outBack)
			}
	}


	fun animatePuzzleMatched() {
		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val delay = 30L * i
				animateFloatDelay(tile, "rotation", 360f, 1000, 350, outBack)

				val colorAnimation = tile.getTag(R.id.tileColorAnimation) as ValueAnimator
				val colorFrom = tile.getTag(R.id.tileCurColor) as Int
				val colorTo = resources.getColor(R.color.colorPuzzleMatched)
				colorAnimation.setObjectValues(colorFrom, colorTo)
				colorAnimation.setEvaluator(ArgbEvaluator())
				colorAnimation.duration = 1000
				colorAnimation.startDelay = delay
				colorAnimation.repeatCount = 0
				colorAnimation.start()
			}
	}

	fun animateTimeRunningOut() {
		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val colorAnimation = tile.getTag(R.id.tileColorAnimation) as ValueAnimator
				val colorFrom = tile.getTag(R.id.tileCurColor) as Int
				val colorTo = resources.getColor(R.color.colorTimeRunningOut)
				colorAnimation.setObjectValues(colorFrom, colorTo)
				colorAnimation.setEvaluator(ArgbEvaluator())
				colorAnimation.duration = 150
				colorAnimation.startDelay = 0
				colorAnimation.repeatCount = 1
				colorAnimation.repeatMode = ObjectAnimator.REVERSE
				colorAnimation.start()
			}
	}

	fun animateTimeOver() {
		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val delay = 30L * i
				val colorAnimation = tile.getTag(R.id.tileColorAnimation) as ValueAnimator
				val colorFrom = tile.getTag(R.id.tileCurColor) as Int
				val colorTo = resources.getColor(R.color.colorTimeOver)
				colorAnimation.setObjectValues(colorFrom, colorTo)
				colorAnimation.setEvaluator(ArgbEvaluator())
				colorAnimation.duration = 1000
				colorAnimation.startDelay = delay
				colorAnimation.repeatCount = 0
				colorAnimation.start()
			}
	}

	fun animateNormalizeTilesColor() {
		for ((i, tile) in tiles.withIndex())
			if (tile != null) {
				val delay = 30L * i
				val colorAnimation = tile.getTag(R.id.tileColorAnimation) as ValueAnimator
				val colorFrom = tile.getTag(R.id.tileCurColor) as Int
				val colorTo = resources.getColor(R.color.colorTileNormal2)
				colorAnimation.setObjectValues(colorFrom, colorTo)
				colorAnimation.setEvaluator(ArgbEvaluator())
				colorAnimation.duration = 1000
				colorAnimation.startDelay = delay
				colorAnimation.repeatCount = 0
				colorAnimation.start()
			}
	}


//-------------------------------  Test different Animations   -----------------------------
	fun buttonDisappeareOnClick(sender: View?) {
		animateTilesDisappeare()
	}

	fun buttonPlaceOnClick(sender: View?) {
		animateNormalizeTilesColor()
		animatePrepareBeforePlace()
		animatePlaceTilesFast()
	}

	fun buttonTimeOverOnClick(sender: View?) {
		animateTimeOver()
	}

	fun buttonTimeRunningOutOnClick(sender: View?) {
		animateTimeRunningOut()
	}

	fun buttonPuzzleMatchedOnClick(sender: View?) {
		animatePuzzleMatched()
	}

	fun panelClientOnLongClick(sender: View): Boolean {
		panelDebugVisible = ! panelDebugVisible
		if (panelDebugVisible)
			panelTestAnimations.visibility = View.VISIBLE
		else
			panelTestAnimations.visibility = View.GONE

		return true
	}

//---------------------------  Realization of Property Animation   -----------------------------


	fun animateFloatDelay(target: View, propertyName: String,
		                   value: Float, duration_ms: Long, delay_ms: Long,
		                   interpolator: TimeInterpolator = linear): ObjectAnimator {

		val objectAnimator = ObjectAnimator.ofFloat(target, propertyName, value)
		objectAnimator.duration = duration_ms
		objectAnimator.startDelay = delay_ms
		objectAnimator.interpolator = interpolator
		objectAnimator.start()
		return objectAnimator
	}

}
