package com.metaverse.coin.strategy.job.risk.application;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.BaselineModelConfigView;
import com.metaverse.coin.strategy.job.smartwallet.model.UpdateBaselineModelConfigRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaselineModelConfigApplicationServiceTest {

    @Test
    void shouldReturnCurrentConfig() {
        SmartWalletProperties properties = new SmartWalletProperties();
        BaselineModelConfigApplicationService service = new BaselineModelConfigApplicationService(properties);

        BaselineModelConfigView current = service.current();

        assertEquals(new BigDecimal("1.00"), current.getGrossEdgePriorWeight());
        assertEquals(new BigDecimal("1.40"), current.getFollowProbability().getWalletScoreWeight());
        assertEquals(new BigDecimal("1.15"), current.getCalibration().getFollowProbabilityScale());
    }

    @Test
    void shouldUpdateRuntimeConfigInPlace() {
        SmartWalletProperties properties = new SmartWalletProperties();
        BaselineModelConfigApplicationService service = new BaselineModelConfigApplicationService(properties);
        UpdateBaselineModelConfigRequest request = new UpdateBaselineModelConfigRequest();
        request.setGrossEdgePriorWeight(new BigDecimal("1.30"));

        UpdateBaselineModelConfigRequest.FollowProbabilityRequest followProbability = new UpdateBaselineModelConfigRequest.FollowProbabilityRequest();
        followProbability.setWalletScoreWeight(new BigDecimal("2.20"));
        request.setFollowProbability(followProbability);

        UpdateBaselineModelConfigRequest.CalibrationRequest calibration = new UpdateBaselineModelConfigRequest.CalibrationRequest();
        calibration.setFollowProbabilityBias(new BigDecimal("-0.20"));
        request.setCalibration(calibration);

        BaselineModelConfigView updated = service.update(request);

        assertEquals(new BigDecimal("1.30"), updated.getGrossEdgePriorWeight());
        assertEquals(new BigDecimal("2.20"), updated.getFollowProbability().getWalletScoreWeight());
        assertEquals(new BigDecimal("-0.20"), updated.getCalibration().getFollowProbabilityBias());
        assertEquals(new BigDecimal("2.20"), properties.getBaselineModel().getFollowProbability().getWalletScoreWeight());
    }
}
