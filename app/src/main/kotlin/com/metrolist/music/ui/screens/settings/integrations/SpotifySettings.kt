/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.menu.AddToPlaylistDialogOnline
import com.metrolist.music.ui.menu.LoadingScreen
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private data class SpotifyResult(
    val name: String,
    val tracks: List<Pair<String, String>>, // title to artists
)

private val spotifyLinkRegex =
    Regex("""(?:open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{22})""")

private fun JsonElement?.str(key: String): String? =
    runCatching { this?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

private suspend fun fetchSpotify(link: String): SpotifyResult = withContext(Dispatchers.IO) {
    val match = spotifyLinkRegex.find(link)
        ?: error("Not a Spotify playlist or album link")
    val type = match.groupValues[1]
    val id = match.groupValues[2]

    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url("https://open.spotify.com/embed/$type/$id")
        .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        )
        .header("Accept-Language", "en-US,en;q=0.9")
        .build()

    val html = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("Spotify returned ${response.code}")
        response.body?.string() ?: error("Empty response from Spotify")
    }

    val jsonText = Regex(
        """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    ).find(html)?.groupValues?.get(1)
        ?: error("Could not read the playlist. Make sure it is public.")

    val entity = Json.parseToJsonElement(jsonText).jsonObject["props"]
        ?.jsonObject?.get("pageProps")
        ?.jsonObject?.get("state")
        ?.jsonObject?.get("data")
        ?.jsonObject?.get("entity")
        ?: error("Unexpected Spotify page format")

    val name = entity.str("name") ?: entity.str("title") ?: "Spotify playlist"
    val list = entity.jsonObject["trackList"]?.jsonArray
        ?: error("No tracks found. Make sure the playlist is public.")

    val tracks = list.mapNotNull { item ->
        val title = item.str("title")?.trim().orEmpty()
        val artists = item.str("subtitle")?.replace('\u00a0', ' ')?.trim().orEmpty()
        if (title.isEmpty()) null else title to artists
    }
    if (tracks.isEmpty()) error("No tracks found in this link")
    SpotifyResult(name, tracks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var link by remember { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var playlistName by remember { mutableStateOf("") }
    val importedSongs = remember { mutableStateListOf<Song>() }
    var showDialog by remember { mutableStateOf(false) }
    var isProgressStarted by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var currentSong by remember { mutableStateOf("") }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.spotify_import_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it
                error = null
            },
            label = { Text(stringResource(R.string.spotify_link_hint)) },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
                    if (text.isNotBlank()) link = text.trim()
                },
            ) {
                Text(stringResource(R.string.spotify_paste))
            }
            Button(
                enabled = link.isNotBlank() && !isFetching,
                onClick = {
                    isFetching = true
                    error = null
                    scope.launch {
                        try {
                            val result = fetchSpotify(link)
                            val songs = result.tracks.map { (title, artists) ->
                                Song(
                                    song = SongEntity(id = "", title = title),
                                    artists = artists.split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .map { ArtistEntity(id = "", name = it) },
                                )
                            }
                            playlistName = result.name
                            importedSongs.clear()
                            importedSongs.addAll(songs)
                            showDialog = true
                        } catch (e: Exception) {
                            error = e.message ?: "Something went wrong"
                        } finally {
                            isFetching = false
                        }
                    }
                },
            ) {
                Text(
                    if (isFetching) {
                        stringResource(R.string.spotify_loading)
                    } else {
                        stringResource(R.string.spotify_import)
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.spotify_import_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.spotify_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )

    AddToPlaylistDialogOnline(
        isVisible = showDialog,
        allowSyncing = false,
        initialTextFieldValue = playlistName,
        songs = importedSongs,
        onDismiss = { showDialog = false },
        onProgressStart = { isProgressStarted = it },
        onPercentageChange = { progress = it },
        onSongChange = { currentSong = it },
    )

    LoadingScreen(
        isVisible = isProgressStarted,
        value = progress,
        songTitle = currentSong,
    )
}
