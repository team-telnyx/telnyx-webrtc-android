package com.telnyx.webrtc.sdk.di

import android.content.Context
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.utility.telecom.call.TelecomCallManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelnyxClientModule {
    @Singleton
    @Provides
    fun provideTelnyxClient(@ApplicationContext context: Context): TelnyxClient {
        return TelnyxClient(context)
    }

    @Singleton
    @Provides
    fun provideTelecomCallManager(
        telnyxClient: TelnyxClient,
        userManager: UserManager,
    ): TelecomCallManager {
        return TelecomCallManager(telnyxClient, userManager)
    }
}