package com.example.orderSystem.websocket;

import com.example.orderSystem.dto.websocket.ChatMessage;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;

    @MessageMapping("/order/{orderId}/chat")
    @SendTo("/topic/order/{orderId}/chat")
    public ChatMessage handleChat(@DestinationVariable String orderId,
                                  ChatMessage incoming,
                                  SimpMessageHeaderAccessor headerAccessor) {
        String userId = headerAccessor.getUser() != null ? headerAccessor.getUser().getName() : "unknown";
        User user = userMapper.selectById(userId);

        ChatMessage msg = new ChatMessage();
        msg.setOrderId(orderId);
        msg.setUserId(userId);
        msg.setUserName(user != null ? user.getUserName() : userId);
        msg.setMessage(incoming.getMessage());
        msg.setTimestamp(LocalDateTime.now().format(FMT));
        return msg;
    }
}
