package com.alysson.kernelbench;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class BenchmarkSurface extends GLSurfaceView {
    public static final class GpuResult {
        public final double megapixelsPerSecond;
        public final double averageSceneFps;
        public final long frames;

        GpuResult(double mpps, double fps, long frames) {
            this.megapixelsPerSecond = mpps;
            this.averageSceneFps = fps;
            this.frames = frames;
        }
    }

    public interface GpuCallback {
        void onComplete(GpuResult result);
    }

    private final RendererImpl renderer;

    public BenchmarkSurface(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        renderer = new RendererImpl(this);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(true);
    }

    public void startGpuBenchmark(long durationMs, GpuCallback callback) {
        queueEvent(() -> renderer.startGpuBenchmark(durationMs, callback));
    }

    public double getDisplayedFps() {
        return renderer.getDisplayedFps();
    }

    private static final class RendererImpl implements GLSurfaceView.Renderer {
        private static final int BENCH_W = 720;
        private static final int BENCH_H = 720;
        private static final int BENCH_PASSES = 4;
        private static final long TARGET_FRAME_NS = 16_666_667L;

        private final BenchmarkSurface view;
        private int width = 1;
        private int height = 1;

        private int sceneProgram;
        private int benchProgram;
        private int cubeVbo;
        private int fbo;
        private int fboTex;

        private final float[] projection = new float[16];
        private long startNs;
        private long lastFpsNs;
        private long fpsFrames;
        private volatile double displayedFps;

        private volatile boolean benchActive;
        private long benchEndNs;
        private GpuCallback benchCallback;
        private long benchFrames;
        private double benchSeconds;
        private double benchPixels;
        private double benchSceneFpsSum;

        RendererImpl(BenchmarkSurface view) {
            this.view = view;
        }

        double getDisplayedFps() {
            return displayedFps;
        }

        void startGpuBenchmark(long durationMs, GpuCallback callback) {
            benchActive = true;
            benchEndNs = System.nanoTime() + durationMs * 1_000_000L;
            benchCallback = callback;
            benchFrames = 0;
            benchSeconds = 0.0;
            benchPixels = 0.0;
            benchSceneFpsSum = 0.0;
        }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            GLES30.glClearColor(0.012f, 0.018f, 0.035f, 1f);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glEnable(GLES30.GL_CULL_FACE);

            sceneProgram = link(SCENE_VERTEX, SCENE_FRAGMENT);
            benchProgram = link(BENCH_VERTEX, BENCH_FRAGMENT);

            float[] cube = cubeVertices();
            FloatBuffer fb = ByteBuffer.allocateDirect(cube.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(cube).position(0);

            int[] ids = new int[1];
            GLES30.glGenBuffers(1, ids, 0);
            cubeVbo = ids[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubeVbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cube.length * 4, fb, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

            setupFbo();

            startNs = System.nanoTime();
            lastFpsNs = startNs;
        }

        @Override
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int w, int h) {
            width = Math.max(1, w);
            height = Math.max(1, h);
            float aspect = width / (float) height;
            Matrix.perspectiveM(projection, 0, 58f, aspect, 0.2f, 30f);
            GLES30.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            long frameStart = System.nanoTime();

            if (benchActive) {
                runBenchBatch();
            }

            drawScene();

            fpsFrames++;
            long now = System.nanoTime();
            long fpsElapsed = now - lastFpsNs;
            if (fpsElapsed >= 1_000_000_000L) {
                displayedFps = fpsFrames * 1_000_000_000.0 / fpsElapsed;
                fpsFrames = 0;
                lastFpsNs = now;
            }

            if (benchActive) {
                benchFrames++;
                benchSceneFpsSum += displayedFps;
                if (now >= benchEndNs) finishBenchmark();
            }

            long elapsed = System.nanoTime() - frameStart;
            long remain = TARGET_FRAME_NS - elapsed;
            if (remain > 500_000L) {
                long ms = remain / 1_000_000L;
                int ns = (int) (remain % 1_000_000L);
                try {
                    Thread.sleep(ms, ns);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void runBenchBatch() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glViewport(0, 0, BENCH_W, BENCH_H);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glUseProgram(benchProgram);

            int timeLoc = GLES30.glGetUniformLocation(benchProgram, "uTime");
            GLES30.glUniform1f(timeLoc, (System.nanoTime() - startNs) / 1_000_000_000f);

            long t0 = System.nanoTime();
            for (int i = 0; i < BENCH_PASSES; i++) {
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            }
            GLES30.glFinish();
            long t1 = System.nanoTime();

            benchSeconds += (t1 - t0) / 1_000_000_000.0;
            benchPixels += (double) BENCH_W * BENCH_H * BENCH_PASSES;

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glViewport(0, 0, width, height);
        }

        private void finishBenchmark() {
            benchActive = false;
            double mpps = benchSeconds > 0 ? (benchPixels / 1_000_000.0) / benchSeconds : 0.0;
            double avgFps = benchFrames > 0 ? benchSceneFpsSum / benchFrames : displayedFps;
            GpuCallback cb = benchCallback;
            benchCallback = null;
            if (cb != null) {
                GpuResult result = new GpuResult(mpps, avgFps, benchFrames);
                view.post(() -> cb.onComplete(result));
            }
        }

        private void drawScene() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, width, height);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glUseProgram(sceneProgram);

            float time = (System.nanoTime() - startNs) / 1_000_000_000f;
            int projLoc = GLES30.glGetUniformLocation(sceneProgram, "uProj");
            int timeLoc = GLES30.glGetUniformLocation(sceneProgram, "uTime");
            GLES30.glUniformMatrix4fv(projLoc, 1, false, projection, 0);
            GLES30.glUniform1f(timeLoc, time);

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubeVbo);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0);
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 36, 48);
            GLES30.glDisableVertexAttribArray(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        private void setupFbo() {
            int[] ids = new int[1];

            GLES30.glGenTextures(1, ids, 0);
            fboTex = ids[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                    BENCH_W, BENCH_H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);

            GLES30.glGenFramebuffers(1, ids, 0);
            fbo = ids[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, fboTex, 0);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        }

        private static int link(String vs, String fs) {
            int v = compile(GLES30.GL_VERTEX_SHADER, vs);
            int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, v);
            GLES30.glAttachShader(p, f);
            GLES30.glLinkProgram(p);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES30.glGetProgramInfoLog(p);
                GLES30.glDeleteProgram(p);
                throw new RuntimeException("GL link failed: " + log);
            }
            GLES30.glDeleteShader(v);
            GLES30.glDeleteShader(f);
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES30.glGetShaderInfoLog(s);
                GLES30.glDeleteShader(s);
                throw new RuntimeException("GL shader failed: " + log);
            }
            return s;
        }

        private static float[] cubeVertices() {
            return new float[] {
                    -1,-1, 1,   1,-1, 1,   1, 1, 1,  -1,-1, 1,   1, 1, 1,  -1, 1, 1,
                     1,-1,-1,  -1,-1,-1,  -1, 1,-1,   1,-1,-1,  -1, 1,-1,   1, 1,-1,
                    -1,-1,-1,  -1,-1, 1,  -1, 1, 1,  -1,-1,-1,  -1, 1, 1,  -1, 1,-1,
                     1,-1, 1,   1,-1,-1,   1, 1,-1,   1,-1, 1,   1, 1,-1,   1, 1, 1,
                    -1, 1, 1,   1, 1, 1,   1, 1,-1,  -1, 1, 1,   1, 1,-1,  -1, 1,-1,
                    -1,-1,-1,   1,-1,-1,   1,-1, 1,  -1,-1,-1,   1,-1, 1,  -1,-1, 1
            };
        }

        private static final String SCENE_VERTEX =
                "#version 300 es\n" +
                "layout(location=0) in vec3 aPos;\n" +
                "uniform mat4 uProj;\n" +
                "uniform float uTime;\n" +
                "out vec3 vColor;\n" +
                "out float vDepth;\n" +
                "void main(){\n" +
                "  int id = gl_InstanceID;\n" +
                "  int ringI = id / 8;\n" +
                "  int segI = id - ringI * 8;\n" +
                "  float ring = float(ringI);\n" +
                "  float seg = float(segI) * 0.78539816339;\n" +
                "  float phase = uTime * 0.22 + ring * 0.31;\n" +
                "  float c = cos(phase), s = sin(phase);\n" +
                "  mat2 r = mat2(c,-s,s,c);\n" +
                "  vec3 lp = vec3(r * aPos.xy, aPos.z) * (0.18 + ring * 0.012);\n" +
                "  float radius = 1.15 + ring * 0.28;\n" +
                "  float a = seg + uTime * (0.08 + ring * 0.006);\n" +
                "  float z = -2.1 - ring * 1.20;\n" +
                "  vec3 p = lp + vec3(cos(a)*radius, sin(a)*radius*0.48, z);\n" +
                "  gl_Position = uProj * vec4(p,1.0);\n" +
                "  vColor = 0.52 + 0.48*cos(vec3(0.0,2.1,4.2)+a+ring*0.4);\n" +
                "  vDepth = clamp((-z-2.0)/7.0,0.0,1.0);\n" +
                "}\n";

        private static final String SCENE_FRAGMENT =
                "#version 300 es\n" +
                "precision highp float;\n" +
                "in vec3 vColor;\n" +
                "in float vDepth;\n" +
                "out vec4 frag;\n" +
                "void main(){\n" +
                "  vec3 base = mix(vColor*0.75, vec3(0.05,0.10,0.22), vDepth*0.65);\n" +
                "  frag = vec4(base,1.0);\n" +
                "}\n";

        private static final String BENCH_VERTEX =
                "#version 300 es\n" +
                "void main(){\n" +
                "  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n" +
                "  gl_Position = vec4(p*2.0-1.0,0.0,1.0);\n" +
                "}\n";

        private static final String BENCH_FRAGMENT =
                "#version 300 es\n" +
                "precision highp float;\n" +
                "uniform float uTime;\n" +
                "out vec4 frag;\n" +
                "void main(){\n" +
                "  vec2 uv = gl_FragCoord.xy / vec2(720.0,720.0);\n" +
                "  vec2 z = (uv*2.0-1.0)*1.15;\n" +
                "  vec2 c = vec2(-0.743 + 0.02*sin(uTime*0.1), 0.131 + 0.02*cos(uTime*0.13));\n" +
                "  float acc = 0.0;\n" +
                "  for(int i=0;i<36;i++){\n" +
                "    z = vec2(z.x*z.x-z.y*z.y, 2.0*z.x*z.y) + c;\n" +
                "    float q = dot(z,z);\n" +
                "    acc += inversesqrt(0.35 + abs(q-1.7));\n" +
                "    z *= 0.985 + 0.01*sin(float(i)*0.37 + uTime*0.07);\n" +
                "  }\n" +
                "  float v = fract(acc*0.071);\n" +
                "  frag = vec4(v, fract(v*1.73), fract(v*2.31), 1.0);\n" +
                "}\n";
    }
}
