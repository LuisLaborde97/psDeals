package com.example.psdeals.model

/** Tiendas del agregador. [id] es el valor que el backend guarda en el campo `platform`. */
enum class StorePlatform(val id: String, val label: String) {
    PLAYSTATION("playstation", "PlayStation"),
    STEAM("steam", "Steam"),
    EPIC("epic", "Epic Games"),
    XBOX("xbox", "Xbox"),
    ;

    companion object {
        fun fromId(id: String): StorePlatform? = entries.firstOrNull { it.id == id }
    }
}
