package com.a4a.g8api.database

import com.a4a.g8api.models.User
import com.a4a.g8api.plugins.dbQuery
import kotlinx.datetime.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

class UsersService : IUsersService {

    object Users : Table("users") {
        val id = uuid("id").autoGenerate()
        // Uniqueness is enforced by the partial index `users_email_active_unique`
        // created in Database.kt (WHERE deleted_at IS NULL). The partial form lets a
        // soft-deleted account's email be re-used by a new signup.
        val email = varchar("email", 1024)
        val stripeCustomerId = varchar("stripe_customer_id", 256).nullable()
        val createdAt = datetime("created_at")
        val updatedAt = datetime("updated_at")
        val deletedAt = datetime("deleted_at").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    override fun resultRowToUser(row: ResultRow) = User(
        id = row[Users.id],
        email = row[Users.email],
        stripeCustomerId = row[Users.stripeCustomerId]
    )

    override suspend fun userById(id: UUID): User? = dbQuery {
        Users.selectAll()
            .where { (Users.id eq id) and Users.deletedAt.isNull() }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    override suspend fun userByEmail(email: String): User? = dbQuery {
        Users.selectAll()
            .where { (Users.email eq email.lowercase().trim()) and Users.deletedAt.isNull() }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    override suspend fun createUser(email: String): UUID = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        Users.insert {
            it[Users.email] = email.lowercase().trim()
            it[createdAt] = now
            it[updatedAt] = now
        }[Users.id]
    }

    override suspend fun updateStripeCustomerId(userId: UUID, customerId: String) = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        Users.update({ Users.id eq userId }) {
            it[stripeCustomerId] = customerId
            it[updatedAt] = now
        }
        Unit
    }

    override suspend fun softDeleteUser(userId: UUID) = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        Users.update({ Users.id eq userId }) {
            it[deletedAt] = now
            it[updatedAt] = now
        }
        Unit
    }
}
