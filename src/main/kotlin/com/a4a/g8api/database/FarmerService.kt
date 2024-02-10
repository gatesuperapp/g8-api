package com.a4a.g8api.database

import com.a4a.g8api.AppConfig
import com.a4a.g8api.models.Farmer
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/*
This implementation was inspired by : https://github.com/JetBrains/Exposed/blob/main/samples/exposed-ktor/src/main/kotlin/plugins/UsersSchema.kt
THe exposed repo is more helpful than the official documentation...
 */
class FarmerService () {

    val driverClassName = "org.h2.Driver"
    val jdbcURL = "jdbc:h2:file:./build/g8-db"
    val database = Database.connect(jdbcURL, driverClassName)

    //The init block will execute immediately after the primary constructor.
    //Notice that there is no fun or parenthesis
    init {

        //Initialize the database with a single table
        transaction(database) {
            SchemaUtils.create(Farmers)
        }
    }

    object Farmers : Table() {
        val id = integer("id").autoIncrement()
        val firstName = varchar("firstName", 128)
        val lastName = varchar("lastName", 1024)
        val email = varchar("email", 1024)
        val password = varchar("password", 1024)

        override val primaryKey = PrimaryKey(id)
    }



    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    private fun resultRowToFarmer(row: ResultRow) = Farmer(
        id = row[Farmers.id],
        firstName = row[Farmers.firstName],
        lastName = row[Farmers.lastName],
        email = row[Farmers.email],
        password = row[Farmers.password]
    )

    suspend fun allFarmers(): List<Farmer> = dbQuery {
        Farmers.selectAll().map(::resultRowToFarmer)
    }

    suspend fun farmerById(id: Int): Farmer? = dbQuery {
        Farmers
            .selectAll().where { Farmers.id eq id }
            .map(::resultRowToFarmer)
            .singleOrNull()
    }

    suspend fun createFarmer(farmer : Farmer): Int = dbQuery {
        Farmers.insert {
            it[lastName] = farmer.lastName
            it[firstName] = farmer.firstName
            it[email] = farmer.email
            it[password] = farmer.password
        }[Farmers.id]
    }
}