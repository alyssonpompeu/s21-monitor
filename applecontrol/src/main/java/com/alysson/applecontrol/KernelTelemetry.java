package com.alysson.applecontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

final class KernelTelemetry {
    private KernelTelemetry() {}

    static final String[] UV_KEYS = {"uv0","uv1","uv2","uvg","uvmif","uvdsu","uvint"};

    static final class ExecResult {
        final int exitCode; final String output;
        ExecResult(int c, String o){ exitCode=c; output=o==null?"":o; }
    }

    static final class RootShell implements Closeable {
        private static final String EOF="__APPLE_ROOT_EOF__";
        private final java.lang.Process process;
        private final BufferedWriter writer;
        private final LinkedBlockingQueue<String> lines=new LinkedBlockingQueue<>();
        private final AtomicLong seq=new AtomicLong();
        private volatile boolean closed;

        private RootShell(java.lang.Process p) throws IOException {
            process=p;
            writer=new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            Thread t=new Thread(() -> {
                try { String l; while((l=r.readLine())!=null) lines.offer(l); }
                catch(IOException ignored){} finally { lines.offer(EOF); try{r.close();}catch(Exception ignored){} }
            },"AppleRootReader");
            t.setDaemon(true); t.start();
        }

        static RootShell open() throws IOException {
            java.lang.Process p=new java.lang.ProcessBuilder("su").redirectErrorStream(true).start();
            RootShell s=new RootShell(p);
            ExecResult id=s.exec("id",12000);
            if(id.exitCode!=0 || !id.output.contains("uid=0")){ s.close(); throw new IOException("root não concedido: "+id.output); }
            return s;
        }

        synchronized ExecResult exec(String command,long timeoutMs){
            if(closed || !process.isAlive()) return new ExecResult(127,"ROOT_SHELL_CLOSED");
            String marker="__APPLE_END_"+seq.incrementAndGet()+"__";
            StringBuilder out=new StringBuilder();
            try {
                writer.write("{\n"+command+"\n}\n__apple_rc=$?\nprintf '\\n"+marker+":%s\\n' \"$__apple_rc\"\n");
                writer.flush();
                long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                while(true){
                    long left=end-System.nanoTime(); if(left<=0) return new ExecResult(124,"TIMEOUT");
                    String l=lines.poll(left,TimeUnit.NANOSECONDS);
                    if(l==null) return new ExecResult(124,"TIMEOUT");
                    if(EOF.equals(l)) return new ExecResult(127,out.toString().trim());
                    if(l.startsWith(marker+":")){
                        int rc=0; try{rc=Integer.parseInt(l.substring(marker.length()+1).trim());}catch(Exception ignored){}
                        return new ExecResult(rc,out.toString().trim());
                    }
                    out.append(l).append('\n');
                }
            } catch(Exception e){ return new ExecResult(127,"ROOT_ERR:"+e.getClass().getSimpleName()+":"+e.getMessage()); }
        }

        boolean isAlive(){ return !closed && process.isAlive(); }
        public synchronized void close(){ if(closed)return; closed=true; try{writer.write("exit\n");writer.flush();}catch(Exception ignored){} process.destroy(); }
    }

    static final class Discovery {
        final Map<String,String> v;
        Discovery(Map<String,String> v){this.v=v;}
        String get(String k){return v.getOrDefault(k,"NA");}
        long[] gpuFreqs(){return numbers(get("gpu_table"));}
        long[] mifFreqs(){return numbers(get("mif_available"));}
        long gpuMax(){return max(gpuFreqs());}
        long mifMax(){return max(mifFreqs());}
        long mifMin(){return minPositive(mifFreqs());}
        boolean gpuHas(long x){return contains(gpuFreqs(),x);}
        boolean mifHas(long x){return contains(mifFreqs(),x);}
        String summary(){return "GPU OPP="+joinMHz(gpuFreqs())+"\nMIF OPP="+joinMHz(mifFreqs());}
    }

    static String discoveryCommand(){
        return "findtz(){ want=\"$1\"; for z in /sys/class/thermal/thermal_zone*; do [ -d \"$z\" ] || continue; t=$(cat \"$z/type\" 2>/dev/null | tr -d '\\r\\n'); [ \"$t\" = \"$want\" ] && { printf '%s\\n' \"$z/temp\"; return; }; done; printf NA; }; " +
                "findmiftz(){ for z in /sys/class/thermal/thermal_zone*; do [ -d \"$z\" ] || continue; t=$(cat \"$z/type\" 2>/dev/null | tr -d '\\r\\n'); case \"$t\" in *MIF*|*mif*|*DDR*|*ddr*|*DRAM*|*dram*|*MEMORY*|*memory*) printf '%s|%s\\n' \"$z/temp\" \"$t\"; return;; esac; done; printf 'NA|NA'; }; " +
                "mif='NA'; for d in /sys/class/devfreq/*mif*; do [ -e \"$d\" ] && { mif=\"$d\"; break; }; done; mt=$(findmiftz); " +
                "printf 'little_path=%s\\n' \"$(findtz LITTLE)\"; printf 'mid_path=%s\\n' \"$(findtz MID)\"; printf 'big_path=%s\\n' \"$(findtz BIG)\"; printf 'g3d_path=%s\\n' \"$(findtz G3D)\"; " +
                "printf 'mif_temp_path=%s\\n' \"${mt%%|*}\"; printf 'mif_temp_source=%s\\n' \"${mt#*|}\"; printf 'mif_path=%s\\n' \"$mif\"; " +
                "printf 'gpu_table='; cat /sys/devices/platform/18500000.mali/dvfs_table 2>/dev/null | tr '\\n' ' '; printf '\\n'; " +
                "printf 'mif_available='; [ \"$mif\" != NA ] && cat \"$mif/available_frequencies\" 2>/dev/null | tr '\\n' ' '; printf '\\n'";
    }

    static Discovery parseDiscovery(String text){
        Map<String,String> m=parseKV(text); return new Discovery(m);
    }

    private static String readCmd(String key,String path){
        if(path==null || path.equals("NA") || path.isEmpty()) return "printf '"+key+"=NA\\n'; ";
        return "printf '"+key+"='; if [ -r '"+path+"' ]; then cat '"+path+"' 2>/dev/null | tr -d '\\r\\n'; else printf NA; fi; printf '\\n'; ";
    }

    static String fastSnapshotCommand(Discovery d){
        String mif=d.get("mif_path");
        StringBuilder s=new StringBuilder();
        s.append(readCmd("little_temp",d.get("little_path")));
        s.append(readCmd("mid_temp",d.get("mid_path")));
        s.append(readCmd("big_temp",d.get("big_path")));
        s.append(readCmd("g3d_temp",d.get("g3d_path")));
        s.append(readCmd("mif_temp",d.get("mif_temp_path")));
        s.append("printf 'mif_temp_source=").append(shellSafe(d.get("mif_temp_source"))).append("\\n'; ");
        String[][] nodes={
                {"p0_cur","/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"},{"p0_max","/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"},
                {"p4_cur","/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq"},{"p4_max","/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"},
                {"p7_cur","/sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq"},{"p7_max","/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq"},
                {"gpu_clock","/sys/devices/platform/18500000.mali/clock"},{"gpu_util","/sys/devices/platform/18500000.mali/utilization"},
                {"gpu_max_lock","/sys/devices/platform/18500000.mali/dvfs_max_lock"},{"gpu_min_lock","/sys/devices/platform/18500000.mali/dvfs_min_lock"},
                {"uv0","/sys/kernel/percent_margin/cpucl0_margin_percent"},{"uv1","/sys/kernel/percent_margin/cpucl1_margin_percent"},
                {"uv2","/sys/kernel/percent_margin/cpucl2_margin_percent"},{"uvg","/sys/kernel/percent_margin/g3d_margin_percent"},
                {"uvmif","/sys/kernel/percent_margin/mif_margin_percent"},{"uvdsu","/sys/kernel/percent_margin/dsu_margin_percent"},{"uvint","/sys/kernel/percent_margin/int_margin_percent"},
                {"batt_temp","/sys/class/power_supply/battery/temp"}
        };
        for(String[] n:nodes)s.append(readCmd(n[0],n[1]));
        if(!mif.equals("NA")){
            s.append(readCmd("mif_cur",mif+"/cur_freq")); s.append(readCmd("mif_min",mif+"/min_freq")); s.append(readCmd("mif_max",mif+"/max_freq"));
        } else s.append("printf 'mif_cur=NA\\nmif_min=NA\\nmif_max=NA\\n'; ");
        return s.toString();
    }

    static String applyMarginsCommand(String[] nodes,int[] values){
        StringBuilder s=new StringBuilder("ok=0; ");
        for(int i=0;i<nodes.length;i++) s.append("[ -w '").append(nodes[i]).append("' ] && printf '%s\\n' '").append(values[i]).append("' > '").append(nodes[i]).append("' 2>/dev/null || ok=13; ");
        s.append("[ $ok -eq 0 ]"); return s.toString();
    }

    static String applyGpuMifCommand(Discovery d,long gpuKHz,long mifKHz,int highspeedLoad){
        if(!d.gpuHas(gpuKHz)) return "echo 'UNSUPPORTED_GPU_OPP'; false";
        if(!d.mifHas(mifKHz)) return "echo 'UNSUPPORTED_MIF_OPP'; false";
        String mif=d.get("mif_path");
        return "printf '%s\\n' '"+highspeedLoad+"' > /sys/devices/platform/18500000.mali/highspeed_load && " +
                "printf '%s\\n' '"+gpuKHz+"' > /sys/devices/platform/18500000.mali/highspeed_clock && " +
                "printf '%s\\n' '"+gpuKHz+"' > /sys/devices/platform/18500000.mali/dvfs_min_lock && " +
                "printf '%s\\n' '"+mifKHz+"' > '"+mif+"/min_freq'";
    }

    static String releaseGpuMifCommand(Discovery d){
        long low=d.mifMin(); if(low<=0)low=421000;
        String mif=d.get("mif_path");
        return "printf '0\\n' > /sys/devices/platform/18500000.mali/dvfs_min_lock; " +
                (!mif.equals("NA")?"printf '%s\\n' '"+low+"' > '"+mif+"/min_freq'; ":"") + "true";
    }

    static final class Snapshot {
        final Map<String,String> values; Snapshot(Map<String,String> v){values=v;}
        static Snapshot parse(String t){return new Snapshot(parseKV(t));}
        String get(String k){return values.getOrDefault(k,"NA");}
        Long longValue(String k){ Matcher m=Pattern.compile("-?\\d+").matcher(get(k)); if(!m.find())return null; try{return Long.parseLong(m.group());}catch(Exception e){return null;} }
        Integer intValue(String k){Long v=longValue(k); return v==null?null:v.intValue();}
        Double tempC(String k){Long n=longValue(k); if(n==null)return null; double v=n; if(Math.abs(v)>1000)v/=1000.0; else if(k.equals("batt_temp")&&Math.abs(v)>100)v/=10.0; return v;}
        int[] margins(){int[] o=new int[UV_KEYS.length]; for(int i=0;i<o.length;i++){Integer v=intValue(UV_KEYS[i]); if(v==null||v<-15||v>15)return null;o[i]=v;}return o;}
        String marginsText(){int[] m=margins(); if(m==null)return "N/D"; return String.format(Locale.US,"A55=%d%% A78=%d%% X1=%d%% GPU=%d%% MIF=%d%% DSU=%d%% INT=%d%%",m[0],m[1],m[2],m[3],m[4],m[5],m[6]);}
    }

    static final class Accumulator {
        private static final String[] TK={"little_temp","mid_temp","big_temp","g3d_temp","mif_temp"};
        private static final String[] TN={"A55_LITTLE","A78_MID","X1_BIG","GPU_G3D","MIF_DDR5"};
        private static final String[] FK={"p0_cur","p4_cur","p7_cur","gpu_clock","mif_cur"};
        private static final String[] FN={"A55","A78","X1","GPU","MIF"};
        private final long interval; private Snapshot first,last; private int samples,failures; private String warning="";
        private final Map<String,Double> maxT=new LinkedHashMap<>(); private final Map<String,Long> peakF=new LinkedHashMap<>();
        Accumulator(long i){interval=i;}
        synchronized void add(Snapshot s){if(s==null)return;if(first==null)first=s;last=s;samples++;for(String k:TK){Double v=s.tempC(k);if(v!=null)maxT.put(k,Math.max(maxT.getOrDefault(k,-999.0),v));}for(String k:FK){Long v=s.longValue(k);if(v!=null)peakF.put(k,Math.max(peakF.getOrDefault(k,0L),v));}}
        synchronized void fail(String e){failures++;if(warning.isEmpty())warning=e==null?"":e.replace('\n',' ');}
        private String ft(Double v){return v==null?"NA":String.format(Locale.US,"%.1f",v);} private String fl(Long v){return v==null?"NA":Long.toString(v);}
        synchronized String reportBlock(Discovery d){StringBuilder b=new StringBuilder(3000);b.append("=== KERNEL / THERMAL TELEMETRY ===\n"); if(first==null||last==null){b.append("telemetry_status=UNAVAILABLE\ntelemetry_failures=").append(failures).append('\n');return b.toString();}
            b.append("telemetry_status=OK\ntelemetry_samples=").append(samples).append("\ntelemetry_failures=").append(failures).append("\ntelemetry_interval_ms=").append(interval).append('\n');
            b.append("uv_start=").append(first.marginsText()).append("\nuv_end=").append(last.marginsText()).append('\n'); b.append("gpu_dvfs_table_kHz=").append(d.get("gpu_table")).append('\n'); b.append("mif_available_kHz=").append(d.get("mif_available")).append('\n'); b.append("mif_temp_source=").append(d.get("mif_temp_source")).append('\n');
            for(int i=0;i<TK.length;i++){b.append(TN[i]).append("_temp_start_C=").append(ft(first.tempC(TK[i]))).append('\n');b.append(TN[i]).append("_temp_max_C=").append(ft(maxT.get(TK[i]))).append('\n');b.append(TN[i]).append("_temp_end_C=").append(ft(last.tempC(TK[i]))).append('\n');}
            for(int i=0;i<FK.length;i++){b.append(FN[i]).append("_freq_start_kHz=").append(fl(first.longValue(FK[i]))).append('\n');b.append(FN[i]).append("_freq_peak_kHz=").append(fl(peakF.get(FK[i]))).append('\n');b.append(FN[i]).append("_freq_end_kHz=").append(fl(last.longValue(FK[i]))).append('\n');}
            b.append("GPU_max_lock_raw_start=").append(first.get("gpu_max_lock")).append('\n'); b.append("GPU_min_lock_raw_start=").append(first.get("gpu_min_lock")).append('\n'); b.append("MIF_min_start_kHz=").append(first.get("mif_min")).append("\nMIF_max_start_kHz=").append(first.get("mif_max")).append('\n');
            if(!warning.isEmpty())b.append("telemetry_warning=").append(warning).append('\n'); return b.toString(); }
    }

    private static Map<String,String> parseKV(String text){Map<String,String> m=new LinkedHashMap<>(); if(text!=null)for(String l:text.split("\\n")){int p=l.indexOf('=');if(p>0)m.put(l.substring(0,p).trim(),l.substring(p+1).trim());}return m;}
    private static long[] numbers(String s){ArrayList<Long> a=new ArrayList<>();Matcher m=Pattern.compile("\\d+").matcher(s==null?"":s);while(m.find())try{long v=Long.parseLong(m.group());if(v>=100000)a.add(v);}catch(Exception ignored){} long[] r=new long[a.size()];for(int i=0;i<r.length;i++)r[i]=a.get(i);return r;}
    private static long max(long[] a){long x=0;for(long v:a)x=Math.max(x,v);return x;} private static long minPositive(long[] a){long x=Long.MAX_VALUE;for(long v:a)if(v>0)x=Math.min(x,v);return x==Long.MAX_VALUE?0:x;} private static boolean contains(long[] a,long x){for(long v:a)if(v==x)return true;return false;}
    private static String joinMHz(long[] a){if(a.length==0)return "N/D";StringBuilder b=new StringBuilder();for(long v:a){if(b.length()>0)b.append(',');b.append(String.format(Locale.US,"%.0f",v/1000.0));}return b.toString();}
    private static String shellSafe(String s){return s==null?"NA":s.replace("'","");}
}