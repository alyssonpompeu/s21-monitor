package com.alysson.s21lab;

import android.opengl.*;
import java.nio.*;

final class GpuBench {
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x40;

    static Result run(long ms) {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) return new Result(0, "EGL_NO_DISPLAY");
        int[] v = new int[2];
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) return new Result(0, "eglInitialize");

        int[] attrs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfg = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfg, 0, 1, num, 0) || num[0] < 1) {
            EGL14.eglTerminate(display);
            return new Result(0, "eglChooseConfig");
        }

        int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
        EGLContext ctx = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
        int[] surfAttr = { EGL14.EGL_WIDTH, 512, EGL14.EGL_HEIGHT, 512, EGL14.EGL_NONE };
        EGLSurface surf = EGL14.eglCreatePbufferSurface(display, cfg[0], surfAttr, 0);
        if (ctx == EGL14.EGL_NO_CONTEXT || surf == EGL14.EGL_NO_SURFACE ||
                !EGL14.eglMakeCurrent(display, surf, surf, ctx)) {
            EGL14.eglTerminate(display);
            return new Result(0, "eglContext/surface");
        }

        String vs = "#version 300 es\n" +
                "layout(location=0) in vec2 p;\n" +
                "void main(){gl_Position=vec4(p,0.0,1.0);}\n";
        String fs = "#version 300 es\nprecision highp float;\nout vec4 c;\n" +
                "void main(){vec2 uv=gl_FragCoord.xy/512.0; float x=uv.x+uv.y;" +
                "for(int i=0;i<28;i++){x=sin(x*1.013+0.17)*cos(x*0.997+0.11)+sqrt(abs(x)+0.001);}" +
                "c=vec4(fract(x),fract(x*1.37),fract(x*1.91),1.0);}\n";

        int prog;
        try {
            prog = makeProgram(vs, fs);
        } catch (RuntimeException e) {
            cleanup(display, surf, ctx);
            return new Result(0, e.getMessage());
        }

        float[] verts = {-1f,-1f, 3f,-1f, -1f,3f};
        FloatBuffer fb = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(verts).position(0);
        int[] bufs = new int[1];
        GLES30.glGenBuffers(1, bufs, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[0]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.length * 4, fb, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glUseProgram(prog);
        GLES30.glViewport(0, 0, 512, 512);

        long start = System.nanoTime();
        long end = start + ms * 1_000_000L;
        long draws = 0;
        while (System.nanoTime() < end) {
            for (int i = 0; i < 16; i++) {
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
                draws++;
            }
            GLES30.glFinish();
        }
        long elapsed = System.nanoTime() - start;

        GLES30.glDeleteBuffers(1, bufs, 0);
        GLES30.glDeleteProgram(prog);
        cleanup(display, surf, ctx);
        return new Result(draws / (elapsed / 1e9), "OK");
    }

    private static int makeProgram(String vs, String fs) {
        int v = shader(GLES30.GL_VERTEX_SHADER, vs);
        int f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        String log = GLES30.glGetProgramInfoLog(p);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        if (ok[0] == 0) throw new RuntimeException("GPU link: " + log);
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        String log = GLES30.glGetShaderInfoLog(s);
        if (ok[0] == 0) {
            GLES30.glDeleteShader(s);
            throw new RuntimeException("GPU shader: " + log);
        }
        return s;
    }

    private static void cleanup(EGLDisplay d, EGLSurface s, EGLContext c) {
        EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, s);
        if (c != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, c);
        EGL14.eglTerminate(d);
    }

    static final class Result {
        final double drawsPerSecond;
        final String status;
        Result(double d, String s) { drawsPerSecond = d; status = s; }
    }
}
