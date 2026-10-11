package com.metaverse.coin.strategy.htx.service;

import java.util.ArrayList;
import java.util.List;

/** Fail-closed release gate. It reports missing capabilities and performs no I/O. */
public final class HtxTrendExitLiveReadinessV5 {
    private HtxTrendExitLiveReadinessV5() { }
    public record Evidence(boolean mysqlMigrationVerified,boolean ownerFencingVerified,boolean nativeReduceOnlyProtectionVerified,boolean primaryAccountingComplete,boolean mirrorAccountingComplete,boolean replayGatesPassed,boolean observeWindowPassed,boolean liveApprovalRecorded) { }
    public record Result(boolean liveAllowed,List<String> blockers) { }
    public static Result evaluate(Evidence value){List<String> blockers=new ArrayList<>();if(!value.mysqlMigrationVerified())blockers.add("MYSQL_MIGRATION_UNVERIFIED");if(!value.ownerFencingVerified())blockers.add("OWNER_FENCING_UNVERIFIED");if(!value.nativeReduceOnlyProtectionVerified())blockers.add("NATIVE_PROTECTION_UNVERIFIED");if(!value.primaryAccountingComplete())blockers.add("PRIMARY_ACCOUNTING_INCOMPLETE");if(!value.mirrorAccountingComplete())blockers.add("MIRROR_ACCOUNTING_INCOMPLETE");if(!value.replayGatesPassed())blockers.add("G0_G4_NOT_PASSED");if(!value.observeWindowPassed())blockers.add("G5_NOT_PASSED");if(!value.liveApprovalRecorded())blockers.add("G6_APPROVAL_MISSING");return new Result(blockers.isEmpty(),List.copyOf(blockers));}
}
