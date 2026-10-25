package io.mosip.registration.controller.device;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.SessionContext;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

@Component
public class Streamer {

	private static final Logger LOGGER = AppConfig.getLogger(Streamer.class);

	/** Gets each JPEG of the live stream, on the stream thread; face liveness uses it (PS04). */
	public interface FrameTap {
		void onJpeg(byte[] jpeg);

		/** The stream ended without being stopped, for example because the device was unplugged. */
		void onStreamEnded(String detail);
	}

	private final List<FrameTap> frameTaps = new CopyOnWriteArrayList<>();

	// The newest frame for the preview, and whether one is already queued on the JavaFX thread
	private volatile Image previewImage;
	private final AtomicBoolean previewPending = new AtomicBoolean();

	public void addFrameTap(FrameTap tap) {
		frameTaps.add(tap);
	}

	public void removeFrameTap(FrameTap tap) {
		frameTaps.remove(tap);
	}

	private InputStream urlStream;

	public void setUrlStream(InputStream inputStream) {

		if (urlStream != null) {
			try {
				urlStream.close();
			} catch (IOException exception) {
				LOGGER.error("Failed to close the stream",exception);
			} finally {
				urlStream = null;
				imageBytes = null;
			}
		}

		if (inputStream != null) {
			this.urlStream = inputStream;
			isRunning = true;
		} else {
			this.urlStream = null;
			isRunning = false;
		}

	}

	public InputStream getUrlStream() {
		return urlStream;
	}

	private boolean isRunning = true;

	private final String CONTENT_LENGTH = "Content-Length:";

	private Thread streamer_thread = null;

	public byte[] imageBytes = null;

	// Last streaming image
	private static Image streamImage;

	// Image View, which UI need to be shown
	private static ImageView imageView;

	// Set Streaming image
	public void setStreamImage(Image streamImage) {
		this.streamImage = streamImage;
	}

	// Get Streaming image
	public Image getStreamImage() {
		return streamImage;
	}

	public byte[] getStreamImageBytes() {
		return imageBytes;
	}

	// Set ImageView
	public static void setImageView(ImageView imageView) {
		Streamer.imageView = imageView;
	}

	// Set Streaming image to ImageView
	public void setStreamImageToImageView() {
		imageView.setImage(streamImage);
	}

	public void startStream(InputStream inputStream, ImageView streamImage, ImageView scanImage) {

		LOGGER.info("Streamer Thread initiation started for : {}", System.currentTimeMillis());

		streamer_thread = new Thread(new Runnable() {

			public void run() {

				setUrlStream(inputStream);

				while (null != urlStream) {
					try {
						imageBytes = retrieveNextImage(urlStream);
						for (FrameTap tap : frameTaps) {
							try {
								tap.onJpeg(imageBytes);
							} catch (RuntimeException e) {
								LOGGER.error("Frame tap failed", e);
							}
						}
						ByteArrayInputStream imageStream = new ByteArrayInputStream(imageBytes);
						Image img = new Image(imageStream);
						// The preview is set on the JavaFX thread; frames that arrive while one is queued are skipped
						previewImage = img;
						if (previewPending.compareAndSet(false, true)) {
							Platform.runLater(() -> {
								previewPending.set(false);
								streamImage.setImage(previewImage);
							});
						}
						if (null != scanImage) {
							setStreamImage(img);
						}
					} catch (Throwable t) {
						LOGGER.error("Failed in streamer_thread", t);
						if (isRunning) {
							for (FrameTap tap : frameTaps) {
								tap.onStreamEnded(t.toString());
							}
						}
						urlStream = null;
					}
				}
			}
		}, "STREAMER_THREAD");

		streamer_thread.start();

		LOGGER.info("Streamer Thread initiated completed for : {}", System.currentTimeMillis());

	}

	/**
	 * Using the urlStream get the next JPEG image as a byte[]
	 *
	 * @return byte[] of the JPEG
	 * @throws IOException
	 */
	public byte[] retrieveNextImage(InputStream urlStream) throws IOException {

		int currByte = -1;

		boolean captureContentLength = false;
		StringWriter contentLengthStringWriter = new StringWriter(128);
		StringWriter headerWriter = new StringWriter(128);

		int contentLength = 0;

		while ((currByte = urlStream.read()) > -1) {
			if (captureContentLength) {
				if (currByte == 10 || currByte == 13) {
					contentLength = Integer.parseInt(contentLengthStringWriter.toString().replace(" ", ""));
					break;
				}
				contentLengthStringWriter.write(currByte);

			} else {
				headerWriter.write(currByte);
				String tempString = headerWriter.toString();
				int indexOf = tempString.indexOf(CONTENT_LENGTH);
				if (indexOf > 0) {
					captureContentLength = true;
				}
			}
		}

		// 255 indicates the start of the jpeg image
		int next;
		while ((next = urlStream.read()) != 255) {
			if (next == -1) {
				// Without this the loop spun forever once the device closed the stream
				throw new IOException("End of the live stream");
			}
		}

		// && urlStream.read()!=-1
		// if(urlStream.read()==-1) {
		// throw new RuntimeException("No stream available");
		// }

		// rest is the buffer
		byte[] imageBytes = new byte[contentLength + 1];
		// since we ate the original 255 , shove it back in
		imageBytes[0] = (byte) 255;
		int offset = 1;
		int numRead = 0;
		while (offset < imageBytes.length
				&& (numRead = urlStream.read(imageBytes, offset, imageBytes.length - offset)) >= 0) {
			offset += numRead;
		}

		return imageBytes;
	}

	/**
	 * Stop the loop, and allow it to clean up
	 */
	public synchronized void stop() {

		// Enable Auto-Logout
		SessionContext.setAutoLogout(true);

		if (streamer_thread != null) {
			try {
				isRunning = false;
				if (urlStream != null)
					urlStream.close();
				streamer_thread = null;
			} catch (IOException e) {
				LOGGER.error("Failed to close the stream thread", e);
			}
		}
	}

}
