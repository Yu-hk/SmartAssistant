package com.example.smartassistant.common.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.example.smartassistant.common.agent.AgentLoopDecision.DecisionContext;
import com.example.smartassistant.common.agent.AgentLoopDecision.LoopAction;

/**
 * AgentLoopDecision 决策状态机单测：验证 11 条优先级链路的命中顺序与阈值。
 */
class AgentLoopDecisionTest {

    private static final int MAX_ITER = 10;

    private DecisionContext ctx(int iteration, boolean noToolCalls, String answerText,
            LoopGuardService.GuardAction guard, int parseFails, int noProgress) {
        return new DecisionContext(iteration, noToolCalls, answerText, guard, parseFails, noProgress, 0);
    }

    private DecisionContext ctx(int iteration, boolean noToolCalls, String answerText,
            LoopGuardService.GuardAction guard, int parseFails, int noProgress, int noIncrement) {
        return new DecisionContext(iteration, noToolCalls, answerText, guard, parseFails, noProgress, noIncrement);
    }

    @Test
    void finalize_whenNoToolCallsAndEnoughContent() {
        var c = ctx(1, true, "A".repeat(60),
                LoopGuardService.GuardAction.CONTINUE, 0, 0);
        assertEquals(LoopAction.FINALIZE, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void advancePhase_whenNoToolCallsAndPhaseChecksPresent() {
        var c = ctx(1, true, "", LoopGuardService.GuardAction.CONTINUE, 0, 0);
        assertEquals(LoopAction.ADVANCE_PHASE, AgentLoopDecision.decide(c, true, MAX_ITER));
    }

    @Test
    void pauseBlocked_highestAmongGuardActions() {
        var c = ctx(1, false, "", LoopGuardService.GuardAction.PAUSE_BLOCKED, 0, 0);
        assertEquals(LoopAction.PAUSE_BLOCKED, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void pauseInfra_takesPrecedenceOverAwaitConfirmation() {
        var c = ctx(1, false, "", LoopGuardService.GuardAction.PAUSE_INFRASTRUCTURE, 0, 0);
        assertEquals(LoopAction.PAUSE_INFRA, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void awaitConfirmation_beforeGenericPause() {
        var c = ctx(1, false, "", LoopGuardService.GuardAction.AWAIT_CONFIRMATION, 0, 0);
        assertEquals(LoopAction.AWAIT_CONFIRMATION, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void pause_genericGuardNonContinue() {
        var c = ctx(1, false, "", LoopGuardService.GuardAction.PAUSE_BLOCKED, 0, 0);
        assertEquals(LoopAction.PAUSE_BLOCKED, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void parseFailure_atThreshold() {
        var c = ctx(1, false, "", LoopGuardService.GuardAction.CONTINUE,
                AgentLoopDecision.MAX_PARSE_FAILURES, 0);
        assertEquals(LoopAction.PARSE_FAILURE, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void iterationBudget_atMax() {
        var c = ctx(MAX_ITER, false, "", LoopGuardService.GuardAction.CONTINUE, 0, 0);
        assertEquals(LoopAction.ITERATION_BUDGET, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void noIncrement_atThreshold_stopsCallStorm() {
        // NO_INCREMENT(9) 优先级高于 STRATEGY_SWITCH(10)
        var c = ctx(1, false, "", LoopGuardService.GuardAction.CONTINUE, 0, 0,
                AgentLoopDecision.NO_INCREMENT_LIMIT);
        assertEquals(LoopAction.NO_INCREMENT, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void noIncrement_yieldsToIterationBudget_priorityOrder() {
        var c = ctx(MAX_ITER, false, "", LoopGuardService.GuardAction.CONTINUE, 0, 0,
                AgentLoopDecision.NO_INCREMENT_LIMIT);
        assertEquals(LoopAction.ITERATION_BUDGET, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void strategySwitch_whenNoProgressReachesThreshold() {
        // 阈值已对齐为 3（= MAX_NO_PROGRESS_ITERATIONS）
        var c = ctx(1, false, "", LoopGuardService.GuardAction.CONTINUE, 0,
                AgentLoopDecision.STRATEGY_SWITCH_THRESHOLD);
        assertEquals(LoopAction.STRATEGY_SWITCH, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void strategySwitch_notTriggeredBelowThreshold() {
        // noProgressCount=2 < 阈值3 → 不终止，继续
        var c = ctx(1, false, "", LoopGuardService.GuardAction.CONTINUE, 0,
                AgentLoopDecision.STRATEGY_SWITCH_THRESHOLD - 1);
        assertEquals(LoopAction.CONTINUE, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void continue_whenEverythingNormal() {
        var c = ctx(1, false, "", LoopGuardService.GuardAction.CONTINUE, 0, 0);
        assertEquals(LoopAction.CONTINUE, AgentLoopDecision.decide(c, false, MAX_ITER));
    }

    @Test
    void strategySwitch_yieldsToParseFailure_priorityOrder() {
        // parseFailure(7) 优先级高于 strategySwitch(9)
        var c = ctx(1, false, "", LoopGuardService.GuardAction.CONTINUE,
                AgentLoopDecision.MAX_PARSE_FAILURES,
                AgentLoopDecision.STRATEGY_SWITCH_THRESHOLD);
        assertEquals(LoopAction.PARSE_FAILURE, AgentLoopDecision.decide(c, false, MAX_ITER));
    }
}
