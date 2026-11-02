package com.aartiq.aavija

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen edge-to-edge photo viewer without glowing borders.
 *
 * Supports:
 * - Edge-to-edge full screen presentation (no card, no border)
 * - Interactive multi-touch Pinch to Zoom (1x to 5x)
 * - Two-finger and one-finger pan when zoomed in
 * - Double-tap to zoom in / reset to 1x
 * - Swipe down to dismiss when at 1x
 * - Floating clean overlay UI on top: "આવતું છે ✨", "Open in Photos", and "Done"
 * - Tap anywhere to toggle UI overlay controls
 */
@Composable
fun ReceivedPhotoViewer(
    image: BonjourAdvertiser.ReceivedImage,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val bitmap by produceState<Bitmap?>(initialValue = null, image.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodePreview(context, image.uri) }.getOrNull()
        }
    }

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(20)
        isVisible = true
    }

    // Toggle controls visibility on single tap
    var showControls by remember { mutableStateOf(true) }

    // Pinch-to-zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Swipe down to dismiss state (active only when scale is 1x)
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val animatedDragY = remember { Animatable(0f) }

    fun triggerDismiss() {
        if (!isVisible) return
        isVisible = false
        scope.launch {
            delay(200)
            onDismiss()
        }
    }

    val entranceProgress by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.80f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "entrance-progress",
    )

    val backdropAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "backdrop-alpha",
    )

    val dragScale = (1f - (dragOffsetY / 1400f).coerceIn(0f, 0.20f))
    val currentTranslationY = (1f - entranceProgress) * 80f + dragOffsetY

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdropAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        // Full screen edge-to-edge photograph with pinch, zoom, and pan
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, currentTranslationY.roundToInt()) }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (scale > 1.2f) {
                                scale = 1f
                                panOffset = Offset.Zero
                            } else {
                                scale = 2.5f
                                val maxX = (screenWidthPx * 1.5f) / 2f
                                val maxY = (screenHeightPx * 1.5f) / 2f
                                panOffset = Offset(
                                    x = ((screenWidthPx / 2f - tapOffset.x) * 1.5f).coerceIn(-maxX, maxX),
                                    y = ((screenHeightPx / 2f - tapOffset.y) * 1.5f).coerceIn(-maxY, maxY),
                                )
                            }
                        },
                        onTap = {
                            showControls = !showControls
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale

                        if (newScale > 1.05f) {
                            // Pan while zoomed
                            val maxX = (screenWidthPx * (newScale - 1f)) / 2f
                            val maxY = (screenHeightPx * (newScale - 1f)) / 2f
                            panOffset = Offset(
                                x = (panOffset.x + pan.x).coerceIn(-maxX, maxX),
                                y = (panOffset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        } else {
                            panOffset = Offset.Zero
                            // Vertical pull down to dismiss when at 1x
                            if (pan.y > 0 || dragOffsetY > 0) {
                                dragOffsetY = (dragOffsetY + pan.y * 0.85f).coerceAtLeast(0f)
                                if (dragOffsetY > 160f) {
                                    triggerDismiss()
                                }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale * dragScale * (0.85f + 0.15f * entranceProgress)
                    scaleY = scale * dragScale * (0.85f + 0.15f * entranceProgress)
                    translationX = panOffset.x
                    translationY = panOffset.y
                    alpha = entranceProgress.coerceIn(0f, 1f)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Loading screenshot…",
                        color = Color.White.copy(alpha = 0.80f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Floating UI Controls on top of the image
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Gujarati Arrival Pill: "આવતું છે ✨"
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "આવતું છે ✨",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }

                // Quick Close Circle Button ("✕")
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                    modifier = Modifier
                        .size(38.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { triggerDismiss() },
                        ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "✕",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Bottom Floating Action Pill on top of the image
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Image details
                        Text(
                            image.name,
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(Modifier.height(10.dp))

                        // Action Buttons: "Open in Photos" and "Done"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { openInGallery(context, image.uri) },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(46.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black,
                                ),
                            ) {
                                Text(
                                    "Open in Photos",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Button(
                                onClick = { triggerDismiss() },
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(46.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.16f),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    "Done",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Hand the saved image to whatever gallery/Photos app is installed. */
private fun openInGallery(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No Photos app could open it.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Decode a preview-sized bitmap without loading a 50-megapixel screenshot at
 * full resolution. Two passes: bounds first, then a sampled decode.
 */
private fun decodePreview(context: Context, uri: Uri, maxDimension: Int = 2_600): Bitmap? {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null

    var sample = 1
    while (max(width, height) / sample > maxDimension) sample *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}
