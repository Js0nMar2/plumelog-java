package com.wf.plumelog.trace.aspect;

import com.alibaba.fastjson.JSONObject;
import com.wf.plumelog.core.LogMessageThreadLocal;
import com.wf.plumelog.core.TraceId;
import com.wf.plumelog.core.TraceMessage;
import com.wf.plumelog.core.constant.LogMessageConstant;
import com.wf.plumelog.core.util.GfJsonUtil;
import com.wf.plumelog.core.util.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * className：AbstractAspect
 * description： 链路追踪打点拦截
 * time：2020-05-26.11:17
 *
 * @author Tank, fran.chen
 * @version 1.2.0
 */
public abstract class AbstractAspect {
    private final static org.slf4j.Logger log = LoggerFactory.getLogger(AbstractAspect.class);

    private final static String AUTHORIZATION = "Authorization";

    private static ThreadLocal<String> logLocal = new ThreadLocal<>();

    private final static String APPLICATION = "X-Application-Name";
    private final static String SOURCE = "X-Source";
    private final static String VERSION = "X-Version";
    private final static String SERVER = "X-Server-Name";
    private final static String TIMEZONE = "X-Timezone";
    private final static String DEVICE = "X-Device-Code";

    @Value("${plumelog.openResponse:false}")
    private boolean openResponse;

    private Map<String, Object> handle(JoinPoint joinPoint) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        Map<String, Object> map = new LinkedHashMap<>();
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = methodSignature.getMethod();
        String url = request.getRequestURI();

        map.put("path", url);
        String ip = getRequestIp(request);
        map.put("ip", ip);
        String requestMethod = request.getMethod();
        map.put("requestMethod", requestMethod);
        String contentType = request.getContentType();
        map.put("contentType", contentType);
        Annotation[][] annotations = method.getParameterAnnotations();
        boolean isRequestBody = isRequestBody(annotations);
        map.put("isRequestBody", Boolean.valueOf(isRequestBody));
        Object requestParamJson = getRequestParamJsonString(joinPoint, request, requestMethod, contentType, isRequestBody);
        map.put("param", requestParamJson);
        map.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
        if (StringUtils.isNotEmpty(request.getHeader(AUTHORIZATION))) {
            map.put("token", request.getHeader(AUTHORIZATION));
        }
        Map<String, Object> header = new HashMap<>();
        header.put(APPLICATION, request.getHeader(APPLICATION));
        header.put(SOURCE, request.getHeader(SOURCE));
        header.put(VERSION, request.getHeader(VERSION));
        header.put(SERVER, request.getHeader(SERVER));
        header.put(TIMEZONE, request.getHeader(TIMEZONE));
        header.put(DEVICE, request.getHeader(DEVICE));
        map.put("header", header);
        return map;
    }

    private String getRequestIp(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }

        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }

        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            ip = getLocalhostIp();
        }

        return ip;
    }

    private String getLocalhostIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException var1) {
            return null;
        }
    }

    protected boolean isRequestBody(Annotation[][] annotations) {
        boolean isRequestBody = false;
        for (Annotation[] annotationArray : annotations) {
            for (Annotation annotation : annotationArray) {
                if (annotation instanceof org.springframework.web.bind.annotation.RequestBody) {
                    isRequestBody = true;
                }
            }
        }
        return isRequestBody;
    }

    protected Object getRequestParamJsonString(JoinPoint joinPoint, HttpServletRequest request, String requestMethod, String contentType, boolean isRequestBody) {
        Object paramObject = null;
        int requestType = 0;
        if (RequestMethod.GET.toString().equals(requestMethod)) {
            requestType = 1;
        } else if (RequestMethod.POST.toString().equals(requestMethod)) {
            if (contentType == null) {
                requestType = 5;
            } else if (contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED.toString())) {
                requestType = 2;
            } else if (contentType.startsWith(MediaType.APPLICATION_JSON.toString())) {
                if (isRequestBody) {
                    requestType = 4;
                } else {
                    requestType = 3;
                }
            }
        }
        if (requestType == 1 || requestType == 2 || requestType == 3 || requestType == 5) {
            Map<String, String[]> paramsMap = request.getParameterMap();
            paramObject = getJsonForParamMap(paramsMap);
        } else if (requestType == 4) {
            Object[] args = joinPoint.getArgs();
            paramObject = argsArrayToJsonString(args);
        }

        return paramObject;
    }

    protected Object argsArrayToJsonString(Object[] args) {
        if (args == null) {
            return null;
        }
        List<Object> realArgs = new ArrayList();
        for (Object arg : args) {
            if (!(arg instanceof HttpServletRequest)) {
                if (!(arg instanceof javax.servlet.http.HttpServletResponse)) {
                    if (!(arg instanceof org.springframework.web.multipart.MultipartFile)) {
                        if (!(arg instanceof org.springframework.web.servlet.ModelAndView)) {
                            realArgs.add(arg);
                        }
                    }
                }
            }
        }
        if (realArgs.size() == 1) {
            return realArgs.get(0);
        }
        return realArgs;
    }

    protected JSONObject getJsonForParamMap(Map<String, String[]> paramsMap) {
        int paramSize = paramsMap.size();
        if (paramsMap == null || paramSize == 0) {
            return null;
        }
        JSONObject jsonObject = new JSONObject();
        for (Map.Entry<String, String[]> kv : paramsMap.entrySet()) {
            String key = kv.getKey();
            String[] values = kv.getValue();
            if (values == null) {
                jsonObject.put(key, null);
                continue;
            }
            if (values.length == 1) {
                jsonObject.put(key, values[0]);
                continue;
            }
            jsonObject.put(key, values);
        }

        return jsonObject;
    }

    public Object aroundExecute(JoinPoint joinPoint) throws Throwable {
        TraceMessage traceMessage = LogMessageThreadLocal.logMessageThreadLocal.get();
        if (traceMessage == null) {
            traceMessage = new TraceMessage();
            traceMessage.getPositionNum().set(0);
        }
        if (StringUtils.isEmpty(traceMessage.getTraceId())) {
            TraceId.set();
            traceMessage.setTraceId(TraceId.logTraceID.get());
        }
        traceMessage.setMessageType(joinPoint.getSignature().toString());
        traceMessage.setPosition(LogMessageConstant.TRACE_START);
        traceMessage.getPositionNum().incrementAndGet();
        LogMessageThreadLocal.logMessageThreadLocal.set(traceMessage);
        if (traceMessage.getTraceId() != null) {
            log.info(LogMessageConstant.TRACE_PRE + GfJsonUtil.toJSONString(traceMessage));
        }
        MDC.clear();
        long startTime = System.currentTimeMillis();
        Map<String, Object> map = handle(joinPoint);
        if (StringUtils.isEmpty(logLocal.get())) {
            log.info("请求体信息:\n{}", JSONObject.toJSONString(map));
            logLocal.set(traceMessage.getTraceId() + "_" + map.get("path"));
        }
        Object proceed = ((ProceedingJoinPoint) joinPoint).proceed();
        traceMessage.setMessageType(joinPoint.getSignature().toString());
        traceMessage.setPosition(LogMessageConstant.TRACE_END);
        traceMessage.getPositionNum().incrementAndGet();
        if (traceMessage.getTraceId() != null) {
            log.info(LogMessageConstant.TRACE_PRE + GfJsonUtil.toJSONString(traceMessage));
        }
        if (Objects.nonNull(proceed)) {
            MDC.put("costTime", (System.currentTimeMillis() - startTime)+"");
            MDC.put("url", logLocal.get().split("_")[1]);
            log.info("接口地址:{}, 接口耗时:{}",logLocal.get().split("_")[1], (System.currentTimeMillis() - startTime) + "ms");
            if (openResponse) {
                log.info("响应体信息:\n{}", JSONObject.toJSONString(proceed));
            }
            TraceId.logTraceID.remove();
            LogMessageThreadLocal.logMessageThreadLocal.remove();
            logLocal.remove();
        }
        return proceed;
    }
}
