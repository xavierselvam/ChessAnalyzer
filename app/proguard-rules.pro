# ProGuard rules for Chess Analyzer

# Keep Moshi adapters
-keep class com.chessanalyzer.data.remote.chesscom.** { *; }
-keep class com.chessanalyzer.data.remote.lichess.** { *; }

# Keep Stockfish native process
-keep class com.chessanalyzer.domain.engine.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }

# Moshi
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
