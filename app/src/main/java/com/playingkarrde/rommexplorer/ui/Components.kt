package com.playingkarrde.rommexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage

enum class ButtonStyle { Primary, Tonal, Outline, Danger }

private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.Primary,
    enabled: Boolean = true,
    hint: String? = null,
) {
    val m = modifier.heightIn(min = 48.dp).focusRing(ButtonShape)
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            ButtonHint(hint)
        }
    }
    val pad = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    when (style) {
        ButtonStyle.Primary -> Button(onClick, m, enabled, shape = ButtonShape, contentPadding = pad) { content() }
        ButtonStyle.Danger -> Button(
            onClick, m, enabled, shape = ButtonShape, contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger, contentColor = Color.White),
        ) { content() }
        ButtonStyle.Tonal -> FilledTonalButton(onClick, m, enabled, shape = ButtonShape, contentPadding = pad) { content() }
        ButtonStyle.Outline -> OutlinedButton(onClick, m, enabled, shape = ButtonShape, contentPadding = pad) { content() }
    }
}

@Composable
fun AppIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.Text) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).focusRing(CircleShape)) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

/** A small badge showing which controller button triggers an action, e.g. "X". */
@Composable
fun ButtonHint(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Palette.SurfaceHi, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim)
    }
}

@Composable
fun Pill(text: String, color: Color = Palette.SurfaceHi, textColor: Color = Palette.Text, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        color = textColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun Cover(url: String?, title: String, modifier: Modifier = Modifier) {
    val placeholder: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Palette.Surface), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VideogameAsset, null, tint = Palette.Muted, modifier = Modifier.size(28.dp))
            }
            Text(
                title, color = Palette.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
    if (url == null) {
        Box(modifier) { placeholder() }
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            loading = { Box(Modifier.fillMaxSize().background(Palette.Surface)) },
            error = { placeholder() },
        )
    }
}
