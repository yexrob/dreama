package com.limelight.local.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.BuildConfig
import com.limelight.R
import com.limelight.local.dlna.CastReceiver
import com.limelight.local.stream.AndroidPrefsStore
import com.limelight.local.stream.BitratePreset
import com.limelight.local.stream.DepthQualityMode
import com.limelight.local.stream.DepthStrength
import com.limelight.local.stream.FileLogMode
import com.limelight.local.stream.GamepadToggleShortcut
import com.limelight.local.stream.MouseMode
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.ScreenDistancePreset
import com.limelight.local.stream.ScreenSizePreset
import com.limelight.local.stream.StreamCodec
import com.limelight.local.stream.StreamFps
import com.limelight.local.stream.StreamResolution
import com.limelight.local.stream.StreamSettingsMapper
import com.limelight.local.stream.UserFacingError
import com.limelight.local.stream.VrEnvironmentPreset
import com.limelight.preferences.PreferenceConfiguration

private enum class SettingsCategory(
    val titleRes: Int,
    val subRes: Int,
    val icon: ImageVector,
) {
    STREAM(R.string.settings_cat_stream, R.string.settings_cat_stream_sub, WallIcons.Computer),
    PLAYBACK_3D(R.string.settings_cat_3d, R.string.settings_cat_3d_sub, WallIcons.Eye),
    SCREEN_ENV(R.string.settings_cat_screen, R.string.settings_cat_screen_sub, WallIcons.Headset),
    CAST(R.string.settings_cat_cast, R.string.settings_cat_cast_sub, WallIcons.Cast),
    INPUT(R.string.settings_cat_input, R.string.settings_cat_input_sub, WallIcons.Gamepad),
    PRIVACY(R.string.settings_cat_privacy, R.string.settings_cat_privacy_sub, WallIcons.Shield),
    ABOUT(R.string.settings_cat_about, R.string.settings_cat_about_sub, WallIcons.Info),
}

/**
 * Full Compose Settings page matching design-opus/mockups/settings.html:
 * left category rail + right 88dp option rows, segmented pills, amber toggle switches,
 * zero dropdowns, writing directly to PreferenceConfiguration's SharedPreferences keys.
 */
@Composable
fun SettingsScreen(model: WallModel, nav: WallNav) {
    val context = LocalContext.current
    val store = remember(context) { AndroidPrefsStore.defaultPrefs(context) }
    val isGen1 = remember { PreferenceConfiguration.isXr2Gen1Headset() }

    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.STREAM) }
    var snapshot by remember { mutableStateOf(StreamSettingsMapper.read(store, isGen1)) }
    val dlna by CastReceiver.status.collectAsState()
    var showRenameSheet by remember { mutableStateOf(false) }
    var showLicenseSheet by remember { mutableStateOf(false) }
    var resetPoseConfirmed by remember { mutableStateOf(false) }

    fun update(block: () -> Unit) {
        block()
        snapshot = StreamSettingsMapper.read(store, isGen1)
    }

    Box(Modifier.fillMaxSize()) {
        // Left category list (matching design-opus/mockups/settings.html)
        LazyColumn(
            Modifier.offset(40.dp, 112.dp).width(344.dp).height(580.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(SettingsCategory.entries.size) { index ->
                val cat = SettingsCategory.entries[index]
                ListRow(
                    label = stringResource(cat.titleRes),
                    sub = stringResource(cat.subRes),
                    lead = cat.icon,
                    selected = selectedCategory == cat,
                ) {
                    selectedCategory = cat
                    resetPoseConfirmed = false
                }
            }
        }

        // Right options pane
        LazyColumn(
            Modifier.offset(416.dp, 112.dp).width(800.dp).height(600.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("cat-heading") {
                Text(
                    stringResource(selectedCategory.titleRes),
                    style = WallType.Title,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            when (selectedCategory) {
                SettingsCategory.STREAM -> {
                    item("res") {
                        SettingRow(
                            title = stringResource(R.string.settings_resolution_title),
                            sub = snapshot.customResolutionLabel?.let {
                                stringResource(R.string.settings_resolution_custom_sub, it)
                            } ?: stringResource(R.string.settings_resolution_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    StreamResolution.R720P to stringResource(R.string.settings_res_720p),
                                    StreamResolution.R1080P to stringResource(R.string.settings_res_1080p),
                                    StreamResolution.R1440P to stringResource(R.string.settings_res_1440p),
                                    StreamResolution.R4K to stringResource(R.string.settings_res_4k),
                                ),
                                selected = snapshot.resolution,
                                onSelect = { update { StreamSettingsMapper.setResolution(store, it, isGen1) } },
                            )
                        }
                    }
                    item("fps") {
                        SettingRow(
                            title = stringResource(R.string.settings_fps_title),
                            sub = snapshot.customFpsLabel?.let {
                                stringResource(R.string.settings_fps_custom_sub, it)
                            } ?: stringResource(R.string.settings_fps_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    StreamFps.FPS_60 to stringResource(R.string.settings_fps_60),
                                    StreamFps.FPS_72 to stringResource(R.string.settings_fps_72),
                                    StreamFps.FPS_90 to stringResource(R.string.settings_fps_90),
                                ),
                                selected = snapshot.fps,
                                onSelect = { update { StreamSettingsMapper.setFps(store, it, isGen1) } },
                            )
                        }
                    }
                    if (snapshot.warn3dLag) {
                        item("3d-lag-warn") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Wall.Radius))
                                    .background(Wall.Surface)
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                            ) {
                                Notice(stringResource(R.string.settings_3d_lag_warning), WallIcons.Alert, danger = true)
                            }
                        }
                    }
                    item("bitrate") {
                        SettingRow(
                            title = stringResource(R.string.settings_bitrate_title),
                            sub = stringResource(R.string.settings_bitrate_sub, snapshot.bitrateMbps),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    BitratePreset.AUTO to stringResource(R.string.settings_bitrate_auto),
                                    BitratePreset.SMOOTH to stringResource(R.string.settings_bitrate_smooth),
                                    BitratePreset.SHARP to stringResource(R.string.settings_bitrate_sharp),
                                    BitratePreset.CUSTOM to stringResource(R.string.settings_bitrate_custom),
                                ),
                                selected = snapshot.bitratePreset,
                                onSelect = { update { StreamSettingsMapper.setBitratePreset(store, it, isGen1) } },
                            )
                        }
                    }
                    if (snapshot.bitratePreset == BitratePreset.CUSTOM) {
                        item("bitrate-custom") {
                            SettingRow(
                                title = stringResource(R.string.settings_bitrate_custom_title),
                                sub = stringResource(R.string.settings_bitrate_custom_sub),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    RoundButton(
                                        WallIcons.Minus,
                                        onClick = {
                                            update {
                                                StreamSettingsMapper.setCustomBitrateMbps(store, snapshot.bitrateMbps - 5)
                                            }
                                        },
                                        filled = true,
                                    )
                                    Text(
                                        stringResource(R.string.settings_bitrate_mbps, snapshot.bitrateMbps),
                                        style = WallType.Label.copy(fontFeatureSettings = "tnum"),
                                        modifier = Modifier.width(120.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                    RoundButton(
                                        WallIcons.Plus,
                                        onClick = {
                                            update {
                                                StreamSettingsMapper.setCustomBitrateMbps(store, snapshot.bitrateMbps + 5)
                                            }
                                        },
                                        filled = true,
                                    )
                                }
                            }
                        }
                    }
                    item("codec") {
                        SettingRow(
                            title = stringResource(R.string.settings_codec_title),
                            sub = snapshot.customCodecLabel?.let {
                                stringResource(R.string.settings_codec_custom_sub, it)
                            } ?: stringResource(R.string.settings_codec_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    StreamCodec.AUTO to stringResource(R.string.settings_codec_auto),
                                    StreamCodec.H264 to stringResource(R.string.settings_codec_h264),
                                    StreamCodec.HEVC to stringResource(R.string.settings_codec_hevc),
                                ),
                                selected = snapshot.codec,
                                onSelect = { update { StreamSettingsMapper.setCodec(store, it) } },
                            )
                        }
                    }
                    item("mouse") {
                        SettingRow(
                            title = stringResource(R.string.settings_mouse_title),
                            sub = stringResource(R.string.settings_mouse_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    MouseMode.RELATIVE to stringResource(R.string.settings_mouse_relative),
                                    MouseMode.ABSOLUTE to stringResource(R.string.settings_mouse_absolute),
                                ),
                                selected = snapshot.mouseMode,
                                onSelect = { update { StreamSettingsMapper.setMouseMode(store, it) } },
                            )
                        }
                    }
                    item("gamepad-shortcut") {
                        SettingRow(
                            title = stringResource(R.string.settings_gamepad_shortcut_title),
                            sub = stringResource(R.string.settings_gamepad_shortcut_sub),
                            vertical = true,
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    GamepadToggleShortcut.MENU_GRIP to stringResource(R.string.settings_shortcut_menu_grip),
                                    GamepadToggleShortcut.STICKS to stringResource(R.string.settings_shortcut_sticks),
                                    GamepadToggleShortcut.TRIGGERS_GRIPS to stringResource(R.string.settings_shortcut_triggers),
                                ),
                                selected = snapshot.gamepadShortcut,
                                onSelect = { update { StreamSettingsMapper.setGamepadShortcut(store, it) } },
                            )
                        }
                    }
                    item("multi-controller") {
                        SettingRow(
                            title = stringResource(R.string.settings_multi_controller_title),
                            sub = stringResource(R.string.settings_multi_controller_sub),
                        ) {
                            WallSwitch(snapshot.multiController) {
                                update { StreamSettingsMapper.setMultiController(store, it) }
                            }
                        }
                    }
                    item("flip-buttons") {
                        SettingRow(
                            title = stringResource(R.string.settings_flip_buttons_title),
                            sub = stringResource(R.string.settings_flip_buttons_sub),
                        ) {
                            WallSwitch(snapshot.flipFaceButtons) {
                                update { StreamSettingsMapper.setFlipFaceButtons(store, it) }
                            }
                        }
                    }
                }

                SettingsCategory.PLAYBACK_3D -> {
                    item("depth-strength") {
                        SettingRow(
                            title = stringResource(R.string.settings_depth_strength_title),
                            sub = stringResource(R.string.settings_depth_strength_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    DepthStrength.OFF to stringResource(R.string.settings_depth_off),
                                    DepthStrength.SOFT to stringResource(R.string.settings_depth_soft),
                                    DepthStrength.STANDARD to stringResource(R.string.settings_depth_standard),
                                    DepthStrength.STRONG to stringResource(R.string.settings_depth_strong),
                                ),
                                selected = snapshot.depthStrength,
                                onSelect = { update { StreamSettingsMapper.setDepthStrength(store, it) } },
                            )
                        }
                    }
                    item("depth-quality") {
                        SettingRow(
                            title = stringResource(R.string.settings_depth_quality_title),
                            sub = stringResource(R.string.settings_depth_quality_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    DepthQualityMode.SMOOTH to stringResource(R.string.settings_depth_smooth),
                                    DepthQualityMode.QUALITY to stringResource(R.string.settings_depth_quality),
                                ),
                                selected = snapshot.depthQuality,
                                onSelect = { update { StreamSettingsMapper.setDepthQuality(store, it) } },
                            )
                        }
                    }
                    item("sharpening") {
                        SettingRow(
                            title = stringResource(R.string.settings_sharpening_title),
                            sub = stringResource(R.string.settings_sharpening_sub),
                        ) {
                            WallSwitch(snapshot.sharpening) {
                                update { StreamSettingsMapper.setSharpening(store, it) }
                            }
                        }
                    }
                    item("supersampling") {
                        SettingRow(
                            title = stringResource(R.string.settings_supersampling_title),
                            sub = stringResource(R.string.settings_supersampling_sub),
                        ) {
                            WallSwitch(snapshot.supersampling) {
                                update { StreamSettingsMapper.setSupersampling(store, it) }
                            }
                        }
                    }
                }

                SettingsCategory.SCREEN_ENV -> {
                    item("screen-size") {
                        SettingRow(
                            title = stringResource(R.string.settings_screen_size_title),
                            sub = stringResource(R.string.settings_screen_size_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    ScreenSizePreset.SMALL to stringResource(R.string.settings_size_small),
                                    ScreenSizePreset.MEDIUM to stringResource(R.string.settings_size_medium),
                                    ScreenSizePreset.LARGE to stringResource(R.string.settings_size_large),
                                    ScreenSizePreset.GIANT to stringResource(R.string.settings_size_giant),
                                ),
                                selected = snapshot.screenSize,
                                onSelect = { update { StreamSettingsMapper.setScreenSize(store, it) } },
                            )
                        }
                    }
                    item("screen-dist") {
                        SettingRow(
                            title = stringResource(R.string.settings_screen_dist_title),
                            sub = stringResource(R.string.settings_screen_dist_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    ScreenDistancePreset.NEAR to stringResource(R.string.settings_dist_near),
                                    ScreenDistancePreset.MEDIUM to stringResource(R.string.settings_dist_medium),
                                    ScreenDistancePreset.FAR to stringResource(R.string.settings_dist_far),
                                ),
                                selected = snapshot.screenDistance,
                                onSelect = { update { StreamSettingsMapper.setScreenDistance(store, it) } },
                            )
                        }
                    }
                    item("curved") {
                        SettingRow(
                            title = stringResource(R.string.settings_curved_title),
                            sub = stringResource(R.string.settings_curved_sub),
                        ) {
                            WallSwitch(snapshot.curvedScreen) {
                                update { StreamSettingsMapper.setCurvedScreen(store, it) }
                            }
                        }
                    }
                    item("env") {
                        SettingRow(
                            title = stringResource(R.string.settings_env_title),
                            sub = stringResource(R.string.settings_env_sub),
                            vertical = true,
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    VrEnvironmentPreset.PASSTHROUGH to stringResource(R.string.settings_env_passthrough),
                                    VrEnvironmentPreset.VOID to stringResource(R.string.settings_env_void),
                                    VrEnvironmentPreset.HOME_THEATER to stringResource(R.string.settings_env_theater),
                                    VrEnvironmentPreset.GRAND_CINEMA to stringResource(R.string.settings_env_cinema),
                                    VrEnvironmentPreset.SYNTHWAVE to stringResource(R.string.settings_env_synthwave),
                                ),
                                selected = snapshot.environment,
                                onSelect = { update { StreamSettingsMapper.setEnvironment(store, it) } },
                            )
                        }
                    }
                    item("ambilight") {
                        SettingRow(
                            title = stringResource(R.string.settings_ambilight_title),
                            sub = stringResource(R.string.settings_ambilight_sub),
                        ) {
                            WallSwitch(snapshot.ambilight) {
                                update { StreamSettingsMapper.setAmbilight(store, it) }
                            }
                        }
                    }
                    item("doff") {
                        SettingRow(
                            title = stringResource(R.string.settings_doff_title),
                            sub = stringResource(R.string.settings_doff_sub),
                        ) {
                            WallSwitch(snapshot.doffGrace) {
                                update { StreamSettingsMapper.setDoffGrace(store, it) }
                            }
                        }
                    }
                    item("reset-pose") {
                        SettingRow(
                            title = stringResource(R.string.settings_reset_pose_title),
                            sub = if (resetPoseConfirmed) stringResource(R.string.settings_reset_pose_done)
                            else stringResource(R.string.settings_reset_pose_sub),
                        ) {
                            WallButton(
                                stringResource(R.string.settings_reset_pose_btn),
                                {
                                    update { StreamSettingsMapper.resetScreenPose(store) }
                                    resetPoseConfirmed = true
                                },
                                small = true,
                            )
                        }
                    }
                }

                SettingsCategory.CAST -> {
                    item("device-name") {
                        SettingRow(
                            title = stringResource(R.string.settings_device_name_title),
                            sub = stringResource(R.string.settings_device_name_sub),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                Text(dlna.name, style = WallType.Label, color = Wall.Accent)
                                WallButton(
                                    stringResource(R.string.settings_device_name_edit),
                                    { showRenameSheet = true },
                                    small = true,
                                )
                            }
                        }
                    }
                }

                SettingsCategory.INPUT -> {
                    item("hand-tracking") {
                        SettingRow(
                            title = stringResource(R.string.settings_hand_tracking_title),
                            sub = stringResource(R.string.settings_hand_tracking_sub),
                        ) {
                            WallSwitch(snapshot.handTracking) {
                                update { StreamSettingsMapper.setHandTracking(store, it) }
                            }
                        }
                    }
                    item("show-ray") {
                        SettingRow(
                            title = stringResource(R.string.settings_show_ray_title),
                            sub = stringResource(R.string.settings_show_ray_sub),
                        ) {
                            WallSwitch(snapshot.showRay) {
                                update { StreamSettingsMapper.setShowRay(store, it) }
                            }
                        }
                    }
                    item("pointer-sleep") {
                        SettingRow(
                            title = stringResource(R.string.settings_pointer_sleep_title),
                            sub = stringResource(R.string.settings_pointer_sleep_sub),
                        ) {
                            WallSwitch(snapshot.pointerSleep) {
                                update { StreamSettingsMapper.setPointerSleep(store, it) }
                            }
                        }
                    }
                    item("head-aim") {
                        SettingRow(
                            title = stringResource(R.string.settings_head_aim_title),
                            sub = stringResource(R.string.settings_head_aim_sub),
                        ) {
                            WallSwitch(snapshot.headAim) {
                                update { StreamSettingsMapper.setHeadAim(store, it) }
                            }
                        }
                    }
                    item("click-sound") {
                        SettingRow(
                            title = stringResource(R.string.settings_click_sound_title),
                            sub = stringResource(R.string.settings_click_sound_sub),
                        ) {
                            WallSwitch(snapshot.clickSound) {
                                update { StreamSettingsMapper.setClickSound(store, it) }
                            }
                        }
                    }
                }

                SettingsCategory.PRIVACY -> {
                    item("file-log") {
                        SettingRow(
                            title = stringResource(R.string.settings_log_title),
                            sub = stringResource(R.string.settings_log_sub),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    FileLogMode.OFF to stringResource(R.string.settings_log_off),
                                    FileLogMode.BASIC to stringResource(R.string.settings_log_basic),
                                    FileLogMode.VERBOSE to stringResource(R.string.settings_log_verbose),
                                ),
                                selected = snapshot.fileLog,
                                onSelect = { update { StreamSettingsMapper.setFileLog(store, it) } },
                            )
                        }
                    }
                }

                SettingsCategory.ABOUT -> {
                    item("about-brand") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Wall.Radius))
                                .background(Wall.Surface)
                                .padding(28.dp),
                        ) {
                            Text(stringResource(R.string.settings_about_product), style = WallType.Title)
                            Text(
                                stringResource(R.string.settings_about_tagline),
                                style = WallType.Body,
                                color = Wall.Text2,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            val ver = if (BuildConfig.GIT_HASH.isNotEmpty()) {
                                "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})"
                            } else {
                                BuildConfig.VERSION_NAME
                            }
                            Text(
                                "${stringResource(R.string.settings_about_version_title)}：$ver",
                                style = WallType.Caption.copy(fontFeatureSettings = "tnum"),
                                color = Wall.Text3,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                    item("about-license") {
                        SettingRow(
                            title = stringResource(R.string.settings_about_license_title),
                            sub = stringResource(R.string.settings_about_license_sub),
                        ) {
                            WallButton(
                                stringResource(R.string.settings_about_license_view),
                                { showLicenseSheet = true },
                                small = true,
                            )
                        }
                    }
                    item("about-third-party") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(Wall.Radius))
                                .background(Wall.Surface)
                                .padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(R.string.settings_about_third_party_title), style = WallType.Head)
                            Text(
                                stringResource(R.string.settings_about_third_party_sub),
                                style = WallType.Caption,
                                color = Wall.Text2,
                            )
                            Spacer(Modifier.height(4.dp))
                            for ((proj, lic, roleRes) in THIRD_PARTY_COMPONENTS) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(proj, style = WallType.Label.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                                        Text(stringResource(roleRes), style = WallType.Caption.copy(fontSize = 19.sp), color = Wall.Text2)
                                    }
                                    Text(lic, style = WallType.Caption.copy(fontSize = 19.sp), color = Wall.Text3)
                                }
                            }
                        }
                    }
                }
            }
        }

        NavBar(selected = null, onTab = nav::tab, onSettings = { nav.back() }, solid = true, settingsSelected = true)

        if (showRenameSheet) {
            PhoneInputSheet(
                mode = PhoneInputServer.Mode.DEVICE_NAME,
                lanIp = model.streaming.localLanIp(),
                initialValue = dlna.name,
                onSubmit = { entered ->
                    val trimmed = entered.trim()
                    if (trimmed.isEmpty()) {
                        UserFacingError(
                            context.getString(R.string.err_device_name_empty_title),
                            context.getString(R.string.err_device_name_empty_detail),
                        )
                    } else {
                        CastReceiver.setName(context, trimmed)
                        null
                    }
                },
                onDismiss = { showRenameSheet = false },
            )
        }

        if (showLicenseSheet) {
            GplLicenseSheet { showLicenseSheet = false }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    sub: String,
    vertical: Boolean = false,
    control: @Composable () -> Unit,
) {
    if (vertical) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Wall.Radius))
                .background(Wall.Surface.copy(alpha = 0.55f))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(title, style = WallType.Label)
                Text(sub, style = WallType.Caption.copy(fontSize = 20.sp, lineHeight = 26.sp), color = Wall.Text2,
                    modifier = Modifier.padding(top = 4.dp))
            }
            control()
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp)
                .clip(RoundedCornerShape(Wall.Radius))
                .background(Wall.Surface.copy(alpha = 0.55f))
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = WallType.Label)
                Text(sub, style = WallType.Caption.copy(fontSize = 20.sp, lineHeight = 26.sp), color = Wall.Text2,
                    modifier = Modifier.padding(top = 4.dp))
            }
            control()
        }
    }
}

@Composable
private fun GplLicenseSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licenseText = remember(context) {
        runCatching {
            context.assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
        }.getOrNull()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Wall.Scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(960.dp)
                .height(620.dp)
                .shadow(40.dp, RoundedCornerShape(Wall.RadiusXl))
                .clip(RoundedCornerShape(Wall.RadiusXl))
                .background(Wall.Sheet)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 44.dp, vertical = 36.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_about_license_title), style = WallType.Head, modifier = Modifier.weight(1f))
                WallButton(stringResource(R.string.action_close), onDismiss, kind = ButtonKind.Plain, small = true)
            }
            Text(
                stringResource(R.string.settings_about_license_summary),
                style = WallType.Caption,
                color = Wall.Text2,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Wall.Radius))
                    .background(Wall.Bg)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(
                    licenseText ?: stringResource(R.string.settings_about_license_missing),
                    style = WallType.Caption.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                    ),
                    color = Wall.Text2,
                )
            }
        }
    }
}

// Upstream & third-party credits bundled in this headset app (sole location where upstream project names appear).
private val THIRD_PARTY_COMPONENTS = listOf(
    Triple("Moonlight Android / MoonlightXR", "GPLv3", R.string.settings_tp_moonlight_role),
    Triple("UxPlay", "GPLv3", R.string.settings_tp_uxplay_role),
    Triple("ZipDepth / MiDaS", "MIT", R.string.settings_tp_depth_role),
    Triple("AndroidX Media3 (ExoPlayer)", "Apache 2.0", R.string.settings_tp_media3_role),
    Triple("Khronos OpenXR Loader & LiteRT", "Apache 2.0", R.string.settings_tp_openxr_role),
    Triple("OkHttp / BouncyCastle / JmDNS", "Apache 2.0 / MIT", R.string.settings_tp_net_role),
)
