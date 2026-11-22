package com.music.bitchord.promo

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app-open promo poster, ported from Shorty 1.9.43.
 *
 * Only the poster + an X, nothing else. The poster fits the screen keeping
 * its aspect ratio (24dp side margin, 48dp top/bottom — the 1.9.43 fit).
 * Tapping a tap zone opens its link; taps elsewhere do nothing. Only the
 * X closes it — no scrim-tap dismiss, no back dismiss (the host adds a
 * no-op BackHandler while this is up, matching the old dialog's
 * setCancelable(false)).
 *
 * Hosted in BitChordApp as an overlay, once per launch, when the server has
 * a poster — see [PromoCheck].
 */
@Composable
fun PromoDialog(
    poster: PromoPoster,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = poster.width.toFloat() / poster.height.toFloat()
            val maxW = maxWidth
            val maxH = maxHeight
            // Fit inside the box, keeping the poster's aspect ratio.
            val fittedW = if (maxW / aspect <= maxH) maxW else maxH * aspect
            val fittedH = if (maxW / aspect <= maxH) maxW / aspect else maxH
            Box(
                modifier = Modifier
                    .size(fittedW, fittedH)
                    .pointerInput(poster) {
                        detectTapGestures { offset ->
                            val fx = (offset.x / size.width).toFloat()
                            val fy = (offset.y / size.height).toFloat()
                            poster.zones.firstOrNull { zone ->
                                fx >= zone.x && fx <= zone.x + zone.w &&
                                    fy >= zone.y && fy <= zone.y + zone.h
                            }?.let { zone ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(zone.url)),
                                    )
                                }
                            }
                        }
                    },
            ) {
                Image(
                    bitmap = poster.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                // Only an X, top-end over the poster.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset((-8).dp, 8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x73000000))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
