package com.a4a.g8api.database

import com.a4a.g8api.models.RefreshToken
import com.a4a.g8api.models.User
import org.jetbrains.exposed.sql.ResultRow

interface IUsersService {
    fun resultRowToUser(row: ResultRow): User

    suspend fun allUsers(): List<User>

    suspend fun userById(id: Int): User?

    suspend fun userByEmail(email: String): User?

    suspend fun createUser(user: User): Int


    fun resultRowToRefreshToken(row: ResultRow): RefreshToken
    suspend fun refreshTokenByUserId(id : Int) : RefreshToken?
}