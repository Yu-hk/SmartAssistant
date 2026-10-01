package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopGuardServiceTest {

    @Test
    void pricesAndQuantitiesAreNotHttpErrors() {
        assertEquals(LoopGuardService.GuardAction.CONTINUE,
                guard.analyze("售价500元，库存429件，型号X503。预算1500元。").action());
        assertEquals(LoopGuardService.GuardAction.PAUSE_INFRASTRUCTURE,
                guard.analyze("HTTP 503 Service Unavailable").action());
        assertEquals(LoopGuardService.GuardAction.PAUSE_INFRASTRUCTURE,
                guard.analyze("API状态码：429").action());
    }

    private final LoopGuardService guard = new LoopGuardService();

    @Test
    void weatherResultContainingConfirmDoesNotRequestUserDecision() {
        assertEquals(LoopGuardService.GuardAction.CONTINUE,
                guard.analyze("查询结果如下，请确认：北京今天晴，26°C。天气数据仅供参考。")
                        .action());
    }

    @Test
    void explicitActionConfirmationStillWaitsForUser() {
        assertEquals(LoopGuardService.GuardAction.AWAIT_CONFIRMATION,
                guard.analyze("请确认是否继续创建订单").action());
        assertEquals(LoopGuardService.GuardAction.AWAIT_CONFIRMATION,
                guard.analyze("订单信息如下，请确认后再下单").action());
    }

    @Test
    void explicitChoiceStillWaitsForUser() {
        assertEquals(LoopGuardService.GuardAction.AWAIT_CONFIRMATION,
                guard.analyze("请选择退款还是换货").action());
        assertEquals(LoopGuardService.GuardAction.AWAIT_CONFIRMATION,
                guard.analyze("请告知是否继续支付该订单").action());
    }

    @Test
    void genericOfferForMoreHelpDoesNotBecomeActionConfirmation() {
        assertEquals(LoopGuardService.GuardAction.CONTINUE,
                guard.analyze("订单 ORD-1 当前状态为已签收，如需其他信息请告知。").action());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "知识库未提供可售库存的计算公式，暂时无法确定锁定库存和质检库存是否需要扣除。",
            "当前资料的信息不足，无法确定可售库存公式；不能凭空给出计算规则。",
            "知识库缺少必要信息，暂时无法提供准确的计算公式。",
            "暂时无法确认该商品的主动降噪参数，当前资料未记录这一特征。",
            "无法完成这部分规则的解释，因为知识库没有相关文档。",
            "需要您提供库存规则文档，我才能核实锁定库存的处理方式。",
            "需要你提供更多商品名称，才能核对具体参数。",
            "资料不足，无法继续推断库存计算规则。",
            "信息不足，请稍后再试或补充库存规则文档。",
            "The available documents do not explain blocked stock; its calculation is unknown.",
            "库存系统处于停滞状态的定义尚未记录，无法确定该术语的业务含义。",
            "当前无需授权，不需要凭据；知识库暂时无法提供公式。",
            "知识库说明普通用户没有权限修改商品，需管理员授权。",
            "目前暂时无法确定锁定库存的算法，现有知识库没有说明。",
            "我暂时无法确定公式，知识库未说明。",
            "我之前没有权限调用查询工具，现在已恢复正常。",
            "根据权限规则，普通用户没有权限修改商品，只有管理员可以操作。",
            "上次任务被阻塞，这次没有异常。",
            "任务之前被阻塞，现已恢复。",
            "当前任务之前被阻塞，现在已经恢复正常。",
            "blocked stock 是库存规则中尚未说明的术语。",
            "历史记录显示 I am blocked 是上一轮状态；当前已恢复。",
            "知识库仅列出库存状态，暂时无法确定可售库存计算公式。请提供相关规则后核实。",
            "现有资料信息不足，没有说明锁定库存和质检库存如何处理；不能据此推测公式。"
    })
    void knowledgeUncertaintyAndMissingBusinessInformationAreNotExecutionBlockers(String answer) {
        assertEquals(LoopGuardService.GuardAction.CONTINUE, guard.analyze(answer).action(), answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "当前任务被阻塞，无法继续执行。",
            "无法继续。",
            "缺少访问凭据，暂时无法调用数据库查询工具。",
            "需要您提供 API Key，才能调用检索接口。",
            "需要你提供访问令牌才能执行查询。",
            "需要授权后才能访问数据库。",
            "没有权限调用查询工具。",
            "权限不足，无法访问数据库。",
            "当前数据库访问权限不足，无法继续查询。",
            "暂时无法处理当前请求，必要工具未配置。",
            "查询工具不可用，无法完成该任务。",
            "I am blocked because credentials are missing.",
            "[BLOCKED] The required database tool is unavailable.",
            "知识库缺少库存公式。另外，没有权限调用补充查询工具。",
            "我无法继续，缺少必要的参数。",
            "任务被阻塞。",
            "任务被阻塞，无法继续。",
            "请求被阻塞。",
            "我被阻塞了，请提供更多信息。",
            "由于权限不足，暂时无法访问数据库。",
            "需要您提供管理员账号及密码，才能继续。",
            "缺少数据库密码，无法连接数据库。",
            "目前没有权限调用查询工具。",
            "没有权限调用订单接口，无法继续执行下单。"
    })
    void genuineExecutionBlockersStillPauseEvenAlongsideKnowledgeGaps(String answer) {
        assertEquals(LoopGuardService.GuardAction.PAUSE_BLOCKED, guard.analyze(answer).action(), answer);
    }

    @Test
    void knowledgeGapDoesNotOverrideInfrastructureOrExplicitConfirmation() {
        assertEquals(LoopGuardService.GuardAction.PAUSE_INFRASTRUCTURE,
                guard.analyze("知识库暂时无法确定库存公式，补充查询出现 HTTP 503。").action());
        assertEquals(LoopGuardService.GuardAction.AWAIT_CONFIRMATION,
                guard.analyze("资料不足，暂时无法核实地址。请确认是否继续提交订单。").action());
    }
}
