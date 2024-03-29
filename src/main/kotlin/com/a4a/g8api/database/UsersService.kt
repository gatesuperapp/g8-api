package com.a4a.g8api.database

import com.a4a.g8api.models.RefreshToken
import com.a4a.g8api.models.User
import com.a4a.g8api.plugins.dbQuery
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/*
This implementation was inspired by : https://github.com/JetBrains/Exposed/blob/main/samples/exposed-ktor/src/main/kotlin/plugins/UsersSchema.kt
THe exposed repo is more helpful than the official documentation...
 */
class UsersService () : IUsersService {

    object Users : Table() {
        val id = integer("id").autoIncrement()
        val firstName = varchar("firstName", 128)
        val lastName = varchar("lastName", 1024)
        val email = varchar("email", 1024)
        val password = varchar("password", 1024)

        override val primaryKey = PrimaryKey(id)
    }

    object RefreshTokens : Table() {
        val id = integer("id").autoIncrement()
        val token = varchar("token", 60)
        val expiresAt = datetime("expiresAt")
        val userId = integer("userId").references(Users.id)

        override val primaryKey = PrimaryKey(id)
    }
    override fun resultRowToUser(row: ResultRow) = User(
        id = row[Users.id],
        firstName = row[Users.firstName],
        lastName = row[Users.lastName],
        email = row[Users.email],
        password = row[Users.password]
    )

    override fun resultRowToRefreshToken(row: ResultRow) = RefreshToken(
        id = row[RefreshTokens.id],
        token = row[RefreshTokens.token],
        expiresAt = row[RefreshTokens.expiresAt],
        userId = row[RefreshTokens.userId]
    )

    override suspend fun allUsers(): List<User> = dbQuery {
        Users.selectAll().map(::resultRowToUser)
    }

    override suspend fun userById(id: Int): User? = dbQuery {
        Users
            .selectAll().where { Users.id eq id }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    override suspend fun userByEmail(email: String): User? = dbQuery {
        Users
            .selectAll().where { Users.email eq email }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    override suspend fun createUser(user : User): Int = dbQuery {
        Users.insert {
            it[lastName] = user.lastName
            it[firstName] = user.firstName
            it[email] = user.email
            it[password] = user.password
        }[Users.id]
    }

    override suspend fun refreshTokenByToken(token :String): RefreshToken? = dbQuery {
        RefreshTokens
            .selectAll().where { RefreshTokens.token eq token }
            .map(::resultRowToRefreshToken)
            .singleOrNull()
    }

    override suspend fun saveRefreshToken(refreshToken: RefreshToken): Int = dbQuery {
        RefreshTokens.insert {
            it[token] = refreshToken.token
            it[expiresAt] = refreshToken.expiresAt
            it[userId] = refreshToken.userId
        }[RefreshTokens.id]
    }

    override suspend fun deleteRefreshToken(refreshTokenId: Int): Unit = dbQuery {
        RefreshTokens.deleteWhere { id eq refreshTokenId }
    }

}