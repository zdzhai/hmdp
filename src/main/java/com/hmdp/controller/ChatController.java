package com.hmdp.controller;

import com.hmdp.dto.Result;
import io.github.flashvayne.chatgpt.service.ChatgptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * @author dongdong
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private ChatgptService chatgptService;

    @GetMapping("/send")
    public Result send(HttpServletRequest request, @RequestParam String message) {
        String requestId = UUID.randomUUID().toString();
        log.info("requestId {}, ip {}, send a message : {}", requestId, request.getRemoteHost(), message);
        if (!StringUtils.hasText(message)) {
            return Result.fail("message can not be blank");
        }
        try {
            String responseMessage = chatgptService.sendMessage(message);
            log.info("requestId {}, ip {}, get a reply : {}", requestId, request.getRemoteHost(), responseMessage);
            return Result.ok(responseMessage);
        } catch (Exception e) {
            log.error("requestId {}, ip {}, error", requestId, request.getRemoteHost(),e);
            return Result.fail(e.getMessage());
        }
    }

}