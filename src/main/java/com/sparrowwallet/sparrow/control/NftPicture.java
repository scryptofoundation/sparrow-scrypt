package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.NftMetadata;
import com.sparrowwallet.drongo.evm.SvgSubset;
import com.sparrowwallet.sparrow.wallet.collectibles.InscriptionPreview;
import javafx.geometry.NodeOrientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Screen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An NFT's picture, from its on-chain metadata only. Nothing is fetched.
 *
 * SVG is drawn from {@link SvgSubset}'s drawing model onto a canvas: it never reaches an SVG library, an Image or a web
 * view. Raster bytes go through {@link InscriptionPreview}'s rules (the bytes decide the format, the declared size is
 * checked before decoding, decoded at display size). A hosted picture is never requested and is named by its host.
 * Anything that cannot be shown safely is the placeholder "No picture", never a partial drawing.
 *
 * Drawing is in two stages. {@link #prepare} is pure: it places every shape (the fit to the canvas composed with the
 * shape's matrix), turns every shape into moves, lines and cubic curves (arcs by the SVG implementation notes), skips
 * what SVG itself does not render, keeps far geometry within reach of the canvas (curves split or straightened, strokes
 * cut to the canvas's diagonal), and refuses the whole picture when a value is not finite, a font is absurdly large or
 * the picture needs more than {@link #MAX_COMMANDS} commands. It runs off the FX thread, when the plan is made. The
 * JavaFX stage only replays that list, once per token and size: the result is kept as an image in a small cache.
 *
 * A listing plans its tiles within one {@link Budget} and for the tile size only ({@link #plan(Optional, double, Budget)}).
 * Once a tile is drawn its plan is {@link Plan#released() released}: the image stays, the drawing and its commands go.
 * A picture needed again (the detail, or a tile that was never drawn) is planned again from the metadata with
 * {@link #planFor}, which the caller runs off the FX thread; {@link #isRendered} says when that is not needed.
 */
public final class NftPicture {
    public static final String NO_PICTURE = "No picture";
    public static final double TILE_SIZE = 120;
    public static final double DETAIL_SIZE = 240;
    /** The largest canvas side drawn, in pixels (a detail picture on a 2x screen fits). */
    public static final double MAX_PIXELS = 1024;
    /** The most path commands one picture may hand to the canvas, after arcs and far curves are converted. */
    static final int MAX_COMMANDS = 50_000;
    /**
     * The canvas is at most 1,024 pixels, so nothing beyond this many pixels from it can show. A curve lying wholly
     * beyond this box is drawn as the straight line between its ends (the same pixels inside the box).
     */
    static final double CLIP_LIMIT = 1e7;
    /** Every curve handed to the canvas lies within this many pixels: one reaching further is split until it does. */
    static final double CURVE_LIMIT = 2e7;
    private static final int MAX_SPLITS = 64;
    /** A font beyond this many pixels refuses the picture. */
    static final double MAX_FONT_PIXELS = 4096;
    static final int MAX_HOST_SHOWN = 40;
    /** Rendered pictures kept: every tile the section builds (96), plus room for the detail pictures. */
    private static final int CACHE_SIZE = 128;
    //Bezier handle length for a quarter circle of radius 1
    private static final double KAPPA = 4.0 / 3.0 * (Math.sqrt(2) - 1);

    static final int MOVE = 0;
    static final int LINE = 1;
    static final int CUBIC = 2;
    static final int CLOSE = 3;

    private record Rendered(String content, Image image) {}

    //Rendered SVG pictures by the caller's key (network, contract, token id), size and pixels, with the content they show
    private static final Map<String, Rendered> rendered = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Rendered> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    private NftPicture() {
    }

    public enum Kind { SVG, RASTER, HOSTED, NONE }

    /**
     * What is shown for a picture: a drawing already laid out for the tile and detail sizes at the screen's scale, raster
     * bytes, or a placeholder with its text. The key is a hash of the picture's content.
     */
    public record Plan(Kind kind, String placeholder, SvgSubset.Drawing drawing, byte[] raster, String key, double scale, Map<Double, Prepared> prepared) {
        static Plan placeholder(Kind kind, String text) {
            return new Plan(kind, text, null, null, null, 1, Map.of());
        }

        /**
         * This plan without its drawing and its laid out commands, which are most of what it weighs: what a view keeps
         * once the picture is drawn. It still names the picture (its content's key), so an image already rendered is
         * found again; drawing it anew needs the plan made again from the metadata.
         */
        Plan released() {
            return kind == Kind.SVG && !isReleased() ? new Plan(kind, placeholder, null, null, key, scale, Map.of()) : this;
        }

        boolean isReleased() {
            return kind == Kind.SVG && drawing == null;
        }

        /** What this plan holds, in the units of a {@link Budget}: its drawing's segments and its laid out commands. */
        int weight() {
            int weight = drawing == null ? 0 : NftPicture.weight(drawing);
            for(Prepared laidOut : prepared.values()) {
                weight += laidOut.commands();
            }
            return weight;
        }

        /** The drawing laid out on a canvas of this many pixels, if it was prepared for it. */
        Optional<Prepared> preparedAt(double pixels) {
            return Optional.ofNullable(prepared.get(pixels));
        }

        /** What drawing it at this size costs, in path commands traced (a text counts one); 1 for anything else. */
        int cost(double pixels) {
            return preparedAt(pixels).map(Prepared::commands).orElse(1);
        }
    }

    /** The canvas side in pixels for a picture of this size on a screen of this scale. */
    static double pixels(double size, double scale) {
        double side = Math.max(16, Math.min(512, size));
        double factor = Double.isFinite(scale) ? Math.max(1, scale) : 1;
        return Math.min(MAX_PIXELS, Math.ceil(side * factor));
    }

    /**
     * Which picture is shown for this metadata, with an SVG drawing laid out for the tile and detail sizes at this screen
     * scale. Pure: no JavaFX, nothing fetched; meant to run off the FX thread.
     */
    public static Plan plan(Optional<NftMetadata> metadata, double scale) {
        return plan(metadata, scale, new double[] {TILE_SIZE, DETAIL_SIZE}, null);
    }

    /**
     * As {@link #plan(Optional, double)} for one tile of a listing: laid out for the tile size only (the detail is laid
     * out when it opens), and within what the listing's budget has left. A drawing that does not fit is the "No picture"
     * placeholder and takes nothing from the budget; raster and hosted pictures cost nothing.
     */
    public static Plan plan(Optional<NftMetadata> metadata, double scale, Budget budget) {
        return plan(metadata, scale, new double[] {TILE_SIZE}, budget);
    }

    /** The plan for one size only, for a picture whose plan was released: the detail, when it opens. */
    public static Plan planFor(Optional<NftMetadata> metadata, double scale, double size) {
        return plan(metadata, scale, new double[] {size}, null);
    }

    /**
     * What every picture of one listing may hold together, in drawing segments plus laid out path commands. Without it
     * only each picture is bounded ({@link #MAX_COMMANDS}), and a listing of {@link CollectiblesPresenter#MAX_TILES}
     * costly pictures would hold them all at once. Not thread safe: one listing, one thread.
     */
    public static final class Budget {
        private int left;

        public Budget(int units) {
            this.left = units;
        }

        public int left() {
            return left;
        }

        private void take(int units) {
            left -= units;
        }
    }

    /** One listing's budget: about 1,000,000 segments and commands, at most about 92 bytes each. */
    public static final int LISTING_UNITS = 1_000_000;

    /** A drawing's size in a budget's units: its path segments and polyline points, one for any other shape. */
    static int weight(SvgSubset.Drawing drawing) {
        long weight = 0;
        for(SvgSubset.Shape shape : drawing.shapes()) {
            weight += switch(shape) {
                case SvgSubset.PathShape path -> 1 + path.segments().size();
                case SvgSubset.Polyline polyline -> 1 + polyline.points().length / 2;
                default -> 1;
            };
        }
        return (int)Math.min(Integer.MAX_VALUE, weight);
    }

    private static Plan plan(Optional<NftMetadata> metadata, double scale, double[] sizes, Budget budget) {
        if(metadata == null || metadata.isEmpty()) {
            return Plan.placeholder(Kind.NONE, NO_PICTURE);
        }
        try {
            NftMetadata.Image image = metadata.get().image();
            if(image instanceof NftMetadata.Image.Svg svg) {
                Optional<SvgSubset.Drawing> drawing = SvgSubset.parse(svg.source());
                if(drawing.isEmpty()) {
                    return Plan.placeholder(Kind.NONE, NO_PICTURE);
                }
                int weight = weight(drawing.get());
                if(budget != null && weight >= budget.left()) {
                    return Plan.placeholder(Kind.NONE, NO_PICTURE);
                }
                int spent = weight;
                Map<Double, Prepared> prepared = new HashMap<>();
                for(double size : sizes) {
                    double pixels = pixels(size, scale);
                    if(!prepared.containsKey(pixels)) {
                        int most = budget == null ? MAX_COMMANDS : Math.min(MAX_COMMANDS, budget.left() - spent);
                        Optional<Prepared> laidOut = most > 0 ? prepare(drawing.get(), pixels, most) : Optional.empty();
                        if(laidOut.isEmpty()) {
                            return Plan.placeholder(Kind.NONE, NO_PICTURE);
                        }
                        spent += laidOut.get().commands();
                        prepared.put(pixels, laidOut.get());
                    }
                }
                if(budget != null) {
                    budget.take(spent);
                }
                return new Plan(Kind.SVG, null, drawing.get(), null, "svg:" + sha256(svg.source().getBytes(StandardCharsets.UTF_8)), scale, Map.copyOf(prepared));
            }
            if(image instanceof NftMetadata.Image.Raster raster) {
                //The media type is only the URI's label: the bytes decide
                byte[] bytes = raster.bytes();
                if(InscriptionPreview.kind(null, bytes) != InscriptionPreview.Kind.IMAGE || !InscriptionPreview.safeToDecode(bytes)) {
                    return Plan.placeholder(Kind.NONE, NO_PICTURE);
                }
                return new Plan(Kind.RASTER, null, null, bytes, "nft:" + sha256(bytes), scale, Map.of());
            }
            if(image instanceof NftMetadata.Image.Hosted hosted) {
                return Plan.placeholder(Kind.HOSTED, "Picture not shown: hosted at " + shortHost(hosted.host()));
            }
        } catch(RuntimeException e) {
            //A bug here must not take the view down: show the placeholder
        }
        return Plan.placeholder(Kind.NONE, NO_PICTURE);
    }

    /** A host as shown: a long one is cut from the front, keeping its end, which names the site that would be asked. */
    static String shortHost(String host) {
        if(host.length() <= MAX_HOST_SHOWN) {
            return host;
        }
        return "..." + host.substring(host.length() - (MAX_HOST_SHOWN - 3));
    }

    /** The screen's output scale, 1 when it cannot be read. FX thread. */
    public static double screenScale() {
        try {
            return Math.max(1, Screen.getPrimary().getOutputScaleX());
        } catch(RuntimeException e) {
            return 1;
        }
    }

    /**
     * The picture for a plan at this size (clamped to 16..512). Must be called on the FX thread. The cache key names
     * the token (network, contract, token id); null draws without caching.
     */
    public static Node node(Plan plan, double size, String cacheKey) {
        double side = Math.max(16, Math.min(512, size));
        Node node = null;
        try {
            if(plan.kind() == Kind.SVG) {
                node = svgNode(plan, side, cacheKey);
            } else if(plan.kind() == Kind.RASTER) {
                node = InscriptionPreview.image(plan.key(), plan.raster(), side).orElse(null);
            }
        } catch(RuntimeException e) {
            node = null;
        }
        if(node == null) {
            node = InscriptionPreview.placeholder(plan.placeholder() == null ? NO_PICTURE : plan.placeholder(), side);
        }
        //The placeholder can name a host from the metadata
        node.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        return node;
    }

    /**
     * Whether this plan's picture at this size is among the rendered images, so that {@link #node} shows it without
     * laying anything out. A released plan whose picture is not must be planned again ({@link #planFor}), off the FX
     * thread, before it can be drawn.
     */
    public static boolean isRendered(Plan plan, double size, String cacheKey) {
        if(plan.kind() != Kind.SVG || cacheKey == null) {
            return false;
        }
        double side = Math.max(16, Math.min(512, size));
        Rendered cached = rendered.get(cacheKey + "@" + side + "@" + pixels(side, plan.scale()));
        return cached != null && cached.content().equals(plan.key());
    }

    private static Node svgNode(Plan plan, double side, String cacheKey) {
        double pixels = pixels(side, plan.scale());
        String key = cacheKey == null ? null : cacheKey + "@" + side + "@" + pixels;
        Rendered cached = key == null ? null : rendered.get(key);
        Image image = cached != null && cached.content().equals(plan.key()) ? cached.image() : null;
        if(image == null) {
            //A released plan holds no drawing: the caller plans it again, off this thread, and comes back with that plan
            if(plan.isReleased()) {
                return null;
            }
            //Laid out off the FX thread when the plan was made; another size is laid out here
            Optional<Prepared> prepared = plan.preparedAt(pixels).or(() -> prepare(plan.drawing(), pixels));
            if(prepared.isEmpty()) {
                return null;
            }
            image = render(prepared.get());
            if(image == null) {
                return null;
            }
            if(key != null) {
                rendered.put(key, new Rendered(plan.key(), image));
            }
        }
        ImageView view = new ImageView(image);
        view.setFitWidth(side);
        view.setFitHeight(side);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        StackPane frame = new StackPane(view);
        frame.setMinSize(side, side);
        frame.setPrefSize(side, side);
        frame.setMaxSize(side, side);
        frame.getStyleClass().add("inscription-frame");
        return frame;
    }

    // ---------------- Pure stage ----------------

    /** One path command: MOVE (x y), LINE (x y), CUBIC (x1 y1 x2 y2 x y) or CLOSE, in the shape's own coordinates. */
    record Cmd(int op, double[] coords) {
    }

    sealed interface Item permits PathItem, TextItem {
        /** Shape coordinates to canvas pixels, {a, b, c, d, e, f} in SVG order. */
        double[] affine();

        double opacity();
    }

    /** A filled and/or stroked path; a null path or paint is not drawn. Paint is 0xAARRGGBB. */
    record PathItem(double[] affine, double opacity, Integer fill, List<Cmd> fillPath, Integer stroke, double strokeWidth, List<Cmd> strokePath)
            implements Item {
    }

    record TextItem(double[] affine, double opacity, Integer fill, Integer stroke, double strokeWidth, double x, double y, double size,
                    SvgSubset.Anchor anchor, boolean bold, boolean monospace, String content) implements Item {
    }

    /** A canvas of size x size pixels, the clip rectangle {x, y, width, height} in pixels, and what to draw in order. */
    record Prepared(double size, double[] clip, List<Item> items) {
        /** The path commands traced to draw it (a path filled and stroked is traced twice), a text counting one. */
        int commands() {
            int commands = 0;
            for(Item item : items) {
                if(item instanceof PathItem path) {
                    commands += (path.fillPath() == null ? 0 : path.fillPath().size()) + (path.strokePath() == null ? 0 : path.strokePath().size());
                } else {
                    commands++;
                }
            }
            return Math.max(1, commands);
        }
    }

    /** As {@link #prepare(SvgSubset.Drawing, double, int)} with at most {@link #MAX_COMMANDS} commands. */
    static Optional<Prepared> prepare(SvgSubset.Drawing drawing, double size) {
        return prepare(drawing, size, MAX_COMMANDS);
    }

    /**
     * The drawing laid out on a square canvas, or empty (the "No picture" placeholder) when any value is not finite, a
     * font would be absurdly large, or the picture needs more than maxCommands path commands. Far geometry is not
     * refused: a curve reaching far off the canvas is split until its pieces are near enough or wholly beyond
     * {@link #CLIP_LIMIT}, where they become straight lines; text anchored beyond it is left out; a stroke wider than the
     * canvas's diagonal is drawn at that width.
     */
    static Optional<Prepared> prepare(SvgSubset.Drawing drawing, double size, int maxCommands) {
        try {
            double[] fit = fit(drawing.width(), drawing.height(), size);
            if(!finite(fit)) {
                return Optional.empty();
            }
            double[] clip = {fit[4], fit[5], drawing.width() * fit[0], drawing.height() * fit[3]};
            if(!finite(clip)) {
                return Optional.empty();
            }
            double diagonal = size * Math.sqrt(2);
            int[] budget = {maxCommands};
            List<Item> items = new ArrayList<>();
            for(SvgSubset.Shape shape : drawing.shapes()) {
                double[] affine = multiply(fit, affineOf(shape));
                if(!finite(affine) || !Double.isFinite(stretch(affine)) || !Double.isFinite(shape.opacity())) {
                    return Optional.empty();
                }
                if(shape instanceof SvgSubset.Text text) {
                    //As for every shape: a value that is not finite refuses the picture, and far away is left out
                    double[] anchor = apply(affine, text.x(), text.y());
                    double fontPixels = text.size() * stretch(affine);
                    if(!finite(anchor) || !Double.isFinite(fontPixels)) {
                        return Optional.empty();
                    }
                    if(Math.abs(anchor[0]) > CLIP_LIMIT || Math.abs(anchor[1]) > CLIP_LIMIT) {
                        //At most 200 characters of at most 4,096 pixels: it cannot reach the canvas
                        continue;
                    }
                    TextItem item = text(text, affine, diagonal);
                    if(item == null) {
                        continue;
                    }
                    if(fontPixels > MAX_FONT_PIXELS || --budget[0] < 0) {
                        return Optional.empty();
                    }
                    items.add(item);
                    continue;
                }
                PathItem item = path(shape, affine, diagonal);
                if(item == null) {
                    continue;
                }
                List<Cmd> fillPath = item.fillPath() == null ? null : bound(item.fillPath(), affine, budget);
                List<Cmd> strokePath = item.strokePath() == null ? null : item.strokePath() == item.fillPath() ? fillPath : bound(item.strokePath(), affine, budget);
                if((item.fillPath() != null && fillPath == null) || (item.strokePath() != null && strokePath == null)) {
                    return Optional.empty();
                }
                if(strokePath != null && strokePath == fillPath && (budget[0] -= strokePath.size()) < 0) {
                    //The same commands are traced twice, once to fill and once to stroke
                    return Optional.empty();
                }
                items.add(new PathItem(affine, item.opacity(), item.fill(), fillPath, item.stroke(), item.strokeWidth(), strokePath));
            }
            return Optional.of(new Prepared(size, clip, List.copyOf(items)));
        } catch(RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The commands with every curve near the canvas: one whose control points (which bound it) all lie within
     * {@link #CURVE_LIMIT} pixels is kept; one wholly beyond {@link #CLIP_LIMIT} becomes a line to its end, which
     * covers the same pixels inside that box; any other is split in half and each half handled the same way. Null when
     * a placed point is not finite or the commands outgrow the budget.
     */
    private static List<Cmd> bound(List<Cmd> cmds, double[] m, int[] budget) {
        List<Cmd> out = new ArrayList<>(cmds.size());
        double cx = 0, cy = 0, sx = 0, sy = 0;
        for(Cmd cmd : cmds) {
            double[] c = cmd.coords();
            for(int i = 0; i + 1 < c.length; i += 2) {
                if(!finite(apply(m, c[i], c[i + 1]))) {
                    return null;
                }
            }
            switch(cmd.op()) {
                case MOVE -> {
                    out.add(cmd);
                    cx = sx = c[0];
                    cy = sy = c[1];
                }
                case LINE -> {
                    out.add(cmd);
                    cx = c[0];
                    cy = c[1];
                }
                case CUBIC -> {
                    curve(out, m, new double[] {cx, cy, c[0], c[1], c[2], c[3], c[4], c[5]}, 0);
                    cx = c[4];
                    cy = c[5];
                }
                default -> {
                    out.add(cmd);
                    cx = sx;
                    cy = sy;
                }
            }
            if(out.size() > budget[0]) {
                return null;
            }
        }
        budget[0] -= out.size();
        return out;
    }

    /** One cubic {x0, y0, x1, y1, x2, y2, x3, y3} from the current point, kept, straightened or split (see bound). */
    private static void curve(List<Cmd> out, double[] m, double[] p, int depth) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for(int i = 0; i < 8; i += 2) {
            double[] d = apply(m, p[i], p[i + 1]);
            minX = Math.min(minX, d[0]);
            maxX = Math.max(maxX, d[0]);
            minY = Math.min(minY, d[1]);
            maxY = Math.max(maxY, d[1]);
        }
        boolean near = minX >= -CURVE_LIMIT && maxX <= CURVE_LIMIT && minY >= -CURVE_LIMIT && maxY <= CURVE_LIMIT;
        if(near) {
            out.add(new Cmd(CUBIC, new double[] {p[2], p[3], p[4], p[5], p[6], p[7]}));
            return;
        }
        boolean beyond = maxX < -CLIP_LIMIT || minX > CLIP_LIMIT || maxY < -CLIP_LIMIT || minY > CLIP_LIMIT;
        if(beyond || depth >= MAX_SPLITS) {
            out.add(new Cmd(LINE, new double[] {p[6], p[7]}));
            return;
        }
        //de Casteljau at t = 1/2
        double x01 = (p[0] + p[2]) / 2, y01 = (p[1] + p[3]) / 2;
        double x12 = (p[2] + p[4]) / 2, y12 = (p[3] + p[5]) / 2;
        double x23 = (p[4] + p[6]) / 2, y23 = (p[5] + p[7]) / 2;
        double xa = (x01 + x12) / 2, ya = (y01 + y12) / 2;
        double xb = (x12 + x23) / 2, yb = (y12 + y23) / 2;
        double xm = (xa + xb) / 2, ym = (ya + yb) / 2;
        curve(out, m, new double[] {p[0], p[1], x01, y01, xa, ya, xm, ym}, depth + 1);
        curve(out, m, new double[] {xm, ym, xb, yb, x23, y23, p[6], p[7]}, depth + 1);
    }

    private static double[] apply(double[] m, double x, double y) {
        return new double[] {m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
    }

    /** Scale to fit, centred: {s, 0, 0, s, dx, dy}. */
    static double[] fit(double width, double height, double size) {
        double scale = Math.min(size / width, size / height);
        return new double[] {scale, 0, 0, scale, (size - width * scale) / 2, (size - height * scale) / 2};
    }

    /**
     * The shape's one placement in drawing coordinates: the model's composed matrix, finite and bounded by
     * construction. The transform list it came from is never multiplied here, since another order can overflow.
     */
    static double[] affineOf(SvgSubset.Shape shape) {
        return shape.composedMatrix();
    }

    /** p followed by t, as SvgSubset composes: (p t)(x) = p(t(x)). */
    static double[] multiply(double[] p, double[] t) {
        return new double[] {p[0] * t[0] + p[2] * t[1], p[1] * t[0] + p[3] * t[1], p[0] * t[2] + p[2] * t[3], p[1] * t[2] + p[3] * t[3],
                p[0] * t[4] + p[2] * t[5] + p[4], p[1] * t[4] + p[3] * t[5] + p[5]};
    }

    /** How much the matrix can stretch a length: the longer of its two column vectors. */
    private static double stretch(double[] m) {
        return Math.max(Math.hypot(m[0], m[1]), Math.hypot(m[2], m[3]));
    }

    /** A stroke width in the shape's units, cut so it is at most the canvas's diagonal once placed; 0 when it cannot show. */
    private static double strokeWidth(double width, double[] m, double diagonal) {
        double stretch = stretch(m);
        if(!(stretch > 0)) {
            return 0;
        }
        return width * stretch > diagonal ? diagonal / stretch : width;
    }

    private static boolean finite(double[] values) {
        for(double value : values) {
            if(!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static Integer argb(SvgSubset.Paint paint) {
        return paint == null ? null : paint.argb();
    }

    private static TextItem text(SvgSubset.Text text, double[] affine, double diagonal) {
        Integer fill = argb(text.fill());
        double strokeWidth = text.strokeWidth() > 0 ? strokeWidth(text.strokeWidth(), affine, diagonal) : 0;
        Integer stroke = strokeWidth > 0 ? argb(text.stroke()) : null;
        if((fill == null && stroke == null) || text.content().isEmpty() || !(text.size() > 0)) {
            return null;
        }
        return new TextItem(affine, text.opacity(), fill, stroke, strokeWidth, text.x(), text.y(), text.size(), text.anchor(), text.bold(),
                text.monospace(), text.content());
    }

    /** The shape as paths, or null when SVG would draw nothing (zero size, no paint, a stroke of width 0). */
    private static PathItem path(SvgSubset.Shape shape, double[] affine, double diagonal) {
        Integer fill = argb(shape.fill());
        double strokeWidth = shape.strokeWidth() > 0 ? strokeWidth(shape.strokeWidth(), affine, diagonal) : 0;
        Integer stroke = strokeWidth > 0 ? argb(shape.stroke()) : null;
        List<Cmd> outline;
        List<Cmd> fillPath;
        switch(shape) {
            case SvgSubset.Rect r -> {
                if(!(r.width() > 0) || !(r.height() > 0)) {
                    return null;
                }
                outline = rect(r.x(), r.y(), r.width(), r.height(), r.rx(), r.ry());
                fillPath = outline;
            }
            case SvgSubset.Circle c -> {
                if(!(c.r() > 0)) {
                    return null;
                }
                outline = ellipse(c.cx(), c.cy(), c.r(), c.r());
                fillPath = outline;
            }
            case SvgSubset.Ellipse e -> {
                if(!(e.rx() > 0) || !(e.ry() > 0)) {
                    return null;
                }
                outline = ellipse(e.cx(), e.cy(), e.rx(), e.ry());
                fillPath = outline;
            }
            case SvgSubset.Line l -> {
                outline = List.of(new Cmd(MOVE, new double[] {l.x1(), l.y1()}), new Cmd(LINE, new double[] {l.x2(), l.y2()}));
                //A line is never filled
                fillPath = null;
                fill = null;
            }
            case SvgSubset.Polyline p -> {
                double[] points = p.points();
                List<Cmd> open = new ArrayList<>();
                for(int i = 0; i + 1 < points.length; i += 2) {
                    open.add(new Cmd(i == 0 ? MOVE : LINE, new double[] {points[i], points[i + 1]}));
                }
                List<Cmd> closed = new ArrayList<>(open);
                closed.add(new Cmd(CLOSE, new double[0]));
                //Filled as if closed, stroked open unless it is a polygon
                fillPath = closed;
                outline = p.closed() ? closed : open;
            }
            case SvgSubset.PathShape p -> {
                outline = path(p.segments());
                fillPath = outline;
            }
            case SvgSubset.Text t -> {
                return null;
            }
        }
        if(fill == null) {
            fillPath = null;
        }
        if(stroke == null) {
            outline = null;
        }
        if(fillPath == null && outline == null) {
            return null;
        }
        return new PathItem(affine, shape.opacity(), fill, fillPath, stroke, strokeWidth, outline);
    }

    private static List<Cmd> rect(double x, double y, double w, double h, double rx, double ry) {
        List<Cmd> cmds = new ArrayList<>();
        if(!(rx > 0) || !(ry > 0)) {
            cmds.add(new Cmd(MOVE, new double[] {x, y}));
            cmds.add(new Cmd(LINE, new double[] {x + w, y}));
            cmds.add(new Cmd(LINE, new double[] {x + w, y + h}));
            cmds.add(new Cmd(LINE, new double[] {x, y + h}));
            cmds.add(new Cmd(CLOSE, new double[0]));
            return cmds;
        }
        double kx = KAPPA * rx;
        double ky = KAPPA * ry;
        cmds.add(new Cmd(MOVE, new double[] {x + rx, y}));
        cmds.add(new Cmd(LINE, new double[] {x + w - rx, y}));
        cmds.add(new Cmd(CUBIC, new double[] {x + w - rx + kx, y, x + w, y + ry - ky, x + w, y + ry}));
        cmds.add(new Cmd(LINE, new double[] {x + w, y + h - ry}));
        cmds.add(new Cmd(CUBIC, new double[] {x + w, y + h - ry + ky, x + w - rx + kx, y + h, x + w - rx, y + h}));
        cmds.add(new Cmd(LINE, new double[] {x + rx, y + h}));
        cmds.add(new Cmd(CUBIC, new double[] {x + rx - kx, y + h, x, y + h - ry + ky, x, y + h - ry}));
        cmds.add(new Cmd(LINE, new double[] {x, y + ry}));
        cmds.add(new Cmd(CUBIC, new double[] {x, y + ry - ky, x + rx - kx, y, x + rx, y}));
        cmds.add(new Cmd(CLOSE, new double[0]));
        return cmds;
    }

    private static List<Cmd> ellipse(double cx, double cy, double rx, double ry) {
        double kx = KAPPA * rx;
        double ky = KAPPA * ry;
        List<Cmd> cmds = new ArrayList<>();
        cmds.add(new Cmd(MOVE, new double[] {cx + rx, cy}));
        cmds.add(new Cmd(CUBIC, new double[] {cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry}));
        cmds.add(new Cmd(CUBIC, new double[] {cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy}));
        cmds.add(new Cmd(CUBIC, new double[] {cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry}));
        cmds.add(new Cmd(CUBIC, new double[] {cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy}));
        cmds.add(new Cmd(CLOSE, new double[0]));
        return cmds;
    }

    /** Path segments as moves, lines and cubics: quadratics raised exactly, arcs converted. */
    private static List<Cmd> path(List<SvgSubset.Segment> segments) {
        List<Cmd> cmds = new ArrayList<>(segments.size());
        double cx = 0, cy = 0, sx = 0, sy = 0;
        for(SvgSubset.Segment segment : segments) {
            switch(segment) {
                case SvgSubset.MoveTo m -> {
                    cmds.add(new Cmd(MOVE, new double[] {m.x(), m.y()}));
                    cx = sx = m.x();
                    cy = sy = m.y();
                }
                case SvgSubset.LineTo l -> {
                    cmds.add(new Cmd(LINE, new double[] {l.x(), l.y()}));
                    cx = l.x();
                    cy = l.y();
                }
                case SvgSubset.CubicTo c -> {
                    cmds.add(new Cmd(CUBIC, new double[] {c.x1(), c.y1(), c.x2(), c.y2(), c.x(), c.y()}));
                    cx = c.x();
                    cy = c.y();
                }
                case SvgSubset.QuadTo q -> {
                    cmds.add(new Cmd(CUBIC, new double[] {cx + 2.0 / 3.0 * (q.x1() - cx), cy + 2.0 / 3.0 * (q.y1() - cy),
                            q.x() + 2.0 / 3.0 * (q.x1() - q.x()), q.y() + 2.0 / 3.0 * (q.y1() - q.y()), q.x(), q.y()}));
                    cx = q.x();
                    cy = q.y();
                }
                case SvgSubset.ArcTo a -> {
                    for(double[] cubic : arcToBeziers(cx, cy, a.rx(), a.ry(), a.rotation(), a.largeArc(), a.sweep(), a.x(), a.y())) {
                        cmds.add(new Cmd(CUBIC, cubic));
                    }
                    cx = a.x();
                    cy = a.y();
                }
                case SvgSubset.Close z -> {
                    cmds.add(new Cmd(CLOSE, new double[0]));
                    //The next segment starts where the closed subpath did
                    cx = sx;
                    cy = sy;
                }
            }
        }
        return cmds;
    }

    /**
     * An SVG elliptical arc from (x0, y0) to (x, y) as cubic Beziers {x1, y1, x2, y2, x, y}, by the SVG implementation
     * notes (F.6.5, F.6.6): the same end points draw nothing; a zero radius is a straight line (one cubic with its
     * handles on the end points); radii too small to reach the end point are scaled up; the flags pick the centre and
     * the direction. Each cubic spans at most a quarter turn, and the last ends exactly at (x, y).
     */
    static List<double[]> arcToBeziers(double x0, double y0, double rx, double ry, double rotation, boolean largeArc, boolean sweep, double x, double y) {
        List<double[]> cubics = new ArrayList<>();
        if(x0 == x && y0 == y) {
            return cubics;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if(rx == 0 || ry == 0) {
            cubics.add(new double[] {x0, y0, x, y, x, y});
            return cubics;
        }
        double phi = Math.toRadians(rotation % 360);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x0 - x) / 2;
        double dy = (y0 - y) / 2;
        double x1p = cos * dx + sin * dy;
        double y1p = -sin * dx + cos * dy;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if(lambda > 1) {
            double grow = Math.sqrt(lambda);
            rx *= grow;
            ry *= grow;
        }
        double rx2 = rx * rx;
        double ry2 = ry * ry;
        double numerator = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
        double denominator = rx2 * y1p * y1p + ry2 * x1p * x1p;
        double coefficient = denominator == 0 ? 0 : Math.sqrt(Math.max(0, numerator / denominator));
        if(largeArc == sweep) {
            coefficient = -coefficient;
        }
        double cxp = coefficient * rx * y1p / ry;
        double cyp = -coefficient * ry * x1p / rx;
        double centreX = cos * cxp - sin * cyp + (x0 + x) / 2;
        double centreY = sin * cxp + cos * cyp + (y0 + y) / 2;
        double ux = (x1p - cxp) / rx;
        double uy = (y1p - cyp) / ry;
        double vx = (-x1p - cxp) / rx;
        double vy = (-y1p - cyp) / ry;
        double start = Math.atan2(uy, ux);
        double extent = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if(!sweep && extent > 0) {
            extent -= 2 * Math.PI;
        } else if(sweep && extent < 0) {
            extent += 2 * Math.PI;
        }
        int count = (int)Math.max(1, Math.min(4, Math.ceil(Math.abs(extent) / (Math.PI / 2) - 1e-9)));
        double delta = extent / count;
        double t = 4.0 / 3.0 * Math.tan(delta / 4);
        for(int i = 0; i < count; i++) {
            double a1 = start + i * delta;
            double a2 = a1 + delta;
            double[] p1 = ellipsePoint(centreX, centreY, rx, ry, cos, sin, a1);
            double[] p2 = ellipsePoint(centreX, centreY, rx, ry, cos, sin, a2);
            double[] d1 = ellipseTangent(rx, ry, cos, sin, a1);
            double[] d2 = ellipseTangent(rx, ry, cos, sin, a2);
            boolean last = i == count - 1;
            cubics.add(new double[] {p1[0] + t * d1[0], p1[1] + t * d1[1], p2[0] - t * d2[0], p2[1] - t * d2[1], last ? x : p2[0], last ? y : p2[1]});
        }
        return cubics;
    }

    private static double[] ellipsePoint(double cx, double cy, double rx, double ry, double cos, double sin, double angle) {
        double ex = rx * Math.cos(angle);
        double ey = ry * Math.sin(angle);
        return new double[] {cos * ex - sin * ey + cx, sin * ex + cos * ey + cy};
    }

    private static double[] ellipseTangent(double rx, double ry, double cos, double sin, double angle) {
        double dx = -rx * Math.sin(angle);
        double dy = ry * Math.cos(angle);
        return new double[] {cos * dx - sin * dy, sin * dx + cos * dy};
    }

    // ---------------- JavaFX stage ----------------

    /** Replays a prepared drawing onto a canvas and keeps it as an image, or null when any shape fails to draw. FX thread only. */
    private static Image render(Prepared prepared) {
        Canvas canvas = new Canvas(prepared.size(), prepared.size());
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFillRule(FillRule.NON_ZERO);
        gc.setLineCap(StrokeLineCap.BUTT);
        gc.setLineJoin(StrokeLineJoin.MITER);
        gc.setMiterLimit(4);
        gc.setTextBaseline(VPos.BASELINE);
        double[] clip = prepared.clip();
        gc.beginPath();
        gc.rect(clip[0], clip[1], clip[2], clip[3]);
        gc.clip();
        for(Item item : prepared.items()) {
            gc.save();
            try {
                double[] m = item.affine();
                gc.setGlobalAlpha(Math.max(0, Math.min(1, item.opacity())));
                gc.setTransform(m[0], m[1], m[2], m[3], m[4], m[5]);
                if(item instanceof PathItem path) {
                    drawPath(gc, path);
                } else if(item instanceof TextItem text) {
                    drawText(gc, text);
                }
            } catch(RuntimeException e) {
                //A shape the canvas cannot take would leave a partial drawing: show the placeholder instead
                return null;
            } finally {
                gc.restore();
            }
        }
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        try {
            return canvas.snapshot(parameters, null);
        } catch(RuntimeException e) {
            return null;
        }
    }

    private static void drawPath(GraphicsContext gc, PathItem path) {
        if(path.fillPath() != null) {
            trace(gc, path.fillPath());
            gc.setFill(color(path.fill()));
            gc.fill();
        }
        if(path.strokePath() != null) {
            trace(gc, path.strokePath());
            gc.setStroke(color(path.stroke()));
            gc.setLineWidth(path.strokeWidth());
            gc.stroke();
        }
    }

    private static void trace(GraphicsContext gc, List<Cmd> cmds) {
        gc.beginPath();
        for(Cmd cmd : cmds) {
            double[] c = cmd.coords();
            switch(cmd.op()) {
                case MOVE -> gc.moveTo(c[0], c[1]);
                case LINE -> gc.lineTo(c[0], c[1]);
                case CUBIC -> gc.bezierCurveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                default -> gc.closePath();
            }
        }
    }

    private static void drawText(GraphicsContext gc, TextItem text) {
        String family = text.monospace() ? "Monospaced" : "SansSerif";
        gc.setFont(Font.font(family, text.bold() ? FontWeight.BOLD : FontWeight.NORMAL, text.size()));
        gc.setTextAlign(switch(text.anchor()) {
            case START -> TextAlignment.LEFT;
            case MIDDLE -> TextAlignment.CENTER;
            case END -> TextAlignment.RIGHT;
        });
        if(text.fill() != null) {
            gc.setFill(color(text.fill()));
            gc.fillText(text.content(), text.x(), text.y());
        }
        if(text.stroke() != null) {
            gc.setStroke(color(text.stroke()));
            gc.setLineWidth(text.strokeWidth());
            gc.strokeText(text.content(), text.x(), text.y());
        }
    }

    private static Color color(int argb) {
        return Color.rgb((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff, ((argb >>> 24) & 0xff) / 255.0);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
