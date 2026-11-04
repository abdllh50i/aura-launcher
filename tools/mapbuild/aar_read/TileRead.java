import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.Random;

/**
 * Reads base.mbtiles as the app would: Android's own SQLite classes, read-only, the tiles view, TMS row. Run with app_process.
 *
 *   CLASSPATH=tileread.dex app_process . TileRead base.mbtiles
 *
 * Prints the SQLite version, the row counts per zoom, what a known tile looks like, the largest tile through a cursor, and the
 * time of random lookups.
 */
public class TileRead {
    static byte[] tile(SQLiteDatabase db, int z, int x, int y) {
        int row = (1 << z) - 1 - y;
        try (Cursor c = db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                new String[]{String.valueOf(z), String.valueOf(x), String.valueOf(row)})) {
            return c.moveToFirst() ? c.getBlob(0) : null;
        }
    }

    public static void main(String[] a) throws Exception {
        long t0 = System.nanoTime();
        SQLiteDatabase db = SQLiteDatabase.openDatabase(a[0], null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        System.out.printf("opened read-only: %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        try (Cursor c = db.rawQuery("SELECT sqlite_version()", null)) {
            c.moveToFirst();
            System.out.println("sqlite " + c.getString(0));
        }
        try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE name='format'", null)) {
            c.moveToFirst();
            System.out.println("format " + c.getString(0));
        }
        t0 = System.nanoTime();
        try (Cursor c = db.rawQuery("SELECT zoom_level, COUNT(*) FROM tiles_shallow GROUP BY zoom_level", null)) {
            StringBuilder sb = new StringBuilder();
            while (c.moveToNext()) sb.append("z").append(c.getInt(0)).append("=").append(c.getInt(1)).append(" ");
            System.out.println(sb);
        }
        System.out.printf("count per zoom: %.0f ms%n", (System.nanoTime() - t0) / 1e6);
        // Khobar, the largest tile, a tile of open desert and one in the sea
        int[][] known = {{14, 10474, 7054}, {14, 10316, 7031}, {14, 10155, 7160}, {14, 10530, 6990}, {0, 0, 0}};
        for (int[] k : known) {
            t0 = System.nanoTime();
            byte[] b = tile(db, k[0], k[1], k[2]);
            System.out.printf("tile %d/%d/%d: %s, %.1f ms%n", k[0], k[1], k[2],
                    b == null ? "no row" : b.length + " bytes, starts " + String.format("%02x%02x", b[0], b[1]), (System.nanoTime() - t0) / 1e6);
        }
        // random lookups, inside the bounds of the Eastern Province
        Random r = new Random(5);
        int found = 0, missing = 0;
        long bytes = 0;
        t0 = System.nanoTime();
        int n = 2000;
        for (int i = 0; i < n; i++) {
            int x = 10400 + r.nextInt(150), y = 6990 + r.nextInt(120);
            byte[] b = tile(db, 14, x, y);
            if (b == null) missing++; else { found++; bytes += b.length; }
        }
        System.out.printf("%d random z14 lookups: %.2f ms each; %d rows, %d without a row; %d bytes%n", n, (System.nanoTime() - t0) / 1e6 / n, found, missing, bytes);
        db.close();
    }
}
