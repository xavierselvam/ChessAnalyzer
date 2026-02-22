# Chess Analyzer — Android App Project Plan

## 1. Overview

A native Android application that connects to **Chess.com** and **Lichess** accounts, automatically syncs games, and provides AI-powered game reviews using the **Stockfish** chess engine running locally on-device.

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI Framework | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| Build System | Gradle (Kotlin DSL) |
| Local Database | Room (SQLite) |
| Networking | Retrofit + OkHttp + Moshi |
| Dependency Injection | Hilt |
| Async/Concurrency | Kotlin Coroutines + Flow |
| Chess Engine | Stockfish 16 (compiled for ARM via NDK / prebuilt .so) |
| Chess Logic | kotlin-chess or custom FEN/PGN parser |
| Background Sync | WorkManager |
| Navigation | Jetpack Navigation Compose |
| Preferences | DataStore (Preferences) |
| Testing | JUnit 5, Espresso, Turbine (Flow testing) |

---

## 3. Architecture

```
┌──────────────────────────────────────────────────┐
│                   UI Layer                        │
│  Jetpack Compose Screens + ViewModels             │
├──────────────────────────────────────────────────┤
│                 Domain Layer                      │
│  Use Cases / Interactors                          │
├──────────────────────────────────────────────────┤
│                  Data Layer                       │
│  Repositories → Remote (APIs) + Local (Room)      │
├──────────────────────────────────────────────────┤
│               Engine Layer                        │
│  Stockfish Process (NDK / stdin-stdout bridge)    │
└──────────────────────────────────────────────────┘
```

---

## 4. Features & Modules

### 4.1 Account Configuration
- **Screen:** Settings / Accounts
- User enters Chess.com username (public API, no auth needed)
- User enters Lichess username (or OAuth2 personal token for private games)
- Credentials stored in encrypted DataStore
- Validation: check if user exists via API before saving

### 4.2 Game Sync
- **Trigger:** App open (+ pull-to-refresh + periodic WorkManager)
- **Chess.com API:** `https://api.chess.com/pub/player/{username}/games/archives` → fetch monthly archives → fetch games
- **Lichess API:** `https://lichess.org/api/games/user/{username}?max=50&pgnInJson=true` (NDJSON streaming)
- Incremental sync: store last-synced timestamp per platform, only fetch new games
- Games stored in Room DB with: id, platform, pgn, white, black, result, date, analysisStatus

### 4.3 Game List
- **Screen:** Home / Games list
- Grouped by date, filterable by platform (Chess.com / Lichess / All)
- Each card shows: opponent, result (W/L/D), time control, date, analysis badge
- Tap → Game Detail

### 4.4 Game Detail & Board View
- **Screen:** Game viewer
- Interactive chessboard (Compose Canvas or custom View)
- Move-by-move navigation (forward/back/start/end buttons + swipe)
- Move list panel (algebraic notation, highlighted current move)
- Evaluation bar (updates per move after analysis)

### 4.5 Game Analysis (Stockfish)
- Run Stockfish binary as a native process via `ProcessBuilder`
- Communicate via UCI protocol over stdin/stdout
- For each position in the game:
  - Send `position fen <fen>` + `go depth 18` (configurable)
  - Collect `bestmove` + `info score cp/mate`
- Classify each move:
  - **Brilliant** (finds only winning move in complex position)
  - **Great** (top engine move)
  - **Good** (within 0.3 pawn of best)
  - **Inaccuracy** (0.3–1.0 pawn loss)
  - **Mistake** (1.0–3.0 pawn loss)
  - **Blunder** (>3.0 pawn loss or misses mate)
- Store per-move evaluation in Room DB
- Background analysis via coroutine (show progress bar)

### 4.6 Game Review Summary
- **Screen:** Review tab on Game Detail
- Overall accuracy score (0–100) per player
- Move classification breakdown (pie chart or bar chart)
- Key moments: worst blunders highlighted with engine suggestion
- Opening name detection (ECO code lookup)

### 4.7 Settings
- Engine depth (12/15/18/20)
- Auto-analyze new games toggle
- Theme (light/dark/system)
- Clear cache / re-sync

---

## 5. Data Models

### 5.1 Room Entities

```kotlin
@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val id: String,           // platform + game_id
    val platform: String,                  // "chesscom" | "lichess"
    val pgn: String,
    val white: String,
    val black: String,
    val result: String,                    // "1-0", "0-1", "1/2-1/2"
    val timeControl: String,
    val playedAt: Long,                    // epoch millis
    val opening: String?,
    val analysisStatus: String,            // "pending" | "analyzing" | "done"
    val userColor: String,                 // "white" | "black"
    val accuracy: Float?                   // 0-100 after analysis
)

@Entity(tableName = "move_evaluations")
data class MoveEvaluationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val moveNumber: Int,
    val color: String,                     // "white" | "black"
    val moveSan: String,                   // e.g. "Nf3"
    val fen: String,
    val evalBefore: Int,                   // centipawns
    val evalAfter: Int,
    val bestMove: String,                  // engine best move SAN
    val classification: String,            // "brilliant"|"great"|"good"|"inaccuracy"|"mistake"|"blunder"
    val isMate: Boolean,
    val mateIn: Int?
)
```

### 5.2 API Response Models

```kotlin
// Chess.com
data class ChessComArchivesResponse(val archives: List<String>)
data class ChessComGamesResponse(val games: List<ChessComGame>)
data class ChessComGame(
    val url: String, val pgn: String, val end_time: Long,
    val time_control: String, val white: ChessComPlayer, val black: ChessComPlayer
)
data class ChessComPlayer(val username: String, val result: String)

// Lichess (NDJSON)
data class LichessGame(
    val id: String, val rated: Boolean, val variant: String,
    val speed: String, val pgn: String?, val players: LichessPlayers,
    val createdAt: Long, val opening: LichessOpening?
)
```

---

## 6. Package Structure

```
com.chessanalyzer
├── app/
│   ├── ChessAnalyzerApp.kt            (Application class + Hilt)
│   └── MainActivity.kt
├── data/
│   ├── local/
│   │   ├── db/
│   │   │   ├── AppDatabase.kt
│   │   │   ├── GameDao.kt
│   │   │   └── MoveEvaluationDao.kt
│   │   └── preferences/
│   │       └── UserPreferences.kt
│   ├── remote/
│   │   ├── chesscom/
│   │   │   ├── ChessComApi.kt
│   │   │   └── ChessComModels.kt
│   │   └── lichess/
│   │       ├── LichessApi.kt
│   │       └── LichessModels.kt
│   └── repository/
│       ├── GameRepository.kt
│       └── SyncRepository.kt
├── domain/
│   ├── model/
│   │   ├── Game.kt
│   │   ├── MoveEvaluation.kt
│   │   └── AnalysisResult.kt
│   ├── usecase/
│   │   ├── SyncGamesUseCase.kt
│   │   ├── AnalyzeGameUseCase.kt
│   │   ├── GetGamesUseCase.kt
│   │   └── GetGameReviewUseCase.kt
│   └── engine/
│       ├── StockfishEngine.kt          (UCI protocol bridge)
│       ├── PositionEvaluator.kt
│       └── MoveClassifier.kt
├── di/
│   ├── AppModule.kt
│   ├── DatabaseModule.kt
│   └── NetworkModule.kt
├── ui/
│   ├── navigation/
│   │   └── NavGraph.kt
│   ├── theme/
│   │   └── Theme.kt
│   ├── screens/
│   │   ├── games/
│   │   │   ├── GamesScreen.kt
│   │   │   └── GamesViewModel.kt
│   │   ├── gamedetail/
│   │   │   ├── GameDetailScreen.kt
│   │   │   └── GameDetailViewModel.kt
│   │   ├── review/
│   │   │   ├── ReviewScreen.kt
│   │   │   └── ReviewViewModel.kt
│   │   └── settings/
│   │       ├── SettingsScreen.kt
│   │       └── SettingsViewModel.kt
│   └── components/
│       ├── ChessBoard.kt
│       ├── EvalBar.kt
│       ├── MoveList.kt
│       └── GameCard.kt
└── worker/
    └── SyncWorker.kt                   (WorkManager)
```

---

## 7. Stockfish Integration Plan

1. **Binary:** Download precompiled Stockfish ARM64 binary (or compile via NDK CMake)
2. **Asset placement:** `app/src/main/assets/stockfish`
3. **Runtime:** On first launch, copy binary to `filesDir`, set executable permission
4. **Process:** `ProcessBuilder(stockfishPath).start()` → get stdin/stdout streams
5. **UCI Protocol:**
   ```
   → uci
   ← uciok
   → isready
   ← readyok
   → position fen <fen>
   → go depth 18
   ← info depth 18 score cp 35 ... pv e2e4 ...
   ← bestmove e2e4 ponder d7d5
   ```
6. **Thread safety:** Single coroutine dispatcher for engine communication
7. **Lifecycle:** Start engine process when analysis begins, kill when done or app backgrounded

---

## 8. API Integration Details

### Chess.com (Public API — no auth)
| Endpoint | Purpose |
|---|---|
| `GET /pub/player/{username}` | Validate username |
| `GET /pub/player/{username}/games/archives` | Get monthly archive URLs |
| `GET /pub/player/{username}/games/{YYYY}/{MM}` | Get games for a month |

### Lichess (Public API — optional OAuth)
| Endpoint | Purpose |
|---|---|
| `GET /api/user/{username}` | Validate username |
| `GET /api/games/user/{username}` | Export games (NDJSON or PGN) |

- Lichess streams NDJSON — use OkHttp streaming response + line-by-line parsing
- Rate limits: Chess.com is lenient; Lichess allows 20 req/sec for OAuth, 1 req/sec anon

---

## 9. Implementation Phases

### Phase 1 — Project Scaffold & Core Infrastructure
- [ ] Initialize Android project (Compose, Hilt, Room, Retrofit)
- [ ] Set up Gradle with all dependencies
- [ ] Create Room database + entities + DAOs
- [ ] Create DataStore for user preferences
- [ ] Set up Hilt DI modules
- [ ] Navigation graph skeleton

### Phase 2 — Account Configuration & API Integration
- [ ] Settings screen with Chess.com / Lichess username fields
- [ ] Chess.com API service + response models
- [ ] Lichess API service + NDJSON parser
- [ ] Username validation endpoints
- [ ] GameRepository implementation

### Phase 3 — Game Sync & Game List
- [ ] SyncRepository: incremental sync logic
- [ ] SyncWorker (WorkManager) for auto-sync on app open
- [ ] Games list screen (grouped, filterable)
- [ ] GameCard composable
- [ ] Pull-to-refresh

### Phase 4 — Chess Board & Game Viewer
- [ ] PGN parser → list of moves + FEN per position
- [ ] ChessBoard composable (Canvas-based rendering)
- [ ] Move navigation (buttons + move list tap)
- [ ] EvalBar composable

### Phase 5 — Stockfish Engine Integration
- [ ] Bundle Stockfish binary for ARM64/ARM32/x86_64
- [ ] StockfishEngine class (process lifecycle + UCI communication)
- [ ] PositionEvaluator (evaluate each position in a game)
- [ ] MoveClassifier (classify centipawn loss into categories)
- [ ] Background analysis with progress tracking

### Phase 6 — Game Review & Polish
- [ ] Review summary screen (accuracy, move breakdown chart)
- [ ] Key moments / blunder highlights
- [ ] Opening name display (ECO database)
- [ ] Dark/Light theme
- [ ] Error handling, edge cases, empty states
- [ ] Performance optimization (lazy loading, caching)

### Phase 7 — Testing & Release
- [ ] Unit tests (repositories, use cases, engine, PGN parser)
- [ ] Integration tests (DB, API)
- [ ] UI tests (Compose testing)
- [ ] ProGuard / R8 rules
- [ ] App icon, splash screen
- [ ] Build signed APK / AAB

---

## 10. Key Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Stockfish binary size (~10 MB per ABI) | Use app bundles (ABI splits) |
| Engine analysis is CPU-intensive | Limit depth, run on background thread, show progress |
| Chess.com API rate limiting | Cache aggressively, exponential backoff |
| Lichess NDJSON streaming complexity | Use OkHttp streaming + coroutine channel |
| PGN parsing edge cases | Use well-tested PGN parser library or thorough unit tests |
| Large game libraries (1000+ games) | Paginated sync, incremental analysis |

---

## 11. Third-Party Libraries (Planned)

```kotlin
// build.gradle.kts (app)
dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    
    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // Network
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

---

## 12. Minimum Viable Product (MVP) Scope

For the first working version, focus on:
1. **Configure one account** (Chess.com — simpler API)
2. **Sync recent games** (last 50)
3. **View game list + board**
4. **Analyze one game** with Stockfish (depth 15)
5. **Show move classifications** + accuracy score

Lichess integration, charts, WorkManager background sync, and advanced settings come in subsequent iterations.

---

*Ready for implementation. Proceeding with Phase 1 after plan approval.*
