package com.a4a.g8api.database

import com.a4a.g8api.models.User
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/*
This implementation was inspired by : https://github.com/JetBrains/Exposed/blob/main/samples/exposed-ktor/src/main/kotlin/plugins/UsersSchema.kt
THe exposed repo is more helpful than the official documentation...
 */
class UsersService () {

    val driverClassName = "org.h2.Driver"
    val jdbcURL = "jdbc:h2:file:./build/g8-db"
    val database = Database.connect(jdbcURL, driverClassName)

    //The init block will execute immediately after the primary constructor.
    //Notice that there is no fun or parenthesis
    init {

        //Initialize the database with a single table
        transaction(database) {
            SchemaUtils.create(Users)
        }
    }

    object Users : Table() {
        val id = integer("id").autoIncrement()
        val firstName = varchar("firstName", 128)
        val lastName = varchar("lastName", 1024)
        val email = varchar("email", 1024)
        val password = varchar("password", 1024)

        override val primaryKey = PrimaryKey(id)
    }



    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    private fun resultRowToUser(row: ResultRow) = User(
        id = row[Users.id],
        firstName = row[Users.firstName],
        lastName = row[Users.lastName],
        email = row[Users.email],
        password = row[Users.password]
    )

    suspend fun allUsers(): List<User> = dbQuery {
        Users.selectAll().map(::resultRowToUser)
    }

    suspend fun userById(id: Int): User? = dbQuery {
        Users
            .selectAll().where { Users.id eq id }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    suspend fun userByEmailAndPassword(email: String, password: String): User? = dbQuery {
        Users
            .selectAll().where { Users.email eq email }
            .andWhere { Users.password eq password }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    suspend fun createUser(user : User): Int = dbQuery {
        Users.insert {
            it[lastName] = user.lastName
            it[firstName] = user.firstName
            it[email] = user.email
            it[password] = user.password
        }[Users.id]
    }
}