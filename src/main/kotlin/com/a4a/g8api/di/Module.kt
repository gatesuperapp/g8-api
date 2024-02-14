package com.a4a.g8api.di

import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.database.UsersService
import org.koin.dsl.module

val appModule= module {
    single<IUsersService> {
        UsersService()
    }
}