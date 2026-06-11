/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.core.gui;

import art.arcane.iris.Iris;
import art.arcane.iris.engine.framework.render.IrisRenderer;
import art.arcane.iris.engine.framework.render.RenderType;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.math.BlockPosition;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RollingSequence;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.O;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import javax.swing.*;
import javax.swing.event.MouseInputListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static art.arcane.iris.util.common.data.registry.Attributes.MAX_HEALTH;

public class VisionGUI extends JPanel implements MouseWheelListener, KeyListener, MouseMotionListener, MouseInputListener {
    private static final long serialVersionUID = 2094606939770332040L;

    private static final Color BG = new Color(18, 18, 22);
    private static final Color CARD_BG = new Color(28, 28, 36, 220);
    private static final Color CARD_BORDER = new Color(60, 60, 75, 180);
    private static final Color TEXT_PRIMARY = new Color(220, 220, 230);
    private static final Color TEXT_SECONDARY = new Color(140, 140, 155);
    private static final Color TEXT_DIM = new Color(90, 90, 105);
    private static final Color ACCENT = new Color(90, 140, 255);
    private static final Color ACCENT_DIM = new Color(60, 100, 200, 100);
    private static final Color PLAYER_COLOR = new Color(80, 200, 120);
    private static final Color MOB_COLOR = new Color(220, 80, 80);
    private static final Color STATUS_BG = new Color(24, 24, 30, 240);
    private static final Color GRID_COLOR = new Color(255, 255, 255, 12);
    private static final Font FONT_STATUS = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font FONT_CARD_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font FONT_CARD_BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font FONT_HELP_KEY = new Font(Font.MONOSPACED, Font.BOLD, 12);
    private static final Font FONT_NOTIFICATION = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    private static final int CARD_RADIUS = 8;
    private static final int CARD_PAD = 12;
    private static final int STATUS_HEIGHT = 26;

    private final KList<LivingEntity> lastEntities = new KList<>();
    private final KMap<String, Long> notifications = new KMap<>();
    private final ChronoLatch centities = new ChronoLatch(1000);
    private final RollingSequence rs = new RollingSequence(512);
    private final O<Integer> m = new O<>();
    private final KMap<BlockPosition, BufferedImage> positions = new KMap<>();
    private final KMap<BlockPosition, BufferedImage> fastpositions = new KMap<>();
    private final KSet<BlockPosition> working = new KSet<>();
    private final KSet<BlockPosition> workingfast = new KSet<>();

    private RenderType currentType = RenderType.BIOME;
    private boolean help = true;
    private boolean helpIgnored = false;
    private boolean shift = false;
    private Player player = null;
    private boolean debug = false;
    private boolean control = false;
    private boolean eco = false;
    private boolean lowtile = false;
    private boolean follow = false;
    private boolean alt = false;
    private boolean grid = false;
    private IrisRenderer renderer;
    private IrisWorld world;
    private double velocity = 0;
    private int lowq = 12;
    private double scale = 128;
    private double mscale = 4D;
    private int w = 0;
    private int h = 0;
    private double lx = 0;
    private double lz = 0;
    private double ox = 0;
    private double oz = 0;
    private double hx = 0;
    private double hz = 0;
    private double oxp = 0;
    private double ozp = 0;
    private Engine engine;
    private int tid = 0;
    private Map<RenderType, JToggleButton> modeButtons;

    private final ExecutorService e = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
        tid++;
        Thread t = new Thread(r);
        t.setName("Iris HD Renderer " + tid);
        t.setPriority(Thread.MIN_PRIORITY);
        t.setUncaughtExceptionHandler((et, ex) -> {
            Iris.info("Exception encountered in " + et.getName());
            ex.printStackTrace();
        });
        return t;
    });

    private final ExecutorService eh = Executors.newFixedThreadPool(3, r -> {
        tid++;
        Thread t = new Thread(r);
        t.setName("Iris Renderer " + tid);
        t.setPriority(Thread.NORM_PRIORITY);
        t.setUncaughtExceptionHandler((et, ex) -> {
            Iris.info("Exception encountered in " + et.getName());
            ex.printStackTrace();
        });
        return t;
    });

    public VisionGUI(JFrame frame) {
        m.set(8);
        rs.put(1);
        setBackground(BG);
        addMouseWheelListener(this);
        addMouseMotionListener(this);
        addMouseListener(this);
        frame.addKeyListener(this);
        J.a(() -> {
            J.sleep(10000);
            if (!helpIgnored && help) {
                help = false;
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                e.shutdown();
                eh.shutdown();
            }
        });
    }

    private static void createAndShowGUI(Engine r, int s, IrisWorld world) {
        JFrame frame = new JFrame("Iris Vision");
        VisionGUI nv = new VisionGUI(frame);
        nv.world = world;
        nv.engine = r;
        nv.renderer = new IrisRenderer(r);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout());
        frame.add(buildToolbar(nv), BorderLayout.NORTH);
        frame.add(nv, BorderLayout.CENTER);
        frame.setSize(1440, 820);
        frame.setMinimumSize(new Dimension(640, 480));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JPanel buildToolbar(VisionGUI nv) {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        toolbar.setBackground(new Color(22, 22, 28));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(45, 45, 55)));

        JLabel modeLabel = new JLabel("View:");
        modeLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        modeLabel.setForeground(TEXT_SECONDARY);
        modeLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
        toolbar.add(modeLabel);

        ButtonGroup modeGroup = new ButtonGroup();
        Map<RenderType, JToggleButton> modeButtons = new LinkedHashMap<>();
        for (RenderType type : RenderType.values()) {
            JToggleButton btn = createToolbarToggle(modeName(type), type == nv.currentType);
            btn.addActionListener(e -> {
                nv.setRenderType(type);
                for (Map.Entry<RenderType, JToggleButton> entry : modeButtons.entrySet()) {
                    entry.getValue().setSelected(entry.getKey() == type);
                }
            });
            modeGroup.add(btn);
            modeButtons.put(type, btn);
            toolbar.add(btn);
        }
        nv.modeButtons = modeButtons;

        toolbar.add(createToolbarSeparator());

        JToggleButton gridBtn = createToolbarToggle("Grid", nv.grid);
        gridBtn.addActionListener(e -> { nv.toggleGrid(); gridBtn.setSelected(nv.grid); });
        toolbar.add(gridBtn);

        JToggleButton followBtn = createToolbarToggle("Follow", nv.follow);
        followBtn.addActionListener(e -> { nv.toggleFollow(); followBtn.setSelected(nv.follow); });
        toolbar.add(followBtn);

        JToggleButton qualityBtn = createToolbarToggle("LQ", nv.lowtile);
        qualityBtn.addActionListener(e -> { nv.toggleQuality(); qualityBtn.setSelected(nv.lowtile); });
        toolbar.add(qualityBtn);

        return toolbar;
    }

    private static JToggleButton createToolbarToggle(String text, boolean selected) {
        JToggleButton btn = new JToggleButton(text, selected);
        btn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        btn.setFocusable(false);
        btn.setForeground(new Color(170, 170, 185));
        btn.setBackground(new Color(32, 32, 40));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(50, 50, 60)),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ));
        btn.setOpaque(true);
        btn.addChangeListener(e -> {
            if (btn.isSelected()) {
                btn.setBackground(new Color(50, 60, 85));
                btn.setForeground(Color.WHITE);
            } else {
                btn.setBackground(new Color(32, 32, 40));
                btn.setForeground(new Color(170, 170, 185));
            }
        });
        return btn;
    }

    private static JSeparator createToolbarSeparator() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 24));
        sep.setForeground(new Color(50, 50, 60));
        sep.setBackground(new Color(22, 22, 28));
        return sep;
    }

    public static void launch(Engine g, int i) {
        J.a(() -> createAndShowGUI(g, i, g.getWorld()));
    }

    public boolean updateEngine() {
        if (engine.isClosed()) {
            if (world.hasRealWorld()) {
                try {
                    engine = IrisToolbelt.access(world.realWorld()).getEngine();
                    return !engine.isClosed();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        Point cp = e.getPoint();
        lx = cp.getX();
        lz = cp.getY();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        Point cp = e.getPoint();
        ox += (lx - cp.getX()) * scale;
        oz += (lz - cp.getY()) * scale;
        lx = cp.getX();
        lz = cp.getY();
    }

    public void notify(String s) {
        notifications.put(s, M.ms() + 2500);
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SHIFT) shift = true;
        if (e.getKeyCode() == KeyEvent.VK_CONTROL) control = true;
        if (e.getKeyCode() == KeyEvent.VK_SEMICOLON) debug = true;
        if (e.getKeyCode() == KeyEvent.VK_SLASH) { help = true; helpIgnored = true; }
        if (e.getKeyCode() == KeyEvent.VK_ALT) alt = true;
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SEMICOLON) debug = false;
        if (e.getKeyCode() == KeyEvent.VK_SHIFT) shift = false;
        if (e.getKeyCode() == KeyEvent.VK_CONTROL) control = false;
        if (e.getKeyCode() == KeyEvent.VK_SLASH) { help = false; helpIgnored = true; }
        if (e.getKeyCode() == KeyEvent.VK_ALT) alt = false;

        if (e.getKeyCode() == KeyEvent.VK_F) { toggleFollow(); return; }
        if (e.getKeyCode() == KeyEvent.VK_R) { dump(); notify("Refreshing"); return; }
        if (e.getKeyCode() == KeyEvent.VK_P) { toggleQuality(); return; }
        if (e.getKeyCode() == KeyEvent.VK_E) { eco = !eco; dump(); notify((eco ? "30" : "60") + " FPS"); return; }
        if (e.getKeyCode() == KeyEvent.VK_G) { toggleGrid(); return; }

        if (e.getKeyCode() == KeyEvent.VK_EQUALS) {
            mscale = mscale + ((0.044 * mscale) * -3);
            mscale = Math.max(mscale, 0.00001);
            dump();
            return;
        }
        if (e.getKeyCode() == KeyEvent.VK_MINUS) {
            mscale = mscale + ((0.044 * mscale) * 3);
            mscale = Math.max(mscale, 0.00001);
            dump();
            return;
        }
        if (e.getKeyCode() == KeyEvent.VK_BACK_SLASH) {
            mscale = 1D;
            dump();
            notify("Zoom Reset");
            return;
        }

        int currentMode = currentType.ordinal();
        for (RenderType i : RenderType.values()) {
            if (e.getKeyChar() == String.valueOf(i.ordinal() + 1).charAt(0)) {
                if (i.ordinal() != currentMode) {
                    setRenderType(i);
                    syncModeButtons();
                    return;
                }
            }
        }

        if (e.getKeyCode() == KeyEvent.VK_M) {
            setRenderType(RenderType.values()[(currentMode + 1) % RenderType.values().length]);
            syncModeButtons();
        }
    }

    private static String modeName(RenderType type) {
        return Form.capitalizeWords(type.name().toLowerCase().replaceAll("\\Q_\\E", " "));
    }

    void setRenderType(RenderType type) {
        currentType = type;
        dump();
        notify(modeName(type));
    }

    void toggleGrid() {
        grid = !grid;
        notify("Grid " + (grid ? "On" : "Off"));
    }

    void toggleFollow() {
        follow = !follow;
        if (player != null && follow) {
            notify("Following " + player.getName());
        } else if (follow) {
            notify("No player in world");
            follow = false;
        } else {
            notify("Follow disabled");
        }
    }

    void toggleQuality() {
        lowtile = !lowtile;
        dump();
        notify((lowtile ? "Low" : "High") + " Quality");
    }

    private void syncModeButtons() {
        if (modeButtons == null) return;
        for (Map.Entry<RenderType, JToggleButton> entry : modeButtons.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == currentType);
        }
    }

    private void dump() {
        positions.clear();
        fastpositions.clear();
    }

    public BufferedImage getTile(KSet<BlockPosition> fg, int div, int x, int z, O<Integer> m) {
        BlockPosition key = new BlockPosition((int) mscale, Math.floorDiv(x, div), Math.floorDiv(z, div));
        fg.add(key);

        if (positions.containsKey(key)) {
            return positions.get(key);
        }

        if (fastpositions.containsKey(key)) {
            if (!working.contains(key) && working.size() < 9) {
                m.set(m.get() - 1);
                if (m.get() >= 0 && velocity < 50) {
                    working.add(key);
                    double mk = mscale;
                    double mkd = scale;
                    e.submit(() -> {
                        PrecisionStopwatch ps = PrecisionStopwatch.start();
                        BufferedImage b = renderer.render(x * mscale, z * mscale, div * mscale, div / (lowtile ? 3 : 1), currentType);
                        rs.put(ps.getMilliseconds());
                        working.remove(key);
                        if (mk == mscale && mkd == scale) {
                            positions.put(key, b);
                        }
                    });
                }
            }
            return fastpositions.get(key);
        }

        if (workingfast.contains(key) || workingfast.size() > Runtime.getRuntime().availableProcessors()) {
            return null;
        }

        workingfast.add(key);
        double mk = mscale;
        double mkd = scale;
        eh.submit(() -> {
            PrecisionStopwatch ps = PrecisionStopwatch.start();
            BufferedImage b = renderer.render(x * mscale, z * mscale, div * mscale, div / lowq, currentType);
            rs.put(ps.getMilliseconds());
            workingfast.remove(key);
            if (mk == mscale && mkd == scale) {
                fastpositions.put(key, b);
            }
        });
        return null;
    }

    private double getWorldX(double screenX) {
        return (mscale * screenX) + ((oxp / scale) * mscale);
    }

    private double getWorldZ(double screenZ) {
        return (mscale * screenZ) + ((ozp / scale) * mscale);
    }

    private double getScreenX(double x) {
        return (x / mscale) - (oxp / scale);
    }

    private double getScreenZ(double z) {
        return (z / mscale) - (ozp / scale);
    }

    private double lerp(double current, double target, double speed) {
        double diff = target - current;
        if (Math.abs(diff) < 0.5) return target;
        return current + diff * speed;
    }

    @Override
    public void paint(Graphics gx) {
        if (engine.isClosed()) {
            EventQueue.invokeLater(() -> {
                try { setVisible(false); } catch (Throwable ignored) { }
            });
            return;
        }

        if (updateEngine()) {
            dump();
        }

        velocity = Math.abs(ox - oxp) * 0.36 + Math.abs(oz - ozp) * 0.36;
        oxp = lerp(oxp, ox, 0.36);
        ozp = lerp(ozp, oz, 0.36);
        hx = lerp(hx, lx, 0.36);
        hz = lerp(hz, lz, 0.36);

        if (centities.flip()) {
            J.s(() -> {
                synchronized (lastEntities) {
                    lastEntities.clear();
                    lastEntities.addAll(world.getEntitiesByClass(LivingEntity.class));
                }
            });
        }

        lowq = Math.max(Math.min((int) M.lerp(8, 28, velocity / 1000D), 28), 8);
        PrecisionStopwatch p = PrecisionStopwatch.start();
        Graphics2D g = (Graphics2D) gx;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        w = getWidth();
        h = getHeight();
        double vscale = scale;
        scale = w / 12D;

        if (scale != vscale) {
            positions.clear();
        }

        KSet<BlockPosition> gg = new KSet<>();
        int iscale = (int) scale;
        g.setColor(BG);
        g.fillRect(0, 0, w, h);
        double offsetX = oxp / scale;
        double offsetZ = ozp / scale;
        m.set(3);

        for (int r = 0; r < Math.max(w, h); r += iscale) {
            for (int i = -iscale; i < w + iscale; i += iscale) {
                for (int j = -iscale; j < h + iscale; j += iscale) {
                    int a = i - (w / 2);
                    int b = j - (h / 2);
                    if (a * a + b * b <= r * r) {
                        int tx = (int) (Math.floor((offsetX + i) / iscale) * iscale);
                        int tz = (int) (Math.floor((offsetZ + j) / iscale) * iscale);
                        BufferedImage t = getTile(gg, iscale, tx, tz, m);

                        if (t != null) {
                            int rx = Math.floorMod((int) Math.floor(offsetX), iscale);
                            int rz = Math.floorMod((int) Math.floor(offsetZ), iscale);
                            g.drawImage(t, i - rx, j - rz, iscale, iscale, null);
                        }
                    }
                }
            }
        }

        if (grid) {
            renderGrid(g, iscale, offsetX, offsetZ);
        }

        p.end();

        for (BlockPosition i : positions.k()) {
            if (!gg.contains(i)) {
                positions.remove(i);
            }
        }

        handleFollow();
        renderOverlays(g, p.getMilliseconds());

        if (!isVisible() || !getParent().isVisible()) {
            return;
        }

        long targetMs = eco ? 32 : 16;
        long sleepMs = Math.max(1, targetMs - (long) p.getMilliseconds());
        J.a(() -> {
            J.sleep(sleepMs);
            repaint();
        });
    }

    private void renderGrid(Graphics2D g, int tileSize, double offsetX, double offsetZ) {
        g.setColor(GRID_COLOR);
        int rx = Math.floorMod((int) Math.floor(offsetX), tileSize);
        int rz = Math.floorMod((int) Math.floor(offsetZ), tileSize);
        for (int i = -tileSize; i < w + tileSize; i += tileSize) {
            g.drawLine(i - rx, 0, i - rx, h);
        }
        for (int j = -tileSize; j < h + tileSize; j += tileSize) {
            g.drawLine(0, j - rz, w, j - rz);
        }
    }

    private void handleFollow() {
        if (follow && player != null) {
            animateTo(player.getLocation().getX(), player.getLocation().getZ());
        }
    }

    private void renderOverlays(Graphics2D g, double frameMs) {
        renderEntities(g);

        if (help) {
            renderOverlayHelp(g);
        } else if (debug) {
            renderOverlayDebug(g);
        }

        renderStatusBar(g, frameMs);
        renderHoverOverlay(g, shift);

        if (!notifications.isEmpty()) {
            renderNotification(g);
        }
    }

    private void renderStatusBar(Graphics2D g, double frameMs) {
        int y = h - STATUS_HEIGHT;
        g.setColor(STATUS_BG);
        g.fillRect(0, y, w, STATUS_HEIGHT);
        g.setColor(CARD_BORDER);
        g.drawLine(0, y, w, y);

        g.setFont(FONT_STATUS);
        g.setColor(TEXT_SECONDARY);

        double wx = getWorldX(w / 2.0);
        double wz = getWorldZ(h / 2.0);
        int fps = frameMs > 0 ? (int) (1000.0 / frameMs) : 0;

        String left = String.format("  %s  |  %.1f bpp  |  %s x %s blocks",
                modeName(currentType), mscale,
                Form.f((int) (mscale * w)), Form.f((int) (mscale * h)));
        g.drawString(left, 8, y + 17);

        String right = String.format("X: %s  Z: %s  |  %d FPS  ",
                Form.f((int) wx), Form.f((int) wz), fps);
        int rw = g.getFontMetrics().stringWidth(right);
        g.drawString(right, w - rw - 8, y + 17);

        g.setColor(ACCENT);
        int modeW = g.getFontMetrics().stringWidth("  " + modeName(currentType));
        g.fillRect(0, y + 1, 3, STATUS_HEIGHT - 1);
    }

    private void renderEntities(Graphics2D g) {
        Player b = null;

        for (Player i : world.getPlayers()) {
            b = i;
            renderPlayerMarker(g, i.getLocation().getX(), i.getLocation().getZ(), i.getName());
        }

        synchronized (lastEntities) {
            double dist = Double.MAX_VALUE;
            LivingEntity nearest = null;

            for (LivingEntity i : lastEntities) {
                if (i instanceof Player) continue;
                renderMobMarker(g, i.getLocation().getX(), i.getLocation().getZ());
                if (shift) {
                    double d = i.getLocation().distanceSquared(
                            new Location(i.getWorld(), getWorldX(hx), i.getLocation().getY(), getWorldZ(hz)));
                    if (d < dist) {
                        dist = d;
                        nearest = i;
                    }
                }
            }

            if (nearest != null && shift) {
                double sx = getScreenX(nearest.getLocation().getX());
                double sz = getScreenZ(nearest.getLocation().getZ());
                g.setColor(MOB_COLOR);
                g.fillOval((int) sx - 6, (int) sz - 6, 12, 12);
                g.setColor(new Color(220, 80, 80, 60));
                g.fillOval((int) sx - 10, (int) sz - 10, 20, 20);

                KList<String> k = new KList<>();
                k.add(Form.capitalizeWords(nearest.getType().name().toLowerCase(Locale.ROOT).replaceAll("\\Q_\\E", " ")));
                k.add("Pos: " + nearest.getLocation().getBlockX() + ", " + nearest.getLocation().getBlockY() + ", " + nearest.getLocation().getBlockZ());
                k.add("HP: " + Form.f(nearest.getHealth(), 1) + " / " + Form.f(nearest.getAttribute(MAX_HEALTH).getValue(), 1));
                drawCard(w - CARD_PAD, CARD_PAD, 1, 0, g, k);
            }
        }

        player = b;
    }

    private void renderPlayerMarker(Graphics2D g, double x, double z, String name) {
        int sx = (int) getScreenX(x);
        int sz = (int) getScreenZ(z);
        g.setColor(new Color(80, 200, 120, 40));
        g.fillOval(sx - 12, sz - 12, 24, 24);
        g.setColor(PLAYER_COLOR);
        g.fillOval(sx - 5, sz - 5, 10, 10);
        g.setColor(new Color(40, 160, 80));
        g.drawOval(sx - 5, sz - 5, 10, 10);

        g.setFont(FONT_CARD_BODY);
        g.setColor(TEXT_PRIMARY);
        int nw = g.getFontMetrics().stringWidth(name);
        g.drawString(name, sx - nw / 2, sz - 14);
    }

    private void renderMobMarker(Graphics2D g, double x, double z) {
        int sx = (int) getScreenX(x);
        int sz = (int) getScreenZ(z);
        g.setColor(MOB_COLOR);
        g.fillRect(sx - 2, sz - 2, 4, 4);
    }

    private void animateTo(double wx, double wz) {
        double cx = getWorldX(getWidth() / 2.0);
        double cz = getWorldZ(getHeight() / 2.0);
        ox += ((wx - cx) / mscale) * scale;
        oz += ((wz - cz) / mscale) * scale;
    }

    private void renderHoverOverlay(Graphics2D g, boolean detailed) {
        IrisBiome biome = engine.getComplex().getTrueBiomeStream().get(getWorldX(hx), getWorldZ(hz));
        IrisRegion region = engine.getComplex().getRegionStream().get(getWorldX(hx), getWorldZ(hz));
        KList<String> l = new KList<>();
        l.add(biome.getName());
        l.add(region.getName());
        l.add("Block " + (int) getWorldX(hx) + ", " + (int) getWorldZ(hz));
        if (detailed) {
            l.add("Chunk " + ((int) getWorldX(hx) >> 4) + ", " + ((int) getWorldZ(hz) >> 4));
            l.add("Region " + (((int) getWorldX(hx) >> 4) >> 5) + ", " + (((int) getWorldZ(hz) >> 4) >> 5));
            l.add("Key: " + biome.getLoadKey());
            l.add("File: " + biome.getLoadFile());
        }
        drawCard((float) hx + 16, (float) hz, 0, 0, g, l);
    }

    private void renderOverlayDebug(Graphics2D g) {
        KList<String> l = new KList<>();
        l.add("Velocity: " + (int) velocity);
        l.add("Tiles: " + positions.size() + " HD / " + fastpositions.size() + " LQ");
        l.add("Workers: " + working.size() + " HD / " + workingfast.size() + " LQ");
        l.add("Center: " + Form.f((int) getWorldX(getWidth() / 2.0)) + ", " + Form.f((int) getWorldZ(getHeight() / 2.0)));
        drawCard(CARD_PAD, h - STATUS_HEIGHT - CARD_PAD, 0, 1, g, l);
    }

    private void renderOverlayHelp(Graphics2D g) {
        KList<String> keys = new KList<>();
        KList<String> descs = new KList<>();
        keys.add("/");      descs.add("Toggle help");
        keys.add("R");      descs.add("Refresh tiles");
        keys.add("F");      descs.add("Follow player");
        keys.add("+/-");    descs.add("Zoom in/out");
        keys.add("\\");     descs.add("Reset zoom");
        keys.add("M");      descs.add("Cycle render mode");
        keys.add("P");      descs.add("Toggle tile quality");
        keys.add("E");      descs.add("Toggle 30/60 FPS");
        keys.add("G");      descs.add("Toggle grid");

        int ff = 0;
        for (RenderType i : RenderType.values()) {
            ff++;
            keys.add(String.valueOf(ff));
            descs.add(modeName(i));
        }

        keys.add("Shift");     descs.add("Detailed biome info");
        keys.add("Ctrl+Click"); descs.add("Teleport to cursor");
        keys.add("Alt+Click");  descs.add("Open biome in editor");

        int maxKeyW = 0;
        g.setFont(FONT_HELP_KEY);
        for (String k : keys) {
            maxKeyW = Math.max(maxKeyW, g.getFontMetrics().stringWidth(k));
        }

        int lineH = 20;
        int totalH = keys.size() * lineH + CARD_PAD * 2 + 4;
        int totalW = maxKeyW + 180 + CARD_PAD * 2;

        drawCardBackground(g, CARD_PAD, CARD_PAD, totalW, totalH);

        for (int i = 0; i < keys.size(); i++) {
            int y = CARD_PAD + 16 + i * lineH;

            g.setFont(FONT_HELP_KEY);
            g.setColor(ACCENT);
            g.drawString(keys.get(i), CARD_PAD * 2, y);

            g.setFont(FONT_CARD_BODY);
            g.setColor(TEXT_SECONDARY);
            g.drawString(descs.get(i), CARD_PAD * 2 + maxKeyW + 16, y);
        }
    }

    private void renderNotification(Graphics2D g) {
        int y = h - STATUS_HEIGHT - 50;
        g.setFont(FONT_NOTIFICATION);

        KList<String> active = new KList<>();
        for (String i : notifications.k()) {
            if (M.ms() > notifications.get(i)) {
                notifications.remove(i);
            } else {
                active.add(i);
            }
        }

        if (active.isEmpty()) return;

        String text = String.join(" | ", active);
        int tw = g.getFontMetrics().stringWidth(text);
        int th = g.getFontMetrics().getHeight();
        int px = (w - tw) / 2 - 16;
        int py = y - th / 2 - 8;
        int bw = tw + 32;
        int bh = th + 16;

        drawCardBackground(g, px, py, bw, bh);
        g.setColor(TEXT_PRIMARY);
        g.drawString(text, px + 16, py + th + 4);
    }

    private void drawCardBackground(Graphics2D g, int x, int y, int w, int h) {
        RoundRectangle2D rect = new RoundRectangle2D.Double(x, y, w, h, CARD_RADIUS, CARD_RADIUS);
        g.setColor(CARD_BG);
        g.fill(rect);
        g.setColor(CARD_BORDER);
        g.draw(rect);
    }

    private void drawCard(float x, float y, double pushX, double pushZ, Graphics2D g, KList<String> text) {
        g.setFont(FONT_CARD_BODY);
        int lineH = g.getFontMetrics().getHeight();
        int cardW = 0;
        for (String i : text) {
            cardW = Math.max(cardW, g.getFontMetrics().stringWidth(i));
        }
        cardW += CARD_PAD * 2;
        int cardH = text.size() * lineH + CARD_PAD * 2 - 4;

        int cx = (int) (x - cardW * pushX);
        int cy = (int) (y - cardH * pushZ);

        drawCardBackground(g, cx, cy, cardW, cardH);

        int ty = cy + CARD_PAD + lineH - 4;
        for (int i = 0; i < text.size(); i++) {
            g.setColor(i == 0 ? TEXT_PRIMARY : TEXT_SECONDARY);
            g.setFont(i == 0 ? FONT_CARD_TITLE : FONT_CARD_BODY);
            g.drawString(text.get(i), cx + CARD_PAD, ty + i * lineH);
        }
    }

    public void mouseWheelMoved(MouseWheelEvent e) {
        int notches = e.getWheelRotation();
        if (e.isControlDown()) return;

        double m0 = mscale;
        double m1 = m0 + ((0.25 * m0) * notches);
        m1 = Math.max(m1, 0.00001);
        if (m1 == m0) return;

        positions.clear();
        fastpositions.clear();

        Point p = e.getPoint();
        double sx = p.getX();
        double sz = p.getY();

        double newOxp = scale * ((m0 / m1) * (sx + (oxp / scale)) - sx);
        double newOzp = scale * ((m0 / m1) * (sz + (ozp / scale)) - sz);

        mscale = m1;
        oxp = newOxp;
        ozp = newOzp;
        ox = oxp;
        oz = ozp;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (control) teleport();
        else if (alt) open();
    }

    @Override public void mousePressed(MouseEvent e) { }
    @Override public void mouseReleased(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }

    private void open() {
        IrisComplex complex = engine.getComplex();
        File r = null;
        switch (currentType) {
            case BIOME, LAYER_LOAD, DECORATOR_LOAD, OBJECT_LOAD, HEIGHT ->
                    r = complex.getTrueBiomeStream().get(getWorldX(hx), getWorldZ(hz)).openInVSCode();
            case BIOME_LAND -> r = complex.getLandBiomeStream().get(getWorldX(hx), getWorldZ(hz)).openInVSCode();
            case BIOME_SEA -> r = complex.getSeaBiomeStream().get(getWorldX(hx), getWorldZ(hz)).openInVSCode();
            case REGION -> r = complex.getRegionStream().get(getWorldX(hx), getWorldZ(hz)).openInVSCode();
            case CAVE_LAND -> r = complex.getCaveBiomeStream().get(getWorldX(hx), getWorldZ(hz)).openInVSCode();
        }
        if (r != null) {
            notify("Opened " + r.getName());
        }
    }

    private void teleport() {
        J.s(() -> {
            if (player != null) {
                int xx = (int) getWorldX(hx);
                int zz = (int) getWorldZ(hz);
                int yy = player.getWorld().getHighestBlockYAt(xx, zz) + 1;
                player.teleport(new Location(player.getWorld(), xx, yy, zz));
                notify("Teleported to " + xx + ", " + yy + ", " + zz);
            } else {
                notify("No player in world");
            }
        });
    }
}
