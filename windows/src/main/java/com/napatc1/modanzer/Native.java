package com.napatc1.modanzer;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Windows functions called straight from Java (no helper program to compile).
 * Everything here only READS: it opens Minecraft with query and read rights, lists the DLLs loaded into it,
 * and walks and reads its memory regions. It never writes to, suspends or injects into any process.
 */
final class Native {
    private Native() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static MethodHandle openProcess, closeHandle, virtualQueryEx, readProcessMemory, queryDosDevice, enumModules, moduleFileName;
    private static boolean loaded, ok;

    private static synchronized boolean load() {
        if (loaded) return ok;
        loaded = true;
        if (!Sys.WINDOWS) return false;
        try {
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
            ValueLayout.OfInt I = ValueLayout.JAVA_INT;
            ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
            ValueLayout A = ValueLayout.ADDRESS;
            openProcess = LINKER.downcallHandle(k32.find("OpenProcess").orElseThrow(), FunctionDescriptor.of(A, I, I, I));
            closeHandle = LINKER.downcallHandle(k32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(I, A));
            virtualQueryEx = LINKER.downcallHandle(k32.find("VirtualQueryEx").orElseThrow(), FunctionDescriptor.of(L, A, A, A, L));
            readProcessMemory = LINKER.downcallHandle(k32.find("ReadProcessMemory").orElseThrow(), FunctionDescriptor.of(I, A, A, A, L, A));
            queryDosDevice = LINKER.downcallHandle(k32.find("QueryDosDeviceW").orElseThrow(), FunctionDescriptor.of(I, A, A, I));
            enumModules = LINKER.downcallHandle(k32.find("K32EnumProcessModulesEx").orElseThrow(), FunctionDescriptor.of(I, A, A, I, A, I));
            moduleFileName = LINKER.downcallHandle(k32.find("K32GetModuleFileNameExW").orElseThrow(), FunctionDescriptor.of(I, A, A, A, I));
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        return ok;
    }

    static boolean available() { return load(); }

    private static final int PROCESS_QUERY_INFORMATION = 0x0400, PROCESS_VM_READ = 0x0010;

    private static MemorySegment open(int pid) throws Throwable {
        MemorySegment h = (MemorySegment) openProcess.invoke(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, 0, pid);
        return h.address() == 0 ? null : h;
    }

    /** The files of the DLLs loaded into a process right now, or null when they can't be read. */
    static List<String> modules(int pid) {
        if (!load()) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = open(pid);
            if (h == null) return null;
            try {
                int cap = 4096;
                MemorySegment mods = arena.allocate(8L * cap, 8);
                MemorySegment needed = arena.allocate(4, 4);
                int r = (int) enumModules.invoke(h, mods, 8 * cap, needed, 3); // LIST_MODULES_ALL
                if (r == 0) return null;
                int count = Math.min(cap, needed.get(ValueLayout.JAVA_INT, 0) / 8);
                MemorySegment name = arena.allocate(2L * 1024, 2);
                List<String> files = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    MemorySegment mod = mods.get(ValueLayout.ADDRESS, 8L * i);
                    int n = (int) moduleFileName.invoke(h, mod, name, 1024);
                    if (n > 0) {
                        String f = new String(name.asSlice(0, 2L * n).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
                        if (!files.contains(f)) files.add(f);
                    }
                }
                return files;
            } finally {
                closeHandle.invoke(h);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Device name of a drive letter such as "C:" (\Device\HarddiskVolume3), used to turn BAM paths into drive paths. */
    static String dosDevice(String drive) {
        if (!load()) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dev = arena.allocateFrom(drive, StandardCharsets.UTF_16LE);
            MemorySegment target = arena.allocate(2L * 1024, 2);
            int n = (int) queryDosDevice.invoke(dev, target, 1024);
            if (n == 0) return null;
            String s = new String(target.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
            int z = s.indexOf('\0');
            return z >= 0 ? s.substring(0, z) : s;
        } catch (Throwable t) {
            return null;
        }
    }

    static final class ScanResult {
        long bytesScanned;
        int regions;
        boolean truncated;
        String error;
        final Map<String, Long> hits = new HashMap<>();
        final List<String> hiddenImages = new ArrayList<>();
    }

    private static boolean isPeHeader(byte[] hdr, int len) {
        if (len < 0x40 || hdr[0] != 0x4D || hdr[1] != 0x5A) return false;
        int lfanew = (hdr[0x3C] & 0xff) | (hdr[0x3D] & 0xff) << 8 | (hdr[0x3E] & 0xff) << 16 | (hdr[0x3F] & 0xff) << 24;
        return lfanew > 0 && lfanew + 4 <= len && hdr[lfanew] == 0x50 && hdr[lfanew + 1] == 0x45 && hdr[lfanew + 2] == 0 && hdr[lfanew + 3] == 0;
    }

    /** Counts cheat names (ASCII and UTF-16) in a buffer. */
    static void countMatches(byte[] buf, int n, List<byte[]> pats, List<String> names, List<Integer>[] byFirst, Map<String, Long> hits) {
        for (int i = 0; i < n; i++) {
            List<Integer> cand = byFirst[buf[i] & 0xff];
            if (cand == null) continue;
            for (int k : cand) {
                byte[] p = pats.get(k);
                if (i + p.length > n) continue;
                int j = 1;
                while (j < p.length && buf[i + j] == p[j]) j++;
                if (j == p.length) hits.merge(names.get(k), 1L, Long::sum);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static ScanResult scan(int pid, String[] needles, long maxBytes) {
        ScanResult res = new ScanResult();
        if (!load()) { res.error = "the memory reader could not be loaded"; return res; }
        List<byte[]> pats = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer>[] byFirst = new List[256];
        int maxLen = 1;
        for (String n : needles) {
            pats.add(n.getBytes(StandardCharsets.US_ASCII)); names.add(n);
            pats.add(n.getBytes(StandardCharsets.UTF_16LE)); names.add(n);
        }
        for (int i = 0; i < pats.size(); i++) {
            int f = pats.get(i)[0] & 0xff;
            if (byFirst[f] == null) byFirst[f] = new ArrayList<>();
            byFirst[f].add(i);
            maxLen = Math.max(maxLen, pats.get(i).length);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = open(pid);
            if (h == null) { res.error = "could not open the game for reading"; return res; }
            try {
                final int CHUNK = 4 * 1024 * 1024;
                MemorySegment buf = arena.allocate(CHUNK, 16);
                MemorySegment hdrSeg = arena.allocate(1024, 16);
                MemorySegment got = arena.allocate(8, 8);
                MemorySegment mbi = arena.allocate(48, 8);
                byte[] data = new byte[CHUNK];
                long addr = 0;
                while (true) {
                    long r = (long) virtualQueryEx.invoke(h, MemorySegment.ofAddress(addr), mbi, 48L);
                    if (r == 0) break;
                    long baseA = mbi.get(ValueLayout.JAVA_LONG, 0), allocBase = mbi.get(ValueLayout.JAVA_LONG, 8);
                    long size = mbi.get(ValueLayout.JAVA_LONG, 24);
                    int state = mbi.get(ValueLayout.JAVA_INT, 32), protect = mbi.get(ValueLayout.JAVA_INT, 36), type = mbi.get(ValueLayout.JAVA_INT, 40);
                    if (size <= 0) break;
                    boolean readable = state == 0x1000 && protect != 0 && (protect & 0x01) == 0 && (protect & 0x100) == 0;
                    if (readable) {
                        res.regions++;
                        // A complete program (DLL/EXE) sitting in private memory with no file behind it.
                        if (type == 0x20000 && baseA == allocBase) {
                            int ok = (int) readProcessMemory.invoke(h, MemorySegment.ofAddress(baseA), hdrSeg, 1024L, got);
                            int len = (int) got.get(ValueLayout.JAVA_LONG, 0);
                            if (ok != 0 && isPeHeader(hdrSeg.toArray(ValueLayout.JAVA_BYTE), len)) {
                                res.hiddenImages.add(String.format("0x%X (%d KB%s)", baseA, size / 1024, (protect & 0xF0) != 0 ? ", executable" : ""));
                            }
                        }
                        long off = 0;
                        while (off < size) {
                            if (res.bytesScanned >= maxBytes) { res.truncated = true; break; }
                            int want = (int) Math.min(CHUNK, size - off);
                            int ok = (int) readProcessMemory.invoke(h, MemorySegment.ofAddress(baseA + off), buf, (long) want, got);
                            long n = got.get(ValueLayout.JAVA_LONG, 0);
                            if (ok == 0 && n <= 0) { off += want; continue; }
                            if (n <= 0) { off += want; continue; }
                            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, data, 0, (int) n);
                            res.bytesScanned += n;
                            countMatches(data, (int) n, pats, names, byFirst, res.hits);
                            if (off + want >= size) break;
                            off += want - (maxLen - 1);
                        }
                        if (res.truncated) break;
                    }
                    long next = baseA + size;
                    if (next <= addr) break;
                    addr = next;
                }
            } finally {
                closeHandle.invoke(h);
            }
        } catch (Throwable t) {
            res.error = String.valueOf(t.getMessage());
        }
        return res;
    }
}
