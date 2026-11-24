package com.example.chessreviewer;

/**
 * The Shizuku user service: runs in a process Shizuku starts for us, with the privilege of
 * whoever started Shizuku (uid 2000 / shell over ADB or wireless debugging, uid 0 if it was
 * started as root). The screenshot is handed back in chunks so a full-resolution frame never
 * has to fit in one binder transaction.
 */
interface ISecureShot {
    void destroy() = 16777114;

    /** Grabs the display; returns the size of the encoded frame in bytes, or a negative code. */
    int capture(int width, int height) = 2;

    /** A slice of the frame from the last capture(). */
    byte[] chunk(int offset, int length) = 3;

    /** Why the last capture() failed, empty when it worked. */
    String lastError() = 4;
}
