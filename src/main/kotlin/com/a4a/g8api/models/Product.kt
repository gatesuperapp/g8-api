package com.a4a.g8api.models

import kotlinx.serialization.Serializable


@Serializable
data class Product(val id: Int, val name: String, val quantity: Int)

val productStorage = mutableListOf<Product>().apply { Product(1,"Carrot", 999) }
