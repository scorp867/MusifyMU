package com.musify.mu.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistCreationDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onCreatePlaylist: (name: String, imageUri: String?) -> Unit
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    
    var playlistName by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<String?>(null) }
    var selectedColor by remember { mutableStateOf<Color?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    
    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { 
            scope.launch {
                val savedUri = saveImageToInternalStorage(context, it)
                selectedImageUri = savedUri
                selectedColor = null // Clear color selection when image is chosen
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    
    // Predefined gradient colors for playlists
    val predefinedColors = remember {
        listOf(
            listOf(Color(0xFF667eea), Color(0xFF764ba2)), // Purple gradient
            listOf(Color(0xFFf093fb), Color(0xFFf5576c)), // Pink gradient
            listOf(Color(0xFF4facfe), Color(0xFF00f2fe)), // Blue gradient
            listOf(Color(0xFF43e97b), Color(0xFF38f9d7)), // Green gradient
            listOf(Color(0xFFfa709a), Color(0xFFfee140)), // Orange gradient
            listOf(Color(0xFFa8edea), Color(0xFFfed6e3)), // Pastel gradient
            listOf(Color(0xFF36d1dc), Color(0xFF5b86e5)), // Ocean gradient
            listOf(Color(0xFFffecd2), Color(0xFFfcb69f))  // Sunset gradient
        )
    }
    
    // Reset state when dialog is dismissed
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            playlistName = ""
            selectedImageUri = null
            selectedColor = null
            isCreating = false
        }
    }

    if (isVisible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Create Playlist",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        IconButton(
                            onClick = onDismiss,
                            enabled = !isCreating
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Playlist image/color selection
                    PlaylistImageSelector(
                        selectedImageUri = selectedImageUri,
                        selectedColor = selectedColor,
                        onImageClick = { imagePickerLauncher.launch("image/*") },
                        onColorSelected = { color ->
                            selectedColor = color
                            selectedImageUri = null // Clear image when color is selected
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        predefinedColors = predefinedColors
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Playlist name input
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Playlist name") },
                        placeholder = { Text("My awesome playlist") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        enabled = !isCreating,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Cancel button
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !isCreating,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                1.dp, 
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                "Cancel",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        
                        // Create button
                        Button(
                            onClick = {
                                if (playlistName.isNotBlank()) {
                                    isCreating = true
                                    scope.launch {
                                        try {
                                            val finalImageUri = selectedImageUri ?: generateColorImage(
                                                context,
                                                selectedColor ?: predefinedColors.first()
                                            )
                                            onCreatePlaylist(playlistName.trim(), finalImageUri)
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } catch (e: Exception) {
                                            android.util.Log.e("PlaylistCreation", "Error creating playlist", e)
                                        } finally {
                                            isCreating = false
                                        }
                                    }
                                }
                            },
                            enabled = playlistName.isNotBlank() && !isCreating,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isCreating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(
                                    "Create",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistImageSelector(
    selectedImageUri: String?,
    selectedColor: Color?,
    onImageClick: () -> Unit,
    onColorSelected: (List<Color>) -> Unit,
    predefinedColors: List<List<Color>>
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Preview
        PlaylistImagePreview(
            imageUri = selectedImageUri,
            selectedColor = selectedColor,
            predefinedColors = predefinedColors,
            onImageClick = onImageClick
        )
        
        // Color options
        Text(
            text = "Choose a color or upload an image",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            // Upload image option
            item {
                ColorSelectorItem(
                    isSelected = selectedImageUri != null,
                    onClick = onImageClick
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = "Upload image",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            // Color options
            items(predefinedColors) { colorPair ->
                ColorSelectorItem(
                    isSelected = selectedColor == colorPair && selectedImageUri == null,
                    onClick = { onColorSelected(colorPair) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(colorPair),
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistImagePreview(
    imageUri: String?,
    selectedColor: Color?,
    predefinedColors: List<List<Color>>,
    onImageClick: () -> Unit
) {
    val backgroundColor = selectedColor ?: predefinedColors.first()
    
    Card(
        modifier = Modifier
            .size(120.dp)
            .clickable { onImageClick() },
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                imageUri != null -> {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "Playlist image",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                selectedColor != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    if (selectedColor is List<*>) selectedColor as List<Color> else listOf(selectedColor, selectedColor)
                                )
                            )
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(backgroundColor))
                    )
                }
            }
            
            // Overlay icon for upload
            if (imageUri == null) {
                Icon(
                    imageVector = Icons.Rounded.AddPhotoAlternate,
                    contentDescription = "Add image",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun ColorSelectorItem(
    isSelected: Boolean,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 3.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "border_width"
    )
    
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(
                borderWidth,
                MaterialTheme.colorScheme.primary,
                CircleShape
            )
            .padding(4.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        content = content
    )
}

// Helper functions

private suspend fun saveImageToInternalStorage(context: Context, uri: Uri): String {
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
    
    val filename = "playlist_${UUID.randomUUID()}.jpg"
    val directory = File(context.filesDir, "playlist_images")
    if (!directory.exists()) {
        directory.mkdirs()
    }
    
    val file = File(directory, filename)
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    
    return file.absolutePath
}

private suspend fun generateColorImage(context: Context, colors: List<Color>): String {
    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    
    val gradient = android.graphics.LinearGradient(
        0f, 0f, 512f, 512f,
        colors.map { it.toArgb() }.toIntArray(),
        null,
        android.graphics.Shader.TileMode.CLAMP
    )
    
    val paint = android.graphics.Paint().apply {
        shader = gradient
    }
    
    canvas.drawRect(0f, 0f, 512f, 512f, paint)
    
    val filename = "playlist_gradient_${UUID.randomUUID()}.jpg"
    val directory = File(context.filesDir, "playlist_images")
    if (!directory.exists()) {
        directory.mkdirs()
    }
    
    val file = File(directory, filename)
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    
    return file.absolutePath
}