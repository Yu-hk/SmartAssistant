package com.example.smartassistant.consumer.service.speech;

import com.example.smartassistant.consumer.service.dispatch.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeechSynthesisServiceTest {
    final ChatDispatchStore store=mock(ChatDispatchStore.class);
    final ChatDispatchCommand command=new ChatDispatchCommand(7L,"session","request","问题",false,0,Long.MAX_VALUE);
    SpeechSynthesisService service() {
        when(store.ownedCommand(7L,"request")).thenReturn(command);
        when(store.status(command)).thenReturn("COMPLETED");
        when(store.result(command)).thenReturn(Map.of("result","订单 ORD-1001 的金额为 **1999.00 元**，未超过预算。","workflowStatus","COMPLETED"));
        return spy(new SpeechSynthesisService(store,true,"test-key","qwen-audio-3.0-tts-flash","longanhuan_v3.6","https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"));
    }
    @SuppressWarnings("unchecked") HttpResponse<InputStream> response(int code,byte[] data) {
        var response=(HttpResponse<InputStream>)mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);when(response.body()).thenReturn(new ByteArrayInputStream(data));return response;
    }
    void fails(int status,Runnable run) {assertEquals(status,assertThrows(ResponseStatusException.class,run::run).getStatusCode().value());}
    @Test void disabledAndInvalidIdNeverUseStore() {
        var service=new SpeechSynthesisService(store,false,"","m","v","https://example.com");
        fails(503,()->service.synthesize(7,"request"));verifyNoInteractions(store);
        var enabled=service();fails(400,()->enabled.synthesize(7,"bad/id"));
    }
    @Test void verifiesOwnershipAndCompletionBeforeAnyModelCall() {
        var s=service();when(store.ownedCommand(7L,"request")).thenThrow(new SecurityException());
        fails(404,()->s.synthesize(7,"request"));
        doReturn(command).when(store).ownedCommand(7L,"request");when(store.status(command)).thenReturn("RUNNING");
        fails(409,()->s.synthesize(7,"request"));verify(s,never()).client();
    }
    @Test void missingOrFailedAnswersAreNotReadAloud() {
        var s=service();when(store.ownedCommand(7L,"request")).thenReturn(null);fails(404,()->s.synthesize(7,"request"));
        fails(409,()->SpeechSynthesisService.answerText(Map.of("result","bad","error","failure")));
        fails(409,()->SpeechSynthesisService.answerText(Map.of("result","bad","workflowStatus","FAILED")));
        fails(409,()->SpeechSynthesisService.answerText(Map.of("result","bad","cancelled",true)));
    }
    @Test void keepsMoneyOrderNumbersAndFullAnswerBeyondAuditSummary() {
        String text=SpeechSynthesisService.answerText(Map.of("result","# 结果\n**1999.00 元**，订单 ORD-1001_A。\n"+"完整说明".repeat(150)));
        assertTrue(text.contains("1999.00 元"));assertTrue(text.contains("ORD-1001_A"));assertTrue(text.length()>500);assertFalse(text.contains("**"));
        fails(422,()->SpeechSynthesisService.answerText(Map.of("result","字".repeat(3001))));
    }
    @Test void completedClarificationTurnCanBeSpokenButUncertainWorkCannot() {
        assertEquals("请提供订单号。",SpeechSynthesisService.answerText(Map.of("result","请提供订单号。","workflowStatus","CLARIFICATION")));
        for(String status:new String[]{"AWAITING_APPROVAL","CANCELLED","FAILED","DEGRADED"})
            fails(409,()->SpeechSynthesisService.answerText(Map.of("result","不应播报","workflowStatus",status)));
    }
    @Test void rejectsUntrustedOutputUrlsAndUpgradesOnlyOfficialBucket() {
        for(String url:new String[]{"https://127.0.0.1/a.mp3","https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com.evil/a","https://user@dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a","https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com:443/a"})fails(502,()->SpeechSynthesisService.safeAudioUri(url));
        assertEquals("https",SpeechSynthesisService.safeAudioUri("http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a?sig=x").getScheme());
    }
    @Test void actualProtocolDownloadsAudioWithoutSendingKeyAndLimitsRepeats() throws Exception {
        var s=service();var http=mock(HttpClient.class);doReturn(http).when(s).client();
        byte[] result="{\"output\":{\"finish_reason\":\"stop\",\"audio\":{\"url\":\"http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.mp3\"}},\"usage\":{\"characters\":32}}".getBytes();
        var generated=response(200,result);var downloaded=response(200,new byte[]{'I','D','3',0,1});
        when(http.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(generated,downloaded);
        var audio=s.synthesize(7,"request");assertEquals(32L,audio.characters());assertEquals(5,audio.bytes().length);
        var requests=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(2)).send(requests.capture(),any());
        assertEquals("POST",requests.getAllValues().get(0).method());
        assertTrue(requests.getAllValues().get(1).headers().firstValue("Authorization").isEmpty());
        assertEquals("https",requests.getAllValues().get(1).uri().getScheme());
        fails(429,()->s.synthesize(7,"request"));
    }
    @Test void providerFailureIsRedacted() throws Exception {
        var s=service();var http=mock(HttpClient.class);doReturn(http).when(s).client();
        var rejected=response(401,"secret-provider-body".getBytes());
        when(http.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(rejected);
        var error=assertThrows(ResponseStatusException.class,()->s.synthesize(7,"request"));
        assertEquals(502,error.getStatusCode().value());assertFalse(error.getReason().contains("secret"));
    }
}
