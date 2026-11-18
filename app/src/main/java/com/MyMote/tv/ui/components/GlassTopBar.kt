package com.MyMote.tv.ui.components

import androidx.compose.foundation.Image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.MyMote.tv.core.model.ConnectionState
import com.MyMote.tv.ui.theme.ElectricBluePrimary
import com.MyMote.tv.ui.theme.GlassBorder
import com.MyMote.tv.ui.theme.GlassSurfaceSubtle
import com.MyMote.tv.ui.theme.StatusErrorRed
import com.MyMote.tv.ui.theme.StatusSuccessGreen
import com.MyMote.tv.ui.theme.StatusWarningAmber
import com.MyMote.tv.ui.theme.TextMuted
import com.MyMote.tv.ui.theme.TextPrimary
import com.MyMote.tv.ui.theme.Typography

@Composable
fun GlassTopBar(
    connectionState: ConnectionState,
    onSettingsClick: () -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF070F1C)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_mymote_foreground_1795007699637),
                    contentDescription = "MyMote Logo",
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "MyMote",
                style = Typography.titleLarge,
                color = TextPrimary
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionStatusBadge(connectionState = connectionState)
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onScanClick,
                modifier = Modifier
                    .testTag("scan_button")
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Scan for TVs",
                    tint = TextPrimary
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .testTag("settings_button")
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextPrimary
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusBadge(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (statusColor, label) = when (connectionState) {
        is ConnectionState.Disconnected -> TextMuted to "Disconnected"
        is ConnectionState.Discovering -> ElectricBluePrimary to "Scanning..."
        is ConnectionState.Connecting -> StatusWarningAmber to "Connecting"
        is ConnectionState.Pairing -> StatusWarningAmber to "Pairing"
        is ConnectionState.Authenticating -> StatusWarningAmber to "Authenticating"
        is ConnectionState.Ready -> StatusSuccessGreen to "Connected"
        is ConnectionState.Reconnecting -> StatusWarningAmber to "Reconnecting"
        is ConnectionState.Failed -> StatusErrorRed to "Error"
    }

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        backgroundColor = GlassSurfaceSubtle,
        borderColor = GlassBorder,
        borderWidth = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = Typography.labelSmall,
                color = TextPrimary
            )
        }
    }
}
