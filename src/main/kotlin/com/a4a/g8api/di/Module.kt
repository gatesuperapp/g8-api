package com.a4a.g8api.di

import com.a4a.g8api.database.IMagicLinkService
import com.a4a.g8api.database.ISessionService
import com.a4a.g8api.database.ISubscriptionService
import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.database.MagicLinkService
import com.a4a.g8api.database.SessionService
import com.a4a.g8api.database.SubscriptionService
import com.a4a.g8api.database.UsersService
import com.a4a.g8api.services.AbuseDetector
import com.a4a.g8api.services.AuthLogger
import com.a4a.g8api.services.CleanupService
import com.a4a.g8api.services.EmailRateLimiter
import com.a4a.g8api.services.EmailService
import org.koin.dsl.module

val appModule = module {
    single<IUsersService> { UsersService() }
    single<ISessionService> { SessionService() }
    single<IMagicLinkService> { MagicLinkService() }
    single<ISubscriptionService> { SubscriptionService() }
    single { EmailService() }
    single { EmailRateLimiter() }
    single { AuthLogger() }
    single { CleanupService() }
    single { AbuseDetector() }
}
