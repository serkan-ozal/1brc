/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

//import jdk.incubator.vector.ByteVector;
//import jdk.incubator.vector.VectorOperators;
//import jdk.incubator.vector.VectorSpecies;
import sun.misc.Unsafe;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author serkan-ozal
 */
public class CalculateAverage_serkan_ozal {

    private static final String FILE = "./measurements.txt";

    private static final ByteOrder NATIVE_BYTE_ORDER = ByteOrder.nativeOrder();
    private static final char NEW_LINE_SEPARATOR = '\n';
    private static final char KEY_VALUE_SEPARATOR = ';';
    private static final int MAX_LINE_LENGTH = 128;

    // Get configurations
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private static final boolean VERBOSE = false; //getBooleanConfig("VERBOSE", false);
    private static final int THREAD_COUNT = 8; //getIntegerConfig("THREAD_COUNT", Runtime.getRuntime().availableProcessors());
    private static final boolean USE_VTHREADS = false; //getBooleanConfig("USE_VTHREADS", false);
    private static final int VTHREAD_COUNT = 1024; //getIntegerConfig("VTHREAD_COUNT", 1024);
    private static final int REGION_COUNT = -1; //getIntegerConfig("REGION_COUNT", -1);
    private static final boolean USE_SHARED_ARENA = true; //getBooleanConfig("USE_SHARED_ARENA", true);
    private static final boolean USE_SHARED_REGION = true; //getBooleanConfig("USE_SHARED_REGION", true);
    private static final int MAP_CAPACITY = 1 << 17; //getIntegerConfig("MAP_CAPACITY", 1 << 17);
    private static final boolean CLOSE_STDOUT_ON_RESULT = true; //getBooleanConfig("CLOSE_STDOUT_ON_RESULT", false);
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // My dear old friend Unsafe
    private static final Unsafe U;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        if (VERBOSE) {
            System.out.println("Processing started at " + start);
//            System.out.println("Vector byte size: " + BYTE_SPECIES.vectorByteSize());
            System.out.println("Use shared memory arena: " + USE_SHARED_ARENA);
            if (USE_VTHREADS) {
                System.out.println("Virtual thread count: " + VTHREAD_COUNT);
            }
            else {
                System.out.println("Thread count: " + THREAD_COUNT);
            }
            System.out.println("Map capacity: " + MAP_CAPACITY);
        }

        int concurrency = USE_VTHREADS ? VTHREAD_COUNT : THREAD_COUNT;
        int regionCount = REGION_COUNT > 0 ? REGION_COUNT : concurrency;
        ByteBuffer lineBuffer = getByteBuffer(MAX_LINE_LENGTH);
        Result result = new Result();

        RandomAccessFile file = new RandomAccessFile(FILE, "r");
        FileChannel fc = file.getChannel();
        Arena arena = USE_SHARED_ARENA ? Arena.global() : null;
        try {
            long fileSize = fc.size();
            long regionSize = fileSize / regionCount;
            long startPos = 0;
            ExecutorService executor = USE_VTHREADS
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : Executors.newFixedThreadPool(concurrency, new RegionProcessorThreadFactory());
            MemorySegment region = null;
            if (USE_SHARED_REGION) {
                arena = Arena.global();
                region = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
            }
            // Split whole file into regions and start region processors to handle those regions
            List<Future<Response>> futures = new ArrayList<>(regionCount);
            for (int i = 0; i < regionCount; i++) {
                long endPos = Math.min(fileSize, startPos + regionSize);
                // Lines might split into different regions.
                // If so, move back to the line starting at the end of previous region
                long closestLineEndPos = (i < regionCount - 1)
                        ? findClosestLineEnd(fc, endPos, lineBuffer)
                        : fileSize;
                Request request = new Request(fc, arena, region, startPos, closestLineEndPos, result);
                RegionProcessor regionProcessor = createRegionProcessor(request);
                Future<Response> future = executor.submit(regionProcessor);
                futures.add(future);
                startPos = closestLineEndPos;
            }

            // Wait processors to complete
            for (Future<Response> future : futures) {
                future.get();
            }

            long finish = System.currentTimeMillis();
            if (VERBOSE) {
                System.out.println("Processing completed at " + finish);
                System.out.println("Processing completed in " + (finish - start) + " milliseconds");
            }

            // Print result to stdout
            result.print();

            if (CLOSE_STDOUT_ON_RESULT) {
                // After printing result, close stdout.
                // So parent process can complete without waiting this process completed.
                // Saves a few hundred milliseconds caused by unmap.
                System.out.close();
            }
        }
        finally {
            // Close memory arena if it is managed globally here (shared arena)
            if (arena != null) {
//                arena.close();
            }
            fc.close();
            if (VERBOSE) {
                long finish = System.currentTimeMillis();
                System.out.println("All completed at " + finish);
                System.out.println("All Completed in " + ((finish - start)) + " milliseconds");
            }
        }
    }

    private static boolean getBooleanConfig(String envVarName, boolean defaultValue) {
        String envVarValue = System.getenv(envVarName);
        if (envVarValue == null) {
            return defaultValue;
        }
        else {
            return Boolean.parseBoolean(envVarValue);
        }
    }

    private static int getIntegerConfig(String envVarName, int defaultValue) {
        String envVarValue = System.getenv(envVarName);
        if (envVarValue == null) {
            return defaultValue;
        }
        else {
            return Integer.parseInt(envVarValue);
        }
    }

    private static ByteBuffer getByteBuffer(int size) {
        ByteBuffer bb = ByteBuffer.allocateDirect(size);
        bb.order(NATIVE_BYTE_ORDER);
        return bb;
    }

    private static long findClosestLineEnd(FileChannel fc, long endPos, ByteBuffer lineBuffer) throws IOException {
        long lineCheckStartPos = Math.max(0, endPos - MAX_LINE_LENGTH);
        lineBuffer.rewind();
        fc.read(lineBuffer, lineCheckStartPos);
        int i = MAX_LINE_LENGTH;
        while (lineBuffer.get(i - 1) != NEW_LINE_SEPARATOR) {
            i--;
        }
        return lineCheckStartPos + i;
    }

    private static RegionProcessor createRegionProcessor(Request request) {
        return new RegionProcessor(request);
    }

    private static class RegionProcessorThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }

    }

    /**
     * Region processor
     */
    private static class RegionProcessor implements Callable<Response> {

        private final FileChannel fc;
        private final Arena arena;
        private final MemorySegment region;
        private final long start;
        private final long end;
        private final long size;
        private OpenMap map;
        private final Result result;

        private RegionProcessor(Request request) {
            this.fc = request.fileChannel;
            this.arena = request.arena;
            this.region = request.region;
            this.start = request.start;
            this.end = request.end;
            this.size = end - start;
            this.result = request.result;
        }

        @Override
        public Response call() throws Exception {
            if (VERBOSE) {
                System.out.println("[Processor-" + Thread.currentThread().getName() + "] Processing started at " + System.currentTimeMillis());
            }
            try {
                processRegion();
                return new Response(map);
            }
            finally {
                if (VERBOSE) {
                    System.out.println("[Processor-" + Thread.currentThread().getName() + "] Processing finished at " + System.currentTimeMillis());
                }
            }
        }

        private void processRegion() throws Exception {
            boolean arenaGiven = arena != null;
            // If no shared global memory arena is used, create and use its own local memory arena
            Arena a = arenaGiven ? arena : Arena.ofConfined();
            try {
                boolean regionGiven = region != null;
                MemorySegment r = regionGiven
                        ? region
                        : fc.map(FileChannel.MapMode.READ_ONLY, start, size, a);
                long regionStart = regionGiven ? (r.address() + start) : r.address();
                long regionEnd = regionStart + size;

                doProcessRegion(regionStart, regionEnd);
                if (VERBOSE) {
                    System.out.println("[Processor-" + Thread.currentThread().getName() + "] Region processed at " + System.currentTimeMillis());
                }

                // Some threads/processors might finish slightly before others.
                // So, instead of releasing their cores idle, merge their own results here.

                // If there is no another processor merging its results now, merge now.
                // Otherwise (there is already another thread/processor got the lock of merging),
                // Close current processor's own local memory arena (if no shared global memory arena is used) now
                // and merge its own results after then.

                boolean merged = result.tryMergeInto(map);
                if (VERBOSE && merged) {
                    System.out.println("[Processor-" + Thread.currentThread().getName() + "] Result merged at " + System.currentTimeMillis());
                }
                if (!merged) {
                    if (!arenaGiven) {
                        a.close();
                        a = null;
                        if (VERBOSE) {
                            System.out.println("[Processor-" + Thread.currentThread().getName() + "] Arena closed at " + System.currentTimeMillis());
                        }
                    }
                    result.mergeInto(map);
                    if (VERBOSE) {
                        System.out.println("[Processor-" + Thread.currentThread().getName() + "] Result merged at " + System.currentTimeMillis());
                    }
                }
            }
            finally {
                // If local memory arena is managed here and not closed yet, close it here
                if (!arenaGiven && a != null) {
                    a.close();
                    if (VERBOSE) {
                        System.out.println("[Processor-" + Thread.currentThread().getName() + "] Arena closed at " + System.currentTimeMillis());
                    }
                }
            }
        }

        private void doProcessRegion(long regionStart, long regionEnd) {
            final long regionMainLimit = regionEnd - MAX_LINE_LENGTH;

            this.map = new OpenMap();
            long regionPtr;

            // Read and process region - main
            for (regionPtr = regionStart; regionPtr < regionMainLimit;) {
                regionPtr = doProcessLine(regionPtr);
            }

//            // Read and process region - tail
//            for (long i = regionPtr, j = regionPtr; i < regionEnd;) {
//                byte b = U.getByte(i);
//                if (b == KEY_VALUE_SEPARATOR) {
//                    long baseOffset = map.putKey(j, (int) (i - j), 0, 0);
//                    i = extractValue(i + 1, map, baseOffset);
//                    j = i;
//                }
//                else {
//                    i++;
//                }
//            }
        }

        private long doProcessLine(long regionPtr) {
            // Find key/value separator
            ////////////////////////////////////////////////////////////////////////////////////////////////////////
            long keyStartPtr = regionPtr;

            int delimiterPos = 0;

            long word1 = U.getLong(keyStartPtr);
            long word2 = U.getLong(keyStartPtr + Long.BYTES);

            long match1 = word1 ^ 0x3B3B3B3B3B3B3B3BL;
            long delimiterMask1 = (match1 - 0x0101010101010101L) & (~match1 & 0x8080808080808080L);
            int delimiterPos1 = Long.numberOfTrailingZeros(delimiterMask1) >>> 3;
            delimiterPos += delimiterPos1;

            long match2 = word2 ^ 0x3B3B3B3B3B3B3B3BL;
            long delimiterMask2 = (match2 - 0x0101010101010101L) & (~match2 & 0x8080808080808080L);
            int delimiterPos2 = Long.numberOfTrailingZeros(delimiterMask2) >>> 3;
            delimiterPos += ((delimiterPos1 / Long.BYTES) * delimiterPos2);

            regionPtr += delimiterPos;

            if (delimiterPos == 2 * Long.BYTES) {
                for (; U.getByte(regionPtr) != KEY_VALUE_SEPARATOR; regionPtr++)
                    ;
            }

            int keyLength = (int) (regionPtr - keyStartPtr);
            regionPtr++;
            ////////////////////////////////////////////////////////////////////////////////////////////////////////

            // Put key and get map offset to put value
            long mapOffset = map.putKey(keyStartPtr, keyLength, word1, word2);

            // Extract value, put it into map and return next position in the region to continue processing from there
            return extractValue(regionPtr, map, mapOffset);
        }

    }

    // Credits: merykitty
    private static long extractValue(long regionPtr, OpenMap map, long mapOffset) {
        long word = U.getLong(regionPtr);
//        if (NATIVE_BYTE_ORDER == ByteOrder.BIG_ENDIAN) {
//            word = Long.reverseBytes(word);
//        }

        // Parse and extract value
        int decimalSepPos = Long.numberOfTrailingZeros(~word & 0x10101000);
        int shift = 28 - decimalSepPos;
        long signed = (~word << 59) >> 63;
        long designMask = ~(signed & 0xFF);
        long digits = ((word & designMask) << shift) & 0x0F000F0F00L;
        long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
        int value = (int) ((absValue ^ signed) - signed);

        // Put extracted value into map
        map.putValue(mapOffset, value);

        // Return new position
        return regionPtr + (decimalSepPos >>> 3) + 3;
    }

    /**
     * Region processor request
     */
    private static final class Request {

        private final FileChannel fileChannel;
        private final Arena arena;
        private final MemorySegment region;
        private final long start;
        private final long end;
        private final Result result;

        private Request(FileChannel fileChannel, Arena arena, MemorySegment region,
                        long start, long end, Result result) {
            this.fileChannel = fileChannel;
            this.arena = arena;
            this.region = region;
            this.start = start;
            this.end = end;
            this.result = result;
        }

    }

    /**
     * Region processor response
     */
    private static final class Response {

        private final OpenMap map;

        private Response(OpenMap map) {
            this.map = map;
        }

    }

    /**
     * Result of each key (city)
     */
    private static final class KeyResult {

        private int count;
        private int minValue;
        private int maxValue;
        private long sum;

        private KeyResult(int count, int minValue, int maxValue, long sum) {
            this.count = count;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.sum = sum;
        }

        private void merge(KeyResult result) {
            count += result.count;
            minValue = Math.min(minValue, result.minValue);
            maxValue = Math.max(maxValue, result.maxValue);
            sum += result.sum;
        }

        @Override
        public String toString() {
            return (minValue / 10.0) + "/" + round(sum / (double) (count * 10)) + "/" + (maxValue / 10.0);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

    }

    /**
     * Global result
     */
    private static final class Result {

        private final Lock lock = new ReentrantLock();
        private final Map<String, KeyResult> resultMap;

        private Result() {
            this.resultMap = new TreeMap<>();
        }

        private boolean tryMergeInto(OpenMap map) {
            // Use lock (not "synchronized" block) to be virtual threads friendly
            if (!lock.tryLock()) {
                return false;
            }
            try {
                map.merge(this.resultMap);
                return true;
            }
            finally {
                lock.unlock();
            }
        }

        private void mergeInto(OpenMap map) {
            // Use lock (not "synchronized" block) to be virtual threads friendly
            lock.lock();
            try {
                map.merge(this.resultMap);
            }
            finally {
                lock.unlock();
            }
        }

        private void print() {
            System.out.println(resultMap);
        }

    }

    private static final class OpenMap {

        // Layout
        // ================================
        // 0 : 4 bytes - count
        // 4 : 2 bytes - min value
        // 6 : 2 bytes - max value
        // 8 : 8 bytes - value sum
        // 16 : 4 bytes - key size
        // 20 : 4 bytes - padding
        // 24 : 100 bytes - key
        // 124 : 4 bytes - padding
        // ================================
        // 128 bytes - total

        private static final int ENTRY_SIZE = 128;
        private static final int COUNT_OFFSET = 0;
        private static final int MIN_VALUE_OFFSET = 4;
        private static final int MAX_VALUE_OFFSET = 6;
        private static final int VALUE_SUM_OFFSET = 8;
        private static final int KEY_SIZE_OFFSET = 16;
        private static final int KEY_OFFSET = 24;

        private static final int ENTRY_HASH_MASK = MAP_CAPACITY - 1;
        private static final int MAP_SIZE = ENTRY_SIZE * MAP_CAPACITY;
        private static final int ENTRY_MASK = MAP_SIZE - 1;

        private final byte[] data;

        private OpenMap() {
            this.data = new byte[MAP_SIZE];
        }

        // Credits: merykitty
        private static int calculateKeyHash(long address, int keyLength) {
            int seed = 0x9E3779B9;
            int rotate = 5;
            int x, y;
            if (keyLength >= Integer.BYTES) {
                x = U.getInt(address);
                y = U.getInt(address + keyLength - Integer.BYTES);
            }
            else {
                x = U.getByte(address);
                y = U.getByte(address + keyLength - Byte.BYTES);
            }
            return (Integer.rotateLeft(x * seed, rotate) ^ y) * seed;
        }

        private long putKey(long keyStartAddress, int keyLength, long word1, long word2) {
            // Calculate hash of key
            int keyHash = calculateKeyHash(keyStartAddress, keyLength);
            // and get the position of the entry in the linear map based on calculated hash
            int idx = keyHash & ENTRY_HASH_MASK;

            // Start searching from the calculated position
            // and continue until find an available slot in case of hash collision
            // TODO Prevent infinite loop if all the slots are in use for other keys
            for (long baseOffset = Unsafe.ARRAY_BYTE_BASE_OFFSET + (idx * ENTRY_SIZE);; baseOffset = (baseOffset + ENTRY_SIZE) & ENTRY_MASK) {
                int keyStartOffset = (int) baseOffset + KEY_OFFSET;
                int keySize = U.getInt(data, baseOffset + KEY_SIZE_OFFSET);
                // Check whether current index is empty (no another key is inserted yet)
                if (keySize == 0) {
                    // Initialize entry slot for new key
                    U.putShort(data, baseOffset + MIN_VALUE_OFFSET, Short.MAX_VALUE);
                    U.putShort(data, baseOffset + MAX_VALUE_OFFSET, Short.MIN_VALUE);
                    U.putInt(data, baseOffset + KEY_SIZE_OFFSET, keyLength);
                    U.copyMemory(null, keyStartAddress, data, keyStartOffset, keyLength);
                    return baseOffset;
                }
                // Check for hash collision (hashes are same, but keys are different).
                // If there is no collision (both hashes and keys are equals), return current slot's offset.
                // Otherwise, continue iterating until find an available slot.
                if (keySize == keyLength && keysEqual(keyStartAddress, keyLength, keyStartOffset, word1, word2)) {
                    return baseOffset;
                }
            }
        }

        private boolean keysEqual(long keyStartAddress, int keyLength, int keyStartOffset,
                                  long word1, long word2) {
            final int maxFastKeyCheckLength = 2 * Long.BYTES;
            final int keyCheckLength = Math.min(maxFastKeyCheckLength, keyLength);

            long wordA1 = word1 != 0 ? word1 : U.getLong(keyStartAddress);
            long wordA2 = word2 != 0 ? word2 : U.getLong(keyStartAddress + Long.BYTES);

            long wordB1 = U.getLong(data, keyStartOffset);
            long wordB2 = U.getLong(data, keyStartOffset + Long.BYTES);

            int byteCount1 = Math.min(Long.BYTES, keyCheckLength);
            int byteCount2 = Math.max(0, keyCheckLength - Long.BYTES);

            int shift1 = (Long.BYTES - byteCount1) << 3;
            long mask1 = 0xFFFFFFFFFFFFFFFFL >>> shift1;

            int halfShift2 = (Long.BYTES - byteCount2) << 2;
            long mask2 = (0xFFFFFFFFFFFFFFFFL >>> halfShift2) >> halfShift2;

            wordA1 = wordA1 & mask1;
            wordA2 = wordA2 & mask2;

            if (keyCheckLength == keyLength) {
                return wordA1 == wordB1 && wordA2 == wordB2;
            }

            // Compare remaining parts of the keys

            int alignedKeyLength = keyLength & 0xFFFFFFF8;
            int i;
            for (i = maxFastKeyCheckLength; i < alignedKeyLength; i += Long.BYTES) {
                if (U.getLong(keyStartAddress + i) != U.getLong(data, keyStartOffset + i)) {
                    return false;
                }
            }

            long wordA = U.getLong(keyStartAddress + i);
            long wordB = U.getLong(data, keyStartOffset + i);
//            if (NATIVE_BYTE_ORDER == ByteOrder.BIG_ENDIAN) {
//                wordA = Long.reverseBytes(wordA);
//                wordB = Long.reverseBytes(wordB);
//            }
            int halfShift = (Long.BYTES - (keyLength & 0x00000007)) << 2;
            long mask = (0xFFFFFFFFFFFFFFFFL >>> halfShift) >> halfShift;
            wordA = wordA & mask;
            // No need to mask "wordB" (word from key in the map), because it is already padded with 0s
            return wordA == wordB;
        }

        private void putValue(long baseOffset, int value) {
            U.putInt(data, baseOffset + COUNT_OFFSET,
                    U.getInt(data, baseOffset + COUNT_OFFSET) + 1);
            short minValue = U.getShort(data, baseOffset + MIN_VALUE_OFFSET);
            if (value < minValue) {
                U.putShort(data, baseOffset + MIN_VALUE_OFFSET, (short) value);
            }
            short maxValue = U.getShort(data, baseOffset + MAX_VALUE_OFFSET);
            if (value > maxValue) {
                U.putShort(data, baseOffset + MAX_VALUE_OFFSET, (short) value);
            }
            U.putLong(data, baseOffset + VALUE_SUM_OFFSET,
                    value + U.getLong(data, baseOffset + VALUE_SUM_OFFSET));
        }

        private void merge(Map<String, KeyResult> resultMap) {
            // Merge this local map into global result map
            for (int i = 0; i < MAP_SIZE; i += ENTRY_SIZE) {
                int baseOffset = Unsafe.ARRAY_BYTE_BASE_OFFSET + i;
                int keyLength = U.getInt(data, baseOffset + KEY_SIZE_OFFSET);
                if (keyLength == 0) {
                    // No entry is available for this index, so continue iterating
                    continue;
                }
                String key = new String(data, i + KEY_OFFSET, keyLength, StandardCharsets.UTF_8);
                int count = U.getInt(data, baseOffset + COUNT_OFFSET);
                short minValue = U.getShort(data, baseOffset + MIN_VALUE_OFFSET);
                short maxValue = U.getShort(data, baseOffset + MAX_VALUE_OFFSET);
                long sum = U.getLong(data, baseOffset + VALUE_SUM_OFFSET);
                KeyResult result = new KeyResult(count, minValue, maxValue, sum);
                KeyResult existingResult = resultMap.get(key);
                if (existingResult == null) {
                    resultMap.put(key, result);
                }
                else {
                    existingResult.merge(result);
                }
            }
        }

    }

}
