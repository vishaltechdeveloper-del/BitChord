package com.music.bitchord.leaderboard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.AlertAction
import com.music.bitchord.ui.components.AlertRule
import com.music.bitchord.ui.components.SCRIM_COLOR
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.replay.ShareAction
import com.music.bitchord.ui.replay.cacheForSharing
import com.music.bitchord.ui.replay.saveToGallery
import com.music.bitchord.ui.replay.sendIntent
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The Top 5 Listeners dialog, ported from Shorty 1.9.43.
 *
 * Frosted dark glass card in the app's own iOS-alert lineage (same haze
 * material as UpdateAvailableDialog): 320dp wide, 32dp corners, 0x47FFFFFF
 * hairline stroke. Title, five ranked rows (1 gold, 2 silver, 3 bronze,
 * 4–5 white), a "Your Rank" row when this device is ranked, then
 * full-width Share and Cancel rows. No X button.
 * A scrim tap does NOT dismiss (setCancelable(false)); Cancel or the system
 * back button closes it.
 *
 * Hosted in BitChordApp as an overlay, next to the update dialog. Shown
 * once per launch, only when the day has data — see LeaderboardCheck.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LeaderboardDialog(
    data: LeaderboardData,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(32.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SCRIM_COLOR),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(shape)
                .border(1.dp, ComposeColor(0x47FFFFFF), shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                // Swallows the tap before it reaches the scrim behind, so
                // touching the card itself never dismisses it.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Top 5 Listeners Today",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                data.top.forEach { entry ->
                    LeaderboardRow(entry)
                }
                if (data.yourRank != null) {
                    AlertRule(modifier = Modifier.padding(top = 12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Your Rank",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.W600,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "#${data.yourRank}  •  ${LeaderboardCheck.formatSeconds(data.yourSeconds)}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            AlertRule()
            AlertAction(label = "Share", emphasised = true, onClick = onShare)
            AlertRule()
            AlertAction(label = "Cancel", emphasised = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry) {
    val rankColor = when (entry.rank) {
        1 -> ComposeColor(0xFFFBBF24)
        2 -> ComposeColor(0xFFD1D5DB)
        3 -> ComposeColor(0xFFD97706)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${entry.rank}",
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.W700,
            ),
            color = rankColor,
            textAlign = TextAlign.Center,
        )
        Text(
            text = entry.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = LeaderboardCheck.formatSeconds(entry.seconds),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * The share sheet for the Top 5, in the Replay share sheet's shape: the
 * card preview, then Save (dark) and Share (white) buttons. Reuses the
 * Replay sheet's ShareAction, cacheForSharing and saveToGallery —
 * same module, same buttons, same hand-off.
 */
@Composable
fun LeaderboardShareSheet(
    data: LeaderboardData,
    holder: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var poster by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(data) {
        poster = runCatching { renderLeaderboardPoster(data, holder) }
            .onFailure { failed = true }
            .getOrNull()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        Text(
            text = "Share Top Listeners",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "Today's Top 5 Listeners on SHORTY PLAY.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth(0.42f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(4f / 5f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val image = poster
            when {
                image != null -> Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Top 5 Listeners",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
                failed -> Text(
                    text = "Couldn't draw the card.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                else -> CircularProgressIndicator()
            }
        }

        Spacer(Modifier.height(22.dp))

        val ready = poster != null
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShareAction(
                label = if (saved) "Saved" else "Save",
                icon = Icons.Rounded.Download,
                accent = false,
                enabled = ready && !saved,
                modifier = Modifier.weight(1f),
            ) {
                val image = poster ?: return@ShareAction
                scope.launch { saved = saveToGallery(context, image, "top-5-listeners", "shorty-play") }
            }
            ShareAction(
                label = "Share",
                icon = Icons.Rounded.IosShare,
                accent = true,
                enabled = ready,
                modifier = Modifier.weight(1f),
            ) {
                val image = poster ?: return@ShareAction
                scope.launch {
                    val uri = cacheForSharing(context, image, "top-listeners.png") ?: return@launch
                    context.startActivity(
                        Intent.createChooser(sendIntent(uri), "Share Top Listeners"),
                    )
                    onDismiss()
                }
            }
        }
    }
}

// ---- Share poster: 1080 x 1350, drawn on Canvas like the Replay poster ----

private const val POSTER_WIDTH = 1080
private const val POSTER_HEIGHT = 1350

private val POSTER_BG = Color.parseColor("#070A1A")
private val POSTER_GOLD = Color.parseColor("#FBBF24")
private val POSTER_SILVER = Color.parseColor("#D1D5DB")
private val POSTER_BRONZE = Color.parseColor("#D97706")

/**
 * The Top 5 as one picture, drawn rather than screenshotted — same reason
 * as the Replay poster: a fixed layout on a canvas is the same picture on
 * every device. No artwork is fetched; the rows are text and rank medals.
 */
suspend fun renderLeaderboardPoster(
    data: LeaderboardData,
    holder: String,
): Bitmap = withContext(Dispatchers.Default) {
    val bitmap = Bitmap.createBitmap(POSTER_WIDTH, POSTER_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(POSTER_BG)

    // Soft purple glow at the top, like the /play page's background.
    val glow = Paint().apply {
        shader = RadialGradient(
            POSTER_WIDTH / 2f, 0f, 900f,
            Color.parseColor("#3B2F6B"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, POSTER_WIDTH.toFloat(), 700f, glow)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.LEFT
    }
    val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA3B2")
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    val rulePaint = Paint().apply {
        color = Color.parseColor("#2A3350")
        strokeWidth = 2f
    }

    // Wordmark.
    titlePaint.textSize = 58f
    titlePaint.letterSpacing = 0.12f
    canvas.drawText("SHORTY PLAY", POSTER_WIDTH / 2f, 120f, titlePaint)

    // Divider under the wordmark.
    canvas.drawLine(90f, 168f, (POSTER_WIDTH - 90).toFloat(), 168f, rulePaint)

    // Title + date.
    titlePaint.textSize = 64f
    titlePaint.letterSpacing = 0f
    canvas.drawText("Top 5 Listeners", POSTER_WIDTH / 2f, 290f, titlePaint)
    dimPaint.textSize = 38f
    canvas.drawText("Today  •  ${prettyDate(data.date)}", POSTER_WIDTH / 2f, 352f, dimPaint)

    // Rows: medal circle, name, time.
    val medalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val medalText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 40f
    }
    var y = 470f
    val rowHeight = 130f
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 44f
    }
    val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B8FFFFFF")
        textSize = 40f
        textAlign = Paint.Align.RIGHT
    }
    data.top.forEach { entry ->
        medalPaint.color = when (entry.rank) {
            1 -> POSTER_GOLD
            2 -> POSTER_SILVER
            3 -> POSTER_BRONZE
            else -> Color.WHITE
        }
        canvas.drawCircle(130f, y, 36f, medalPaint)
        canvas.drawText("${entry.rank}", 130f, y + 14f, medalText)
        val name = ellipsize(namePaint, entry.name, 560f)
        canvas.drawText(name, 200f, y + 15f, namePaint)
        canvas.drawText(
            LeaderboardCheck.formatSeconds(entry.seconds),
            (POSTER_WIDTH - 90).toFloat(), y + 15f, timePaint,
        )
        y += rowHeight
    }

    // Your rank.
    if (data.yourRank != null) {
        canvas.drawLine(90f, y + 10f, (POSTER_WIDTH - 90).toFloat(), y + 10f, rulePaint)
        val yourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9AA3B2")
            textSize = 40f
        }
        canvas.drawText("Your Rank", 90f, y + 78f, yourPaint)
        val yourValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 40f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            "#${data.yourRank}  •  ${LeaderboardCheck.formatSeconds(data.yourSeconds)}",
            (POSTER_WIDTH - 90).toFloat(), y + 78f, yourValue,
        )
    }

    // Footer: whose card it is, when known.
    dimPaint.textSize = 30f
    val footer = if (holder.isNotBlank()) "$holder on SHORTY PLAY" else "Made with SHORTY PLAY"
    canvas.drawText(footer, POSTER_WIDTH / 2f, (POSTER_HEIGHT - 56).toFloat(), dimPaint)
    bitmap
}

private fun prettyDate(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso) ?: return iso
        SimpleDateFormat("d MMM yyyy", Locale.US).format(parsed)
    } catch (_: Exception) {
        iso
    }
}

/** Truncates with an ellipsis to fit maxWidth, measured on the paint. */
private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var cut = text
    while (cut.length > 1 && paint.measureText("$cut…") > maxWidth) {
        cut = cut.dropLast(1)
    }
    return "$cut…"
}
