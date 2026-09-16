package cz.openradio.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale

@Composable
fun StationLogo(
    station: Station,
    modifier: Modifier = Modifier
) {
    val fallback = remember(station.name) {
        station.name.trim().take(1).uppercase().ifBlank { "R" }
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (station.logoUrl.isNullOrBlank()) {
            Text(fallback, style = MaterialTheme.typography.titleLarge)
        } else {
            AsyncImage(
                model = station.logoUrl,
                contentDescription = "Logo ${station.name}",
                modifier = Modifier.size(56.dp),
                contentScale = ContentScale.Crop,
                onError = { }
            )
        }
    }
}
