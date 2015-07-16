/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2015 phrack
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.camera;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.config.Configuration;
import com.shootoff.gui.CanvasManager;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.paint.Color;

public class ShotSearcher implements Runnable {
	public static final int SECTOR_COLUMNS = 3;
	public static final int SECTOR_ROWS = 3;

	private final Logger logger = LoggerFactory.getLogger(ShotSearcher.class);
	private final Configuration config;
	private final CanvasManager canvasManager;
	private final boolean[][] sectorStatuses;
	private final BufferedImage currentFrame;
	private final BufferedImage threshed;
	private final Optional<Bounds> projectionBounds;

    // We only detect a color if the largest component is at least
    // 5% bigger than the other components. This is based on the
    // heuristic that noise tends to have color values that are very
    // similar
	private double colorDiffThreshold = 1.05;
	private int borderWidth = 3; // px
	private int minShotDim = 6; // px

	public ShotSearcher(Configuration config, CanvasManager canvasManager, boolean[][] sectorStatuses,
			BufferedImage currentFrame, BufferedImage threshed, Optional<Bounds> projectionBounds) {
		this.config = config;
		this.canvasManager = canvasManager;
		this.sectorStatuses = sectorStatuses;
		this.currentFrame = currentFrame;
		this.threshed = threshed;
		this.projectionBounds = projectionBounds;
	}

	public void setColorDiffThreshold(double threshold) {
		colorDiffThreshold = threshold;
	}

	public void setCenterApproxBorderSize(int width) {
		borderWidth = width;
	}

	public void setMinimumShotDimension(int minDim) {
		minShotDim = minDim;
	}

	@Override
	public void run() {
		// Split the image into x columns and y rows, and search
		// each independently
		int subWidth = threshed.getWidth() / SECTOR_COLUMNS;
		int subHeight = threshed.getHeight() / SECTOR_ROWS;

		for (int startY = 0, sectorY = 0; sectorY < SECTOR_ROWS;
				startY += subHeight, sectorY++) {
			for (int startX = 0, sectorX = 0; sectorX < SECTOR_COLUMNS;
					startX += subWidth, sectorX++) {

				// Don't detect a shot in a sector that is turned off
				if (sectorStatuses[sectorY][sectorX])
					findShot(startX, startX + subWidth, startY, startY + subHeight);
			}
		}
	}

	private void findShot(int startX, int endX, int startY, int endY) {
		for (int x = startX; x < endX; x++) {
			for (int y = startY; y < endY; y++) {
				if (threshed.getRGB(x, y) == java.awt.Color.WHITE.getRGB()) {
					Optional<Color> areaColor = detectColor(x, y);
					if (areaColor.isPresent()) {
						if (config.ignoreLaserColor() && config.getIgnoreLaserColor().isPresent() &&
								areaColor.get().equals(config.getIgnoreLaserColor().get()))
									continue;

						Optional<Point2D> center = approximateCenter(x, y);

						if (center.isPresent()) {
							/*try {
								ImageIO.write(currentFrame, "PNG", new File(System.nanoTime() + ".png"));
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}*/
							
							logger.debug("Suspected shot accepted: Original Coords ({}, {}), Center ({}, {}), {}",
									x, y, center.get().getX(),
									center.get().getY(), areaColor.get());

							if (projectionBounds.isPresent()) {
								Bounds b = projectionBounds.get();
								canvasManager.addShot(areaColor.get(), center.get().getX() + b.getMinX(),
										center.get().getY() + b.getMinY());
							} else {
								canvasManager.addShot(areaColor.get(), center.get().getX(),
										center.get().getY());
							}
							return;
						}
					}
				}
			}
		}
	}

	private Optional<Color> detectColor(int x, int y) {
		int rgb = currentFrame.getRGB(x, y);
		double r = getRed(rgb);
		double g = getGreen(rgb);
		double b = getBlue(rgb);

		final int colorDetectionRadius = 5;
		int pixelsSeen = 1;

		// Average colorDetectionRadius pixels left
		for (int x_offset = x; x_offset > 0 && x - x_offset < colorDetectionRadius;
				x_offset--) {

			rgb = currentFrame.getRGB(x_offset, y);
			r += getRed(rgb);
			g += getGreen(rgb);
			b += getBlue(rgb);
			pixelsSeen++;
		}

		// Average colorDetectionRadius pixels right
		for (int x_offset = x;
				x_offset < currentFrame.getWidth() && x_offset - x < colorDetectionRadius;
				x_offset++) {

			rgb = currentFrame.getRGB(x_offset, y);
			r += getRed(rgb);
			g += getGreen(rgb);
			b += getBlue(rgb);
			pixelsSeen++;
		}

		// Average colorDetectionRadius pixels up
		for (int y_offset = y;
				y_offset < currentFrame.getHeight() && y_offset - y < colorDetectionRadius;
				y_offset++) {

			rgb = currentFrame.getRGB(x, y_offset);
			r += getRed(rgb);
			g += getGreen(rgb);
			b += getBlue(rgb);
			pixelsSeen++;
		}

		// Average colorDetectionRadius pixels down
		for (int y_offset = y;
				y_offset > 0 && y - y_offset < colorDetectionRadius;
				y_offset--) {

			rgb = currentFrame.getRGB(x, y_offset);
			r += getRed(rgb);
			g += getGreen(rgb);
			b += getBlue(rgb);
			pixelsSeen++;
		}

		r /= (double)pixelsSeen;
		g /= (double)pixelsSeen;
		b /= (double)pixelsSeen;

        if (g == 0 || b == 0)
            return Optional.empty();

        if ((r / g) > colorDiffThreshold && (r / b) > colorDiffThreshold)
            return Optional.of(Color.RED);

        if (r == 0 || b == 0)
        	return Optional.empty();

        if ((g / r) > colorDiffThreshold && (g / b) > colorDiffThreshold)
            return Optional.of(Color.GREEN);


		return Optional.empty();
	}

	private int getRed(int rgb) {
		return (rgb & 0x00ff0000) >> 16;
	}

	private int getGreen(int rgb) {
		return (rgb & 0x0000ff00) >> 8;
	}

	private int getBlue(int rgb) {
		return (rgb & 0x000000ff) >> 0;
	}

	/**
	 * Find the approximate center of the shot given initial coordinates.
	 *
	 * @param x	initial x coordinate of the shot location
	 * @param y initial y coordinate of the shot location
	 * @return the approximate center of the shot
	 */
	private Optional<Point2D> approximateCenter(double x, double y) {
		double minX = x, minY = y;
		double maxX = x, maxY = y;

		// We need to see a certain number of black pixels because the shot
		// does not have sharp borders (we may hit a black pixel right away
		// even though it's not the read edge otherwise)
		int blackCount = 0;

		for (;maxY < threshed.getHeight(); maxY++) {
			if (threshed.getRGB((int)maxX, (int)maxY) == java.awt.Color.BLACK.getRGB())
				blackCount++; else blackCount = 0;
			if (blackCount == borderWidth) break;
		}

		blackCount = 0;
		minY -= borderWidth;
		double shotHeight = maxY - minY;
		double centerY = minY + (shotHeight / 2);

		for (;maxX < threshed.getWidth(); maxX++) {
			if (threshed.getRGB((int)maxX, (int)centerY) == java.awt.Color.BLACK.getRGB())
				blackCount++; else blackCount = 0;
			if (blackCount == borderWidth) break;
		}

		maxX -= borderWidth;

		double shotWidth = maxX - minX;
		double centerX = minX + (shotWidth / 2);

		// If the width and height of the shot are really small it's a false positive
		if (shotWidth < minShotDim && shotHeight < minShotDim) {
			logger.debug("Suspected shot rejected: Dimensions Too Small "
					+ "(x={}, y={}, width={} height={} min={})", x, y, shotWidth, shotHeight, minShotDim);
			return Optional.empty();
		}

		return Optional.of(new Point2D(centerX, centerY));
	}
}