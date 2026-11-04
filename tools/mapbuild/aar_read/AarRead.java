import com.valhalla.valhalla.ValhallaKotlin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A real read of a routing tar through the library the app will ship, outside any app: run with app_process on the
 * emulator (x86_64) or, one day, on the unit (armeabi-v7a).
 *
 *   CLASSPATH=aarread.dex app_process . AarRead libvalhalla-wrapper.so config.json outdir request1.json [request2.json ...]
 *
 * Prints the time of createActor and of each route, twice each, the size of the answer and this process's memory after it.
 * The answer of the second run of each request is written to outdir.
 */
public class AarRead {
    static String status() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("VmHWM") || l.startsWith("VmRSS") || l.startsWith("RssAnon") || l.startsWith("RssFile") || l.startsWith("VmPeak")) {
                    sb.append(l.replaceAll("\\s+", " ")).append("; ");
                }
            }
        }
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        System.out.println("before load: " + status());
        long t0 = System.nanoTime();
        System.load(new File(a[0]).getAbsolutePath());
        System.out.printf("loaded the library: %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        ValhallaKotlin v = new ValhallaKotlin();
        t0 = System.nanoTime();
        long h = v.createActor(new File(a[1]).getAbsolutePath(), null);
        System.out.printf("createActor: %.1f ms, handle %s; %s%n", (System.nanoTime() - t0) / 1e6, h != 0, status());
        for (int i = 3; i < a.length; i++) {
            byte[] req = Files.readAllBytes(new File(a[i]).toPath());
            byte[] res = null;
            for (int run = 1; run <= 2; run++) {
                t0 = System.nanoTime();
                res = v.route(h, req);
                System.out.printf("%s run %d: %.1f ms, %d bytes; %s%n", new File(a[i]).getName(), run, (System.nanoTime() - t0) / 1e6, res == null ? -1 : res.length, status());
            }
            File out = new File(a[2], new File(a[i]).getName());
            try (FileOutputStream f = new FileOutputStream(out)) {
                f.write(res);
            }
            String head = new String(res, 0, Math.min(res.length, 160), StandardCharsets.UTF_8);
            System.out.println("  starts: " + head.replace('\n', ' '));
        }
        v.deleteActor(h);
        System.out.println("done; " + status());
    }
}
