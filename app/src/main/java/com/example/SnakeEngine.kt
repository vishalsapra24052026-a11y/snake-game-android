package com.example

import android.util.Log

object SnakeEngine {
    private var isLibLoaded = false

    init {
        try {
            System.loadLibrary("snake_engine")
            isLibLoaded = true
            Log.i("SnakeEngine", "Successfully loaded native snake_engine C++ library!")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("SnakeEngine", "Could not load native snake_engine: ${e.message}")
        }
    }

    fun isNativeActive(): Boolean = isLibLoaded

    external fun getEngineVersion(): String

    external fun stepGame(
        snakeX: IntArray,
        snakeY: IntArray,
        direction: Int,
        appleX: Int,
        appleY: Int,
        wallMode: Int,
        gridWidth: Int,
        gridHeight: Int
    ): IntArray
}
