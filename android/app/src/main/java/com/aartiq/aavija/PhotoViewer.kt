package com.aartiq.aavija

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
 * High-performance full-screen photo viewer with physics-based spring unfold,
 * ambient aurora backdrop, swipe-to-dismiss, and glassmorphic control pill.
 */
@Composable
fun ReceivedPhotoViewer(
    image: BonjourAdvertiser.ReceivedImage,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bitmap by produceState<Bitmap?>(initialValue = null, image.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodePreview(context, image.uri) }.getOrNull()
        }
    }

    // State for smooth entrance and exit animations
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(30)
        isVisible = true
    }

    // Interactive swipe down to dismiss
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val animatedDragY = remember { Animatable(0f) }

    fun triggerDismiss() {
        if (!isVisible) return
        isVisible = false
        scope.launch {
            delay(240)
            onDismiss()
        }
    }

    val entranceProgress by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "entrance-progress",
    )

    val backdropAlpha by animateFloatAsState(
        targetValue = if (isVisible) 0.94f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "backdrop-alpha",
    )

    val motion = rememberInfiniteTransition(label = "viewer-aurora")
    val sheenProgress by motion.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sheen-sweep",
    )
    val auroraGlow by motion.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aurora-pulse",
    )

    val dragScale = (1f - (dragOffsetY / 1200f).coerceIn(0f, 0.25f))
    val currentScale = (0.75f + 0.25f * entranceProgress) * dragScale
    val currentTranslationY = (1f - entranceProgress) * 120f + dragOffsetY

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdropAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { triggerDismiss() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Living aurora glow backdrop
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerTop = Offset(size.width * 0.25f, size.height * 0.20f)
            val centerBottom = Offset(size.width * 0.75f, size.height * 0.75f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00E5FF).copy(alpha = 0.22f * auroraGlow * backdropAlpha),
                        Color.Transparent,
                    ),
                    center = centerTop,
                    radius = size.minDimension * 0.65f,
                ),
                radius = size.minDimension * 0.65f,
                center = centerTop,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF7C4DFF).copy(alpha = 0.25f * auroraGlow * backdropAlpha),
                        Color.Transparent,
                    ),
                    center = centerBottom,
                    radius = size.minDimension * 0.70f,
                ),
                radius = size.minDimension * 0.70f,
                center = centerBottom,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle pill at the top
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.40f * entranceProgress)),
            )

            Spacer(Modifier.height(14.dp))

            // Main Photo Card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .offset { IntOffset(0, currentTranslationY.roundToInt()) }
                    .graphicsLayer {
                        scaleX = currentScale
                        scaleY = currentScale
                        alpha = entranceProgress.coerceIn(0f, 1f)
                    }
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(28.dp),
                        spotColor = Color(0xFF00E5FF).copy(alpha = 0.35f),
                        ambientColor = Color(0xFF7C4DFF).copy(alpha = 0.25f),
                    )
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF0D121D))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.65f),
                                Color(0xFF00E5FF).copy(alpha = 0.45f),
                                Color(0xFF7C4DFF).copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.15f),
                            ),
                        ),
                        shape = RoundedCornerShape(28.dp),
                    )
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                if (dragOffsetY > 130f) {
                                    triggerDismiss()
                                } else {
                                    scope.launch {
                                        animatedDragY.snapTo(dragOffsetY)
                                        animatedDragY.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = 0.75f,
                                                stiffness = Spring.StiffnessMedium,
                                            ),
                                        ) {
                                            dragOffsetY = value
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                dragOffsetY = 0f
                            },
                            onDrag = { _, dragAmount ->
                                if (dragAmount.y > 0 || dragOffsetY > 0) {
                                    dragOffsetY = (dragOffsetY + dragAmount.y * 0.85f).coerceAtLeast(0f)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = image.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(25.dp)),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E5FF),
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Unfolding screenshot…",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // Modern Specular glass sweep across the photograph
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(28.dp)),
                ) {
                    val x = size.width * sheenProgress
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.08f),
                                Color.White.copy(alpha = 0.32f),
                                Color.White.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                            start = Offset(x - size.width * 0.25f, 0f),
                            end = Offset(x + size.width * 0.25f, size.height),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Details and actions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = entranceProgress.coerceIn(0f, 1f)
                        translationY = (1f - entranceProgress) * 40f
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Glowing Gujarati arrival pill
                Surface(
                    color = Color(0xFF00E5FF).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(
                        text = "આવતું છે ✨",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF00E5FF),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }

                Text(
                    image.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Saved to ${ReceivedFiles.DISPLAY_FOLDER}",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                    )
                }

                Spacer(Modifier.height(18.dp))

                // Bottom floating action capsule
                Surface(
                    color = Color(0xFF1B2232).copy(alpha = 0.85f),
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.12f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(56.dp)
                        .shadow(12.dp, RoundedCornerShape(22.dp)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Button(
                            onClick = { openInGallery(context, image.uri) },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color.Black,
                            ),
                        ) {
                            Text(
                                "Open in Photos",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = { triggerDismiss() },
                            modifier = Modifier
                                .weight(0.7f)
                                .height(44.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color.White.copy(alpha = 0.20f),
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
