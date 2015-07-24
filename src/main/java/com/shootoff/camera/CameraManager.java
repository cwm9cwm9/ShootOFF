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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.config.Configuration;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.DebuggerListener;
import com.xuggle.mediatool.IMediaReader;
import com.xuggle.mediatool.IMediaWriter;
import com.xuggle.mediatool.MediaListenerAdapter;
import com.xuggle.mediatool.ToolFactory;
import com.xuggle.mediatool.event.ICloseEvent;
import com.xuggle.mediatool.event.IVideoPictureEvent;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IVideoPicture;
import com.xuggle.xuggler.video.ConverterFactory;
import com.xuggle.xuggler.video.IConverter;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.image.Image;


public class CameraManager {
	public static final int HISTORY_SIZE = 30;
	public static final float HISTORY_SIZE_FLOAT = (float)HISTORY_SIZE;
	public static final int FEED_WIDTH = 640;
	public static final int FEED_HEIGHT = 480;
	public static final int MIN_SHOT_DETECTION_FPS = 5;

	// These thresholds were calculated using all of the test videos
	public static final int LIGHTING_CONDITION_VERY_BRIGHT_THRESHOLD = 130;
	// Anything below this threshold is considered dark
	public static final int LIGHTING_CONDITION__BRIGHT_THRESHOLD = 90;

	public static final float IDEAL_R_AVERAGE = 171; // Determined by averaging all of the red pixels per frame
												     // for a video recorded using a webcam with hw settings that
												     // worked well
	public static final float IDEAL_LUM = 136;		 // See comment above
	public static final int INIT_FRAME_COUNT = 5; // Used by current pixel transformer to decide how many frames
												  // to use for initialization

	private final PixelTransformer pixelTransformer = new BrightnessPixelTransformer();

	private final Logger logger = LoggerFactory.getLogger(CameraManager.class);
	private final Optional<Camera> webcam;
	private final Object processingLock;
	private boolean processedVideo = false;
	private final CanvasManager canvasManager;
	private final Configuration config;
	private Optional<Bounds> projectionBounds = Optional.empty();

	private boolean isStreaming = true;
	private boolean isDetecting = true;
	private boolean cropFeedToProjection = false;
	private boolean limitDetectProjection = false;
	private Optional<Integer> centerApproxBorderSize = Optional.empty();
	private Optional<Integer> minimumShotDimension = Optional.empty();
	private Optional<DebuggerListener> debuggerListener = Optional.empty();

	private boolean recording = false;
	private boolean isFirstFrame = true;
	private IMediaWriter videoWriter;
	private long recordingStartTime;
	private boolean[][] sectorStatuses;

	protected CameraManager(Camera webcam, CanvasManager canvas, Configuration config) {
		this.webcam = Optional.of(webcam);
		processingLock = null;
		this.canvasManager = canvas;
		this.config = config;

		init(new Detector());
	}

	protected CameraManager(File videoFile, CanvasManager canvas, Configuration config) {
		this.webcam = Optional.empty();
		processingLock = null;
		this.canvasManager = canvas;
		this.config = config;

		init(new Detector(videoFile));

	}

	protected CameraManager(File videoFile, Object processingLock, CanvasManager canvas,
			Configuration config, boolean[][] sectorStatuses, Optional<Bounds> projectionBounds) {
		this.webcam = Optional.empty();
		this.processingLock = processingLock;
		this.canvasManager = canvas;
		this.config = config;

		if (projectionBounds.isPresent()) {
			setLimitDetectProjection(true);
			setProjectionBounds(projectionBounds.get());
		}

		Detector detector = new Detector();

	    IMediaReader reader = ToolFactory.makeReader(videoFile.getAbsolutePath());
	    reader.setBufferedImageTypeToGenerate(BufferedImage.TYPE_3BYTE_BGR);
	    reader.addListener(detector);

	    setSectorStatuses(sectorStatuses);

	    while (reader.readPacket() == null)
	      do {} while(false);
	}

	private void init(Detector detector) {
		sectorStatuses = new boolean[ShotSearcher.SECTOR_ROWS][ShotSearcher.SECTOR_COLUMNS];

		// Turn on all shot sectors by default
		for (int x = 0; x < ShotSearcher.SECTOR_COLUMNS; x++) {
			for (int y = 0; y < ShotSearcher.SECTOR_ROWS; y++) {
				sectorStatuses[y][x] = true;
			}
		}

		new Thread(detector).start();
	}

	public boolean[][] getSectorStatuses() {
		return sectorStatuses;
	}

	public void setSectorStatuses(boolean[][] sectorStatuses) {
		this.sectorStatuses = sectorStatuses;
	}

	public void clearShots() {
		canvasManager.clearShots();
	}

	public void reset() {
		canvasManager.reset();
	}

	public void close() {
		if (webcam.isPresent()) webcam.get().close();
		if (recording) stopRecording();
	}

	public void setStreaming(boolean isStreaming) {
		this.isStreaming = isStreaming;
	}

	public void setDetecting(boolean isDetecting) {
		this.isDetecting = isDetecting;
	}

	public void setProjectionBounds(Bounds projectionBounds) {
		this.projectionBounds = Optional.ofNullable(projectionBounds);
	}

	public void setCropFeedToProjection(boolean cropFeed) {
		cropFeedToProjection = cropFeed;
	}

	public void setLimitDetectProjection(boolean limitDetection) {
		limitDetectProjection = limitDetection;
	}

	public void startRecording(File videoFile) {
		logger.debug("Writing Video Feed To: {}", videoFile.getAbsoluteFile());
		videoWriter = ToolFactory.makeWriter(videoFile.getName());
		videoWriter.addVideoStream(0, 0, ICodec.ID.CODEC_ID_H264, FEED_WIDTH, FEED_HEIGHT);
		recordingStartTime = System.currentTimeMillis();
		isFirstFrame = true;

		recording = true;
	}

	public void stopRecording() {
		recording = false;
		videoWriter.close();
	}

	public Image getCurrentFrame() {
		if (webcam.isPresent()) {
			return SwingFXUtils.toFXImage(webcam.get().getImage(), null);
		} else {
			return null;
		}
	}

	public CanvasManager getCanvasManager() {
		return canvasManager;
	}

	public boolean isVideoProcessed() {
		return processedVideo;
	}

	public void setCenterApproxBorderSize(int borderSize) {
		centerApproxBorderSize = Optional.of(borderSize);
		logger.debug("Set the shot center approximation border size to: {}", borderSize);
	}

	public void setMinimumShotDimension(int minDim) {
		minimumShotDimension = Optional.of(minDim);
		logger.debug("Set the minimum dimension for shots to: {}", minDim);
	}

	public void setThresholdListener(DebuggerListener thresholdListener) {
		this.debuggerListener = Optional.ofNullable(thresholdListener);
	}

	private class Detector extends MediaListenerAdapter implements Runnable {
		private boolean showedFPSWarning = false;
		private boolean pixelTransformerInitialized = false;
		private int seenFrames = 0;
		private final ExecutorService detectionExecutor = Executors.newFixedThreadPool(200);

		private BufferedImage[] frameHistory = new BufferedImage[HISTORY_SIZE];
		private int[][][] variance_historyR = new int [640][480][HISTORY_SIZE];
		private int[][][] variance_historyG = new int [640][480][HISTORY_SIZE];
		private int[][][] variance_historyB = new int [640][480][HISTORY_SIZE];
		private int[][] pixelSumOverTimeR = new int [640][480];
		private int[][] pixelSumOverTimeG = new int [640][480];
		private int[][] pixelSumOverTimeB = new int [640][480];
		private int[][] varianceSumOverTimeR = new int [640][480];
		private int[][] varianceSumOverTimeG = new int [640][480];
		private int[][] varianceSumOverTimeB = new int [640][480];
		private float[][] meanOfR = new float [640][480];
		private float[][] meanOfG = new float [640][480];
		private float[][] meanOfB = new float [640][480];
	    float[][] shotTransform = new float [640][480];

		private int historyIndex;
		private boolean historyReady;

		private File videoFile;
		private IMediaReader reader;

		public Detector()
		{
			for (int i=0;i<HISTORY_SIZE;i++) {
				frameHistory[i] = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
			}

			historyReady = false;
		}

		public Detector(File inVideoFile) {
			this();
			videoFile = inVideoFile;
			reader = ToolFactory.makeReader(videoFile.getAbsolutePath());
			reader.setBufferedImageTypeToGenerate(BufferedImage.TYPE_3BYTE_BGR);
			reader.addListener(this);
		}

		@Override
		public void run() {

			if (webcam.isPresent()) {
				if (!webcam.get().isOpen()) {
					webcam.get().setViewSize(new Dimension(FEED_WIDTH, FEED_HEIGHT));
					webcam.get().open();
				}

				streamCameraFrames();
			}
			else
			{
				while (reader.readPacket() == null);
			}
		}

		@Override
		/**
		 * From the MediaListenerAdapter. This method is used to get a new frame
		 * from a video that is being played back in a unit test, not to get
		 * a frame from the webcam.
		 */
		public void onVideoPicture(IVideoPictureEvent event)
		{
			//frameProcessing=true;
			BufferedImage currentFrame = event.getImage();
			ProcessImage(currentFrame);
		}

		@Override
		public void onClose(ICloseEvent event) {
			synchronized (processingLock) {
				processedVideo = true;
				processingLock.notifyAll();
			}

			detectionExecutor.shutdown();
		}

		private void streamCameraFrames() {

			while (isStreaming) {
				if (!webcam.isPresent() || !webcam.get().isImageNew()) continue;

				BufferedImage currentFrame = webcam.get().getImage();

				if (currentFrame == null && webcam.isPresent() && !webcam.get().isOpen()) {
					showMissingCameraError();
					detectionExecutor.shutdown();
					return;
				}
                ProcessImage(currentFrame);
			}


			detectionExecutor.shutdown();
		}

		private void ProcessImage(BufferedImage currentFrame) {
			long startDetectionCycle = System.currentTimeMillis();

			final AverageFrameComponents averages = averageFrameComponents(currentFrame);

			if (pixelTransformerInitialized == false) {
				seenFrames++;
				if (seenFrames == INIT_FRAME_COUNT) {
					if (averages.getLightingCondition() == LightingCondition.VERY_BRIGHT) {
						showBrightnessWarning();
					}

					pixelTransformerInitialized = true;
				} else {
					return;
				}
			}

			if (recording) {
				BufferedImage image = ConverterFactory.convertToType(currentFrame, BufferedImage.TYPE_3BYTE_BGR);
				IConverter converter = ConverterFactory.createConverter(image, IPixelFormat.Type.YUV420P);

				IVideoPicture frame = converter.toPicture(image,
						(System.currentTimeMillis() - recordingStartTime) * 1000);
				frame.setKeyFrame(isFirstFrame);
				frame.setQuality(0);
				isFirstFrame = false;

				videoWriter.encodeVideo(0, frame);
			}

			if (cropFeedToProjection && projectionBounds.isPresent()) {
				Bounds b = projectionBounds.get();
				currentFrame = currentFrame.getSubimage((int)b.getMinX(), (int)b.getMinY(),
						(int)b.getWidth(), (int)b.getHeight());
			}

			Image img = SwingFXUtils.toFXImage(currentFrame, null);

			if (cropFeedToProjection) {
				canvasManager.updateBackground(img, projectionBounds);
			} else {
				canvasManager.updateBackground(img, Optional.empty());
			}

			final BufferedImage frame = currentFrame;
			detectShotsNew(frame, averages);
			if (System.currentTimeMillis() -
					startDetectionCycle >= config.getDetectionRate()) {

				startDetectionCycle = System.currentTimeMillis();
				//detectionExecutor.submit(new Thread(() -> {detectShotsNew(frame, averages);}));
			}
		}

		private class AverageFrameComponents {
			private final float averageLum;
			private final float averageRed;

			public AverageFrameComponents(float lum, float red) {
				averageLum = lum; averageRed = red;
			}

			public float getAverageRed() {
				return averageRed;
			}

			public LightingCondition getLightingCondition() {
				if (averageLum > LIGHTING_CONDITION_VERY_BRIGHT_THRESHOLD) {
					return LightingCondition.VERY_BRIGHT;
				} else if (averageLum > LIGHTING_CONDITION__BRIGHT_THRESHOLD) {
					return LightingCondition.BRIGHT;
				} else {
					return LightingCondition.DARK;
				}
			}
		}

		private AverageFrameComponents averageFrameComponents(BufferedImage frame) {
			long totalLum = 0;
			long totalRed = 0;

			int minX;
			int maxX;
			int minY;
			int maxY;

			if (limitDetectProjection && projectionBounds.isPresent()) {
				minX = (int)projectionBounds.get().getMinX();
				maxX = (int)projectionBounds.get().getMaxX();
				minY = (int)projectionBounds.get().getMinY();
				maxY = (int)projectionBounds.get().getMaxY();
			} else {
				minX = 0;
				maxX = frame.getWidth();
				minY = 0;
				maxY = frame.getHeight();
			}

			for (int x = minX; x < maxX; x++) {
				for (int y = minY; y < maxY; y++) {
					Color c = new Color(frame.getRGB(x, y));

					pixelTransformer.updateFilter(x, y, c);

					totalLum += (c.getRed() + c.getRed() + c.getRed() +
							c.getBlue() +
							c.getGreen() + c.getGreen() + c.getGreen() + c.getGreen()) >> 3;
					totalRed += c.getRed();
				}
			}

			float totalPixels = (float)(frame.getWidth() * frame.getHeight());

			return new AverageFrameComponents((float)(totalLum) / totalPixels,
					(float)(totalRed) / totalPixels);
		}

		/* This is not perfect because it treats color temps as linear.
		 * Essentially we use the difference between the ideal average r
		 * component and the average for the current frame to adjust red
		 * and blue up or down to get roughly the ideal color temperature
		 * for shot detection.
		 */
		private void adjustColorTemperature(BufferedImage frame, int x, int y, float dr, float db) {
			Color c = new Color(frame.getRGB(x, y));

			float r = c.getRed() * dr;
			if (r > 255) r = 255;
			if (r < 0) r = 0;
			float b = c.getBlue() * db;
			if (b > 255) b = 255;
			if (b < 0) b = 0;

			frame.setRGB(x, y, new Color((int)r, c.getGreen(), (int)b).getRGB());
		}

		private void detectShotsNew(BufferedImage frame, AverageFrameComponents averages) {
			float oneStandardDeviationR=0;
			float oneStandardDeviationG=0;
			float oneStandardDeviationB=0;
			int R,G,B;
			float amplitude;
			int count;
			count=0;
			if (!isDetecting) {
				//frameProcessing=false;
				return;
			}

			for (int x = 0; x < 640; x++) {
				for (int y = 0; y < 480; y++) {
					shotTransform[x][y]=(float)0.0;
				}
			}

			for (int x = 2; x < 638; x++) {
				for (int y = 2; y < 478; y++) {
					// Grab the pixel colors, we'll need them a few times
					R = (frame.getRGB(x,y) >> 16) & 0x000000FF;
					G = (frame.getRGB(x,y) >> 8) & 0x000000FF;
					B = (frame.getRGB(x,y) >> 0) & 0x000000FF;

					// Remove the exiting circular buffer frame from the various total variables
					pixelSumOverTimeR[x][y]-=(frameHistory[historyIndex].getRGB(x,y) >> 16) & 0x000000FF;
					pixelSumOverTimeG[x][y]-=(frameHistory[historyIndex].getRGB(x,y) >> 8) & 0x000000FF;
					pixelSumOverTimeB[x][y]-=(frameHistory[historyIndex].getRGB(x,y) >> 0) & 0x000000FF;
					varianceSumOverTimeR[x][y]-=variance_historyR[x][y][historyIndex];
					varianceSumOverTimeG[x][y]-=variance_historyG[x][y][historyIndex];
					varianceSumOverTimeB[x][y]-=variance_historyB[x][y][historyIndex];
					// Add the new pixels into the various pixel totals
					pixelSumOverTimeR[x][y]+=R;
					pixelSumOverTimeG[x][y]+=G;
					pixelSumOverTimeB[x][y]+=B;
					// Calculate the mean over time
					meanOfR[x][y]=(float)(pixelSumOverTimeR[x][y]/HISTORY_SIZE_FLOAT);
					meanOfG[x][y]=(float)(pixelSumOverTimeG[x][y]/HISTORY_SIZE_FLOAT);
					meanOfB[x][y]=(float)(pixelSumOverTimeB[x][y]/HISTORY_SIZE_FLOAT);
				}
			}
			for (int x = 2; x < 638; x++) {
				for (int y = 2; y < 478; y++) {
					R = (frame.getRGB(x,y) >> 16) & 0x000000FF;
					G = (frame.getRGB(x,y) >> 8) & 0x000000FF;
					B = (frame.getRGB(x,y) >> 0) & 0x000000FF;

					float highestNearbyMeanOfR;
					float highestNearbyMeanOfG;
					float highestNearbyMeanOfB;
					highestNearbyMeanOfR=
							Math.max(
									Math.max(
											Math.max(
													Math.max(
															Math.max(
																	Math.max(
																			Math.max(
																					Math.max(meanOfR[x-1][y-1],
																							meanOfR[x-1][y]),
																					meanOfR[x-1][y+1]),
																			meanOfR[x][y-1]),
																	meanOfR[x][y]),
															meanOfR[x][y+1]),
													meanOfR[x+1][y-1]),
											meanOfR[x+1][y]),
									meanOfR[x+1][y+1]);
					highestNearbyMeanOfG=
							Math.max(
									Math.max(
											Math.max(
													Math.max(
															Math.max(
																	Math.max(
																			Math.max(
																					Math.max(meanOfG[x-1][y-1],
																							meanOfG[x-1][y]),
																					meanOfG[x-1][y+1]),
																			meanOfG[x][y-1]),
																	meanOfG[x][y]),
															meanOfG[x][y+1]),
													meanOfG[x+1][y-1]),
											meanOfG[x+1][y]),
									meanOfG[x+1][y+1]);
					highestNearbyMeanOfB=
							Math.max(
									Math.max(
											Math.max(
													Math.max(
															Math.max(
																	Math.max(
																			Math.max(
																					Math.max(meanOfB[x-1][y-1],
																							meanOfB[x-1][y]),
																					meanOfB[x-1][y+1]),
																			meanOfB[x][y-1]),
																	meanOfB[x][y]),
															meanOfB[x][y+1]),
													meanOfB[x+1][y-1]),
											meanOfB[x+1][y]),
									meanOfB[x+1][y+1]);

					// Calcualte the new variances and put them into the history circular buffer
					variance_historyR[x][y][historyIndex]=(int)((highestNearbyMeanOfR-R)*(highestNearbyMeanOfR-R));
					variance_historyG[x][y][historyIndex]=(int)((highestNearbyMeanOfG-G)*(highestNearbyMeanOfG-G));
					variance_historyB[x][y][historyIndex]=(int)((highestNearbyMeanOfB-B)*(highestNearbyMeanOfB-B));
                    // Add the new variances into the various variance totals
					varianceSumOverTimeR[x][y]+=variance_historyR[x][y][historyIndex];
					varianceSumOverTimeG[x][y]+=variance_historyG[x][y][historyIndex];
					varianceSumOverTimeB[x][y]+=variance_historyB[x][y][historyIndex];
					// Calculate strandard deviations
					oneStandardDeviationR = (float)Math.sqrt(varianceSumOverTimeR[x][y]/HISTORY_SIZE_FLOAT)+(float)1;
					oneStandardDeviationG = (float)Math.sqrt(varianceSumOverTimeG[x][y]/HISTORY_SIZE_FLOAT)+(float)1;
					oneStandardDeviationB = (float)Math.sqrt(varianceSumOverTimeB[x][y]/HISTORY_SIZE_FLOAT)+(float)1;

//					if( R > highestNearbyMeanOfR ) {
//						count++;
						amplitude=Math.min(3, (R-highestNearbyMeanOfR)/oneStandardDeviationR);
						shotTransform[x][y-2] += amplitude; // Copy the values to the grid
						shotTransform[x][y+2] += amplitude; // Copy the values to the grid
						for(int dx=-1;dx<=1;dx++) {
							 shotTransform[x+dx][y-1] += amplitude; // Copy the values to the grid
							 shotTransform[x+dx][y+1] += amplitude; // Copy the values to the grid
						}
						for(int dx=-2;dx<=2;dx++) {
							 shotTransform[x+dx][y] += amplitude; // Copy the values to the grid
						}
//					}
//					if( G > highestNearbyMeanOfG ) {
//						count++;
						amplitude=Math.min(3, (G-highestNearbyMeanOfG)/oneStandardDeviationG);
						shotTransform[x][y-2] += amplitude; // Copy the values to the grid
						shotTransform[x][y+2] += amplitude; // Copy the values to the grid
						for(int dx=-1;dx<=1;dx++) {
							 shotTransform[x+dx][y-1] += amplitude; // Copy the values to the grid
							 shotTransform[x+dx][y+1] += amplitude; // Copy the values to the grid
						}
						for(int dx=-2;dx<=2;dx++) {
							 shotTransform[x+dx][y] += amplitude; // Copy the values to the grid
						}
//					}
//					if( B > highestNearbyMeanOfB ) {
//						count++;
						amplitude=Math.max(-1, Math.min(3, (B-highestNearbyMeanOfB)/oneStandardDeviationB));
						shotTransform[x][y-2] += amplitude; // Copy the values to the grid
						shotTransform[x][y+2] += amplitude; // Copy the values to the grid
						for(int dx=-1;dx<=1;dx++) {
							 shotTransform[x+dx][y-1] += amplitude; // Copy the values to the grid
							 shotTransform[x+dx][y+1] += amplitude; // Copy the values to the grid
						}
						for(int dx=-2;dx<=2;dx++) {
							 shotTransform[x+dx][y] += amplitude; // Copy the values to the grid
						}
//					}

/*
  					if( R > (meanOfR+oneStandardDeviationR*4) ) {
						 shotTransform[x][y-2] += 1; // Copy the values to the grid
						 shotTransform[x][y+2] += 1; // Copy the values to the grid
						for(int dx=-1;dx<=1;dx++) {
							 shotTransform[x+dx][y-1] += 1; // Copy the values to the grid
							 shotTransform[x+dx][y+1] += 1; // Copy the values to the grid
						}
						for(int dx=-2;dx<=2;dx++) {
							 shotTransform[x+dx][y] += 1; // Copy the values to the grid
						}
					}
  					if( G > (meanOfR+oneStandardDeviationG*4) ) {
						 shotTransform[x][y-2] += 1; // Copy the values to the grid
						 shotTransform[x][y+2] += 1; // Copy the values to the grid
						for(int dx=-1;dx<=1;dx++) {
							 shotTransform[x+dx][y-1] += 1; // Copy the values to the grid
							 shotTransform[x+dx][y+1] += 1; // Copy the values to the grid
						}
						for(int dx=-2;dx<=2;dx++) {
							 shotTransform[x+dx][y] += 1; // Copy the values to the grid
						}
					}
  					if( B > (meanOfR+oneStandardDeviationB*4) ) {
						 shotTransform[x][y-2] += 1; // Copy the values to the grid
						 shotTransform[x][y+2] += 1; // Copy the values to the grid
						for(int dx=-1;dx<=1;dx++) {
							 shotTransform[x+dx][y-1] += 1; // Copy the values to the grid
							 shotTransform[x+dx][y+1] += 1; // Copy the values to the grid
						}
						for(int dx=-2;dx<=2;dx++) {
							 shotTransform[x+dx][y] += 1; // Copy the values to the grid
						}
					}
*/

				}
			}
			// Copy the current frame into the circular buffer
			frameHistory[historyIndex].createGraphics().drawImage(frame, 0, 0, null);

			historyIndex=(historyIndex+1) % HISTORY_SIZE;
			if (historyIndex==0) historyReady=true;

			if (historyReady==false) {
				//frameProcessing=false;
				return;
			}


			// Check for hit
			float max;
			max=0;
			for (int x = 2; x < 638; x++) {
				for (int y = 2; y < 478; y++) {
					if (max<shotTransform[x][y]) max = shotTransform[x][y];
					if (shotTransform[x][y]>6) {
						logger.debug("Suspected shot accepted: ({}, {})", x, y);
  				    	canvasManager.addShot(javafx.scene.paint.Color.RED, (double)x, (double)y);
					}
				}
			}
			System.out.printf("Max shottransform %f \n", max);
			System.out.printf("pixels over avg %d \n", count);

			//frameProcessing=false;

			if (webcam.isPresent()) {
				double webcamFPS = webcam.get().getFPS();
				if (debuggerListener.isPresent()) {
					debuggerListener.get().updateFeedData(webcamFPS, LightingCondition.BRIGHT);
					// Not currently analyzing lighting condition
				}
				if (webcamFPS < MIN_SHOT_DETECTION_FPS && !showedFPSWarning) {
					logger.warn("[{}] Current webcam FPS is {}, which is too low for reliable shot detection",
							webcam.get().getName(), webcamFPS);
					showFPSWarning(webcamFPS);
					showedFPSWarning = true;
				}
			}

			if (debuggerListener.isPresent()) {
//				debuggerListener.get().updateDebugView(workingCopy);
			}
		}


		private void showMissingCameraError() {
			Platform.runLater(() -> {
				Alert cameraAlert = new Alert(AlertType.ERROR);

				Optional<String> cameraName = config.getWebcamsUserName(webcam.get());
				String messageFormat = "ShootOFF can no longer communicate with the webcam %s. Was it unplugged?";
				String message;
				if (cameraName.isPresent()) {
					message = String.format(messageFormat, cameraName.get());
				} else {
					message = String.format(messageFormat, webcam.get().getName());
				}

				cameraAlert.setTitle("Webcam Missing");
				cameraAlert.setHeaderText("Cannot Communicate with Camera!");
				cameraAlert.setResizable(true);
				cameraAlert.setContentText(message);
				cameraAlert.show();
			});
		}

		private void showFPSWarning(double fps) {
			Platform.runLater(() -> {
				Alert cameraAlert = new Alert(AlertType.WARNING);

				Optional<String> cameraName = config.getWebcamsUserName(webcam.get());
				String messageFormat = "The FPS from %s has dropped to %f, which is too low for reliable shot detection. Some"
						+ " shots may be missed. You may be able to raise the FPS by closing other applications.";
				String message;
				if (cameraName.isPresent()) {
					message = String.format(messageFormat, cameraName.get(), fps);
				} else {
					message = String.format(messageFormat, webcam.get().getName(), fps);
				}

				cameraAlert.setTitle("Webcam FPS Too Low");
				cameraAlert.setHeaderText("Webcam FPS is too low!");
				cameraAlert.setResizable(true);
				cameraAlert.setContentText(message);
				cameraAlert.show();
			});
		}

		private void showBrightnessWarning() {
			Platform.runLater(() -> {
				Alert cameraAlert = new Alert(AlertType.WARNING);

				Optional<String> cameraName = config.getWebcamsUserName(webcam.get());
				String messageFormat = "The camera %s is streaming frames that are very bright. "
						+ " This will increase the odds of shots falsely being detected."
						+ " For best results, please do any mix of the following:\n\n"
						+ "-Turn off auto white balance and auto focus on your webcam and reduce the brightness\n"
						+ "-Remove any bright light sources in the camera's view\n"
						+ "-Turn down your projector's brightness and contrast\n"
						+ "-Dim any lights in the room or turn them off, especially those behind the shooter";
				String message;
				if (cameraName.isPresent()) {
					message = String.format(messageFormat, cameraName.get());
				} else {
					message = String.format(messageFormat, webcam.get().getName());
				}

				cameraAlert.setTitle("Conditions Very Bright");
				cameraAlert.setHeaderText("Webcam detected very bright conditions!");
				cameraAlert.setResizable(true);
				cameraAlert.setContentText(message);
				cameraAlert.show();
			});
		}
	}
}