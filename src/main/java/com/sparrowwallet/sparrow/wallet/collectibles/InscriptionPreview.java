package com.sparrowwallet.sparrow.wallet.collectibles;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns inscription content into something safe to show. Images are recognised by their bytes, not the declared
 * type, and decoded only when their header declares a modest size (a small file can declare a huge canvas). Text is
 * shown as plain text. Everything else, HTML and SVG included, is a placeholder: nothing is ever executed.
 */
public final class InscriptionPreview {
    public static final int MAX_SIDE = 4096;
    public static final long MAX_PIXELS = 2048L * 2048L;
    private static final int IMAGE_CACHE_SIZE = 128;
    //Decoded previews keyed by caller key and size, so refreshing the view does not decode again
    private static final Map<String, Image> images = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > IMAGE_CACHE_SIZE;
        }
    });
    public static final int MAX_TEXT_CHARS = 20_000;

    public enum Kind { IMAGE, TEXT, PLACEHOLDER }

    private InscriptionPreview() {
    }

    public static Kind kind(String contentType, byte[] bytes) {
        if(isPng(bytes) || isJpeg(bytes) || isGif(bytes) || isBmp(bytes)) {
            return Kind.IMAGE;
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if(type.equals("text/plain") || type.equals("application/json")) {
            return Kind.TEXT;
        }
        return Kind.PLACEHOLDER;
    }

    /** Width and height declared by the image header, if it is one of the supported formats and readable. */
    public static Optional<int[]> dimensions(byte[] bytes) {
        try {
            if(isPng(bytes) && bytes.length >= 24) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
                return Optional.of(new int[] {buffer.getInt(16), buffer.getInt(20)});
            }
            if(isGif(bytes) && bytes.length >= 10) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                return Optional.of(new int[] {Short.toUnsignedInt(buffer.getShort(6)), Short.toUnsignedInt(buffer.getShort(8))});
            }
            if(isBmp(bytes) && bytes.length >= 26) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                //A negative height means the rows are stored top-down
                return Optional.of(new int[] {buffer.getInt(18), Math.abs(buffer.getInt(22))});
            }
            if(isJpeg(bytes)) {
                return jpegDimensions(bytes);
            }
        } catch(RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<int[]> jpegDimensions(byte[] bytes) {
        int pos = 2;
        while(pos + 4 <= bytes.length) {
            if((bytes[pos] & 0xff) != 0xff) {
                return Optional.empty();
            }
            int marker = bytes[pos + 1] & 0xff;
            if(marker == 0xff) {
                pos++;
                continue;
            }
            if(marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                pos += 2;
                continue;
            }
            int length = ((bytes[pos + 2] & 0xff) << 8) | (bytes[pos + 3] & 0xff);
            boolean startOfFrame = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if(startOfFrame && pos + 9 <= bytes.length) {
                int height = ((bytes[pos + 5] & 0xff) << 8) | (bytes[pos + 6] & 0xff);
                int width = ((bytes[pos + 7] & 0xff) << 8) | (bytes[pos + 8] & 0xff);
                return Optional.of(new int[] {width, height});
            }
            if(length < 2) {
                return Optional.empty();
            }
            pos += 2 + length;
        }
        return Optional.empty();
    }

    public static boolean safeToDecode(byte[] bytes) {
        Optional<int[]> dimensions = dimensions(bytes);
        if(dimensions.isEmpty()) {
            return false;
        }
        int width = dimensions.get()[0];
        int height = dimensions.get()[1];
        //Every frame of an animated GIF is decoded up front, so the budget covers all of them
        long frames = isGif(bytes) ? Math.max(1, countGifFrames(bytes)) : 1;
        return width > 0 && height > 0 && width <= MAX_SIDE && height <= MAX_SIDE && (long)width * height * frames <= MAX_PIXELS;
    }

    //Counts graphic control extensions (21 F9 04), one per animation frame; may overcount, which only refuses a preview
    private static int countGifFrames(byte[] bytes) {
        int frames = 0;
        for(int i = 0; i + 2 < bytes.length; i++) {
            if(bytes[i] == 0x21 && (bytes[i + 1] & 0xff) == 0xf9 && bytes[i + 2] == 0x04) {
                frames++;
            }
        }
        return frames;
    }

    /** UTF-8 text, capped, without control or formatting characters (other than newline and tab) that could disguise it. */
    public static String text(byte[] bytes) {
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder();
        decoded.codePoints().forEach(codePoint -> {
            if(text.length() >= MAX_TEXT_CHARS) {
                return;
            }
            int type = Character.getType(codePoint);
            if(codePoint == '\n' || codePoint == '\t' || !(Character.isISOControl(codePoint) || type == Character.FORMAT)) {
                text.appendCodePoint(codePoint);
            }
        });
        return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text.toString();
    }

    public static Node node(String contentType, byte[] bytes, double size) {
        return node(null, contentType, bytes, size);
    }

    /** As {@link #node(String, byte[], double)}, reusing the decoded image for the same key and size. */
    public static Node node(String key, String contentType, byte[] bytes, double size) {
        Kind kind = bytes == null ? Kind.PLACEHOLDER : kind(contentType, bytes);
        if(kind == Kind.IMAGE && safeToDecode(bytes)) {
            String cacheKey = key == null ? null : key + "@" + size;
            Image image = cacheKey == null ? null : images.get(cacheKey);
            if(image == null) {
                //Decoded at display size, so the full-resolution pixels are not kept
                image = new Image(new ByteArrayInputStream(bytes), size, size, true, true);
                if(cacheKey != null && !image.isError()) {
                    images.put(cacheKey, image);
                }
            }
            if(!image.isError()) {
                ImageView view = new ImageView(image);
                view.setPreserveRatio(true);
                view.setFitWidth(size);
                view.setFitHeight(size);
                view.setSmooth(image.getWidth() > size);
                return frame(view, size);
            }
        }
        if(kind == Kind.TEXT) {
            Label label = new Label(text(bytes));
            label.setWrapText(true);
            label.setMaxWidth(size);
            label.setMaxHeight(size);
            label.setAlignment(Pos.TOP_LEFT);
            return frame(label, size);
        }
        return placeholder(kind == Kind.IMAGE ? "Image too large to preview" : (contentType == null ? "Unknown content" : contentType), size);
    }

    public static Node placeholder(String text, double size) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(size - 8);
        label.getStyleClass().add("inscription-placeholder");
        return frame(label, size);
    }

    private static Node frame(Node content, double size) {
        StackPane pane = new StackPane(content);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.getStyleClass().add("inscription-frame");
        return pane;
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static boolean isJpeg(byte[] b) {
        return b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
    }

    private static boolean isGif(byte[] b) {
        return b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
    }

    private static boolean isBmp(byte[] b) {
        return b.length >= 2 && b[0] == 'B' && b[1] == 'M';
    }
}
