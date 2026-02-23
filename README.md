# ChessAnalyzer

An Android app that imports your chess games from **Chess.com** and **Lichess**, runs engine analysis with Stockfish, and helps you learn from your mistakes through an interactive practice mode.

---

## Features

- **Multi-platform import** – sync games from Chess.com and Lichess in one place.
- **Engine analysis** – analyse positions with Stockfish (local), the Lichess cloud database, or a hybrid of both.
- **Move classification** – automatically labels moves as blunder, mistake, inaccuracy, good, excellent, or best.
- **Eval bar & chart** – visual advantage chart across all moves in a game.
- **Opening detection** – identifies openings via the Lichess opening database.
- **Train from Mistakes** – interactive practice mode that presents your worst moves and asks you to find the best continuation; tracks solved puzzles and shows a confetti animation on success.
- **Stats & insights** – per-opening win rates, game phase weaknesses, and rating history.
- **Move sounds** – audio feedback for moves, captures, checks, and errors (configurable volume and on/off toggle in Settings).
- **Theming** – light / dark / system theme support.
- **Background analysis** – auto-analyse new games with WorkManager even when the app is closed.

---

## Tech Stack

| Layer | Libraries |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM + StateFlow, Hilt DI |
| Database | Room |
| Networking | Retrofit 2, Moshi, OkHttp |
| Persistence | DataStore Preferences |
| Background | WorkManager |
| Engine | Stockfish (via JNI / bundled binary) |
| Audio | Android `SoundPool` |
| Language | Kotlin (coroutines) |

---

## Building & Running

### Prerequisites

- Android Studio Meerkat (2024.3.1) or newer
- JDK 17
- Android SDK 34 (compile) / min SDK 26

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/xavierselvam/ChessAnalyzer.git
   cd ChessAnalyzer
   ```
2. Open the project in Android Studio (`File → Open`).
3. Let Gradle sync finish.
4. Run on a physical device or an API 26+ emulator via **Run → Run 'app'**.

> **Note:** Stockfish requires a device with an ARM or x86 processor that supports the bundled `.so` library. x86_64 emulators work on most development machines.

---

## Screenshots

| Games List | Game Detail | Practice Mode | Settings |
|---|---|---|---|
| *(screenshot placeholder)* | *(screenshot placeholder)* | *(screenshot placeholder)* | *(screenshot placeholder)* |

---

## Sound Credits

Move, capture, check, and error sounds are adapted from the **[lichess-org/lila](https://github.com/lichess-org/lila)** project (`public/sound`).  
Please verify the specific license and attribution requirements of the individual sound files before redistribution.

---

## License

This project is provided for educational and personal use. See [LICENSE](LICENSE) for details (if present).

