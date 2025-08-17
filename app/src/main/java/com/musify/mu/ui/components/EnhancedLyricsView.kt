package com.musify.mu.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import com.musify.mu.data.repo.LyricsRepository
import com.musify.mu.data.repo.LyricsStateStore
import com.musify.mu.lyrics.LrcLine
import com.musify.mu.lyrics.LrcParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import androidx.compose.runtime.collectAsState

@Composable
fun EnhancedLyricsView(
    mediaId: String?,
    currentPositionMs: Long,
    dominantColor: Color,
    vibrantColor: Color
) {
    val context = LocalContext.current
    val lyricsRepo = remember { LyricsRepository(context) }
    val lyricsStateStore = remember { LyricsStateStore.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    // Observe lyrics state from the store
    val lyricsState by lyricsStateStore.currentLyrics.collectAsState()

    // Local state for UI interactions
    var isEditing by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }

    // For editing
    var editingText by remember { mutableStateOf("") }
    var currentEditingLine by remember { mutableStateOf<Int?>(null) }

    // For syncing
    var isSyncing by remember { mutableStateOf(false) }
    var syncStartTime by remember { mutableStateOf(0L) }

    // For scrolling to active lyric
    val listState = rememberLazyListState()
    // Separate state for fullscreen list to avoid conflicts
    val fullListState = rememberLazyListState()
    var activeLineIndex by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    // Extract values from lyrics state
    val lyricsText = lyricsState?.text
    val lrcLines = lyricsState?.lrcLines ?: emptyList()
    val isLrc = lyricsState?.isLrc ?: false
    val isLoading = lyricsState?.isLoading ?: false

    // Update editing text when lyrics change
    LaunchedEffect(lyricsText) {
        if (!isEditing && lyricsText != null) {
            editingText = lyricsText
        }
    }

    // Find active line based on current position and keep it slightly above center in the card
    LaunchedEffect(currentPositionMs, lrcLines) {
        if (lrcLines.isNotEmpty() && !isSyncing) {
            val newActiveIndex = lrcLines.indexOfLast { it.timeMs <= currentPositionMs }
            if (newActiveIndex != -1 && newActiveIndex != activeLineIndex) {
                activeLineIndex = newActiveIndex
                // Small delay to avoid fighting with layout when lines change
                delay(100)
                val layoutInfo = listState.layoutInfo
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                val viewportHalf = (viewportEnd - viewportStart) / 2
                // Place active line a bit above center (e.g., 48dp)
                val aboveOffsetPx = with(density) { 48.dp.toPx().toInt() }
                val targetOffset = -(viewportHalf - aboveOffsetPx).coerceAtLeast(0)
                listState.animateScrollToItem(
                    index = activeLineIndex,
                    scrollOffset = targetOffset
                )
            }
        }
    }

    // Keep fullscreen list in sync with active line
    LaunchedEffect(activeLineIndex, lrcLines) {
        if (lrcLines.isNotEmpty()) {
            // Slight delay to ensure dialog/layout is ready
            delay(80)
            val layoutInfo = fullListState.layoutInfo
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset
            val viewportHalf = (viewportEnd - viewportStart) / 2
            val aboveOffsetPx = with(density) { 48.dp.toPx().toInt() }
            val targetOffset = -(viewportHalf - aboveOffsetPx).coerceAtLeast(0)
            fullListState.animateScrollToItem(
                index = activeLineIndex.coerceIn(0, lrcLines.lastIndex),
                scrollOffset = targetOffset
            )
        }
    }

    // Refresh function for manual refresh
    fun refreshLyrics() {
        mediaId?.let { id ->
            coroutineScope.launch(Dispatchers.IO) {
                // Clear cache and reload
                lyricsStateStore.clearCache(id)
                lyricsStateStore.loadLyrics(id)
            }
        }
    }

    // File picker for LRC import
    val lrcFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    // Read the LRC file
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val text = inputStream?.use { stream ->
                        BufferedReader(InputStreamReader(stream)).readText()
                    }

                    if (!text.isNullOrBlank()) {
                        // Check if it's actually LRC format and parse accordingly
                        val isLrcFormat = LrcParser.isLrcFormat(text)
                        val parsedLines = if (isLrcFormat) LrcParser.parse(text) else emptyList()

                        // Save to repository regardless of format
                        mediaId?.let { id ->
                            if (isLrcFormat) {
                                lyricsRepo.attachLrc(id, uri)
                                // Update state store with LRC
                                lyricsStateStore.updateLyrics(id, text, parsedLines, true)
                            } else {
                                // Treat as plain text if no timestamps found
                                lyricsRepo.attachText(id, text)
                                lyricsStateStore.updateLyrics(id, text, emptyList(), false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Show error
                    mediaId?.let { id ->
                        lyricsStateStore.updateLyrics(
                            id,
                            "Error importing LRC file",
                            emptyList(),
                            false
                        )
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header with title and actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Lyrics",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Edit button
                IconButton(
                    onClick = {
                        if (isEditing) {
                            // Save edited lyrics
                            if (lyricsText != editingText) {
                                coroutineScope.launch {
                                    mediaId?.let { id ->
                                        // Auto-detect format based on content
                                        val isLrcFormat = LrcParser.isLrcFormat(editingText)

                                        if (isLrcFormat) {
                                            // Parse and save as LRC
                                            val parsedLines = LrcParser.parse(editingText)
                                            // Create a temporary file to store the edited LRC
                                            val safeId = id.hashCode().toString()
                                            val fileName = "edited_lyrics_${safeId}.lrc"
                                            context.openFileOutput(
                                                fileName,
                                                android.content.Context.MODE_PRIVATE
                                            ).use {
                                                it.write(editingText.toByteArray())
                                            }
                                            val fileUri =
                                                Uri.parse("file://${context.filesDir}/$fileName")
                                            lyricsRepo.attachLrc(id, fileUri)
                                            // Update state store
                                            lyricsStateStore.updateLyrics(
                                                id,
                                                editingText,
                                                parsedLines,
                                                true
                                            )
                                        } else {
                                            // Save as plain text
                                            lyricsRepo.attachText(id, editingText)
                                            // Update state store
                                            lyricsStateStore.updateLyrics(
                                                id,
                                                editingText,
                                                emptyList(),
                                                false
                                            )
                                        }
                                    }
                                }
                            }
                            isEditing = false
                        } else {
                            // Start editing
                            editingText = lyricsText ?: ""
                            isEditing = true
                            isSyncing = false
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Rounded.Save else Icons.Rounded.Edit,
                        contentDescription = if (isEditing) "Save" else "Edit",
                        tint = Color.White
                    )
                }

                // Sync button (only visible for LRC)
                if (isLrc || lyricsText == null || lyricsText == "No lyrics found") {
                    IconButton(
                        onClick = {
                            if (isSyncing) {
                                isSyncing = false
                            } else {
                                isSyncing = true
                                syncStartTime = currentPositionMs
                                isEditing = false
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                    ) {
                        Icon(
                            imageVector = if (isSyncing) Icons.Rounded.Stop else Icons.Rounded.Timer,
                            contentDescription = if (isSyncing) "Stop Syncing" else "Sync Lyrics",
                            tint = Color.White
                        )
                    }
                }

                // Import button
                IconButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = "Import Lyrics",
                        tint = Color.White
                    )
                }

                // Enlarge button
                IconButton(
                    onClick = { showFullscreen = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fullscreen,
                        contentDescription = "Enlarge Lyrics",
                        tint = Color.White
                    )
                }
            }
        }

        // Lyrics content
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            if (isEditing) {
                // Editing mode
                BasicTextField(
                    value = editingText,
                    onValueChange = { editingText = it },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 600.dp)
                        .background(
                            Color.Black.copy(alpha = 0.3f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp),
                    cursorBrush = SolidColor(dominantColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            isEditing = false
                            // Save logic here
                            coroutineScope.launch {
                                mediaId?.let { id ->
                                    // Auto-detect format based on content
                                    val isLrcFormat = LrcParser.isLrcFormat(editingText)

                                    if (isLrcFormat) {
                                        // Parse and save as LRC
                                        val parsedLines = LrcParser.parse(editingText)
                                        // Create a temporary file to store the edited LRC
                                        val safeId = id.hashCode().toString()
                                        val fileName = "edited_lyrics_${safeId}.lrc"
                                        context.openFileOutput(
                                            fileName,
                                            android.content.Context.MODE_PRIVATE
                                        ).use {
                                            it.write(editingText.toByteArray())
                                        }
                                        val fileUri =
                                            Uri.parse("file://${context.filesDir}/$fileName")
                                        lyricsRepo.attachLrc(id, fileUri)
                                        // Update state store
                                        lyricsStateStore.updateLyrics(
                                            id,
                                            editingText,
                                            parsedLines,
                                            true
                                        )
                                    } else {
                                        // Save as plain text
                                        lyricsRepo.attachText(id, editingText)
                                        // Update state store
                                        lyricsStateStore.updateLyrics(
                                            id,
                                            editingText,
                                            emptyList(),
                                            false
                                        )
                                    }
                                }
                            }
                        }
                    )
                )
            } else if (isSyncing) {
                // Syncing mode
                // Sync helper hint
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val minutes = (syncStartTime / 60000).toInt()
                    val seconds = ((syncStartTime % 60000) / 1000).toInt()
                    val startLabel = String.format("%02d:%02d", minutes, seconds)
                    Text(
                        text = "Tap a lyric to set its time (started at $startLabel)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    val lines = lyricsText?.lines() ?: listOf("No lyrics to sync")
                    currentEditingLine?.let { idx ->
                        // Ensure index bounds
                        if (idx < 0 || idx >= lines.size) {
                            currentEditingLine = null
                        }
                    }

                    itemsIndexed(lines) { index, line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("[")) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        // When clicked, add timestamp to this line
                                        val currentTime = currentPositionMs
                                        val minutes = (currentTime / 60000).toInt()
                                        val seconds = ((currentTime % 60000) / 1000).toInt()
                                        val millis = (currentTime % 1000) / 10

                                        val timestamp = String.format(
                                            "[%02d:%02d.%02d]",
                                            minutes,
                                            seconds,
                                            millis
                                        )
                                        val newLine = "$timestamp$trimmedLine"

                                        // Replace this line in the editing text
                                        val updatedText = lyricsText?.lines()?.map {
                                            if (it.trim() == trimmedLine) newLine else it
                                        }?.joinToString("\n") ?: ""

                                        editingText = updatedText
                                        currentEditingLine = index
                                        syncStartTime = currentTime

                                        // Re-parse LRC lines
                                        val updatedLrcLines = LrcParser.parse(updatedText)

                                        // Save to repository
                                        coroutineScope.launch {
                                            mediaId?.let { id ->
                                                val safeId = id.hashCode().toString()
                                                val fileName = "synced_lyrics_${safeId}.lrc"
                                                context.openFileOutput(
                                                    fileName,
                                                    android.content.Context.MODE_PRIVATE
                                                ).use {
                                                    it.write(updatedText.toByteArray())
                                                }
                                                val fileUri =
                                                    Uri.parse("file://${context.filesDir}/$fileName")
                                                lyricsRepo.attachLrc(id, fileUri)
                                                // Update state store
                                                lyricsStateStore.updateLyrics(
                                                    id,
                                                    updatedText,
                                                    updatedLrcLines,
                                                    true
                                                )
                                            }
                                        }
                                    }
                                    .background(
                                        if (trimmedLine.contains("[") && trimmedLine.contains("]"))
                                            vibrantColor.copy(alpha = 0.3f)
                                        else
                                            Color.White.copy(alpha = 0.1f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = trimmedLine,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (currentEditingLine == index) vibrantColor else Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            } else if (lrcLines.isNotEmpty()) {
                // LRC display mode in mid-sized card (always autoscroll current lines)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.25f))
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 12.dp)
                    ) {
                        itemsIndexed(lrcLines) { index, line ->
                            val isActive = index == activeLineIndex
                            val alpha by animateFloatAsState(
                                targetValue = if (isActive) 1f else 0.6f,
                                animationSpec = tween(300),
                                label = "alpha"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = if (isActive) 0.dp else 8.dp)
                                    .heightIn(min = if (line.text.isBlank()) 16.dp else 0.dp) // Preserve space for empty lines
                            ) {
                                Text(
                                    text = if (line.text.isBlank()) "♪" else line.text, // Show musical note for empty lines
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = if (isActive) 18.sp else 16.sp
                                    ),
                                    color = if (isActive)
                                        vibrantColor.copy(alpha = 0.9f)
                                    else if (line.text.isBlank())
                                        Color.White.copy(alpha = 0.3f) // Dim empty line indicators
                                    else
                                        Color.White.copy(alpha = alpha),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(alpha)
                                )
                            }
                        }
                    }
                }
            } else if (isLoading) {
                // Show loading indicator
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = vibrantColor,
                        modifier = Modifier.size(48.dp)
                    )
                }
            } else {
                // Plain text lyrics or no lyrics found
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 400.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.25f))
                ) {
                    if (!lyricsText.isNullOrBlank()) {
                        // Display plain text lyrics
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            val lines = lyricsText.lines().filter { it.isNotBlank() }
                            items(lines) { line ->
                                Text(
                                    text = line.trim(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        // No lyrics found
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No lyrics found",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showImportDialog = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = vibrantColor.copy(alpha = 0.8f)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Add Lyrics")
                                    }

                                    // Refresh button
                                    IconButton(
                                        onClick = {
                                            refreshLyrics()
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                dominantColor.copy(alpha = 0.3f),
                                                CircleShape
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "Refresh Lyrics",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Import dialog
        if (showImportDialog) {
            Dialog(onDismissRequest = { showImportDialog = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.7f)
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.5f),
                                dominantColor.copy(alpha = 0.3f),
                                vibrantColor.copy(alpha = 0.3f)
                            )
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Import Lyrics",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White
                        )

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.2f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        Button(
                            onClick = {
                                lrcFilePicker.launch("*/*")
                                showImportDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = dominantColor.copy(alpha = 0.8f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import LRC File")
                        }

                        Button(
                            onClick = {
                                // Create new lyrics
                                isEditing = true
                                editingText = ""
                                // Update store with empty plain text for this mediaId so UI reflects editing state
                                mediaId?.let { id ->
                                    coroutineScope.launch {
                                        lyricsStateStore.updateLyrics(id, "", emptyList(), false)
                                    }
                                }
                                showImportDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = vibrantColor.copy(alpha = 0.8f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Create,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Create New Lyrics")
                        }

                        TextButton(
                            onClick = { showImportDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel", color = Color.White)
                        }
                    }
                }
            }
        }

        // Fullscreen lyrics dialog
        if (showFullscreen) {
            Dialog(
                onDismissRequest = { showFullscreen = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    // Dark glassmorphism background using extracted colors
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        dominantColor.copy(alpha = 0.75f),
                                        dominantColor.copy(alpha = 0.55f)
                                    )
                                )
                            )
                    )
                    GlassBackdrop(
                        modifier = Modifier.fillMaxSize(),
                        tintTop = Color.Black.copy(alpha = 0.36f),
                        tintBottom = Color.Black.copy(alpha = 0.52f),
                        blurRadius = 40
                    )
                    // Additional scrim to fully mask the player screen beneath
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.8f))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Lyrics",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            IconButton(onClick = { showFullscreen = false }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Fullscreen list styled like mini card
                        if (lrcLines.isNotEmpty()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.Black.copy(
                                        alpha = 0.25f
                                    )
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                            ) {
                                LazyColumn(
                                    state = fullListState,
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(
                                        vertical = 16.dp,
                                        horizontal = 12.dp
                                    )
                                ) {
                                    itemsIndexed(lrcLines) { index, line ->
                                        val isActive = index == activeLineIndex
                                        val alpha by animateFloatAsState(
                                            targetValue = if (isActive) 1f else 0.6f,
                                            animationSpec = tween(300),
                                            label = "alpha"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = if (isActive) 0.dp else 8.dp)
                                                .heightIn(min = if (line.text.isBlank()) 16.dp else 0.dp)
                                        ) {
                                            Text(
                                                text = if (line.text.isBlank()) "♪" else line.text,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = if (isActive) 18.sp else 16.sp
                                                ),
                                                color = if (isActive)
                                                    vibrantColor.copy(alpha = 0.9f)
                                                else if (line.text.isBlank())
                                                    Color.White.copy(alpha = 0.3f)
                                                else
                                                    Color.White.copy(alpha = alpha),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .alpha(alpha)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        color = vibrantColor,
                                        modifier = Modifier.size(48.dp)
                                    )
                                } else {
                                    Text(
                                        text = lyricsText ?: "No lyrics found",
                                        color = Color.White,
                                        style = MaterialTheme.typography.headlineSmall,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
