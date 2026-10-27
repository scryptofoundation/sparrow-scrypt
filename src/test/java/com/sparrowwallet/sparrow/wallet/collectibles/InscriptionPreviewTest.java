package com.sparrowwallet.sparrow.wallet.collectibles;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class InscriptionPreviewTest {
    private static byte[] png(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[] {(byte)0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
        buffer.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height);
        buffer.put(new byte[] {8, 6, 0, 0, 0}).putInt(0);
        return buffer.array();
    }

    private static byte[] gif(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("GIF89a".getBytes(StandardCharsets.US_ASCII)).putShort((short)width).putShort((short)height).put(new byte[3]);
        return buffer.array();
    }

    private static byte[] bmp(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("BM".getBytes(StandardCharsets.US_ASCII)).putInt(54).putInt(0).putInt(54).putInt(40).putInt(width).putInt(height);
        return buffer.array();
    }

    private static byte[] jpeg(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[] {(byte)0xff, (byte)0xd8, (byte)0xff, (byte)0xe0}).putShort((short)16).put(new byte[14]);
        buffer.put(new byte[] {(byte)0xff, (byte)0xc0}).putShort((short)17).put((byte)8).putShort((short)height).putShort((short)width);
        return buffer.array();
    }

    @Test
    public void pngDimensions() {
        Assertions.assertArrayEquals(new int[] {1, 1}, InscriptionPreview.dimensions(png(1, 1)).orElseThrow());
        Assertions.assertEquals(InscriptionPreview.Kind.IMAGE, InscriptionPreview.kind("image/png", png(1, 1)));
        Assertions.assertTrue(InscriptionPreview.safeToDecode(png(1, 1)));
    }

    @Test
    public void gifBmpAndJpegDimensions() {
        Assertions.assertArrayEquals(new int[] {20, 10}, InscriptionPreview.dimensions(gif(20, 10)).orElseThrow());
        Assertions.assertArrayEquals(new int[] {30, 40}, InscriptionPreview.dimensions(bmp(30, -40)).orElseThrow());
        Assertions.assertArrayEquals(new int[] {64, 32}, InscriptionPreview.dimensions(jpeg(64, 32)).orElseThrow());
    }

    @Test
    public void hugePngIsNotSafe() {
        Assertions.assertFalse(InscriptionPreview.safeToDecode(png(100000, 100000)));
        Assertions.assertFalse(InscriptionPreview.safeToDecode(png(4096, 4097)));
        //A solid 4096x4096 PNG is a few KB to inscribe but 64 MB decoded; a handful would exhaust the heap
        Assertions.assertFalse(InscriptionPreview.safeToDecode(png(4096, 4096)));
        Assertions.assertTrue(InscriptionPreview.safeToDecode(png(2048, 2048)));
        Assertions.assertFalse(InscriptionPreview.safeToDecode(png(2048, 2049)));
        Assertions.assertFalse(InscriptionPreview.safeToDecode(new byte[] {(byte)0x89, 'P', 'N', 'G'}));
    }

    /** JavaFX decodes every frame of an animated GIF up front, so the pixel budget covers all frames. */
    @Test
    public void gifWithManyFramesIsNotSafe() {
        byte[] header = gif(1024, 1024);
        byte[] frame = {0x21, (byte)0xf9, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] animated = new byte[header.length + frame.length * 20];
        System.arraycopy(header, 0, animated, 0, header.length);
        for(int i = 0; i < 20; i++) {
            System.arraycopy(frame, 0, animated, header.length + i * frame.length, frame.length);
        }
        Assertions.assertTrue(InscriptionPreview.safeToDecode(header));
        Assertions.assertFalse(InscriptionPreview.safeToDecode(animated));
    }

    @Test
    public void htmlIsPlaceholder() {
        Assertions.assertEquals(InscriptionPreview.Kind.PLACEHOLDER, InscriptionPreview.kind("text/html;charset=utf-8", "<html><script>x</script></html>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void svgIsPlaceholder() {
        Assertions.assertEquals(InscriptionPreview.Kind.PLACEHOLDER, InscriptionPreview.kind("image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void declaredPngButTextBytesIsPlaceholder() {
        Assertions.assertEquals(InscriptionPreview.Kind.PLACEHOLDER, InscriptionPreview.kind("image/png", "hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void imageBytesWinOverDeclaredType() {
        Assertions.assertEquals(InscriptionPreview.Kind.IMAGE, InscriptionPreview.kind("application/octet-stream", gif(2, 2)));
    }

    @Test
    public void textAndJson() {
        Assertions.assertEquals(InscriptionPreview.Kind.TEXT, InscriptionPreview.kind("text/plain;charset=utf-8", "hi".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals(InscriptionPreview.Kind.TEXT, InscriptionPreview.kind("application/json", "{}".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals(InscriptionPreview.Kind.PLACEHOLDER, InscriptionPreview.kind(null, "hi".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void textIsCappedAndCleaned() {
        Assertions.assertEquals(20000, InscriptionPreview.text("a".repeat(30000).getBytes(StandardCharsets.UTF_8)).length());
        Assertions.assertEquals("a\nb\tc", InscriptionPreview.text("a\nb\tc\u0007‮".getBytes(StandardCharsets.UTF_8)));
    }
}
