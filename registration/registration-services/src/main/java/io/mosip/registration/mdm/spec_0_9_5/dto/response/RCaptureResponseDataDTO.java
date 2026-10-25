package io.mosip.registration.mdm.spec_0_9_5.dto.response;

import java.util.Base64;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mosip.kernel.core.util.CryptoUtil;
import lombok.Data;

// Devices may add fields to the signed capture data, for example their own PAD result (PS04)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RCaptureResponseDataDTO {

	private String deviceCode;
	private String bioType;
	private String digitalId;
	private String deviceServiceVersion;
	private String bioSubType;
	private String purpose;
	private String env;
	private String bioValue;
	private String transactionId;
	private String timestamp;
	private String requestedScore;
	private String qualityScore;

	@JsonIgnore
	public byte[] getDecodedBioValue() {
		return CryptoUtil.decodeURLSafeBase64(bioValue);
	}

}
