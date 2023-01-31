/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.customization.picker.quickaffordance.ui.binder

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.SurfaceView
import androidx.cardview.widget.CardView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.android.customization.picker.quickaffordance.ui.viewmodel.KeyguardQuickAffordancePickerViewModel
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardQuickAffordancePreviewConstants
import com.android.wallpaper.R
import com.android.wallpaper.asset.Asset
import com.android.wallpaper.asset.BitmapCachingAsset
import com.android.wallpaper.model.LiveWallpaperInfo
import com.android.wallpaper.model.WallpaperInfo
import com.android.wallpaper.picker.WorkspaceSurfaceHolderCallback
import com.android.wallpaper.util.PreviewUtils
import com.android.wallpaper.util.ResourceUtils
import com.android.wallpaper.util.WallpaperConnection
import com.android.wallpaper.util.WallpaperSurfaceCallback
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.launch

object KeyguardQuickAffordancePreviewBinder {

    /** Binds view for the preview of the lock screen. */
    @JvmStatic
    fun bind(
        activity: Activity,
        previewView: CardView,
        viewModel: KeyguardQuickAffordancePickerViewModel,
        lifecycleOwner: LifecycleOwner,
        wallpaperInfoProvider: suspend () -> WallpaperInfo?,
    ) {
        val workspaceSurface: SurfaceView = previewView.requireViewById(R.id.workspace_surface)
        val wallpaperSurface: SurfaceView = previewView.requireViewById(R.id.wallpaper_surface)

        previewView.radius =
            previewView.resources.getDimension(R.dimen.wallpaper_picker_entry_card_corner_radius)
        previewView.contentDescription =
            previewView.context.getString(
                R.string.lockscreen_wallpaper_preview_card_content_description
            )

        var previewSurfaceCallback: WorkspaceSurfaceHolderCallback? = null
        var wallpaperSurfaceCallback: WallpaperSurfaceCallback? = null
        var wallpaperConnection: WallpaperConnection? = null
        var wallpaperInfo: WallpaperInfo? = null

        lifecycleOwner.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> {
                        previewSurfaceCallback =
                            WorkspaceSurfaceHolderCallback(
                                workspaceSurface,
                                PreviewUtils(
                                    context = previewView.context,
                                    authority =
                                        previewView.context.getString(
                                            R.string.lock_screen_preview_provider_authority
                                        ),
                                ),
                                Bundle().apply {
                                    putString(
                                        KeyguardQuickAffordancePreviewConstants
                                            .KEY_INITIALLY_SELECTED_SLOT_ID,
                                        viewModel.selectedSlotId.value,
                                    )
                                },
                            )
                        workspaceSurface.holder.addCallback(previewSurfaceCallback)
                        workspaceSurface.setZOrderMediaOverlay(true)

                        wallpaperSurfaceCallback =
                            WallpaperSurfaceCallback(
                                previewView.context,
                                previewView,
                                wallpaperSurface,
                                CompletableFuture.completedFuture(
                                    WallpaperInfo.ColorInfo(
                                        /* wallpaperColors= */ null,
                                        ResourceUtils.getColorAttr(
                                            previewView.context,
                                            android.R.attr.colorSecondary,
                                        )
                                    )
                                ),
                            ) {
                                maybeLoadThumbnail(
                                    activity = activity,
                                    wallpaperInfo = wallpaperInfo,
                                    surfaceCallback = wallpaperSurfaceCallback,
                                )
                            }
                        wallpaperSurface.holder.addCallback(wallpaperSurfaceCallback)
                        wallpaperSurface.setZOrderMediaOverlay(true)
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        workspaceSurface.holder.removeCallback(previewSurfaceCallback)
                        previewSurfaceCallback?.cleanUp()
                        wallpaperSurface.holder.removeCallback(wallpaperSurfaceCallback)
                        wallpaperSurfaceCallback?.cleanUp()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        lifecycleOwner.lifecycleScope.launch {
                            wallpaperInfo = wallpaperInfoProvider()
                            (wallpaperInfo as? LiveWallpaperInfo)?.let { liveWallpaperInfo ->
                                if (WallpaperConnection.isPreviewAvailable()) {
                                    wallpaperConnection =
                                        WallpaperConnection(
                                            Intent(WallpaperService.SERVICE_INTERFACE).apply {
                                                setClassName(
                                                    liveWallpaperInfo.wallpaperComponent
                                                        .packageName,
                                                    liveWallpaperInfo.wallpaperComponent.serviceName
                                                )
                                            },
                                            previewView.context,
                                            null,
                                            wallpaperSurface,
                                            null,
                                        )

                                    wallpaperConnection?.connect()
                                    wallpaperConnection?.setVisibility(true)
                                }
                            }
                            maybeLoadThumbnail(
                                activity = activity,
                                wallpaperInfo = wallpaperInfo,
                                surfaceCallback = wallpaperSurfaceCallback,
                            )
                        }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        wallpaperConnection?.setVisibility(false)
                    }
                    Lifecycle.Event.ON_STOP -> {
                        wallpaperConnection?.disconnect()
                    }
                    else -> Unit
                }
            }
        )

        lifecycleOwner.lifecycleScope.launch {
            viewModel.selectedSlotId
                .flowWithLifecycle(lifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { slotId ->
                    previewSurfaceCallback?.send(
                        KeyguardQuickAffordancePreviewConstants.MESSAGE_ID_SLOT_SELECTED,
                        Bundle().apply {
                            putString(KeyguardQuickAffordancePreviewConstants.KEY_SLOT_ID, slotId)
                        },
                    )
                }
        }
    }

    private fun maybeLoadThumbnail(
        activity: Activity,
        wallpaperInfo: WallpaperInfo?,
        surfaceCallback: WallpaperSurfaceCallback?,
    ) {
        if (wallpaperInfo == null || surfaceCallback == null) {
            return
        }

        val imageView = surfaceCallback.homeImageWallpaper
        val thumbAsset: Asset = BitmapCachingAsset(activity, wallpaperInfo.getThumbAsset(activity))
        if (imageView != null && imageView.drawable == null) {
            thumbAsset.loadPreviewImage(
                activity,
                imageView,
                ResourceUtils.getColorAttr(activity, android.R.attr.colorSecondary)
            )
        }
    }
}
