package com.example.smartassistant.consumer.service.speech;

import com.example.smartassistant.consumer.service.dispatch.ChatDispatchStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Only synthesize an owned, completed server-side answer. Never accept arbitrary text or URLs. */
@Service
public class SpeechSynthesisService {
    static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;
    private final ChatDispatchStore store;
    private final boolean enabled;
    private final String key, model, voice;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore capacity = new Semaphore(3);
    private final java.util.Set<Long> active = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> recent = new LinkedHashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"tts-body-deadline");thread.setDaemon(true);return thread;
    });
    HttpClient client() {return http;}
    private static byte[] bounded(java.io.InputStream input,int maximum,int seconds) throws java.io.IOException {
        var deadline=DEADLINES.schedule(()->{try{input.close();}catch(java.io.IOException ignored){}},seconds,java.util.concurrent.TimeUnit.SECONDS);
        try{return input.readNBytes(maximum+1);}finally{deadline.cancel(false);}
    }

    public SpeechSynthesisService(ChatDispatchStore store,
            @Value("${speech.tts.enabled:false}") boolean enabled,
            @Value("${speech.tts.api-key:}") String key,
            @Value("${speech.tts.model:qwen-audio-3.0-tts-flash}") String model,
            @Value("${speech.tts.voice:longanhuan_v3.6}") String voice,
            @Value("${speech.tts.endpoint:https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer}") String endpoint) {
        this.store=store;this.enabled=enabled;this.key=key;this.model=model;this.voice=voice;
        this.endpoint=URI.create(endpoint);
        if (!"https".equals(this.endpoint.getScheme()) || this.endpoint.getHost()==null
                || this.endpoint.getUserInfo()!=null || this.endpoint.getQuery()!=null || this.endpoint.getFragment()!=null)
            throw new IllegalArgumentException("TTS endpoint must be HTTPS");
    }
    public boolean available() { return enabled && key!=null && !key.isBlank(); }
    public record Audio(byte[] bytes, Long characters) {}

    public Audio synthesize(long userId, String requestId) {
        if (!available()) throw fail(HttpStatus.SERVICE_UNAVAILABLE,"语音回复暂未启用");
        if (requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,128}"))
            throw fail(HttpStatus.BAD_REQUEST,"无效的回复标识");
        String text;
        try {
            var command=store.ownedCommand(userId,requestId);
            if (command==null) throw fail(HttpStatus.NOT_FOUND,"回复已过期，无法生成语音，请查看文字回复");
            if (!"COMPLETED".equals(store.status(command))) throw fail(HttpStatus.CONFLICT,"请等待回复完整生成");
            text=answerText(store.result(command));
        } catch (SecurityException e) { throw fail(HttpStatus.NOT_FOUND,"回复不存在或无权访问"); }
        catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw fail(HttpStatus.SERVICE_UNAVAILABLE,"暂时无法读取回复，请稍后重试"); }
        if (!capacity.tryAcquire()) throw fail(HttpStatus.TOO_MANY_REQUESTS,"语音服务繁忙，请稍后再试");
        if (!active.add(userId)) {capacity.release();throw fail(HttpStatus.TOO_MANY_REQUESTS,"已有语音正在生成");}
        try {
            rateLimit(userId);
            String body=json.writeValueAsString(Map.of("model",model,"input",Map.of("text",text,
                    "voice",voice,"format","mp3","sample_rate",24000,"enable_aigc_tag",true)));
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                    .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client().send(request,HttpResponse.BodyHandlers.ofInputStream());
            byte[] data;
            try(var input=response.body()) {
                if(response.statusCode()!=200) throw fail(response.statusCode()==429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY,"语音生成失败，请稍后重试");
                data=bounded(input,65536,15);
            }
            if(data.length>65536) throw fail(HttpStatus.BAD_GATEWAY,"语音服务返回异常");
            var result=json.readTree(data);
            if(result==null || !"stop".equals(result.path("output").path("finish_reason").asText()))
                throw fail(HttpStatus.BAD_GATEWAY,"语音未完整生成");
            URI audioUri=safeAudioUri(result.path("output").path("audio").path("url").asText());
            var audio=client().send(HttpRequest.newBuilder(audioUri).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try(var input=audio.body()) {
                if(audio.statusCode()!=200) throw fail(HttpStatus.BAD_GATEWAY,"暂时无法获取语音");
                bytes=bounded(input,MAX_AUDIO_BYTES,10);
            }
            if(bytes.length<4 || bytes.length>MAX_AUDIO_BYTES || !isMp3(bytes))
                throw fail(HttpStatus.BAD_GATEWAY,"语音格式异常，请使用文字回复");
            var count=result.path("usage").path("characters");
            return new Audio(bytes,count.isIntegralNumber() && count.canConvertToLong() && count.asLong()>=0 ? count.asLong() : null);
        } catch (ResponseStatusException e) {throw e;}
        catch (InterruptedException e) {Thread.currentThread().interrupt();throw fail(HttpStatus.GATEWAY_TIMEOUT,"语音生成已中断");}
        catch (java.net.http.HttpTimeoutException e) {throw fail(HttpStatus.GATEWAY_TIMEOUT,"语音生成超时，请重试");}
        catch (Exception e) {throw fail(HttpStatus.BAD_GATEWAY,"语音生成失败，请重试或查看文字回复");}
        finally {active.remove(userId);capacity.release();}
    }
    private synchronized void rateLimit(long userId) {
        long now=System.currentTimeMillis();recent.entrySet().removeIf(e->now-e.getValue()>=10000);
        if(recent.containsKey(userId) || recent.size()>=10000) throw fail(HttpStatus.TOO_MANY_REQUESTS,"语音请求过于频繁，请在十秒后重试");
        recent.put(userId,now);
    }
    static URI safeAudioUri(String value) {
        URI uri=URI.create(value);
        // Official Beijing output bucket only; upgrade provider HTTP URLs, never forward authorization.
        if(!"dashscope-result-bj.oss-cn-beijing.aliyuncs.com".equals(uri.getHost())
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getFragment()!=null)
            throw fail(HttpStatus.BAD_GATEWAY,"语音下载地址异常");
        return URI.create(value.replaceFirst("^http:","https:"));
    }
    static boolean isMp3(byte[] value) {return (value[0]=='I' && value[1]=='D' && value[2]=='3')
            || ((value[0]&255)==255 && (value[1]&224)==224);}
    static String answerText(Map<String,Object> result) {
        if(result==null || result.get("error")!=null || Boolean.TRUE.equals(result.get("cancelled"))
                || (result.get("workflowStatus")!=null && !java.util.Set.of("COMPLETED","CLARIFICATION").contains(result.get("workflowStatus")))
                || !(result.get("result") instanceof String))
            throw fail(HttpStatus.CONFLICT,"这条回复尚无可播报的完整结果");
        String raw=(String)result.get("result");
        if(raw.length()>8000) throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"回复较长，请查看完整文字，本次不自动截断播报");
        String text=raw.replaceAll("(?s)```.*?```","（代码内容请查看文字回复）")
                .replaceAll("!\\[([^]]*)]\\([^)]*\\)","$1")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)","$1")
                .replaceAll("(?m)^\\s{0,3}(?:#{1,6}\\s+|>\\s*|[-*+]\\s+)","")
                .replaceAll("(?m)^\\s*\\|?[ :|-]*[-:][ :|-]*\\|?\\s*$","")
                .replaceAll("\\*\\*(.*?)\\*\\*","$1").replaceAll("__(.*?)__","$1")
                .replaceAll("`([^`]+)`","$1").replace('|','，')
                .replaceAll("<[^>]*>","").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","").strip();
        if(text.isBlank() || text.length()>3000) throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"这条回复不适合语音播报，请查看文字");
        return text;
    }
    private static ResponseStatusException fail(HttpStatus status,String message) {return new ResponseStatusException(status,message);}
}
