package com.irisai.theseven.linkremoval;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.zip.GZIPInputStream;

final class HeaderChange {
    static void apply(InputStream sourceStream, InputStream changeStream, OutputStream output,
                      IntConsumer progress) throws IOException {
        DataInputStream change = new DataInputStream(changeStream);
        DataInputStream source = new DataInputStream(sourceStream);
        if (change.readInt() != 0x54535549) throw new IOException("변경 자료를 읽을 수 없습니다.");
        int block = change.readInt();
        long inputSize = change.readLong();
        long outputSize = change.readLong();
        if (block != 4194304 || inputSize != 1989346436L || outputSize != 1989346468L)
            throw new IOException("변경 자료의 크기가 올바르지 않습니다.");
        byte[] sourceHash = readHash(change);
        byte[] targetHash = readHash(change);
        MessageDigest sourceDigest = digest(), targetDigest = digest(), blockDigest = digest();
        byte[] buffer = new byte[block], mask = new byte[65536];

        for (long offset = 0; offset < outputSize; offset += block) {
            int read = (int) Math.min(block, inputSize - offset);
            int write = (int) Math.min(block, outputSize - offset);
            source.readFully(buffer, 0, read);
            blockDigest.update(buffer, 0, read);
            if (!MessageDigest.isEqual(blockDigest.digest(), readHash(change)))
                throw new IOException("선택한 파일이 TheSeven-Transparent-UI-1.0.apk와 일치하지 않습니다.");
            sourceDigest.update(buffer, 0, read);
            if (write > read) Arrays.fill(buffer, read, write, (byte) 0);
            int size = change.readInt();
            if (size < 0 || size > block + 1024) throw new IOException("변경 자료가 손상됐습니다.");
            if (size > 0) {
                byte[] encoded = new byte[size];
                change.readFully(encoded);
                try (DataInputStream diff = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(encoded)))) {
                    for (int at = 0; at < write;) {
                        int length = Math.min(mask.length, write - at);
                        diff.readFully(mask, 0, length);
                        for (int i = 0; i < length; i++) buffer[at + i] ^= mask[i];
                        at += length;
                    }
                    if (diff.read() != -1) throw new IOException("변경 자료의 길이가 올바르지 않습니다.");
                }
            }
            targetDigest.update(buffer, 0, write);
            output.write(buffer, 0, write);
            progress.accept((int) ((offset + write) * 100 / outputSize));
        }
        if (source.read() != -1 || change.read() != -1
            || !MessageDigest.isEqual(sourceDigest.digest(), sourceHash)
            || !MessageDigest.isEqual(targetDigest.digest(), targetHash))
            throw new IOException("파일 검증에 실패했습니다.");
        output.flush();
    }

    private static byte[] readHash(DataInputStream input) throws IOException {
        byte[] bytes = new byte[32];
        input.readFully(bytes);
        return bytes;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
