package com.a4a.g8api.database

import com.a4a.g8api.models.User
import org.jetbrains.exposed.sql.ResultRow

interface IUsersService {
    fun resultRowToUser(row: ResultRow): User

    suspend fun allUsers(): List<User>

    suspend fun userById(id: Int): User?

    suspend fun userByEmailAndPassword(email: String, password: String): User?

    suspend fun createUser(user: User): Int
}