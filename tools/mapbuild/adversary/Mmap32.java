import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How much contiguous address space a process has, as the :route process would see it: ART started by app_process32, the same runtime
 * layout a zygote child gets, in a 32-bit process on a 64-bit kernel.
 *
 *   CLASSPATH=mmap32.dex app_process32 . Mmap32 <file to create sparse> [real file to map and read]
 *
 * Prints the holes in /proc/self/maps, the largest read-only shared file mapping that succeeds (a binary search over a sparse file), and
 * what is left after route.tar is mapped: the same for the file of the plan's GCC pack.
 */
public class Mmap32 {
    static final long MB = 1024 * 1024;
    static final long TAR = 378378240L;

    static List<long[]> holes() throws Exception {
        List<long[]> out = new ArrayList<>();
        long prevEnd = 0x10000L;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] a = l.split("[ -]")[0].isEmpty() ? null : l.split(" ")[0].split("-");
                if (a == null) continue;
                long s = Long.parseUnsignedLong(a[0], 16), e = Long.parseUnsignedLong(a[1], 16);
                if (s > prevEnd) out.add(new long[]{s - prevEnd, prevEnd, s});
                prevEnd = Math.max(prevEnd, e);
            }
        }
        if (prevEnd < (1L << 32)) out.add(new long[]{(1L << 32) - prevEnd, prevEnd, 1L << 32});
        Collections.sort(out, (x, y) -> Long.compare(y[0], x[0]));
        return out;
    }

    static void printHoles(String title, int n) throws Exception {
        List<long[]> h = holes();
        StringBuilder sb = new StringBuilder(title).append(": ");
        long total = 0;
        for (long[] x : h) total += x[0];
        for (int i = 0; i < Math.min(n, h.size()); i++)
            sb.append(String.format("%d MB at %08x; ", h.get(i)[0] / MB, h.get(i)[1]));
        sb.append(String.format("all holes %d MB", total / MB));
        System.out.println(sb);
    }

    static long largest(FileDescriptor fd, long max) {
        long lo = 0, hi = max;
        while (hi - lo > MB) {
            long mid = ((lo + hi) / 2) & ~(4096L - 1);
            try {
                long a = Os.mmap(0, mid, OsConstants.PROT_READ, OsConstants.MAP_SHARED, fd, 0);
                Os.munmap(a, mid);
                lo = mid;
            } catch (Exception e) {
                hi = mid;
            }
        }
        return lo;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("os.arch " + System.getProperty("os.arch") + ", is64Bit " + android.os.Process.is64Bit()
                + ", maxMemory " + Runtime.getRuntime().maxMemory() / MB + " MB, pid " + android.os.Process.myPid());
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String l;
            while ((l = r.readLine()) != null) if (l.startsWith("VmSize") || l.startsWith("VmRSS") || l.startsWith("VmPeak")) System.out.println(l);
        }
        printHoles("holes at start", 6);
        File sparse = new File(args[0]);
        try (RandomAccessFile f = new RandomAccessFile(sparse, "rw")) {
            f.setLength(4096L * MB - 4096);
            FileDescriptor fd = f.getFD();
            long l = largest(fd, 4095L * MB);
            System.out.println("largest one read-only shared mapping: " + l / MB + " MB (" + (l / 1000000) + " million B)");
            long a = Os.mmap(0, TAR, OsConstants.PROT_READ, OsConstants.MAP_SHARED, fd, 0);
            System.out.printf("mapped the size of route.tar, %d MB, at %08x%n", TAR / MB, a);
            printHoles("holes after route.tar", 4);
            System.out.println("largest further mapping after route.tar: " + largest(fd, 4095L * MB) / MB + " MB");
            // anonymous heap-like requests such as vector growth, by doubling
            long want = 16 * MB, ok = 0;
            FileDescriptor none = new FileDescriptor(); // -1, as MAP_ANONYMOUS wants
            while (want <= 2048 * MB) {
                try {
                    long b = Os.mmap(0, want, OsConstants.PROT_READ | OsConstants.PROT_WRITE, OsConstants.MAP_PRIVATE | 0x20 /* MAP_ANONYMOUS: not in this API level's OsConstants */, none, 0);
                    Os.munmap(b, want);
                    ok = want;
                    want *= 2;
                } catch (Exception e) {
                    break;
                }
            }
            System.out.println("largest anonymous block, by doubling, with route.tar mapped: " + ok / MB + " MB");
            Os.munmap(a, TAR);
            long gcc = 650 * MB;
            a = Os.mmap(0, gcc, OsConstants.PROT_READ, OsConstants.MAP_SHARED, fd, 0);
            System.out.printf("the plan's largest GCC estimate, 650 MB, maps at %08x; further %d MB left%n", a, largest(fd, 4095L * MB) / MB);
            Os.munmap(a, gcc);
        } finally {
            sparse.delete();
        }
        if (args.length > 1) {
            // The real file: map it, read one byte per page, as a worst-case walk through it
            try (RandomAccessFile f = new RandomAccessFile(args[1], "r")) {
                long n = f.length();
                long t0 = System.nanoTime();
                long a = Os.mmap(0, n, OsConstants.PROT_READ, OsConstants.MAP_SHARED, f.getFD(), 0);
                ByteBuffer one = ByteBuffer.allocate(4096);
                System.out.printf("real file %d B mapped at %08x in %.1f ms%n", n, a, (System.nanoTime() - t0) / 1e6);
                printStatus("after mapping");
                Os.munmap(a, n);
            }
        }
        printStatus("at the end");
    }

    static void printStatus(String t) throws Exception {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String l;
            StringBuilder sb = new StringBuilder(t + ":");
            while ((l = r.readLine()) != null) if (l.startsWith("VmSize") || l.startsWith("VmRSS")) sb.append(' ').append(l.replaceAll("\\s+", " "));
            System.out.println(sb);
        }
    }
}
