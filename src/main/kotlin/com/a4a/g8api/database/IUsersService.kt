package com.a4a.g8api.database

import com.a4a.g8api.models.User
import org.jetbrains.exposed.sql.ResultRow
import java.util.UUID

interface IUsersService {
    fun resultRowToUser(row: ResultRow): User
    suspend fun userById(id: UUID): User?
    suspend fun userByEmail(email: String): User?
    suspend fun createUser(email: String): UUID
    suspend fun updateStripeCustomerId(userId: UUID, customerId: String)
    suspend fun softDeleteUser(userId: UUID)
}
