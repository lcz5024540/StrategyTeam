package com.metaverse.coin.strategy.htx.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Read-only projection of persisted v5 facts. No method advances state or invokes an exchange. */
@Service
public class HtxTrendExitV5QueryService {
    private static final ObjectMapper JSON=new ObjectMapper();private final JdbcTemplate jdbc;
    public HtxTrendExitV5QueryService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record View(long positionId,String accountLabel,String engineVersion,String policyHash,LocalDateTime asOf,String dataQuality,String modelState,String phase,String executionState,BigDecimal hardStop,BigDecimal activeSoftBoundary,BigDecimal pendingSoftBoundary,int eligibleBars,int requiredBars,int breachBars,int requiredBreachBars,BigDecimal remainingQty,BigDecimal pendingExitQty,List<String> reasonCodes,List<String> evidenceGroups,boolean accountingComplete){}
    public View get(long positionId,String accountLabel){List<View> rows=jdbc.query("""
        SELECT s.position_id,p.account_label,c.engine_version,c.policy_hash,c.contract_json,s.state_json,s.phase,s.execution_state,s.confirmed_remaining_qty,s.updated_at,
          (SELECT d.evidence_json FROM htx_trend_exit_decision d WHERE d.position_id=s.position_id AND d.engine_version='trend-exit-v5' ORDER BY d.id DESC LIMIT 1) decision_json,
          (SELECT i.target_remaining_qty FROM htx_trend_exit_intent_v5 i WHERE i.position_id=s.position_id AND i.status IN ('PENDING','PARTIAL','SENT','UNKNOWN') ORDER BY i.id DESC LIMIT 1) pending_target
        FROM htx_trend_exit_state_v5 s JOIN htx_trend_entry_contract c ON c.id=s.contract_id JOIN htx_trend_position p ON p.id=s.position_id
        WHERE s.position_id=? AND p.account_label=?
        """,(rs,index)->map(rs),positionId,accountLabel);if(rows.isEmpty())throw new IllegalArgumentException("v5 position not found");return rows.get(0);}
    private static View map(ResultSet rs)throws SQLException{try{JsonNode contract=JSON.readTree(rs.getString("contract_json")),state=JSON.readTree(rs.getString("state_json"));String raw=rs.getString("decision_json");JsonNode evidence=raw==null?null:JSON.readTree(raw),decision=evidence==null?null:evidence.get("decision"),event=evidence==null?null:evidence.get("event"),policy=evidence==null?null:evidence.get("policy");BigDecimal remaining=rs.getBigDecimal("confirmed_remaining_qty"),target=rs.getBigDecimal("pending_target");String execution=rs.getString("execution_state");boolean complete=!"UNKNOWN".equals(execution)&&target==null;return new View(rs.getLong("position_id"),rs.getString("account_label"),rs.getString("engine_version"),rs.getString("policy_hash"),rs.getTimestamp("updated_at").toLocalDateTime(),complete?"COMPLETE":"INCOMPLETE",text(event,"model_state","UNKNOWN"),rs.getString("phase"),execution,decimal(contract,"immutable_initial_stop"),decimal(state,"active_soft_boundary"),decimal(state,"pending_soft_boundary"),integer(state,"eligible_bars"),integer(policy,"maturity_bars"),integer(state,"soft_breach_bars"),integer(policy,"soft_breach_confirm_bars"),remaining,target==null?BigDecimal.ZERO:remaining.subtract(target),strings(decision,"reason_codes"),strings(decision,"evidence_groups"),complete);}catch(Exception error){throw new SQLException("invalid persisted v5 JSON",error);}}
    private static String text(JsonNode node,String key,String fallback){return node==null||node.get(key)==null||node.get(key).isNull()?fallback:node.get(key).asText();}
    private static int integer(JsonNode node,String key){return node==null||node.get(key)==null?0:node.get(key).asInt();}
    private static BigDecimal decimal(JsonNode node,String key){String value=text(node,key,null);return value==null?null:new BigDecimal(value);}
    private static List<String> strings(JsonNode node,String key){List<String> out=new ArrayList<>();if(node!=null&&node.get(key)!=null)node.get(key).forEach(item->out.add(item.asText()));return List.copyOf(out);}
}
