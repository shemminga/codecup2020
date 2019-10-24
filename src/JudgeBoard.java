import java.util.Arrays;

class JudgeBoard {
    static final int BOARD_SIZE = 16;
    boolean isGameEnded = false;
    GameResult gameResult = GameResult.INDETERMINATE;
    Stone playerToMove = Stone.WHITE;
    int moveNumber = 0;
    private Stone[][] board = new Stone[BOARD_SIZE][BOARD_SIZE];

    JudgeBoard() {
        for (final Stone[] stones : board)
            Arrays.fill(stones, Stone.NONE);
    }

    void move(String inputMove) {
        moveNumber++;
        togglePlayerToMove();

        final String move = inputMove.trim();

        if (move.length() != 2) {
            throw new AssertionError("Invalid move " + inputMove + " by " + playerToMove + " player");
        }

        if (move.equals("Zz")) {
            if (moveNumber != 4) {
                throw new AssertionError("Illegal move " + inputMove + " in move " + moveNumber);
            }
            return;
        }

        int row = move.charAt(0) - 'A';
        int col = move.charAt(1) - 'a';

        if (row < 0 || row >= BOARD_SIZE || col < 0 || col >= BOARD_SIZE) {
            throw new AssertionError("Invalid move (out of bounds) " + inputMove);
        }

        if (board[row][col] != Stone.NONE) {
            throw new AssertionError("Illegal move (field already occupied) " + inputMove);
        }

        board[row][col] = playerToMove;
        updateGameEnded();
    }

    Stone getCell(int row, int col) {
        return board[row][col];
    }

    private void updateGameEnded() {
        boolean hasEmptyField = false;
        for (int i = 0; i < board.length; i++) {
            for (int j = 0; j < board[i].length; j++) {
                boolean hLine = horizontalLine(i, j);
                boolean vLine = verticalLine(i, j);
                boolean dLine = diagonalLine(i, j);
                boolean bLine = diagonalBackLine(i, j);

                if (hLine || vLine || dLine || bLine) {
                    isGameEnded = true;
                    gameResult = board[i][j] == Stone.BLACK ? GameResult.BLACK_WINS : GameResult.WHITE_WINS;
                    return;
                }

                hasEmptyField |= board[i][j] == Stone.NONE;
            }
        }

        if (!hasEmptyField) {
            isGameEnded = true;
            gameResult = GameResult.DRAW;
        }
    }

    private boolean horizontalLine(final int i, final int j) {
        if (j >= board[i].length - 5) return false;
        if (board[i][j] == Stone.NONE) return false;
        if (board[i][j + 1] != board[i][j]) return false;
        if (board[i][j + 2] != board[i][j]) return false;
        if (board[i][j + 3] != board[i][j]) return false;
        if (board[i][j + 4] != board[i][j]) return false;
        return true;
    }

    private boolean verticalLine(final int i, final int j) {
        if (i >= board.length - 5) return false;
        if (board[i][j] == Stone.NONE) return false;
        if (board[i + 1][j] != board[i][j]) return false;
        if (board[i + 2][j] != board[i][j]) return false;
        if (board[i + 3][j] != board[i][j]) return false;
        if (board[i + 4][j] != board[i][j]) return false;
        return true;
    }

    private boolean diagonalLine(final int i, final int j) {
        if (i >= board.length - 5) return false;
        if (j >= board[i].length - 5) return false;
        if (board[i][j] == Stone.NONE) return false;
        if (board[i + 1][j + 1] != board[i][j]) return false;
        if (board[i + 2][j + 2] != board[i][j]) return false;
        if (board[i + 3][j + 3] != board[i][j]) return false;
        if (board[i + 4][j + 4] != board[i][j]) return false;
        return true;
    }

    private boolean diagonalBackLine(final int i, final int j) {
        if(i < 4) return false;
        if (j >= board[i].length - 5) return false;
        if (board[i][j] == Stone.NONE) return false;
        if (board[i - 1][j + 1] != board[i][j]) return false;
        if (board[i - 2][j + 2] != board[i][j]) return false;
        if (board[i - 3][j + 3] != board[i][j]) return false;
        if (board[i - 4][j + 4] != board[i][j]) return false;
        return true;
    }

    private void togglePlayerToMove() {
        switch (playerToMove) {
        case NONE:
            throw new AssertionError("Corrupted playerToMove");
        case WHITE:
            playerToMove = Stone.BLACK;
            break;
        case BLACK:
            playerToMove = Stone.WHITE;
            break;
        }
    }

    enum Stone {
        NONE, WHITE, BLACK
    }
}
