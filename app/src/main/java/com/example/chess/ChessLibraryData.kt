package com.example.chess

data class TacticalPuzzle(
  val id: String,
  val title: String,
  val theme: String,
  val difficulty: String,
  val sideToMove: PieceColor,
  val fen: String,
  val solutionUci: String,
  val hint: String,
)

data class OpeningLine(
  val id: String,
  val name: String,
  val eco: String,
  val style: String,
  val movesPgn: String,
  val description: String,
)

object ChessLibraryData {

  val puzzles: List<TacticalPuzzle> =
    listOf(
      TacticalPuzzle(
        id = "p1",
        title = "Back-Rank Rook Strike",
        theme = "Mate in 1",
        difficulty = "Beginner",
        sideToMove = PieceColor.WHITE,
        fen = "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
        solutionUci = "d1d8",
        hint = "Black's king has no luft squares on the 7th rank.",
      ),
      TacticalPuzzle(
        id = "p2",
        title = "Royal Knight Fork",
        theme = "Knight Fork",
        difficulty = "Intermediate",
        sideToMove = PieceColor.WHITE,
        fen = "r2q1rk1/ppp2ppp/2n5/3p4/3P2b1/2N2N2/PPP1BPPP/R2Q1RK1 w - - 0 1",
        solutionUci = "f3e5",
        hint = "Leap to the outpost attacking both bishop and knight.",
      ),
      TacticalPuzzle(
        id = "p3",
        title = "Smothered Pattern Setup",
        theme = "Queen + Knight",
        difficulty = "Tactical",
        sideToMove = PieceColor.WHITE,
        fen = "r4rk1/ppp2Npp/8/3n4/2B5/8/PPPP1PPP/R1BQ1RK1 w - - 0 1",
        solutionUci = "c4d5",
        hint = "Remove the defender or exploit the pin on the a2-g8 diagonal.",
      ),
      TacticalPuzzle(
        id = "p4",
        title = "Greek Gift Bishop Sacrifice",
        theme = "King Hunt",
        difficulty = "Advanced",
        sideToMove = PieceColor.WHITE,
        fen = "r1bq1rk1/pppn1ppp/4pn2/3p4/2PP4/2NBPN2/PP3PPP/R1BQK2R w KQ - 0 7",
        solutionUci = "d3h7",
        hint = "Examine the classic h7 sacrifice followed by Ng5+.",
      ),
      TacticalPuzzle(
        id = "p5",
        title = "Skewer on the Long Diagonal",
        theme = "Skewer / Pin",
        difficulty = "Intermediate",
        sideToMove = PieceColor.BLACK,
        fen = "3r2k1/pp3ppp/2b5/8/8/2P5/PP2QPPP/4R1K1 b - - 0 1",
        solutionUci = "d8e8",
        hint = "Challenge the open e-file and target the back rank.",
      ),
      TacticalPuzzle(
        id = "p6",
        title = "Queen Battery Checkmate",
        theme = "Mate Threat",
        difficulty = "Beginner",
        sideToMove = PieceColor.WHITE,
        fen = "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 2 3",
        solutionUci = "h5f7",
        hint = "The f7 pawn is only defended by the king!",
      ),
    )

  val openings: List<OpeningLine> =
    listOf(
      OpeningLine(
        id = "o1",
        name = "Italian Game: Giuoco Piano",
        eco = "C50",
        style = "Classical",
        movesPgn = "1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5",
        description = "Rapid piece development aiming at f7 and central d4 control.",
      ),
      OpeningLine(
        id = "o2",
        name = "Ruy Lopez: Morphy Defense",
        eco = "C78",
        style = "Grandmaster",
        movesPgn = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6",
        description = "Pressures Black's knight on c6 while preparing kingside castling.",
      ),
      OpeningLine(
        id = "o3",
        name = "Sicilian Defense: Open",
        eco = "B32",
        style = "Sharp",
        movesPgn = "1. e4 c5 2. Nf3 Nc6 3. d4 cxd4 4. Nxd4",
        description = "Asymmetrical counter-attacking battle for central space.",
      ),
      OpeningLine(
        id = "o4",
        name = "Queen's Gambit Declined",
        eco = "D30",
        style = "Positional",
        movesPgn = "1. d4 d5 2. c4 e6 3. Nc3 Nf6",
        description = "Solid central tension and classical queen-side expansion.",
      ),
      OpeningLine(
        id = "o5",
        name = "London System",
        eco = "D02",
        style = "Universal",
        movesPgn = "1. d4 d5 2. Bf4 Nf6 3. e3 e6 4. Nf3",
        description = "Reliable pyramid pawn structure with fast dark-squared bishop development.",
      ),
      OpeningLine(
        id = "o6",
        name = "Caro-Kann Defense",
        eco = "B12",
        style = "Solid",
        movesPgn = "1. e4 c6 2. d4 d5 3. Nc3 dxe4 4. Nxe4",
        description = "Resilient pawn structure keeping the light-squared bishop active.",
      ),
    )
}
