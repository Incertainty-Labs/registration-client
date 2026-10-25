package io.mosip.registration.controller.device;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.liveness.api.FaceFrame;
import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SourceCapabilities;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.liveness.config.LivenessConfigLoader;
import io.mosip.registration.liveness.desktop.LivenessGate;
import io.mosip.registration.liveness.desktop.PushFrameSource;
import io.mosip.registration.liveness.desktop.overlay.LivenessOverlay;
import io.mosip.registration.liveness.desktop.overlay.Messages;
import io.mosip.registration.liveness.desktop.overlay.OverlayView;
import io.mosip.registration.liveness.engine.EngineOptions;
import io.mosip.registration.liveness.engine.LivenessEngines;
import io.mosip.registration.liveness.model.ModelRegistry;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Face liveness before the resident face capture (PS04, mosip-face-liveness): a {@link LivenessGate} runs on the
 * client's own live stream, through {@link Streamer}'s frame tap, and is shown in a {@link LivenessOverlay} window.
 * Only a pass lets the controller ask the device for the face. Attempts are counted per face field of one
 * registration. Off unless {@code mosip.registration.face.liveness.enabled} is true.
 */
@Component
public class ResidentLivenessCheck {

	private static final Logger LOGGER = AppConfig.getLogger(ResidentLivenessCheck.class);
	private static final String MODEL_DIR = LivenessConfigLoader.PREFIX + "model.dir";
	private static final String STUB_FORCE = LivenessConfigLoader.PREFIX + "stub.force";

	@Autowired
	private Streamer streamer;

	private LivenessEngine engine;
	/** The gates of the current registration, by field and modality. */
	private final Map<String, LivenessGate> gates = new HashMap<>();
	/** Where each gate's events go: the window of the field's current check. */
	private final Map<String, AtomicReference<LivenessGate.Listener>> listeners = new HashMap<>();
	private String registrationId;

	/**
	 * @param modality the field's modality
	 * @return whether this capture needs a liveness check first: a face or an exception photo, with liveness enabled
	 */
	public boolean isRequired(io.mosip.registration.enums.Modality modality) {
		if (modality != io.mosip.registration.enums.Modality.FACE
				&& modality != io.mosip.registration.enums.Modality.EXCEPTION_PHOTO) {
			return false;
		}
		return LivenessConfigLoader.load(config(), workflow(modality)).enabled();
	}

	/**
	 * @return whether the field still has a liveness attempt; when not, Scan stays off (S12)
	 */
	public synchronized boolean attemptLeft(String registrationId, String fieldId,
			io.mosip.registration.enums.Modality modality) {
		LivenessGate gate = registrationId != null && registrationId.equals(this.registrationId)
				? gates.get(fieldId + "_" + modality) : null;
		return gate == null || gate.attemptLeft();
	}

	/**
	 * Runs one check on the stream the controller has just started.
	 *
	 * @param device           the face device, for the source's id and facts
	 * @param registrationId   the registration, so attempts start again for the next one
	 * @param fieldId          the face field
	 * @param modality         FACE, or EXCEPTION_PHOTO (passive only)
	 * @param clientRetryLimit the client's face retry limit
	 * @param owner            the window the liveness window belongs to
	 * @param onPassed         on the JavaFX thread after a pass: ask the device for the face
	 * @param onEnded          on the JavaFX thread when the attempt ended without a pass, with whether Scan may run again
	 * @param onRetry          on the JavaFX thread when the operator chose Retry in the liveness window
	 * @throws LivenessException with LIV-013 if the engine cannot be loaded; liveness then blocks the capture
	 */
	public void run(MdmBioDevice device, String registrationId, String fieldId,
			io.mosip.registration.enums.Modality modality, int clientRetryLimit, Window owner, Runnable onPassed,
			Consumer<Boolean> onEnded, Runnable onRetry) throws LivenessException {
		Map<String, String> config = config();
		Workflow workflow = workflow(modality);
		LivenessPolicy policy = LivenessConfigLoader.load(config, workflow);
		LivenessEngine liveness = engine(config);

		LivenessOverlay overlay = new LivenessOverlay(Messages.english());
		Stage window = new Stage();
		window.initOwner(owner);
		window.initModality(Modality.WINDOW_MODAL);
		window.setTitle("Face liveness");
		window.setScene(new Scene(overlay, 900, 720));

		PushFrameSource frames = new PushFrameSource("sbi095:" + device.getDeviceId(), SourceCapabilities.builder()
				.device(String.valueOf(device.getDeviceMake()), String.valueOf(device.getDeviceModel()))
				.certification(device.getCertification())
				.formats(new HashSet<>(java.util.Collections.singletonList("image/jpeg"))).build());
		Streamer.FrameTap tap = new Streamer.FrameTap() {
			@Override
			public void onJpeg(byte[] jpeg) {
				frames.offer(jpeg);
			}

			@Override
			public void onStreamEnded(String detail) {
				frames.streamEnded(detail);
			}
		};
		AtomicReference<FaceFrame> latest = new AtomicReference<>();
		AtomicBoolean framePending = new AtomicBoolean();
		LivenessGate gate = gate(registrationId, fieldId, modality, liveness, policy, workflow, clientRetryLimit,
				new LivenessGate.Listener() {
					@Override
					public void view(OverlayView view) {
						Platform.runLater(() -> overlay.show(view));
					}

					@Override
					public void frame(FaceFrame frame) {
						// Only the newest frame is shown; the rest are dropped while the JavaFX thread is busy
						latest.set(frame);
						if (framePending.compareAndSet(false, true)) {
							Platform.runLater(() -> {
								framePending.set(false);
								overlay.showFrame(latest.get());
							});
						}
					}

					@Override
					public void passed(LivenessResult result) {
						LOGGER.info("Face liveness passed for {}: score {}", fieldId, result.passiveScore());
						streamer.removeFrameTap(tap);
						Platform.runLater(() -> {
							window.close();
							onPassed.run();
						});
					}

					@Override
					public void ended(LivenessResult result, boolean retryAllowed) {
						LOGGER.info("Face liveness ended for {}: {} {}", fieldId, result.finalState(),
								result.errorCode() == null ? "" : result.errorCode().code());
						streamer.removeFrameTap(tap);
						// The window stays open with the failure screen until the operator closes it or retries
						Platform.runLater(() -> onEnded.accept(retryAllowed));
					}
				});
		overlay.onAction(action -> {
			switch (action) {
				case CANCEL:
					gate.cancel();
					break;
				case RETRY:
				case RESCAN:
					window.close();
					onRetry.run();
					break;
				default:
					window.close();
					break;
			}
		});
		window.setOnCloseRequest(event -> gate.cancel());
		window.show();
		streamer.addFrameTap(tap);
		gate.start(frames);
	}

	private synchronized LivenessGate gate(String registrationId, String fieldId,
			io.mosip.registration.enums.Modality modality, LivenessEngine liveness, LivenessPolicy policy,
			Workflow workflow, int clientRetryLimit, LivenessGate.Listener listener) {
		if (registrationId == null || !registrationId.equals(this.registrationId)) {
			gates.clear();
			listeners.clear();
			this.registrationId = registrationId;
		}
		// One gate per field keeps its attempt count; each check's window gets the gate's events through the holder
		String key = fieldId + "_" + modality;
		AtomicReference<LivenessGate.Listener> current = listeners.computeIfAbsent(key, k -> new AtomicReference<>());
		current.set(listener);
		return gates.computeIfAbsent(key,
				k -> new LivenessGate(liveness, policy, workflow, clientRetryLimit, forwardingTo(current)));
	}

	private static LivenessGate.Listener forwardingTo(AtomicReference<LivenessGate.Listener> current) {
		return new LivenessGate.Listener() {
			@Override
			public void view(OverlayView view) {
				current.get().view(view);
			}

			@Override
			public void frame(FaceFrame frame) {
				current.get().frame(frame);
			}

			@Override
			public void passed(LivenessResult result) {
				current.get().passed(result);
			}

			@Override
			public void ended(LivenessResult result, boolean retryAllowed) {
				current.get().ended(result, retryAllowed);
			}
		};
	}

	private synchronized LivenessEngine engine(Map<String, String> config) throws LivenessException {
		if (engine == null) {
			if (config.containsKey(STUB_FORCE)) {
				// For tests and demos without the models; never set in production
				engine = LivenessEngines.stub(config);
			} else {
				String dir = config.get(MODEL_DIR);
				if (dir == null || dir.trim().isEmpty()) {
					throw new LivenessException(LivenessErrorCode.ENGINE_ERROR, MODEL_DIR + " is not set");
				}
				engine = LivenessEngines.hybrid(ModelRegistry.load(Paths.get(dir.trim())), EngineOptions.defaults());
				LOGGER.info("Face liveness engine {}", engine.info().engineVersion());
			}
		}
		return engine;
	}

	private static Workflow workflow(io.mosip.registration.enums.Modality modality) {
		return modality == io.mosip.registration.enums.Modality.EXCEPTION_PHOTO ? Workflow.RESIDENT_EXCEPTION_PHOTO
				: Workflow.RESIDENT_REGISTRATION;
	}

	/** @return the liveness keys of the client's configuration, as strings */
	private static Map<String, String> config() {
		Map<String, String> config = new HashMap<>();
		for (Map.Entry<String, Object> entry : ApplicationContext.map().entrySet()) {
			if (entry.getKey().startsWith(LivenessConfigLoader.PREFIX) && entry.getValue() != null) {
				config.put(entry.getKey(), String.valueOf(entry.getValue()));
			}
		}
		return config;
	}
}
