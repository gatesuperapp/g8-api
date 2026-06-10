package com.a4a.g8api.plugins

import com.a4a.g8api.di.appModule
import io.ktor.server.application.*
import org.koin.ktor.plugin.Koin


fun Application.configureDependencyInjection(){
    install(Koin){
        modules(appModule)
    }
}