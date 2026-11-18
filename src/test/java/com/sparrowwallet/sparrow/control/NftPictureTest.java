package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.NftMetadata;
import com.sparrowwallet.drongo.evm.SvgSubset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class NftPictureTest {
    private static final String SVG_HEAD = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">";

    private static NftMetadata withImage(NftMetadata.Image image) {
        return new NftMetadata("Name", "", List.of(), image);
    }

    private static String vaultBadgeUri() throws IOException {
        try(InputStream in = NftPictureTest.class.getResourceAsStream("vault-badge-world-1-token-uri.txt")) {
            Assertions.assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip();
        }
    }

    private static byte[] png(int width, int height) {
        byte[] bytes = new byte[33];
        byte[] head = {(byte)0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R'};
        System.arraycopy(head, 0, bytes, 0, head.length);
        java.nio.ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height);
        return bytes;
    }

    private static SvgSubset.Drawing drawing(String svg) {
        Optional<SvgSubset.Drawing> drawing = SvgSubset.parse(svg);
        Assertions.assertTrue(drawing.isPresent(), "not in the subset: " + svg);
        return drawing.get();
    }

    private static void assertClose(double expected, double actual) {
        Assertions.assertEquals(expected, actual, 1e-3);
    }

    @Test
    public void noMetadataOrNoImageIsNoPicture() {
        NftPicture.Plan none = NftPicture.plan(Optional.empty(), 1);
        Assertions.assertEquals(NftPicture.Kind.NONE, none.kind());
        Assertions.assertEquals("No picture", none.placeholder());
        NftPicture.Plan noImage = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.None())), 1);
        Assertions.assertEquals(NftPicture.Kind.NONE, noImage.kind());
        Assertions.assertEquals("No picture", noImage.placeholder());
    }

    @Test
    public void theVaultBadgeIsDrawn() throws IOException {
        Optional<NftMetadata> metadata = NftMetadata.parse(vaultBadgeUri());
        Assertions.assertTrue(metadata.isPresent());
        NftPicture.Plan plan = NftPicture.plan(metadata, 1);
        Assertions.assertEquals(NftPicture.Kind.SVG, plan.kind());
        Assertions.assertNull(plan.placeholder());
        Assertions.assertNotNull(plan.drawing());
        Assertions.assertNotNull(plan.key());
    }

    @Test
    public void anSvgOutsideTheSubsetIsNoPicture() {
        for(String svg : List.of(SVG_HEAD + "<script>alert(1)</script></svg>", SVG_HEAD + "<rect fill=\"url(#g)\"/></svg>", "<svg")) {
            NftPicture.Plan plan = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Svg(svg))), 1);
            Assertions.assertEquals(NftPicture.Kind.NONE, plan.kind(), svg);
            Assertions.assertEquals("No picture", plan.placeholder());
        }
    }

    @Test
    public void aRasterIsDecidedByItsBytesNotItsLabel() {
        NftPicture.Plan png = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Raster("image/gif", png(32, 32)))), 1);
        Assertions.assertEquals(NftPicture.Kind.RASTER, png.kind());
        Assertions.assertNull(png.placeholder());
        Assertions.assertNotNull(png.raster());

        NftPicture.Plan notAnImage = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Raster("image/png", "hello".getBytes(StandardCharsets.US_ASCII)))), 1);
        Assertions.assertEquals(NftPicture.Kind.NONE, notAnImage.kind());
        Assertions.assertEquals("No picture", notAnImage.placeholder());

        NftPicture.Plan huge = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Raster("image/png", png(100_000, 100_000)))), 1);
        Assertions.assertEquals(NftPicture.Kind.NONE, huge.kind());
        Assertions.assertEquals("No picture", huge.placeholder());
    }

    @Test
    public void aHostedPictureIsNotShownAndNamesItsHost() {
        NftPicture.Plan hosted = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Hosted("example.com"))), 1);
        Assertions.assertEquals(NftPicture.Kind.HOSTED, hosted.kind());
        Assertions.assertEquals("Picture not shown: hosted at example.com", hosted.placeholder());
        Assertions.assertEquals("Picture not shown: hosted at IPFS",
                NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Hosted("IPFS"))), 1).placeholder());
    }

    @Test
    public void aLongHostIsShortenedFromTheFront() {
        String host = "a".repeat(200) + ".looks-like-a-trusted-site.evil.example";
        String shown = NftPicture.shortHost(host);
        Assertions.assertTrue(shown.length() <= NftPicture.MAX_HOST_SHOWN, shown);
        Assertions.assertTrue(shown.startsWith("..."), shown);
        Assertions.assertTrue(shown.endsWith("evil.example"), shown);
        Assertions.assertEquals("example.com", NftPicture.shortHost("example.com"));
    }

    @Test
    public void aQuarterCircleArcIsOneCubic() {
        List<double[]> cubics = NftPicture.arcToBeziers(10, 0, 10, 10, 0, false, true, 0, 10);
        Assertions.assertEquals(1, cubics.size());
        double k = 4.0 / 3.0 * Math.tan(Math.PI / 8) * 10;
        double[] c = cubics.getFirst();
        assertClose(10, c[0]);
        assertClose(k, c[1]);
        assertClose(k, c[2]);
        assertClose(10, c[3]);
        Assertions.assertEquals(0, c[4]);
        Assertions.assertEquals(10, c[5]);

        //The other sweep turns the other way round the other centre, (10, 10)
        double[] other = NftPicture.arcToBeziers(10, 0, 10, 10, 0, false, false, 0, 10).getFirst();
        assertClose(10 - k, other[0]);
        assertClose(0, other[1]);
        assertClose(0, other[2]);
        assertClose(10 - k, other[3]);
        Assertions.assertEquals(0, other[4]);
        Assertions.assertEquals(10, other[5]);

        //The large arc is the remaining three quarters of the circle round (10, 10)
        List<double[]> large = NftPicture.arcToBeziers(10, 0, 10, 10, 0, true, true, 0, 10);
        Assertions.assertEquals(3, large.size());
        assertClose(20, large.getFirst()[4]);
        assertClose(10, large.getFirst()[5]);
        Assertions.assertEquals(0, large.getLast()[4]);
        Assertions.assertEquals(10, large.getLast()[5]);
    }

    @Test
    public void aZeroRadiusArcIsAStraightLine() {
        List<double[]> cubics = NftPicture.arcToBeziers(0, 0, 0, 10, 0, true, true, 20, 10);
        Assertions.assertEquals(1, cubics.size());
        Assertions.assertArrayEquals(new double[] {0, 0, 20, 10, 20, 10}, cubics.getFirst());
        Assertions.assertTrue(NftPicture.arcToBeziers(5, 5, 10, 10, 0, false, true, 5, 5).isEmpty(), "same end points draw nothing");
    }

    @Test
    public void radiiTooSmallAreScaledUp() {
        //Radius 1 cannot span 10: it becomes 5, a half circle through (5, -5) or (5, 5)
        List<double[]> cubics = NftPicture.arcToBeziers(0, 0, 1, 1, 0, false, true, 10, 0);
        Assertions.assertEquals(2, cubics.size());
        assertClose(5, cubics.getFirst()[4]);
        assertClose(5, Math.abs(cubics.getFirst()[5]));
        Assertions.assertEquals(10, cubics.get(1)[4]);
        Assertions.assertEquals(0, cubics.get(1)[5]);
    }

    @Test
    public void theFitCentresAndScales() {
        NftPicture.Prepared prepared = NftPicture.prepare(drawing("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 32\"><rect width=\"64\" height=\"32\"/></svg>"), 128).orElseThrow();
        Assertions.assertArrayEquals(new double[] {0, 32, 128, 64}, prepared.clip());
        NftPicture.PathItem rect = (NftPicture.PathItem)prepared.items().getFirst();
        Assertions.assertArrayEquals(new double[] {2, 0, 0, 2, 0, 32}, rect.affine());
    }

    @Test
    public void theVaultBadgePreparesWithoutError() throws IOException {
        NftPicture.Plan plan = NftPicture.plan(NftMetadata.parse(vaultBadgeUri()), 1);
        NftPicture.Prepared prepared = NftPicture.prepare(plan.drawing(), NftPicture.TILE_SIZE).orElseThrow();
        Assertions.assertEquals(7, prepared.items().size());
        Assertions.assertArrayEquals(new double[] {0, 0, NftPicture.TILE_SIZE, NftPicture.TILE_SIZE}, prepared.clip());
        assertFiniteAndInside(prepared);
        NftPicture.TextItem text = (NftPicture.TextItem)prepared.items().getLast();
        Assertions.assertEquals("WORLD 1", text.content());
        Assertions.assertEquals(SvgSubset.Anchor.MIDDLE, text.anchor());
        Assertions.assertTrue(text.monospace());
        Assertions.assertTrue(text.bold());
        Assertions.assertEquals(Integer.valueOf(0xFFFFFFFF), text.fill());
        NftPicture.PathItem background = (NftPicture.PathItem)prepared.items().getFirst();
        Assertions.assertEquals(Integer.valueOf(0xFF0E1E38), background.fill());
        Assertions.assertNull(background.stroke());
    }

    @Test
    public void everyAcceptVectorPrepares() throws IOException {
        //The shared vectors live in drongo's tests; run against them when the checkout has them
        Path dir = Path.of("drongo", "src", "test", "resources", "com", "sparrowwallet", "drongo", "evm", "svg", "accept");
        if(!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try(Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.toString().endsWith(".svg")).sorted().toList();
        }
        Assertions.assertFalse(files.isEmpty());
        for(Path file : files) {
            SvgSubset.Drawing drawing = drawing(Files.readString(file, StandardCharsets.UTF_8));
            //The limit vectors sit at the model's edges, where the renderer may refuse; every other one is drawn
            String name = file.getFileName().toString();
            boolean extreme = name.startsWith("limit-") || name.startsWith("transform-");
            for(double size : new double[] {NftPicture.TILE_SIZE, NftPicture.DETAIL_SIZE, NftPicture.MAX_PIXELS}) {
                Optional<NftPicture.Prepared> prepared = NftPicture.prepare(drawing, size);
                Assertions.assertTrue(extreme || prepared.isPresent(), name);
                prepared.ifPresent(NftPictureTest::assertFinite);
            }
        }
    }

    @Test
    public void eachShapeIsPlacedByItsComposedMatrix() {
        String svg = SVG_HEAD + "<g transform=\"scale(2)\"><g transform=\"translate(3 4)\"><rect width=\"10\" height=\"10\" transform=\"translate(1 1)\"/></g></g></svg>";
        SvgSubset.Drawing drawing = drawing(svg);
        SvgSubset.Shape rect = drawing.shapes().getFirst();
        Assertions.assertArrayEquals(rect.composedMatrix(), NftPicture.affineOf(rect));
        //At 64 pixels the fit is the identity, so the placement is the composed matrix itself
        NftPicture.Prepared prepared = NftPicture.prepare(drawing, 64).orElseThrow();
        Assertions.assertArrayEquals(new double[] {2, 0, 0, 2, 8, 10}, prepared.items().getFirst().affine(), 1e-9);
    }

    @Test
    public void farAwayTextIsClippedLikeAnyShapeAndOnlyANearAbsurdFontRefuses() {
        //Far beyond the canvas a huge font cannot show: the text is left out, as far geometry is, and the picture stays
        String far = SVG_HEAD + "<rect width=\"8\" height=\"8\"/><text x=\"1000000\" font-size=\"4096\" transform=\"scale(100)\">A</text></svg>";
        NftPicture.Prepared prepared = NftPicture.prepare(drawing(far), NftPicture.TILE_SIZE).orElseThrow();
        Assertions.assertEquals(1, prepared.items().size());
    }

    @Test
    public void eachPictureCostsItsCommands() {
        NftPicture.Plan rect = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Svg(SVG_HEAD + "<rect width=\"8\" height=\"8\"/></svg>"))), 1);
        //Move, three lines and close, filled only
        Assertions.assertEquals(5, rect.cost(NftPicture.pixels(NftPicture.TILE_SIZE, 1)));
        NftPicture.Plan stroked = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Svg(SVG_HEAD + "<rect width=\"8\" height=\"8\" stroke=\"red\"/></svg>"))), 1);
        //Traced twice, to fill and to stroke
        Assertions.assertEquals(10, stroked.cost(NftPicture.pixels(NftPicture.TILE_SIZE, 1)));
        //A placeholder costs one
        Assertions.assertEquals(1, NftPicture.plan(Optional.empty(), 1).cost(NftPicture.pixels(NftPicture.TILE_SIZE, 1)));
    }

    @Test
    public void aHugeFontRefusesThePicture() {
        String hugeText = SVG_HEAD + "<text font-size=\"4096\" transform=\"scale(100)\">A</text></svg>";
        Assertions.assertTrue(NftPicture.prepare(drawing(hugeText), NftPicture.TILE_SIZE).isEmpty());
        NftPicture.Plan plan = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Svg(hugeText))), 1);
        Assertions.assertEquals(NftPicture.Kind.NONE, plan.kind());
        Assertions.assertEquals("No picture", plan.placeholder());
    }

    @Test
    public void aHugeBackgroundIsDrawnNotRefused() {
        //Far beyond the 24-unit box, as a background rectangle or a huge circle whose edge crosses it
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><rect x=\"-500000\" y=\"-500000\" width=\"1000000\" height=\"1000000\" fill=\"red\"/>"
                + "<circle cx=\"12\" cy=\"1000000\" r=\"999988\" fill=\"blue\" stroke=\"black\"/><rect width=\"10\" height=\"10\" transform=\"scale(1000)\" fill=\"lime\"/>"
                + "<text x=\"1000000\" y=\"12\">far away</text></svg>";
        NftPicture.Plan plan = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Svg(svg))), 1);
        Assertions.assertEquals(NftPicture.Kind.SVG, plan.kind());
        NftPicture.Prepared prepared = NftPicture.prepare(drawing(svg), NftPicture.MAX_PIXELS).orElseThrow();
        assertFinite(prepared);
        //The far text cannot reach the canvas and is left out
        Assertions.assertEquals(3, prepared.items().size());
        int commands = 0;
        for(NftPicture.Item item : prepared.items()) {
            NftPicture.PathItem path = (NftPicture.PathItem)item;
            double[] m = path.affine();
            List<NftPicture.Cmd> all = new ArrayList<>(path.fillPath());
            all.addAll(path.strokePath() == null ? List.of() : path.strokePath());
            commands += all.size();
            for(NftPicture.Cmd cmd : all) {
                if(cmd.op() != NftPicture.CUBIC) {
                    continue;
                }
                //Every curve handed to the canvas lies within a bounded box around it
                double[] c = cmd.coords();
                for(int i = 0; i < 6; i += 2) {
                    Assertions.assertTrue(Math.abs(m[0] * c[i] + m[2] * c[i + 1] + m[4]) <= NftPicture.CURVE_LIMIT);
                    Assertions.assertTrue(Math.abs(m[1] * c[i] + m[3] * c[i + 1] + m[5]) <= NftPicture.CURVE_LIMIT);
                }
            }
        }
        Assertions.assertTrue(commands < 2000, "commands: " + commands);
    }

    @Test
    public void aPictureOverTheCommandCapIsNoPicture() {
        StringBuilder svg = new StringBuilder(SVG_HEAD + "<path d=\"M0 0");
        for(int i = 0; i < 300; i++) {
            svg.append(" l1 1");
        }
        svg.append("\"/></svg>");
        SvgSubset.Drawing drawing = drawing(svg.toString());
        Assertions.assertEquals(50_000, NftPicture.MAX_COMMANDS);
        Assertions.assertTrue(NftPicture.prepare(drawing, 64, 1000).isPresent());
        Assertions.assertTrue(NftPicture.prepare(drawing, 64, 300).isEmpty());
    }

    @Test
    public void aHugeStrokeIsClampedToTheCanvasDiagonal() {
        String svg = SVG_HEAD + "<line x1=\"1\" y1=\"1\" x2=\"5\" y2=\"5\" stroke=\"red\" stroke-width=\"1000000\"/><line x2=\"5\" stroke=\"red\" stroke-width=\"2\"/></svg>";
        List<NftPicture.Item> items = NftPicture.prepare(drawing(svg), NftPicture.TILE_SIZE).orElseThrow().items();
        NftPicture.PathItem huge = (NftPicture.PathItem)items.get(0);
        double scale = NftPicture.TILE_SIZE / 64;
        Assertions.assertEquals(NftPicture.TILE_SIZE * Math.sqrt(2), huge.strokeWidth() * scale, 1e-6);
        Assertions.assertEquals(2, ((NftPicture.PathItem)items.get(1)).strokeWidth());
    }

    @Test
    public void picturesArePreparedOffTheFxThreadForBothSizes() {
        NftPicture.Plan plan = NftPicture.plan(Optional.of(withImage(new NftMetadata.Image.Svg(SVG_HEAD + "<rect width=\"8\" height=\"8\"/></svg>"))), 2);
        Assertions.assertTrue(plan.preparedAt(NftPicture.pixels(NftPicture.TILE_SIZE, 2)).isPresent());
        Assertions.assertTrue(plan.preparedAt(NftPicture.pixels(NftPicture.DETAIL_SIZE, 2)).isPresent());
        Assertions.assertEquals(240, NftPicture.pixels(NftPicture.TILE_SIZE, 2));
        Assertions.assertEquals(NftPicture.MAX_PIXELS, NftPicture.pixels(NftPicture.DETAIL_SIZE, 8));
    }

    @Test
    public void everyFlagCombinationPicksItsCentreAndDirection() {
        //From (10, 0) to (0, 10) with radius 10: the centres are (0, 0) and (10, 10)
        double[][] centres = {{0, 0}, {10, 10}};
        //large, sweep -> centre index, number of quarter turns, the first piece's end point
        Object[][] cases = {
                {false, true, 0, 1, new double[] {0, 10}},
                {false, false, 1, 1, new double[] {0, 10}},
                {true, true, 1, 3, new double[] {20, 10}},
                {true, false, 0, 3, new double[] {0, -10}}};
        for(Object[] c : cases) {
            boolean large = (Boolean)c[0];
            boolean sweep = (Boolean)c[1];
            double[] centre = centres[(Integer)c[2]];
            List<double[]> cubics = NftPicture.arcToBeziers(10, 0, 10, 10, 0, large, sweep, 0, 10);
            String label = "large=" + large + " sweep=" + sweep;
            Assertions.assertEquals(c[3], cubics.size(), label);
            double[] firstEnd = (double[])c[4];
            assertClose(firstEnd[0], cubics.getFirst()[4]);
            assertClose(firstEnd[1], cubics.getFirst()[5]);
            for(double[] cubic : cubics) {
                Assertions.assertEquals(10, Math.hypot(cubic[4] - centre[0], cubic[5] - centre[1]), 1e-9, label);
            }
            Assertions.assertEquals(0, cubics.getLast()[4]);
            Assertions.assertEquals(10, cubics.getLast()[5]);
        }
    }

    @Test
    public void whatSvgDoesNotRenderIsSkipped() {
        String svg = SVG_HEAD + "<rect width=\"0\" height=\"10\" stroke=\"red\"/><circle r=\"0\" stroke=\"red\"/><ellipse rx=\"5\" ry=\"0\" stroke=\"red\"/>"
                + "<line x2=\"10\" y2=\"10\" stroke=\"red\" stroke-width=\"0\"/><rect width=\"5\" height=\"5\" fill=\"none\"/></svg>";
        Assertions.assertTrue(NftPicture.prepare(drawing(svg), NftPicture.TILE_SIZE).orElseThrow().items().isEmpty());
    }

    @Test
    public void aLineIsNeverFilledAndAPolylineIsFilledClosedButStrokedOpen() {
        String svg = SVG_HEAD + "<line x2=\"10\" y2=\"10\" stroke=\"red\"/><polyline points=\"0,0 10,0 10,10\" stroke=\"blue\"/></svg>";
        List<NftPicture.Item> items = NftPicture.prepare(drawing(svg), 64).orElseThrow().items();
        Assertions.assertEquals(2, items.size());
        NftPicture.PathItem line = (NftPicture.PathItem)items.get(0);
        Assertions.assertNull(line.fill());
        Assertions.assertNotNull(line.stroke());
        NftPicture.PathItem polyline = (NftPicture.PathItem)items.get(1);
        Assertions.assertEquals(NftPicture.CLOSE, polyline.fillPath().getLast().op());
        Assertions.assertNotEquals(NftPicture.CLOSE, polyline.strokePath().getLast().op());
    }

    @Test
    public void everyPathCommandBecomesMovesLinesAndCubics() {
        String svg = SVG_HEAD + "<path d=\"M1 1 L5 5 H10 V12 C1 2 3 4 5 6 S7 8 9 10 Q1 1 2 2 T4 4 A5 5 0 0 1 20 20 A0 3 0 1 1 30 30 Z L40 40\"/></svg>";
        NftPicture.PathItem path = (NftPicture.PathItem)NftPicture.prepare(drawing(svg), 64).orElseThrow().items().getFirst();
        for(NftPicture.Cmd cmd : path.fillPath()) {
            Assertions.assertTrue(List.of(NftPicture.MOVE, NftPicture.LINE, NftPicture.CUBIC, NftPicture.CLOSE).contains(cmd.op()));
        }
        //After Z the current point is the subpath's start again, so the final line starts from (1, 1)
        Assertions.assertEquals(NftPicture.LINE, path.fillPath().getLast().op());
    }

    private static void assertFinite(NftPicture.Prepared prepared) {
        for(NftPicture.Item item : prepared.items()) {
            for(double v : item.affine()) {
                Assertions.assertTrue(Double.isFinite(v));
            }
            if(item instanceof NftPicture.PathItem path) {
                List<NftPicture.Cmd> all = new ArrayList<>(path.fillPath() == null ? List.of() : path.fillPath());
                all.addAll(path.strokePath() == null ? List.of() : path.strokePath());
                for(NftPicture.Cmd cmd : all) {
                    for(double v : cmd.coords()) {
                        Assertions.assertTrue(Double.isFinite(v));
                    }
                }
            }
        }
    }

    //The badge stays inside its 64 x 64 box, so everything maps inside the tile
    private static void assertFiniteAndInside(NftPicture.Prepared prepared) {
        assertFinite(prepared);
        for(NftPicture.Item item : prepared.items()) {
            if(item instanceof NftPicture.PathItem path) {
                double[] m = path.affine();
                for(NftPicture.Cmd cmd : path.fillPath()) {
                    double[] c = cmd.coords();
                    for(int i = 0; i + 1 < c.length; i += 2) {
                        double x = m[0] * c[i] + m[2] * c[i + 1] + m[4];
                        double y = m[1] * c[i] + m[3] * c[i + 1] + m[5];
                        Assertions.assertTrue(x >= -1e-9 && x <= NftPicture.TILE_SIZE + 1e-9 && y >= -1e-9 && y <= NftPicture.TILE_SIZE + 1e-9);
                    }
                }
            }
        }
    }
}
