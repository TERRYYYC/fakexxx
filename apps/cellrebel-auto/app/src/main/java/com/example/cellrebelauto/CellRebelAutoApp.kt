package com.example.cellrebelauto

import android.app.Application
import com.example.cellrebelauto.db.AppDatabase

/**
 * Application class — initializes database singleton.
 * # Application 类：初始化数据库单例
 */
class CellRebelAutoApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
