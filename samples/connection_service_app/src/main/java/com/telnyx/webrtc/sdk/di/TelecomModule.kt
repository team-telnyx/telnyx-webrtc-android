package com.telnyx.webrtc.sdk.di

import android.content.Context
import androidx.core.telecom.CallsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelecomModule {

    @Provides
    @Singleton
    fun provideCallsManager(
        @ApplicationContext context: Context
    ): CallsManager {
        return CallsManager(context).apply {
            // Register with telecom interface
            registerAppWithTelecom(
                capabilities = (CallsManager.CAPABILITY_SUPPORTS_CALL_STREAMING and
                        CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
            )
        }
    }
}