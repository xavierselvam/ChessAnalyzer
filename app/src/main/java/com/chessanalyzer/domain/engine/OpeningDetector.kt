package com.chessanalyzer.domain.engine

/**
 * ECO opening detection by matching move sequences.
 * Maps first N moves to opening name.
 *
 * This is a curated set of the most common openings.
 * Real app could load from a full ECO database JSON asset.
 */
object OpeningDetector {

    data class Opening(val eco: String, val name: String, val moves: List<String>)

    /** Detect opening from a list of SAN moves. Matches longest prefix. */
    fun detect(moves: List<String>): String? {
        if (moves.isEmpty()) return null

        var bestMatch: Opening? = null
        var bestLen = 0

        for (opening in OPENINGS) {
            if (opening.moves.size <= moves.size) {
                val prefix = moves.take(opening.moves.size)
                if (prefix == opening.moves && opening.moves.size > bestLen) {
                    bestMatch = opening
                    bestLen = opening.moves.size
                }
            }
        }

        return bestMatch?.name
    }

    // Curated list of 80+ common openings (first 4-12 moves)
    private val OPENINGS = listOf(
        // Sicilian Defense variants
        Opening("B20", "Sicilian Defense", listOf("e4", "c5")),
        Opening("B21", "Sicilian Defense: Smith-Morra Gambit", listOf("e4", "c5", "d4", "cxd4", "c3")),
        Opening("B30", "Sicilian Defense: Old Sicilian", listOf("e4", "c5", "Nf3", "Nc6")),
        Opening("B33", "Sicilian Defense: Sveshnikov", listOf("e4", "c5", "Nf3", "Nc6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "e5")),
        Opening("B50", "Sicilian Defense", listOf("e4", "c5", "Nf3", "d6")),
        Opening("B60", "Sicilian Defense: Richter-Rauzer", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "Nc6", "Bg5")),
        Opening("B90", "Sicilian Defense: Najdorf Variation", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6")),
        Opening("B80", "Sicilian Defense: Scheveningen", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "e6")),
        Opening("B96", "Sicilian Defense: Najdorf, English Attack", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6", "Be3")),

        // French Defense
        Opening("C00", "French Defense", listOf("e4", "e6")),
        Opening("C01", "French Defense: Exchange Variation", listOf("e4", "e6", "d4", "d5", "exd5", "exd5")),
        Opening("C02", "French Defense: Advance Variation", listOf("e4", "e6", "d4", "d5", "e5")),
        Opening("C03", "French Defense: Tarrasch Variation", listOf("e4", "e6", "d4", "d5", "Nd2")),
        Opening("C11", "French Defense: Classical", listOf("e4", "e6", "d4", "d5", "Nc3", "Nf6")),
        Opening("C18", "French Defense: Winawer", listOf("e4", "e6", "d4", "d5", "Nc3", "Bb4")),

        // Caro-Kann
        Opening("B10", "Caro-Kann Defense", listOf("e4", "c6")),
        Opening("B12", "Caro-Kann Defense: Advance Variation", listOf("e4", "c6", "d4", "d5", "e5")),
        Opening("B13", "Caro-Kann Defense: Exchange Variation", listOf("e4", "c6", "d4", "d5", "exd5", "cxd5")),
        Opening("B15", "Caro-Kann Defense: Main Line", listOf("e4", "c6", "d4", "d5", "Nc3", "dxe4", "Nxe4")),

        // Italian Game
        Opening("C50", "Italian Game", listOf("e4", "e5", "Nf3", "Nc6", "Bc4")),
        Opening("C51", "Evans Gambit", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "b4")),
        Opening("C53", "Italian Game: Classical", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5")),
        Opening("C54", "Italian Game: Giuoco Piano", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "c3")),

        // Ruy Lopez
        Opening("C60", "Ruy Lopez", listOf("e4", "e5", "Nf3", "Nc6", "Bb5")),
        Opening("C65", "Ruy Lopez: Berlin Defense", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "Nf6")),
        Opening("C68", "Ruy Lopez: Exchange Variation", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Bxc6")),
        Opening("C78", "Ruy Lopez: Morphy Defense", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6")),
        Opening("C84", "Ruy Lopez: Closed", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7")),
        Opening("C88", "Ruy Lopez: Marshall Attack", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7", "Re1", "b5", "Bb3", "O-O", "c3", "d5")),

        // Scotch Game
        Opening("C44", "Scotch Game", listOf("e4", "e5", "Nf3", "Nc6", "d4")),
        Opening("C45", "Scotch Game: Classical", listOf("e4", "e5", "Nf3", "Nc6", "d4", "exd4", "Nxd4")),

        // King's Gambit
        Opening("C30", "King's Gambit", listOf("e4", "e5", "f4")),
        Opening("C33", "King's Gambit Accepted", listOf("e4", "e5", "f4", "exf4")),

        // Pirc / Modern
        Opening("B07", "Pirc Defense", listOf("e4", "d6", "d4", "Nf6", "Nc3")),
        Opening("B06", "Modern Defense", listOf("e4", "g6")),

        // Scandinavian
        Opening("B01", "Scandinavian Defense", listOf("e4", "d5")),
        Opening("B01", "Scandinavian Defense: Mieses-Kotrč", listOf("e4", "d5", "exd5", "Qxd5")),

        // Alekhine
        Opening("B02", "Alekhine's Defense", listOf("e4", "Nf6")),

        // Queen's Gambit
        Opening("D06", "Queen's Gambit", listOf("d4", "d5", "c4")),
        Opening("D10", "Queen's Gambit: Slav Defense", listOf("d4", "d5", "c4", "c6")),
        Opening("D30", "Queen's Gambit Declined", listOf("d4", "d5", "c4", "e6")),
        Opening("D35", "Queen's Gambit Declined: Exchange", listOf("d4", "d5", "c4", "e6", "Nc3", "Nf6", "cxd5", "exd5")),
        Opening("D37", "Queen's Gambit Declined: Classical", listOf("d4", "d5", "c4", "e6", "Nc3", "Nf6", "Nf3", "Be7")),
        Opening("D20", "Queen's Gambit Accepted", listOf("d4", "d5", "c4", "dxc4")),

        // Nimzo-Indian
        Opening("E20", "Nimzo-Indian Defense", listOf("d4", "Nf6", "c4", "e6", "Nc3", "Bb4")),
        Opening("E32", "Nimzo-Indian Defense: Classical", listOf("d4", "Nf6", "c4", "e6", "Nc3", "Bb4", "Qc2")),

        // Queen's Indian
        Opening("E15", "Queen's Indian Defense", listOf("d4", "Nf6", "c4", "e6", "Nf3", "b6")),

        // King's Indian
        Opening("E60", "King's Indian Defense", listOf("d4", "Nf6", "c4", "g6")),
        Opening("E62", "King's Indian Defense: Fianchetto", listOf("d4", "Nf6", "c4", "g6", "g3")),
        Opening("E76", "King's Indian Defense: Four Pawns Attack", listOf("d4", "Nf6", "c4", "g6", "Nc3", "Bg7", "e4", "d6", "f4")),
        Opening("E80", "King's Indian Defense: Sämisch", listOf("d4", "Nf6", "c4", "g6", "Nc3", "Bg7", "e4", "d6", "f3")),
        Opening("E97", "King's Indian Defense: Classical, Main Line", listOf("d4", "Nf6", "c4", "g6", "Nc3", "Bg7", "e4", "d6", "Nf3", "O-O", "Be2", "e5")),

        // Grünfeld
        Opening("D80", "Grünfeld Defense", listOf("d4", "Nf6", "c4", "g6", "Nc3", "d5")),
        Opening("D85", "Grünfeld Defense: Exchange Variation", listOf("d4", "Nf6", "c4", "g6", "Nc3", "d5", "cxd5", "Nxd5", "e4")),

        // Dutch Defense
        Opening("A80", "Dutch Defense", listOf("d4", "f5")),
        Opening("A87", "Dutch Defense: Leningrad", listOf("d4", "f5", "c4", "Nf6", "g3", "g6")),

        // London System
        Opening("D00", "London System", listOf("d4", "d5", "Bf4")),
        Opening("A46", "London System", listOf("d4", "Nf6", "Nf3", "e6", "Bf4")),

        // Catalan
        Opening("E01", "Catalan Opening", listOf("d4", "Nf6", "c4", "e6", "g3")),
        Opening("E06", "Catalan Opening: Closed", listOf("d4", "Nf6", "c4", "e6", "g3", "d5", "Bg2", "Be7")),

        // English
        Opening("A10", "English Opening", listOf("c4")),
        Opening("A20", "English Opening: Reversed Sicilian", listOf("c4", "e5")),
        Opening("A30", "English Opening: Symmetrical", listOf("c4", "c5")),

        // Réti
        Opening("A04", "Réti Opening", listOf("Nf3", "d5", "g3")),
        Opening("A09", "Réti Opening: Advance Variation", listOf("Nf3", "d5", "c4")),

        // Vienna Game
        Opening("C25", "Vienna Game", listOf("e4", "e5", "Nc3")),

        // Philidor
        Opening("C41", "Philidor Defense", listOf("e4", "e5", "Nf3", "d6")),

        // Petroff
        Opening("C42", "Petrov's Defense", listOf("e4", "e5", "Nf3", "Nf6")),

        // Bird's Opening
        Opening("A02", "Bird's Opening", listOf("f4")),

        // Benoni
        Opening("A56", "Benoni Defense", listOf("d4", "Nf6", "c4", "c5")),
        Opening("A60", "Benoni Defense: Modern", listOf("d4", "Nf6", "c4", "c5", "d5", "e6")),

        // Bogo-Indian
        Opening("E11", "Bogo-Indian Defense", listOf("d4", "Nf6", "c4", "e6", "Nf3", "Bb4+")),

        // Trompowsky
        Opening("A45", "Trompowsky Attack", listOf("d4", "Nf6", "Bg5")),

        // King's Indian Attack
        Opening("A07", "King's Indian Attack", listOf("Nf3", "d5", "g3", "Nf6", "Bg2")),

        // Symmetrical openings
        Opening("C24", "Bishop's Opening", listOf("e4", "e5", "Bc4")),
        Opening("C20", "King's Pawn Game", listOf("e4", "e5")),

        // Basic pawn openings
        Opening("A40", "Queen's Pawn Game", listOf("d4")),
        Opening("B00", "King's Pawn Game", listOf("e4"))
    )
}
