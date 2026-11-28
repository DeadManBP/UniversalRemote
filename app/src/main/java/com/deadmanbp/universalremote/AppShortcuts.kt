package com.deadmanbp.universalremote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

data class TvAppShortcut(
    val name: String,
    val samsungAppId: String,
    val googlePackage: String,
    val googleAppLink: String
)

val tvAppShortcuts = listOf(
    TvAppShortcut("YouTube", "111299001912", "com.google.android.youtube.tv", "vnd.youtube://"),
    TvAppShortcut("Netflix", "11101200001", "com.netflix.ninja", "https://www.netflix.com/"),
    TvAppShortcut("Prime Video", "3201910019365", "com.amazon.amazonvideo.livingroom", "https://app.primevideo.com/"),
    TvAppShortcut("Disney+", "3201901017640", "com.disney.disneyplus", "https://www.disneyplus.com/"),
    TvAppShortcut("Spotify", "3201606009685", "com.spotify.tv.android", "https://open.spotify.com/"),
    TvAppShortcut("Plex", "3201512006963", "com.plexapp.android", "https://app.plex.tv/")
)

@Composable
fun AppShortcutsRow(
    isSamsung: Boolean,
    onLaunch: (TvAppShortcut) -> Unit
) {
    Column {
        Text("QUICK LAUNCH", color = Color(0xFF92959E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 9.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tvAppShortcuts.forEach { app ->
                Button(
                    onClick = { onLaunch(app) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292C33))
                ) {
                    Text(app.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
