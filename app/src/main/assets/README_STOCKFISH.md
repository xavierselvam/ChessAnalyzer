# Stockfish Binary Placeholder

This directory should contain the Stockfish chess engine binary for Android ARM64.

## How to obtain:

### Option 1: Download pre-built binary
1. Go to https://stockfishchess.org/download/
2. Download the Android (ARM64) binary
3. Place it here and rename to `stockfish`

### Option 2: Build from source
1. Clone https://github.com/official-stockfish/Stockfish
2. Build with Android NDK:
   ```bash
   cd src
   make -j build ARCH=armv8 COMP=ndk
   ```
3. Copy the resulting binary here as `stockfish`

### Option 3: Use Stockfish Android library
Consider using https://github.com/nicklasoxhammar/stockfish-android
which provides a pre-compiled AAR.

## Note
The binary should be executable. The app will copy it to internal storage
and set executable permissions at runtime.
