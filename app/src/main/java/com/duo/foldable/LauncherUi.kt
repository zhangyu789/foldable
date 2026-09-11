package com.duo.foldable

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ICON_PX = 72

private fun Drawable.toImageBitmap(px: Int): ImageBitmap =
    toBitmap(px, px).asImageBitmap()

@Composable
private fun StatusBar(
    time: String,
    date: String,
    battery: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(time, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(date, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔋", fontSize = 16.sp)
            Text("$battery%", fontSize = 14.sp, color = Color.White)
        }
    }
}

/** Main display (inner): status bar + app icon grid + bottom dock. */
@Composable
fun LauncherScreen(
    apps: List<AppEntry>,
    nowMs: State<Long>,
    battery: State<Int>,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val date = Date(nowMs.value)
    val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(date)
    val dateStr = SimpleDateFormat("EEEE, MMM d", Locale.US).format(date)

    Box(modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar(timeStr, dateStr, battery.value)
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(apps) { app -> AppIconItem(app, onLaunch) }
            }
            Dock(apps.take(5), onLaunch)
        }
    }
}

@Composable
private fun AppIconItem(app: AppEntry, onLaunch: (AppEntry) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLaunch(app) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color.White.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    bitmap = app.icon.toImageBitmap(ICON_PX),
                    contentDescription = app.label,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(app.label, fontSize = 11.sp, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun Dock(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.28f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            apps.forEach { app ->
                Column(
                    modifier = Modifier.clickable { onLaunch(app) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.18f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = app.icon.toImageBitmap(ICON_PX),
                                contentDescription = app.label,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Cover display (outer): large clock + date + battery + favorite apps, shown when folded. */
@Composable
fun CoverScreen(
    apps: List<AppEntry>,
    nowMs: State<Long>,
    battery: State<Int>,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val date = Date(nowMs.value)
    val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(date)
    val dateStr = SimpleDateFormat("MMM d", Locale.US).format(date)

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(timeStr, fontSize = 72.sp, fontWeight = FontWeight.Light, color = Color.White)
            Text(dateStr, fontSize = 16.sp, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(10.dp))
            Text("🔋 $battery%", fontSize = 14.sp, color = Color.White)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                apps.take(4).forEach { app ->
                    Column(
                        modifier = Modifier.clickable { onLaunch(app) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(13.dp),
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = app.icon.toImageBitmap(ICON_PX),
                                    contentDescription = app.label,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(app.label, fontSize = 10.sp, color = Color.White, maxLines = 1)
                    }
                }
            }
        }
    }
}
