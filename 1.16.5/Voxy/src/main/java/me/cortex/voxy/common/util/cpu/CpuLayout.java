package me.cortex.voxy.common.util.cpu;

import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import org.lwjgl.system.Platform;

import java.util.Arrays;
import java.util.Random;

//Represents the layout of the current cpu running on
public class CpuLayout {
    private CpuLayout(){}

    public static void setThreadAffinity(Core... cores) {
        var affinity = new Affinity[cores.length];
        for (int i = 0; i < cores.length; i++) {
            affinity[i] = cores[i].affinity;
        }
        setThreadAffinity(affinity);
    }

    public static void setThreadAffinity(Affinity... affinities) {
        var platform = Platform.get();
        if (platform == Platform.WINDOWS) {
            long[] msks = new long[affinities.length];
            short[] groups = new short[affinities.length];Arrays.fill(groups, (short) -1);
            int i = 0;
            for (var a : affinities) {
                int idx;
                for (idx = 0; idx<i && groups[idx]!=a.group; idx++);
                if (idx == i) {groups[idx] = a.group; i++;}
                msks[idx] |= a.msk;
            }
            ThreadUtils.SetThreadSelectedCpuSetMasksWin32(Arrays.copyOf(msks, i), Arrays.copyOf(groups, i));
        } else if (platform == Platform.LINUX) {
            Arrays.sort(affinities, (a, b) -> a.group - b.group);
            long[] msks = new long[affinities.length];
            for (int i=0; i<affinities.length; i++) {
                msks[i] = affinities[i].msk;
            }
            ThreadUtils.schedSetaffinityLinux(msks);
        } else {
            Logger.error("Don't know how to set thread affinity on this platform.");
        }
    }

    private static Core[] generateCoreLayoutWindows() {
        var cores = Kernel32Util.getLogicalProcessorInformationEx(WinNT.LOGICAL_PROCESSOR_RELATIONSHIP.RelationProcessorCore);
        boolean allSameClass = true;
        for (var coreO : cores) {
            var core = (WinNT.PROCESSOR_RELATIONSHIP) coreO;
            allSameClass &= core.efficiencyClass == 0;
        }

        int i = 0;
        var res = new Core[cores.length];
        for (var coreO : cores) {
            var core = (WinNT.PROCESSOR_RELATIONSHIP) coreO;
            boolean smt = (core.flags&1)==1;
            byte eclz = core.efficiencyClass;
            if (core.groupMask.length!=1) {
                throw new IllegalStateException("Unsupported architecture");
            }
            var msk = core.groupMask[0].mask.longValue();
            if (Long.bitCount(msk)>1 != smt) {
                throw new IllegalStateException("Logic issue");
            }
            res[i++] = new Core((!allSameClass)&&eclz==0, new Affinity(msk, core.groupMask[0].group));
        }
        sort(res);
        return res;
    }

    /**
     * Reads the CPU topology straight out of sysfs rather than via OSHI.
     * <p>
     * Minecraft 1.16.5 ships oshi-core 1.1, which predates the {@code CentralProcessor}
     * logical/physical-processor API this used on 1.21.1 entirely. Compiling against a newer OSHI
     * would resolve here and then NoSuchMethodError at runtime against the one the game actually
     * loads, so the topology is read directly instead -- /sys/devices/system/cpu is stable kernel
     * ABI and needs no dependency at all.
     * <p>
     * Logical CPUs sharing a (physical_package_id, core_id) pair are SMT siblings of one core.
     * Efficiency-core detection is not attempted here: sysfs only exposes it indirectly via
     * cpu_capacity, and the 1.21.1 path already collapsed to "all cores equal" whenever the
     * platform reported a uniform efficiency.
     */
    private static Core[] generateCoreLayoutLinux() {
        java.io.File cpuDir = new java.io.File("/sys/devices/system/cpu");
        java.io.File[] entries = cpuDir.listFiles();
        if (entries == null) {
            throw new IllegalStateException("Cannot enumerate " + cpuDir);
        }
        // key: (package << 32) | core_id -- both are only unique together.
        java.util.LinkedHashMap<Long, Affinity> byCore = new java.util.LinkedHashMap<>();
        for (java.io.File entry : entries) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("^cpu(\\d+)$").matcher(entry.getName());
            if (!m.matches()) {
                continue;
            }
            int cpu = Integer.parseInt(m.group(1));
            java.io.File topology = new java.io.File(entry, "topology");
            int coreId = readSysfsInt(new java.io.File(topology, "core_id"), -1);
            int pkgId = readSysfsInt(new java.io.File(topology, "physical_package_id"), 0);
            if (coreId < 0) {
                continue;  // offline CPU, or a kernel without topology exposed
            }
            long key = (((long) pkgId) << 32) | (coreId & 0xFFFFFFFFL);
            Affinity existing = byCore.get(key);
            long msk = (existing == null ? 0L : existing.msk) | (1L << cpu);
            byCore.put(key, new Affinity(msk, (short) pkgId));
        }
        if (byCore.isEmpty()) {
            throw new IllegalStateException("Read no CPU topology from " + cpuDir);
        }
        Core[] cores = new Core[byCore.size()];
        int i = 0;
        for (Affinity aff : byCore.values()) {
            cores[i++] = new Core(false, aff);
        }
        sort(cores);
        return cores;
    }

    private static int readSysfsInt(java.io.File file, int fallback) {
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
            return Integer.parseInt(new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }


    private static void sort(Core[] cores) {
        Arrays.sort(cores, (a,b)->{
            if (a.isEfficiency == b.isEfficiency) {
                int c = Integer.compare(a.affinity.group & 0xFFFF, b.affinity.group & 0xFFFF);
                if (c==0) {
                    return Long.compareUnsigned(a.affinity.msk, b.affinity.msk);
                }
                return c;
            } else {
                return a.isEfficiency?1:-1;
            }
        });
    }

    public static final class Affinity {
        private final long msk;
        private final short group;

        public Affinity(long msk, short group) {
            this.msk = msk;
            this.group = group;
        }

        public long msk() { return this.msk; }
        public short group() { return this.group; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Affinity)) return false;
            Affinity o = (Affinity) obj;
            return this.msk == o.msk && this.group == o.group;
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.msk, this.group); }

        @Override public String toString() { return "Affinity[msk=" + this.msk + ", group=" + this.group + "]"; }

    }
    public static final class Core {
        private final boolean isEfficiency;
        private final Affinity affinity;

        public Core(boolean isEfficiency, Affinity affinity) {
            this.isEfficiency = isEfficiency;
            this.affinity = affinity;
        }

        public boolean isEfficiency() { return this.isEfficiency; }
        public Affinity affinity() { return this.affinity; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Core)) return false;
            Core o = (Core) obj;
            return this.isEfficiency == o.isEfficiency && java.util.Objects.equals(this.affinity, o.affinity);
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.isEfficiency, this.affinity); }

        @Override public String toString() { return "Core[isEfficiency=" + this.isEfficiency + ", affinity=" + this.affinity + "]"; }

    }

    public static final Core[] CORES;
    static {
        Core[] cores = null;
        try {
            if (Platform.get() == Platform.WINDOWS) {
                cores = generateCoreLayoutWindows();
            } else if (Platform.get() == Platform.LINUX) {
                cores = generateCoreLayoutLinux();
            }
        } catch (Exception e) {
            Logger.error("Failed to generate cpu core layout, falling back to null: ", e);
        }
        CORES = cores;
    }

    public static void main(String[] args) throws InterruptedException {
        System.err.println(Arrays.toString(CORES));
        setThreadAffinity(CORES[0], CORES[1]);
        for (int i = 0; i < 20; i++) {
            int finalI = i;
            new Thread(()->{
                setThreadAffinity(CORES[finalI&3]);
                Random r = new Random();
                int j= 0;
                while (r.nextLong()!=0) {
                    j++;
                }
                System.out.println(j);
            }).start();
        }
        while (true) {
            Thread.sleep(100);
        }
    }

    public static int getCoreCount() {
        if (CORES==null) {
            return Runtime.getRuntime().availableProcessors();
        } else {
            return CORES.length;
        }
    }
}
