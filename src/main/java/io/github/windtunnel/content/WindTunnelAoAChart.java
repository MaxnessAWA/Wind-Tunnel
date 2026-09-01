package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Self-drawn line chart for the mount auto-test page:
 * X axis = angle of attack (deg), Y axis = force/moment samples with auto scaling.
 * <p>
 * Features: smooth line-strip rendering, hover highlight with a value tooltip and clickable
 * legend entries to toggle each series. LDLib2 has no data-line chart widget, so the chart is
 * painted manually on top of the panel.
 */
final class WindTunnelAoAChart extends UIElement {

    /** One measured point of an angle-of-attack sweep. */
    record Sample(double alpha, double lift, double drag, double pitchMoment) {
    }

    private enum Series {
        LIFT, DRAG, PITCH
    }

    private record LegendHitbox(int x, int y, int width, int height, Series series) {
        boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private static final int COLOR_LIFT = 0xFF2E5E8C;
    private static final int COLOR_DRAG = 0xFF9C3B2E;
    private static final int COLOR_PITCH = 0xFF7A3FA8;
    private static final int COLOR_GRID = 0xFFCFC3A2;
    private static final int COLOR_AXIS = 0xFF6B5F45;
    private static final int COLOR_HIDDEN = 0xFF8B8378;
    private static final float LINE_HALF_WIDTH = 0.77F;

    private static final int PLOT_LEFT = 44;
    private static final int PLOT_RIGHT = 10;
    private static final int PLOT_TOP = 28;
    private static final int PLOT_BOTTOM = 20;
    private static final int HOVER_RADIUS = 10;

    private final List<Sample> samples = new ArrayList<>();
    private final EnumSet<Series> hiddenSeries = EnumSet.noneOf(Series.class);
    private final List<LegendHitbox> legendHitboxes = new ArrayList<>();
    private int hoveredIndex = -1;

    WindTunnelAoAChart() {
        addEventListener(UIEvents.MOUSE_DOWN, this::handleClick);
    }

    public void setData(List<Sample> data) {
        samples.clear();
        if (data != null) {
            samples.addAll(data);
        }
        hoveredIndex = -1;
    }

    public void clear() {
        samples.clear();
        hoveredIndex = -1;
    }

    @Override
    public void drawBackgroundAdditional(@NotNull GUIContext ctx) {
        GuiGraphics graphics = ctx.graphics;
        Font font = Objects.requireNonNull(Minecraft.getInstance().font);
        // The GUI context does not translate to the element origin; shift the pose so all
        // drawing below uses element-local coordinates (mirrors the diagram element).
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(getPositionX(), getPositionY(), 0.0F);
        drawChart(graphics, font, ctx.mouseX, ctx.mouseY);
        pose.popPose();
    }

    @Override
    public void drawContents(@NotNull GUIContext ctx) {
        super.drawContents(ctx);
        if (hoveredIndex >= 0 && hoveredIndex < samples.size()) {
            Sample sample = samples.get(hoveredIndex);
            ctx.graphics.renderComponentTooltip(
                    Objects.requireNonNull(Minecraft.getInstance().font),
                    List.of(
                            Objects.requireNonNull(Component.translatable(
                                    "block.windtunnel.wind_tunnel_mount.test_chart_tooltip_alpha", formatValue(sample.alpha()))),
                            Objects.requireNonNull(Component.translatable(
                                    "block.windtunnel.wind_tunnel_mount.test_chart_tooltip_lift", formatValue(sample.lift()))),
                            Objects.requireNonNull(Component.translatable(
                                    "block.windtunnel.wind_tunnel_mount.test_chart_tooltip_drag", formatValue(sample.drag()))),
                            Objects.requireNonNull(Component.translatable(
                                    "block.windtunnel.wind_tunnel_mount.test_chart_tooltip_pitch", formatValue(sample.pitchMoment())))
                    ),
                    ctx.mouseX, ctx.mouseY);
        }
    }

    private void handleClick(UIEvent event) {
        int localX = (int) (event.x - getPositionX());
        int localY = (int) (event.y - getPositionY());
        for (LegendHitbox hitbox : legendHitboxes) {
            if (hitbox.contains(localX, localY)) {
                toggleSeries(hitbox.series());
                return;
            }
        }
    }

    private void toggleSeries(Series series) {
        if (!hiddenSeries.add(series)) {
            hiddenSeries.remove(series);
        }
    }

    private boolean isSeriesVisible(Series series) {
        return !hiddenSeries.contains(series);
    }

    private void drawChart(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int width = Math.round(getSizeWidth());
        int height = Math.round(getSizeHeight());

        graphics.fill(0, 0, width, height, WindTunnelLdlib2Theme.PANEL_BACKGROUND);
        graphics.fill(0, 0, width, 1, WindTunnelLdlib2Theme.SECTION_LINE);
        graphics.fill(0, height - 1, width, height, WindTunnelLdlib2Theme.SECTION_LINE);

        graphics.drawString(font, Objects.requireNonNull(Component.translatable(
                "block.windtunnel.wind_tunnel_mount.test_chart")), 6, 4, WindTunnelLdlib2Theme.TEXT, false);

        drawLegend(graphics, font, 8, 14);

        if (samples.isEmpty()) {
            hoveredIndex = -1;
            Component empty = Objects.requireNonNull(Component.translatable(
                    "block.windtunnel.wind_tunnel_mount.test_chart_empty"));
            int tw = font.width(empty);
            graphics.drawString(font, empty, Math.max(0, (width - tw) / 2), height / 2 - 4, WindTunnelLdlib2Theme.TEXT, false);
            return;
        }

        hoveredIndex = resolveHoveredIndex(mouseX, mouseY);
        drawPlot(graphics, font, width, height, mouseX, mouseY);
    }

    private void drawLegend(GuiGraphics graphics, Font font, int x, int y) {
        legendHitboxes.clear();
        int cursor = x;
        cursor = drawLegendItem(graphics, font, cursor, y, Series.LIFT, COLOR_LIFT,
                Component.translatable("block.windtunnel.wind_tunnel_mount.test_chart_lift"));
        cursor += 12;
        cursor = drawLegendItem(graphics, font, cursor, y, Series.DRAG, COLOR_DRAG,
                Component.translatable("block.windtunnel.wind_tunnel_mount.test_chart_drag"));
        cursor += 12;
        drawLegendItem(graphics, font, cursor, y, Series.PITCH, COLOR_PITCH,
                Component.translatable("block.windtunnel.wind_tunnel_mount.test_chart_pitch"));
    }

    private int drawLegendItem(GuiGraphics graphics, Font font, int x, int y, Series series, int color, Component name) {
        boolean visible = isSeriesVisible(series);
        if (visible) {
            graphics.fill(x, y, x + 7, y + 7, color);
        } else {
            graphics.fill(x, y, x + 7, y + 7, WindTunnelLdlib2Theme.PANEL_BACKGROUND);
            graphics.fill(x, y, x + 7, y + 1, COLOR_HIDDEN);
            graphics.fill(x, y + 6, x + 7, y + 7, COLOR_HIDDEN);
            graphics.fill(x, y, x + 1, y + 7, COLOR_HIDDEN);
            graphics.fill(x + 6, y, x + 7, y + 7, COLOR_HIDDEN);
        }
        int textColor = visible ? WindTunnelLdlib2Theme.TEXT : COLOR_HIDDEN;
        graphics.drawString(font, name, x + 10, y - 1, textColor, false);
        int itemWidth = 10 + font.width(name);
        legendHitboxes.add(new LegendHitbox(x, y - 2, itemWidth, 12, series));
        return x + itemWidth;
    }

    private void drawPlot(GuiGraphics graphics, Font font, int width, int height, int mouseX, int mouseY) {
        double minAlpha = Double.MAX_VALUE;
        double maxAlpha = -Double.MAX_VALUE;
        double maxAbs = 0.0D;
        for (Sample s : samples) {
            minAlpha = Math.min(minAlpha, s.alpha());
            maxAlpha = Math.max(maxAlpha, s.alpha());
            maxAbs = Math.max(maxAbs, Math.abs(s.lift()));
            maxAbs = Math.max(maxAbs, Math.abs(s.drag()));
            maxAbs = Math.max(maxAbs, Math.abs(s.pitchMoment()));
        }
        if (maxAlpha - minAlpha < 1.0E-6D) {
            minAlpha -= 1.0D;
            maxAlpha += 1.0D;
        }
        if (maxAbs < 1.0E-6D) {
            maxAbs = 1.0D;
        }
        double spanA = maxAlpha - minAlpha;
        double spanV = maxAbs * 2.0D;

        int x0 = PLOT_LEFT;
        int x1 = width - PLOT_RIGHT;
        int y0 = PLOT_TOP;
        int y1 = height - PLOT_BOTTOM;

        // grid + Y labels (symmetric around zero)
        for (int div = 0; div <= 4; div++) {
            double value = maxAbs - spanV * div / 4.0D;
            int y = (int) Math.round(y0 + (y1 - y0) * div / 4.0D);
            graphics.fill(x0, y, x1, y + 1, COLOR_GRID);
            graphics.drawString(font, String.format(Locale.ROOT, "%.1f", value),
                    2, y - 4, COLOR_AXIS, false);
        }
        // vertical grid at a few alpha ticks
        int tickCount = Math.min(6, Math.max(2, (int) Math.ceil((maxAlpha - minAlpha) / 10.0D) + 1));
        for (int i = 0; i < tickCount; i++) {
            double alpha = minAlpha + spanA * i / (tickCount - 1);
            int x = (int) Math.round(x0 + (x1 - x0) * (alpha - minAlpha) / spanA);
            graphics.fill(x, y0, x + 1, y1, COLOR_GRID);
            String label = String.format(Locale.ROOT, "%.0f", alpha);
            graphics.drawString(font, label, x - font.width(label) / 2, y1 + 4, COLOR_AXIS, false);
        }
        // zero line
        int zeroY = mapY(0.0D, maxAbs, spanV, y0, y1);
        graphics.fill(x0, zeroY, x1, zeroY + 1, COLOR_AXIS);

        // data series as smooth line strips (only the visible ones)
        if (isSeriesVisible(Series.LIFT)) {
            drawSeries(graphics, Series.LIFT, s -> s.lift(), minAlpha, spanA, spanV, maxAbs, x0, x1, y0, y1);
        }
        if (isSeriesVisible(Series.DRAG)) {
            drawSeries(graphics, Series.DRAG, s -> s.drag(), minAlpha, spanA, spanV, maxAbs, x0, x1, y0, y1);
        }
        if (isSeriesVisible(Series.PITCH)) {
            drawSeries(graphics, Series.PITCH, s -> s.pitchMoment(), minAlpha, spanA, spanV, maxAbs, x0, x1, y0, y1);
        }

        drawHoverMarker(graphics, font, minAlpha, spanA, spanV, maxAbs, x0, x1, y0, y1);
    }

    private void drawSeries(GuiGraphics graphics, Series series,
                            java.util.function.ToDoubleFunction<Sample> value,
                            double minAlpha, double spanA, double spanV, double maxAbs,
                            int x0, int x1, int y0, int y1) {
        List<Vector2f> points = new ArrayList<>(samples.size());
        for (Sample s : samples) {
            points.add(new Vector2f(
                    mapX(s.alpha(), minAlpha, spanA, x0, x1),
                    mapY(value.applyAsDouble(s), maxAbs, spanV, y0, y1)));
        }
        if (points.size() >= 2) {
            DrawerHelper.drawLines(graphics, points, seriesColor(series), seriesColor(series), LINE_HALF_WIDTH);
        }
        // sample markers
        for (Vector2f point : points) {
            int px = Math.round(point.x);
            int py = Math.round(point.y);
            graphics.fill(px - 1, py - 1, px + 2, py + 2, seriesColor(series));
        }
    }

    private void drawHoverMarker(GuiGraphics graphics, Font font,
                                 double minAlpha, double spanA, double spanV, double maxAbs,
                                 int x0, int x1, int y0, int y1) {
        if (hoveredIndex < 0 || hoveredIndex >= samples.size()) {
            return;
        }
        Sample sample = samples.get(hoveredIndex);
        int markerX = mapX(sample.alpha(), minAlpha, spanA, x0, x1);
        graphics.fill(markerX, y0, markerX + 1, y1, COLOR_AXIS);

        drawSeriesPointMarker(graphics, sample.lift(), Series.LIFT, minAlpha, spanA, spanV, maxAbs, x0, x1, y0, y1);
        drawSeriesPointMarker(graphics, sample.drag(), Series.DRAG, minAlpha, spanA, spanV, maxAbs, x0, x1, y0, y1);
        drawSeriesPointMarker(graphics, sample.pitchMoment(), Series.PITCH, minAlpha, spanA, spanV, maxAbs, x0, x1, y0, y1);

        // Readout near the marker.
        String alphaText = formatValue(sample.alpha()) + "°";
        String liftText = formatValue(sample.lift());
        String dragText = formatValue(sample.drag());
        String pitchText = formatValue(sample.pitchMoment());
        int textWidth = Math.max(font.width(alphaText), Math.max(font.width(liftText), Math.max(font.width(dragText), font.width(pitchText))));
        int boxX = Math.max(2, Math.min(markerX - textWidth / 2, x1 - textWidth - 2));
        int firstY = mapY(sample.lift(), maxAbs, spanV, y0, y1);
        int boxY = Math.max(y0 + 2, firstY - 44);
        graphics.fill(boxX - 3, boxY - 3, boxX + textWidth + 3, boxY + 43, 0xE8F7F0DD);
        graphics.fill(boxX - 3, boxY - 3, boxX + textWidth + 3, boxY - 2, WindTunnelLdlib2Theme.SECTION_LINE);
        graphics.drawString(font, alphaText, boxX, boxY, COLOR_AXIS, false);
        graphics.drawString(font, liftText, boxX, boxY + 10, COLOR_LIFT, false);
        graphics.drawString(font, dragText, boxX, boxY + 20, COLOR_DRAG, false);
        graphics.drawString(font, pitchText, boxX, boxY + 30, COLOR_PITCH, false);
    }

    private void drawSeriesPointMarker(GuiGraphics graphics, double value, Series series,
                                       double minAlpha, double spanA, double spanV, double maxAbs,
                                       int x0, int x1, int y0, int y1) {
        if (!isSeriesVisible(series)) {
            return;
        }
        int px = mapX(samples.get(hoveredIndex).alpha(), minAlpha, spanA, x0, x1);
        int py = mapY(value, maxAbs, spanV, y0, y1);
        graphics.fill(px - 2, py - 2, px - 1, py + 3, 0xFFFFFFFF);
        graphics.fill(px + 2, py - 2, px + 3, py + 3, 0xFFFFFFFF);
        graphics.fill(px - 2, py - 2, px + 3, py - 1, 0xFFFFFFFF);
        graphics.fill(px - 2, py + 2, px + 3, py + 3, 0xFFFFFFFF);
        graphics.fill(px - 1, py - 1, px + 2, py + 2, seriesColor(series));
    }

    private static int seriesColor(Series series) {
        return switch (series) {
            case LIFT -> COLOR_LIFT;
            case DRAG -> COLOR_DRAG;
            case PITCH -> COLOR_PITCH;
        };
    }

    private int resolveHoveredIndex(int mouseX, int mouseY) {
        if (samples.isEmpty()) {
            return -1;
        }
        int localX = (int) (mouseX - getPositionX());
        int localY = (int) (mouseY - getPositionY());
        int width = Math.round(getSizeWidth());
        int height = Math.round(getSizeHeight());
        int x0 = PLOT_LEFT;
        int x1 = width - PLOT_RIGHT;
        if (localX < x0 - HOVER_RADIUS || localX > x1 + HOVER_RADIUS
                || localY < PLOT_TOP - HOVER_RADIUS || localY > height - PLOT_BOTTOM + HOVER_RADIUS) {
            return -1;
        }
        double minAlpha = Double.MAX_VALUE;
        double maxAlpha = -Double.MAX_VALUE;
        for (Sample s : samples) {
            minAlpha = Math.min(minAlpha, s.alpha());
            maxAlpha = Math.max(maxAlpha, s.alpha());
        }
        if (maxAlpha - minAlpha < 1.0E-6D) {
            minAlpha -= 1.0D;
            maxAlpha += 1.0D;
        }
        double spanA = maxAlpha - minAlpha;
        int nearest = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < samples.size(); i++) {
            int px = mapX(samples.get(i).alpha(), minAlpha, spanA, x0, x1);
            double distance = Math.abs(px - localX);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = i;
            }
        }
        return bestDistance <= HOVER_RADIUS ? nearest : -1;
    }

    private static int mapX(double alpha, double minAlpha, double spanA, int x0, int x1) {
        return (int) Math.round(x0 + (x1 - x0) * (alpha - minAlpha) / spanA);
    }

    private static int mapY(double value, double maxAbs, double spanV, int y0, int y1) {
        return (int) Math.round(y0 + (y1 - y0) * (maxAbs - value) / spanV);
    }

    private static String formatValue(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
