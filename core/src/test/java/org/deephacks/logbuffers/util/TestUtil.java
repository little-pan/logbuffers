package org.deephacks.logbuffers.util;

import com.google.common.base.Charsets;
import org.deephacks.logbuffers.Log;
import org.deephacks.logbuffers.Tail;
import org.deephacks.logbuffers.serializer.JacksonSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class TestUtil {
    public static List<String> randomStrings = new ArrayList<>();
    static {
        for (int i = 0; i < 1000; i++) {
            randomStrings.add(UUID.randomUUID().toString());
        }
    }

    public static String tmpDir() {
        try {
            return Files.createTempDirectory("logBufferTest-" + UUID.randomUUID().toString()).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Log randomLog(long timestamp) {
        return new Log(Log.DEFAULT_TYPE, UUID.randomUUID().toString().getBytes(Charsets.UTF_8), timestamp, timestamp);
    }

    public static A randomA() {
        return new A(UUID.randomUUID().toString(), new Random().nextLong());
    }

    public static B randomB() {
        return new B(UUID.randomUUID().toString(), new Random().nextLong());
    }

    public static String randomCachedItem() {
        return randomStrings.get(Math.abs(new Random().nextInt()) % randomStrings.size());
    }

    public static JacksonSerializer getSerializer() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.put(A.class, 123L);
        serializer.put(B.class, 124L);
        return serializer;
    }

    public static class TailA implements Tail<A> {

        public List<A> logs = new ArrayList<>();

        @Override
        public void process(List<A> logs) {
            this.logs.addAll(logs);
        }
    }

    public static class TailB implements Tail<B> {

        public List<B> logs = new ArrayList<>();

        @Override
        public void process(List<B> logs) {
            this.logs.addAll(logs);
        }
    }

    public static class TailLog implements Tail<Log> {

        public List<Log> logs = new ArrayList<>();

        @Override
        public void process(List<Log> logs) {
            this.logs.addAll(logs);
        }
    }


    public static class A {
        private String str;
        private Long val;

        private A() {

        }

        public A(String str, Long val) {
            this.str = str;
            this.val = val;
        }

        public String getStr() {
            return str;
        }

        public Long getVal() {
            return val;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            A a = (A) o;

            if (str != null ? !str.equals(a.str) : a.str != null) return false;
            if (val != null ? !val.equals(a.val) : a.val != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = str != null ? str.hashCode() : 0;
            result = 31 * result + (val != null ? val.hashCode() : 0);
            return result;
        }
    }


    public static class B {
        private String str;
        private Long val;

        private B() {

        }

        public B(String str, Long val) {
            this.str = str;
            this.val = val;
        }

        public String getStr() {
            return str;
        }

        public Long getVal() {
            return val;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            B b = (B) o;

            if (str != null ? !str.equals(b.str) : b.str != null) return false;
            if (val != null ? !val.equals(b.val) : b.val != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = str != null ? str.hashCode() : 0;
            result = 31 * result + (val != null ? val.hashCode() : 0);
            return result;
        }
    }
}