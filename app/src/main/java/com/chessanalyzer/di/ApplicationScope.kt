package com.chessanalyzer.di

import javax.inject.Qualifier

/** Qualifier for a [kotlinx.coroutines.CoroutineScope] tied to the Application lifetime. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
