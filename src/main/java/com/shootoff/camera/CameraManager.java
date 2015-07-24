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
	public static final int LASER_TRANSITION = 20;
	public static final int HISTORY_SIZE = 5;
	public static final int HUGHES_TRANFORM_THRESHOLD=120;
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
	    int[][] amplitudeR = new int [640][480];
	    int[][] amplitudeG = new int [640][480];
	    int[][] amplitudeB = new int [640][480];
	    int[][] shotTransform = new int [640][480];
		private int historyIndex;
		private boolean historyReady;

		private File videoFile;
		private IMediaReader reader;

		private int framesProcessed;
		long startTime;

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
			framesProcessed=0;
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
			int R,G,B,maxR,maxG,maxB;
			if (!isDetecting) {
				//frameProcessing=false;
				return;
			}

			for (int x = 0; x < 640; x++) {
				for (int y = 0; y < 480; y++) {
					shotTransform[x][y]=0;
				}
			}

			for (int x = 2; x < 638; x++) {
				for (int y = 2; y < 478; y++) {
					// Reset the minimum values
					maxR=0;
					maxG=0;
					maxB=0;
					int rgbPixel;

					for (int i = 0; i < HISTORY_SIZE; i++) {
						rgbPixel=frameHistory[i].getRGB(x,y);
						maxR=Math.max(maxR, (rgbPixel >> 16) & 0x000000FF);
						maxG=Math.max(maxG, (rgbPixel >> 8) & 0x000000FF);
						maxB=Math.max(maxB, (rgbPixel >> 0) & 0x000000FF);
					}

					rgbPixel=frame.getRGB(x,y);
					R = (rgbPixel >> 16) & 0x000000FF;
					G = (rgbPixel >> 8) & 0x000000FF;
					B = (rgbPixel >> 0) & 0x000000FF;

					amplitudeR[x][y]=Math.max(-10, Math.min(40, (R-maxR)));
					amplitudeG[x][y]=Math.max(-10, Math.min(40, (G-maxG)));
					amplitudeB[x][y]=Math.max(-10, Math.min(40, (B-maxB)));
				}
			}
			// Perform hough transform
			int amplitude;
			for (int x = 2; x < 638; x++) {
				for (int y = 2; y < 478; y++) {
					amplitude=0;
					int offamplitude=amplitudeR[x][y];
					// Seach for bright pixels that are potientially edges (must neighbor a dark pixel)
					if(amplitudeR[x][y]>LASER_TRANSITION &&
							(
							  	  (amplitudeR[x+1][y]<offamplitude&&amplitudeR[x+2][y]<offamplitude)
								||(amplitudeR[x-1][y]<offamplitude&&amplitudeR[x-2][y]<offamplitude)
								||(amplitudeR[x][y+1]<offamplitude&&amplitudeR[x][y+2]<offamplitude)
								||(amplitudeR[x][y-1]<offamplitude&&amplitudeR[x][y-2]<offamplitude))) {
						// Update acumulator space for Hough transform
						amplitude = amplitudeR[x][y]-LASER_TRANSITION;
					}

					// Seach for bright pixels that are potientially edges (must neighbor a dark pixel)
					if(amplitudeG[x][y]>LASER_TRANSITION && (amplitudeG[x+1][y]<LASER_TRANSITION||amplitudeG[x-1][y]<LASER_TRANSITION||amplitudeG[x][y+1]<LASER_TRANSITION||amplitudeG[x][y-1]<LASER_TRANSITION)) {
						// Update acumulator space for Hough transform
						amplitude += amplitudeG[x][y]-LASER_TRANSITION;
					}

					// Seach for bright pixels that are potientially edges (must neighbor a dark pixel)
					if(amplitudeB[x][y]>LASER_TRANSITION && (amplitudeB[x+1][y]<LASER_TRANSITION||amplitudeB[x-1][y]<LASER_TRANSITION||amplitudeB[x][y+1]<LASER_TRANSITION||amplitudeB[x][y-1]<LASER_TRANSITION)) {
						// Update acumulator space for Hough transform
						amplitude += amplitudeB[x][y]-LASER_TRANSITION;
					}
					for(int dx=-1;dx<=1;dx++) {
						shotTransform[x+dx][y+1] += amplitude;
						shotTransform[x+dx][y-1] += amplitude;
					}
					shotTransform[x-1][y] += amplitude;
					shotTransform[x+1][y] += amplitude;

					shotTransform[x-2][y] += amplitude;
					shotTransform[x+2][y] += amplitude;
					shotTransform[x][y-2] += amplitude;
					shotTransform[x][y+2] += amplitude;
				}
			}

			// Copy the current frame into the circular buffer
			frameHistory[historyIndex].createGraphics().drawImage(frame, 0, 0, null);

			historyIndex=(historyIndex+1) % HISTORY_SIZE;
			if (historyIndex==0) historyReady=true;
			if(!historyReady) startTime = System.currentTimeMillis();

			if (historyReady==false) {
				//frameProcessing=false;
				return;
			}
			framesProcessed++;

			// Check for hit
			float max;
			max=0;
			for (int x = 10; x < 630; x++) {
				for (int y = 10; y < 470; y++) {
					if (max<shotTransform[x][y]) max = shotTransform[x][y];
					if (shotTransform[x][y]>HUGHES_TRANFORM_THRESHOLD) {
						int xLocalMaxima=0, yLocalMaxima=0;
						int Maxima=0;
						for(int dx = -10; dx<=10;dx++){
							for(int dy = -10; dy<=10;dy++){
								if (Maxima<shotTransform[dx+x][dy+y]) {
									xLocalMaxima=dx+x;
									yLocalMaxima=dy+y;
									Maxima=shotTransform[dx+x][dy+y];
								}
							}
						}

						logger.debug("Suspected shot accepted: ({}, {})", xLocalMaxima, yLocalMaxima);
  				    	canvasManager.addShot(javafx.scene.paint.Color.RED, (double)xLocalMaxima, (double)yLocalMaxima);
					}
				}
			}
			System.out.printf("Max shottransform %f \n", max);
			System.out.printf("FPS %f \n", (framesProcessed*1000.0/(System.currentTimeMillis()-startTime)));


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